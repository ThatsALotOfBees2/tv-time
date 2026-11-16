// Shown when a page tried to send you to a site you didn't choose (usually an ad).
var target = decodeURIComponent(location.hash.slice(1));
document.getElementById('host').textContent = target.replace(/^https?:\/\//, '');
setTimeout(function () {
  if (history.length > 1) history.back();
  else location.href = chrome.runtime.getURL('start/index.html');
}, 1200);
