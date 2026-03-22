let lastSig = ''
let lastSentAt = 0

const POLL_MS = 1200
const HEARTBEAT_MS = 7000

function cleanArtworkUrl(url) {
  if (!url) return ''
  return String(url)
    .replace(/=w\d+-h\d+(-l\d+)?(-rj)?/g, '=w600-h600')
    .replace(/-w\d+-h\d+(-l\d+)?(-rj)?/g, '-w600-h600')
}

function fromMediaSession() {
  const md = navigator.mediaSession?.metadata
  if (!md) return null

  const title = (md.title || '').trim()
  const artist = (md.artist || '').trim()
  const album = (md.album || '').trim()
  let artworkUrl = ''

  if (Array.isArray(md.artwork) && md.artwork.length > 0) {
    const best = md.artwork[md.artwork.length - 1]
    artworkUrl = cleanArtworkUrl(best?.src || '')
  }

  if (!title) return null
  return { title, artist, album, artwork_url: artworkUrl }
}

function fromDom() {
  const title = (
    document.querySelector('.title.ytmusic-player-bar')?.textContent ||
    document.querySelector('yt-formatted-string.title')?.textContent ||
    ''
  ).trim()

  const artist = (
    document.querySelector('.byline.ytmusic-player-bar .subtitle')?.textContent ||
    document.querySelector('.byline.ytmusic-player-bar')?.textContent ||
    ''
  ).trim()

  const album = (
    document.querySelector('.byline.ytmusic-player-bar .subtitle a:nth-child(2)')?.textContent ||
    ''
  ).trim()

  // Find artwork URL from ytmusic-player-bar image
  const artEl = document.querySelector('ytmusic-player-bar img');
  const artworkUrl = cleanArtworkUrl(artEl?.src || '')

  if (!title) return null
  return { title, artist, album, artwork_url: artworkUrl }
}

function getTrackInfo() {
  return fromMediaSession() || fromDom()
}

function getPlaybackState() {
  const video = document.querySelector('video')
  if (!video) return 'unknown'
  return video.paused ? 'paused' : 'playing'
}

function setupVideoListeners() {
  const video = document.querySelector('video');
  if (video && !video.dataset.ytmBridgeWatch) {
    video.dataset.ytmBridgeWatch = 'true';
    video.addEventListener('play', () => emitTrackUpdate(true));
    video.addEventListener('pause', () => emitTrackUpdate(true));
    video.addEventListener('seeking', () => emitTrackUpdate(true));
  }
}

function emitTrackUpdate(force = false) {
  setupVideoListeners();
  const track = getTrackInfo()
  if (!track) return

  const state = getPlaybackState()
  const payload = { ...track, state }
  const sig = JSON.stringify(payload)
  const now = Date.now()

  if (!force && sig === lastSig && now - lastSentAt < HEARTBEAT_MS) {
    return
  }

  lastSig = sig
  lastSentAt = now
  try {
    chrome.runtime.sendMessage({ type: 'TRACK_UPDATE', track: payload }).catch(() => { });
  } catch (e) {
    // Ignore error if extension context is invalidated
  }
}

document.addEventListener('visibilitychange', () => emitTrackUpdate(true))
window.addEventListener('focus', () => emitTrackUpdate(true))

setInterval(() => emitTrackUpdate(false), POLL_MS)
setInterval(() => emitTrackUpdate(true), HEARTBEAT_MS)
emitTrackUpdate(true)
