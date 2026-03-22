const BRIDGE_URL = 'http://127.0.0.1:9001/track'

async function forwardTrack(track) {
  const payload = {
    source: 'YouTube Music',
    title: track.title || '',
    artist: track.artist || '',
    album: track.album || '',
    artwork_url: track.artwork_url || '',
    state: track.state || 'unknown',
    ts: Date.now()
  }

  try {
    const res = await fetch(BRIDGE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    if (!res.ok) {
      console.warn('YTM Bridge: HTTP', res.status)
    }
  } catch (err) {
    console.warn('YTM Bridge: bridge unavailable', err)
  }
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg?.type === 'TRACK_UPDATE' && msg.track) {
    forwardTrack(msg.track)
  }
  // Optional: send a response if we ever need it
  if (sendResponse) {
    sendResponse({ ok: true });
  }
})
