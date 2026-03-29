// Runs in ISOLATED world — has access to chrome.runtime.
// Listens for CustomEvents dispatched by page.js (MAIN world) and forwards to background.

document.addEventListener('__ytm_bridge_track__', (e) => {
  const track = e.detail
  if (!track || !track.title) return

  try {
    chrome.runtime.sendMessage({ type: 'TRACK_UPDATE', track }).catch(() => {})
  } catch (err) {
    // Extension context may be invalidated after update/reload
  }
})
