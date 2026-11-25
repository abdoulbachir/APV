// Apply the saved preference before the first paint. Light is the default.
try { document.documentElement.dataset.theme = localStorage.getItem("apv-theme") === "dark" ? "dark" : "light"; }
catch { document.documentElement.dataset.theme = "light"; }
