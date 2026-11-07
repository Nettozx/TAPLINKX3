// Stands in for WebView's evaluateJavascript and addJavascriptInterface when TapLink runs on
// GeckoView: the app sends scripts to run and the interface names to expose over a native port.
(function () {
  "use strict";

  const pageWindow = window.wrappedJSObject;
  let port = null;
  let connectAttempts = 0;
  const definedInterfaces = new Set();

  // Gecko waits ~300ms after a tap for a possible double tap before sending the click. Pages
  // that react on pointerup (e.g. closing an overlay) then get the click on whatever is behind.
  function disableDoubleTapDelay() {
    const style = document.createElement("style");
    style.textContent = "html { touch-action: manipulation !important; }";
    (document.head || document.documentElement).appendChild(style);
  }

  function send(message) {
    if (port) port.postMessage(message);
  }

  // Shows up in the app's logcat (tag GeckoBridge)
  function log(text) {
    send({ type: "log", text: String(text) });
  }

  function toPlainArg(value) {
    if (value === undefined || value === null) return null;
    const type = typeof value;
    return type === "string" || type === "number" || type === "boolean" ? value : String(value);
  }

  // Like addJavascriptInterface: window[name].method(...) calls the app's @JavascriptInterface
  // method. Calls are one-way; return values aren't passed back.
  function defineInterfaces(defs) {
    for (const name of Object.keys(defs)) {
      if (definedInterfaces.has(name)) continue;
      definedInterfaces.add(name);
      const sandboxObject = {};
      const pageObject = cloneInto({}, pageWindow);
      for (const method of defs[name]) {
        const call = (...args) => {
          // As a JSON string: a mixed-type array (["id", false]) fails GeckoView's message conversion
          send({ type: "call", iface: name, method: method, argsJson: JSON.stringify(args.map(toPlainArg)) });
        };
        sandboxObject[method] = call;
        exportFunction(call, pageObject, { defineAs: method });
      }
      window[name] = sandboxObject;
      pageWindow[name] = pageObject;
    }
  }

  // Like evaluateJavascript: run in the page's context (it sees page functions), falling back to
  // this script's sandbox when the page's CSP forbids eval. The result is JSON, as WebView returns it.
  function evaluate(id, code) {
    let json = "null";
    try {
      let result;
      let stringify;
      let mode = "page";
      try {
        result = pageWindow.eval(code);
        stringify = pageWindow.JSON.stringify;
      } catch (e) {
        // The error comes from the page's compartment, so instanceof EvalError doesn't match
        if (!isCspError(e)) throw e;
        mode = "sandbox";
        result = (0, eval)(code);
        stringify = JSON.stringify;
      }
      const encoded = result === undefined ? undefined : stringify(result);
      if (encoded !== undefined) json = encoded;
      if (json !== "null") log("script " + id + " (" + mode + ") -> " + json.slice(0, 60));
    } catch (e) {
      log("script " + id + " failed: " + e + " :: " + String(code).slice(0, 120));
    }
    if (id !== undefined && id !== null) send({ type: "result", id: id, value: json });
  }

  function isCspError(e) {
    const text = String(e);
    return (e && e.name === "EvalError") || text.indexOf("CSP") >= 0 || text.indexOf("Content-Security-Policy") >= 0;
  }

  // Chrome sends a tap's click to the element the touch started on (or one containing it). Gecko
  // hit-tests again after touchend, so a button that closes an overlay on pointerup lets the click
  // land on whatever was behind it. Drop those mouse/click events, like Chrome would.
  const CONTROL_SELECTOR = "button, a, label, summary, [role=button], [role=link], [role=menuitem], [role=tab], [onclick]";

  function guardClickThrough() {
    let startTarget = null;
    let endTime = 0;
    window.addEventListener("touchstart", (e) => {
      startTarget = e.touches && e.touches.length === 1 ? e.target : null;
    }, true);
    window.addEventListener("touchend", () => {
      endTime = Date.now();
    }, true);
    const filter = (e) => {
      if (!startTarget || Date.now() - endTime > 800) return;
      const target = e.target;
      if (!target || !startTarget.isConnected) return;
      if (target === startTarget || startTarget.contains(target) || target.contains(startTarget)) return;
      // Same control: a button that swaps its icon on press (Plex's play/pause) gets the click on
      // the new icon. Dropping that click left Plex's player toggling play/pause by itself.
      const control = startTarget.closest && startTarget.closest(CONTROL_SELECTOR);
      if (control && control.contains(target)) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      if (e.type === "click") startTarget = null;
    };
    for (const type of ["mousedown", "mouseup", "click"]) window.addEventListener(type, filter, true);
  }

  // TapLink's scrollbar uses the size its page observer last reported, which only reports on
  // scroll, resize and added nodes. Pages that grow after a tap (an expanding form) or by style
  // changes left the bar stale. Ask the observer for a fresh report after taps and size changes.
  function keepScrollMetricsFresh() {
    let timer = null;
    const report = () => {
      timer = null;
      try {
        const fn = window.__taplinkReportScroll || pageWindow.__taplinkReportScroll;
        if (typeof fn === "function") fn();
      } catch (e) {
        // the page's observer isn't injected yet
      }
    };
    const schedule = (delay) => {
      if (timer === null) timer = setTimeout(report, delay);
    };
    window.addEventListener("touchend", () => {
      setTimeout(report, 400);
      setTimeout(report, 1300);
      setTimeout(report, 2600);
    }, true);
    const observe = () => {
      if (typeof ResizeObserver !== "function") return;
      const observer = new ResizeObserver(() => schedule(250));
      observer.observe(document.documentElement);
      if (document.body) observer.observe(document.body);
    };
    if (document.body) observe();
    else document.addEventListener("DOMContentLoaded", observe, { once: true });
  }

  function onMessage(message) {
    try {
      if (message.type === "interfaces") {
        defineInterfaces(message.defs);
      } else if (message.type === "eval") {
        evaluate(message.id, message.code);
      }
    } catch (e) {
      log("message " + message.type + " failed: " + e);
      if (message.type === "eval" && message.id !== undefined && message.id !== null) {
        send({ type: "result", id: message.id, value: "null" });
      }
    }
  }

  function connect() {
    try {
      port = browser.runtime.connectNative("taplink");
    } catch (e) {
      port = null;
      retry();
      return;
    }
    port.onMessage.addListener(onMessage);
    port.onDisconnect.addListener(() => {
      port = null;
      retry();
    });
    log("connected " + location.href.slice(0, 120));
  }

  // The app registers its side shortly after startup; early pages retry until it's there.
  function retry() {
    if (connectAttempts++ < 40) setTimeout(connect, 250);
  }

  disableDoubleTapDelay();
  guardClickThrough();
  keepScrollMetricsFresh();
  connect();
})();
