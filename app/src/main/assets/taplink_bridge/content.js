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
          send({ type: "call", iface: name, method: method, args: args.map(toPlainArg) });
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
      try {
        result = pageWindow.eval(code);
        stringify = pageWindow.JSON.stringify;
      } catch (e) {
        if (!(e instanceof EvalError) && String(e).indexOf("Content-Security-Policy") < 0) throw e;
        result = (0, eval)(code);
        stringify = JSON.stringify;
      }
      const encoded = result === undefined ? undefined : stringify(result);
      if (encoded !== undefined) json = encoded;
    } catch (e) {
      log("script " + id + " failed: " + e + " :: " + String(code).slice(0, 120));
    }
    if (id !== undefined && id !== null) send({ type: "result", id: id, value: json });
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
  connect();
})();
