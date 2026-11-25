// Activation/recovery uses typed or pasted credentials, not credential links.
// Discard any unexpected fragment on that route before the application starts.
if (typeof location !== "undefined" && location.pathname === "/auth/set-password" && location.hash) {
  history.replaceState(null, "", location.pathname + location.search);
}
// Apply the saved preference before the first paint. Light is the default.
try { document.documentElement.dataset.theme = localStorage.getItem("apv-theme") === "dark" ? "dark" : "light"; }
catch { document.documentElement.dataset.theme = "light"; }
