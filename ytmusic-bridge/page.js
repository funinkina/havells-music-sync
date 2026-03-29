// Runs in MAIN world — has access to navigator.mediaSession and page JS context.
// Dispatches CustomEvents to communicate with the ISOLATED-world content script.

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
    document.querySelector('ytmusic-player-bar .title')?.textContent ||
    document.querySelector('.title.ytmusic-player-bar')?.textContent ||
    document.querySelector('yt-formatted-string.title')?.textContent ||
    ''
  ).trim()

  const artist = (
    document.querySelector('ytmusic-player-bar .byline .subtitle yt-formatted-string a')?.textContent ||
    document.querySelector('ytmusic-player-bar .subtitle a')?.textContent ||
    document.querySelector('.byline.ytmusic-player-bar .subtitle')?.textContent ||
    document.querySelector('.byline.ytmusic-player-bar')?.textContent ||
    ''
  ).trim()

  const album = (
    document.querySelector('ytmusic-player-bar .byline .subtitle a:nth-of-type(2)')?.textContent ||
    ''
  ).trim()

  const artEl =
    document.querySelector('ytmusic-player-bar .thumbnail img') ||
    document.querySelector('ytmusic-player-bar img.image') ||
    document.querySelector('ytmusic-player-bar img')
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

function emitTrackUpdate(force) {
  const track = getTrackInfo()
  if (!track) return

  const state = getPlaybackState()
  const payload = { title: track.title, artist: track.artist, album: track.album, artwork_url: track.artwork_url, state }
  const sig = JSON.stringify(payload)
  const now = Date.now()

  if (!force && sig === lastSig && now - lastSentAt < HEARTBEAT_MS) return

  lastSig = sig
  lastSentAt = now

  console.log('[YTM Bridge] track update:', payload.title, '-', payload.artist, '(' + state + ')')
  document.dispatchEvent(new CustomEvent('__ytm_bridge_track__', { detail: payload }))
}

// Watch for <video> element appearing/changing via MutationObserver
let watchedVideo = null
function watchVideo() {
  const video = document.querySelector('video')
  if (video && video !== watchedVideo) {
    watchedVideo = video
    video.addEventListener('play', () => emitTrackUpdate(true))
    video.addEventListener('pause', () => emitTrackUpdate(true))
    video.addEventListener('emptied', () => emitTrackUpdate(true))
  }
}

const observer = new MutationObserver(() => {
  watchVideo()
  emitTrackUpdate(false)
})
observer.observe(document.documentElement, { childList: true, subtree: true })

setInterval(() => emitTrackUpdate(false), POLL_MS)
setInterval(() => emitTrackUpdate(true), HEARTBEAT_MS)
watchVideo()
emitTrackUpdate(true)
