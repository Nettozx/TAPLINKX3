// Keeps sign-ins across TapLink restarts.
//
// Sites that don't tick "keep me signed in" (Messenger's default) store the login in session
// cookies, which a browser forgets when it closes. On the glasses TapLink is closed all the
// time (RayNeo stops apps that leave the screen), so every visit meant signing in again.
// Give each session cookie an expiry (30 days) so Gecko writes it to disk.

const KEEP_SECONDS = 30 * 24 * 60 * 60;

function persist(cookie) {
  if (!cookie.session) return;
  const host = cookie.domain.startsWith('.') ? cookie.domain.slice(1) : cookie.domain;
  const details = {
    url: (cookie.secure ? 'https://' : 'http://') + host + cookie.path,
    name: cookie.name,
    value: cookie.value,
    path: cookie.path,
    secure: cookie.secure,
    httpOnly: cookie.httpOnly,
    sameSite: cookie.sameSite,
    storeId: cookie.storeId,
    expirationDate: Math.floor(Date.now() / 1000) + KEEP_SECONDS,
  };
  if (!cookie.hostOnly) details.domain = cookie.domain;
  if (cookie.firstPartyDomain) details.firstPartyDomain = cookie.firstPartyDomain;
  if (cookie.partitionKey) details.partitionKey = cookie.partitionKey;
  browser.cookies.set(details).catch(() => {});
}

browser.cookies.onChanged.addListener(change => {
  if (!change.removed) persist(change.cookie);
});

// Cookies set before this script loaded.
browser.cookies.getAll({}).then(all => all.forEach(persist)).catch(() => {});
