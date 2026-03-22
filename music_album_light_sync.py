#!/usr/bin/env python3
import colorsys
import io
import importlib
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from queue import Empty, Queue
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request

import tinytuya
from PIL import Image

try:
    np = importlib.import_module("numpy")
    sd = importlib.import_module("sounddevice")
    AUDIO_IMPORT_ERROR = ""
except Exception:
    np = None
    sd = None
    AUDIO_IMPORT_ERROR = "missing numpy and/or sounddevice"


# ==========================
# Hardcoded config (edit here)
# ==========================
DEVICE_IP = "192.168.0.101"
DEVICE_ID = "d76bde5d392473b313toiy"
DEVICE_LOCAL_KEY = "tG:LKqa>K^HCJhi?"
DEVICE_VERSION = 3.5

POLL_INTERVAL_SECONDS = 3
ITUNES_STOREFRONT = "US"
NOWPLAYING_CLI_BIN = "nowplaying-cli"
BRIDGE_HOST = "127.0.0.1"
BRIDGE_PORT = 9001

BRIGHTNESS_FIXED = 900
MIN_SATURATION = 180
IGNORE_DARK_PIXELS_BELOW = 25

USE_BEAT_BRIGHTNESS_SYNC = False
BEAT_INPUT_DEVICE = None
BEAT_SAMPLE_RATE = 22050
BEAT_BLOCK_SIZE = 1024
BEAT_GAIN = 10.0
BEAT_BASE_BRIGHTNESS = 220
BEAT_MAX_BRIGHTNESS = 1000
BEAT_UPDATE_INTERVAL_SECONDS = 0.12
BEAT_MIN_BRIGHTNESS_DELTA = 25

COLOR_TRANSITION_SECONDS = 2
COLOR_TRANSITION_STEPS = 10


def log(level: str, message: str) -> None:
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{ts}] [{level}] {message}")


def run_applescript(script: str) -> str:
    result = subprocess.run(
        ["osascript", "-e", script],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return ""
    return result.stdout.strip()


class BeatEnergyTracker:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._fast = 0.0
        self._slow = 0.0
        self._level = 0.0
        self._stream = None

    def _callback(self, indata, _frames, _time_info, status) -> None:
        if status:
            return
        if np is None:
            return

        mono = indata[:, 0].astype(np.float32)
        energy = float(np.mean(np.abs(mono)))

        with self._lock:
            self._fast = 0.55 * self._fast + 0.45 * energy
            self._slow = 0.97 * self._slow + 0.03 * energy
            pulse = max(0.0, self._fast - self._slow)
            raw = min(1.0, pulse * BEAT_GAIN)
            self._level = 0.80 * self._level + 0.20 * raw

    def start(self) -> bool:
        if sd is None:
            return False
        try:
            self._stream = sd.InputStream(
                device=BEAT_INPUT_DEVICE,
                channels=1,
                samplerate=BEAT_SAMPLE_RATE,
                blocksize=BEAT_BLOCK_SIZE,
                callback=self._callback,
            )
            self._stream.start()
            return True
        except Exception:
            self._stream = None
            return False

    def stop(self) -> None:
        if self._stream is None:
            return
        try:
            self._stream.stop()
            self._stream.close()
        except Exception:
            pass
        self._stream = None

    def level(self) -> float:
        with self._lock:
            return max(0.0, min(1.0, self._level))


def run_command(args: list[str]) -> str:
    result = subprocess.run(
        args,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return ""
    return result.stdout.strip()


def get_system_now_playing() -> dict | None:
    if not shutil.which(NOWPLAYING_CLI_BIN):
        return None

    title = run_command([NOWPLAYING_CLI_BIN, "get", "title"])
    artist = run_command([NOWPLAYING_CLI_BIN, "get", "artist"])
    album = run_command([NOWPLAYING_CLI_BIN, "get", "album"])

    if title:
        title = "" if title.lower() in {"null", "(null)"} else title
        artist = "" if artist.lower() in {"null", "(null)"} else artist
        album = "" if album.lower() in {"null", "(null)"} else album

        if title:
            return {
                "source": "SystemMedia",
                "title": title,
                "artist": artist,
                "album": album,
                "artwork_url": find_artwork_via_itunes(title, artist, album),
            }

    raw = run_command([NOWPLAYING_CLI_BIN, "get-raw"])
    if not raw:
        return None

    try:
        data = json.loads(raw)
    except Exception:
        return None

    title = str(data.get("title") or "").strip()
    artist = str(data.get("artist") or "").strip()
    album = str(data.get("album") or "").strip()
    artwork_url = str(data.get("artworkURL") or "").strip()

    if not title:
        return None

    if not artwork_url:
        artwork_url = find_artwork_via_itunes(title, artist, album)

    return {
        "source": "SystemMedia",
        "title": title,
        "artist": artist,
        "album": album,
        "artwork_url": artwork_url,
    }


def _parse_yt_music_tab_title(tab_title: str) -> tuple[str, str]:
    clean = tab_title.strip()
    if clean.endswith(" - YouTube Music"):
        clean = clean[: -len(" - YouTube Music")].strip()

    # Heuristic only: many tabs include just song name.
    for sep in (" • ", " · ", " — "):
        if sep in clean:
            left, right = clean.split(sep, 1)
            return left.strip(), right.strip()

    return clean, ""


def get_yt_music_from_browser_tabs() -> dict | None:
    script = r"""
on findYtMusicInChromium(appName)
    tell application "System Events"
        set appRunning to exists (processes where name is appName)
    end tell
    if appRunning is false then
        return ""
    end if

    tell application appName
        repeat with w in windows
            repeat with t in tabs of w
                set u to URL of t
                if u contains "music.youtube.com" then
                    set ttl to title of t
                    return appName & "|||" & ttl & "|||" & u
                end if
            end repeat
        end repeat
    end tell
    return ""
end findYtMusicInChromium

on findYtMusicInSafari()
    tell application "System Events"
        set appRunning to exists (processes where name is "Safari")
    end tell
    if appRunning is false then
        return ""
    end if

    tell application "Safari"
        repeat with w in windows
            repeat with t in tabs of w
                set u to URL of t
                if u contains "music.youtube.com" then
                    set ttl to name of t
                    return "Safari" & "|||" & ttl & "|||" & u
                end if
            end repeat
        end repeat
    end tell
    return ""
end findYtMusicInSafari

set resultLine to findYtMusicInChromium("Google Chrome")
if resultLine is not "" then return resultLine

set resultLine to findYtMusicInChromium("Brave Browser")
if resultLine is not "" then return resultLine

set resultLine to findYtMusicInChromium("Microsoft Edge")
if resultLine is not "" then return resultLine

set resultLine to findYtMusicInChromium("Arc")
if resultLine is not "" then return resultLine

return findYtMusicInSafari()
"""

    out = run_applescript(script)
    if not out:
        return None

    parts = out.split("|||", 2)
    if len(parts) != 3:
        return None

    browser_name, tab_title, _tab_url = parts
    title, artist = _parse_yt_music_tab_title(tab_title)
    if not title:
        return None

    return {
        "source": f"YouTubeMusicTab:{browser_name}",
        "title": title,
        "artist": artist,
        "album": "",
        "artwork_url": find_artwork_via_itunes(title, artist, ""),
    }


def get_spotify_now_playing() -> dict | None:
    script = r"""
tell application "System Events"
    set spotifyRunning to exists (processes where name is "Spotify")
end tell
if spotifyRunning is false then
    return ""
end if

tell application "Spotify"
    if player state is not playing then
        return ""
    end if
    set trackName to name of current track
    set trackArtist to artist of current track
    set trackAlbum to album of current track
    set trackArtwork to artwork url of current track
    return trackName & "|||" & trackArtist & "|||" & trackAlbum & "|||" & trackArtwork
end tell
"""
    out = run_applescript(script)
    if not out:
        return None

    parts = out.split("|||", 3)
    if len(parts) != 4:
        return None

    return {
        "source": "Spotify",
        "title": parts[0],
        "artist": parts[1],
        "album": parts[2],
        "artwork_url": parts[3],
    }


def get_music_now_playing() -> dict | None:
    script = r"""
tell application "System Events"
    set musicRunning to exists (processes where name is "Music")
end tell
if musicRunning is false then
    return ""
end if

tell application "Music"
    if player state is not playing then
        return ""
    end if
    set trackName to name of current track
    set trackArtist to artist of current track
    set trackAlbum to album of current track
    return trackName & "|||" & trackArtist & "|||" & trackAlbum
end tell
"""
    out = run_applescript(script)
    if not out:
        return None

    parts = out.split("|||", 2)
    if len(parts) != 3:
        return None

    return {
        "source": "Music",
        "title": parts[0],
        "artist": parts[1],
        "album": parts[2],
        "artwork_url": find_artwork_via_itunes(parts[0], parts[1], parts[2]),
    }


def find_artwork_via_itunes(title: str, artist: str, album: str) -> str:
    query = " ".join([artist, album, title]).strip()
    params = urllib.parse.urlencode(
        {
            "term": query,
            "entity": "song",
            "limit": 1,
            "country": ITUNES_STOREFRONT,
        }
    )
    url = f"https://itunes.apple.com/search?{params}"

    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except Exception:
        return ""

    results = payload.get("results", [])
    if not results:
        return ""

    art = results[0].get("artworkUrl100", "")
    if not art:
        return ""
    return art.replace("100x100bb", "600x600bb")


def normalize_track_payload(payload: dict) -> dict | None:
    title = str(payload.get("title") or "").strip()
    if not title:
        return None

    artist = str(payload.get("artist") or "").strip()
    album = str(payload.get("album") or "").strip()
    source = str(payload.get("source") or "YouTube Music").strip()
    state = str(payload.get("state") or "unknown").strip().lower()

    artwork_url = str(
        payload.get("artwork_url")
        or payload.get("artworkURL")
        or payload.get("artworkUrl")
        or ""
    ).strip()
    if not artwork_url:
        artwork_url = find_artwork_via_itunes(title, artist, album)

    return {
        "source": source,
        "title": title,
        "artist": artist,
        "album": album,
        "state": state,
        "artwork_url": artwork_url,
    }


def start_track_bridge_server(track_queue: Queue):
    class BridgeHandler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            return

        def do_POST(self):
            if self.path != "/track":
                self.send_response(404)
                self.end_headers()
                return

            try:
                size = int(self.headers.get("Content-Length", "0"))
                body = self.rfile.read(size)
                payload = json.loads(body.decode("utf-8"))
                track = normalize_track_payload(payload)
                if track:
                    track_queue.put(track)

                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"ok":true}')
            except Exception as exc:
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                msg = json.dumps({"ok": False, "error": str(exc)})
                self.wfile.write(msg.encode("utf-8"))

    server = ThreadingHTTPServer((BRIDGE_HOST, BRIDGE_PORT), BridgeHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    log(
        "INFO", f"YT Music bridge listening at http://{BRIDGE_HOST}:{BRIDGE_PORT}/track"
    )
    return server


def get_now_playing_fallback() -> dict | None:
    return (
        get_system_now_playing()
        or get_spotify_now_playing()
        or get_music_now_playing()
        or get_yt_music_from_browser_tabs()
    )


def fallback_poll_worker(stop_event: threading.Event, track_queue: Queue) -> None:
    last_sig = ""
    while not stop_event.is_set():
        try:
            track = get_now_playing_fallback()
            if track:
                sig = "|".join(
                    [
                        str(track.get("source", "")),
                        str(track.get("title", "")),
                        str(track.get("artist", "")),
                        str(track.get("album", "")),
                    ]
                )
                if sig != last_sig:
                    track["state"] = "playing"
                    track_queue.put(track)
                    last_sig = sig
            else:
                last_sig = ""
        except Exception as exc:
            log("WARN", f"Fallback poll error: {exc}")

        stop_event.wait(POLL_INTERVAL_SECONDS)


def resolve_nowplaying_binary() -> str | None:
    script_dir = os.path.dirname(os.path.abspath(__file__))
    local_nowplaying = os.path.join(script_dir, "nowplaying")
    if os.path.exists(local_nowplaying) and os.access(local_nowplaying, os.X_OK):
        return local_nowplaying
    return shutil.which("nowplaying")


def nowplaying_worker(
    stop_event: threading.Event,
    track_queue: Queue,
    nowplaying_bin: str,
) -> None:
    while not stop_event.is_set():
        process: subprocess.Popen[str] | None = None
        try:
            process = subprocess.Popen(
                [nowplaying_bin],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1,
            )
            log(
                "INFO",
                f"Started nowplaying binary ({nowplaying_bin}) for event-driven tracking",
            )

            if process.stdout is None:
                raise RuntimeError("nowplaying process started without stdout pipe")

            for line in process.stdout:
                if stop_event.is_set():
                    break

                line = line.strip()
                if not line:
                    continue

                try:
                    payload = json.loads(line)
                except Exception:
                    continue

                track = normalize_track_payload(payload)
                if track:
                    track_queue.put(track)

            if stop_event.is_set():
                break

            rc = process.poll()
            log("WARN", f"nowplaying exited unexpectedly (code={rc}); restarting")
        except Exception as exc:
            if not stop_event.is_set():
                log("WARN", f"nowplaying worker error: {exc}; retrying")
        finally:
            if process is not None:
                try:
                    process.terminate()
                    process.wait(timeout=1.0)
                except Exception:
                    try:
                        process.kill()
                    except Exception:
                        pass

        stop_event.wait(1.0)


def download_image(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=10) as response:
        return response.read()


def _to_int(value: object, default: int = 0) -> int:
    if isinstance(value, (int, float)):
        return int(value)
    return default


def _pixel_to_rgb(pixel: object) -> tuple[int, int, int]:
    if isinstance(pixel, (tuple, list)) and len(pixel) >= 3:
        return _to_int(pixel[0]), _to_int(pixel[1]), _to_int(pixel[2])
    if isinstance(pixel, (int, float)):
        v = int(pixel)
        return v, v, v
    return 0, 0, 0


def pick_dominant_rgb(image_data: bytes) -> tuple[int, int, int]:
    img = Image.open(io.BytesIO(image_data)).convert("RGB")

    # Resize to speed up processing — 100px is plenty
    img = img.resize((100, 100), Image.LANCZOS)
    if hasattr(img, "get_flattened_data"):
        flat = list(img.get_flattened_data())
        if flat and isinstance(flat[0], (tuple, list)):
            # Some Pillow builds may already yield pixel tuples.
            pixels = [_pixel_to_rgb(p) for p in flat]
        else:
            # Flattened scalar channel data (R, G, B, R, G, B, ...)
            pixels = [
                _pixel_to_rgb((flat[i], flat[i + 1], flat[i + 2]))
                for i in range(0, len(flat), 3)
                if i + 2 < len(flat)
            ]
    else:
        # Pillow < version that introduced get_flattened_data
        pixels = [_pixel_to_rgb(p) for p in img.getdata()]

    scored = []
    for rgb in pixels:
        r, g, b = rgb
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)

        # Skip near-blacks, near-whites, and grays
        if v < 0.15:
            continue  # too dark
        if v > 0.95 and s < 0.1:
            continue  # near white
        if s < 0.15:
            continue  # gray / desaturated

        # Score: high saturation + mid-high brightness wins
        # Penalise extremes of brightness
        brightness_penalty = 1 - abs(v - 0.65) * 1.2
        score = s * max(brightness_penalty, 0.1)
        scored.append((score, (r, g, b)))

    if not scored:
        # Fallback: return average of all pixels
        avg = tuple(int(sum(p[i] for p in pixels) / len(pixels)) for i in range(3))
        return avg

    # Sort by score, take top 20% and average them
    scored.sort(reverse=True)
    top = [rgb for _, rgb in scored[: max(1, len(scored) // 5)]]
    avg_r = int(sum(p[0] for p in top) / len(top))
    avg_g = int(sum(p[1] for p in top) / len(top))
    avg_b = int(sum(p[2] for p in top) / len(top))

    return (avg_r, avg_g, avg_b)


def rgb_to_tuya_hsv_hex(
    r: int, g: int, b: int, value_override: int = BRIGHTNESS_FIXED
) -> tuple[int, int, int, str]:
    h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
    h_i = max(0, min(360, int(round(h * 360))))
    s_i = max(MIN_SATURATION, min(1000, int(round(s * 1000))))
    v_i = max(10, min(1000, int(value_override)))
    payload = f"{h_i:04x}{s_i:04x}{v_i:04x}"
    return h_i, s_i, v_i, payload


def hsv_to_tuya_hex(h: int, s: int, v: int) -> str:
    h_i = max(0, min(360, int(h)))
    s_i = max(MIN_SATURATION, min(1000, int(s)))
    v_i = max(10, min(1000, int(v)))
    return f"{h_i:04x}{s_i:04x}{v_i:04x}"


def _interpolate_hue(start_h: int, end_h: int, t: float) -> int:
    s = float(start_h) % 360.0
    e = float(end_h) % 360.0
    delta = ((e - s + 180.0) % 360.0) - 180.0
    return int(round((s + delta * t) % 360.0))


def build_bulb() -> tinytuya.BulbDevice:
    bulb = tinytuya.BulbDevice(
        dev_id=DEVICE_ID,
        address=DEVICE_IP,
        local_key=DEVICE_LOCAL_KEY,
        version=DEVICE_VERSION,
    )
    bulb.set_version(DEVICE_VERSION)
    bulb.set_socketPersistent(False)
    bulb.set_socketRetryLimit(2)
    bulb.set_socketTimeout(5.0)
    bulb.set_bulb_type("B")
    return bulb


def set_bulb_to_album_color(
    bulb: tinytuya.BulbDevice, rgb: tuple[int, int, int]
) -> tuple[int, int, int]:
    r, g, b = rgb
    h, s, v, colour_hex = rgb_to_tuya_hsv_hex(r, g, b)
    payload = {
        "20": True,
        "21": "colour",
        "24": colour_hex,
    }
    response = bulb.set_multiple_values(payload)
    log("LIGHT", f"RGB=({r},{g},{b}) -> H={h} S={s} V={v} | response={response}")
    return h, s, v


def set_bulb_hsv(bulb: tinytuya.BulbDevice, h: int, s: int, v: int) -> None:
    payload = {
        "20": True,
        "21": "colour",
        "24": hsv_to_tuya_hex(h, s, v),
    }
    bulb.set_multiple_values(payload)


def transition_bulb_to_album_color(
    bulb: tinytuya.BulbDevice,
    rgb: tuple[int, int, int],
    current_h: int | None,
    current_s: int | None,
    current_v: int | None,
) -> tuple[int, int, int]:
    r, g, b = rgb
    target_h, target_s, target_v, _ = rgb_to_tuya_hsv_hex(r, g, b)

    if current_h is None or current_s is None or current_v is None:
        set_bulb_hsv(bulb, target_h, target_s, target_v)
        log(
            "LIGHT",
            f"RGB=({r},{g},{b}) -> H={target_h} S={target_s} V={target_v} | direct set",
        )
        return target_h, target_s, target_v

    start_h = int(current_h)
    start_s = int(current_s)
    start_v = int(current_v)
    steps = max(1, int(COLOR_TRANSITION_STEPS))
    step_sleep = max(0.01, float(COLOR_TRANSITION_SECONDS) / steps)

    log(
        "TRANSITION",
        f"Smooth color change H:{start_h}->{target_h} S:{start_s}->{target_s} over {COLOR_TRANSITION_SECONDS:.1f}s",
    )

    for i in range(1, steps + 1):
        t = i / steps
        h = _interpolate_hue(start_h, target_h, t)
        s = int(round(start_s + (target_s - start_s) * t))
        v = int(round(start_v + (target_v - start_v) * t))
        set_bulb_hsv(bulb, h, s, v)
        time.sleep(step_sleep)

    log("LIGHT", f"Transition complete -> H={target_h} S={target_s} V={target_v}")
    return target_h, target_s, target_v


def beat_worker(
    stop_event: threading.Event,
    bulb: tinytuya.BulbDevice,
    beat_tracker: BeatEnergyTracker,
    color_state: dict,
    color_lock: threading.Lock,
) -> None:
    while not stop_event.is_set():
        with color_lock:
            active_h = color_state.get("h")
            active_s = color_state.get("s")
            last_brightness_sent = color_state.get("last_brightness")

        if active_h is not None and active_s is not None:
            beat_level = beat_tracker.level()
            brightness = int(
                BEAT_BASE_BRIGHTNESS
                + beat_level * (BEAT_MAX_BRIGHTNESS - BEAT_BASE_BRIGHTNESS)
            )
            brightness = max(10, min(1000, brightness))

            if (
                last_brightness_sent is None
                or abs(brightness - int(last_brightness_sent))
                >= BEAT_MIN_BRIGHTNESS_DELTA
            ):
                set_bulb_hsv(bulb, int(active_h), int(active_s), brightness)
                with color_lock:
                    color_state["v"] = brightness
                    color_state["last_brightness"] = brightness

        stop_event.wait(BEAT_UPDATE_INTERVAL_SECONDS)


def main() -> None:
    bulb = build_bulb()
    beat_tracker = BeatEnergyTracker() if USE_BEAT_BRIGHTNESS_SYNC else None
    stop_event = threading.Event()
    color_lock = threading.Lock()
    color_state: dict[str, int | None] = {
        "h": None,
        "s": None,
        "v": None,
        "last_brightness": None,
    }
    beat_thread = None

    if USE_BEAT_BRIGHTNESS_SYNC:
        if BEAT_INPUT_DEVICE is None:
            log("INFO", "Beat input configured: default system input (mic)")
        else:
            log("INFO", f"Beat input configured: {BEAT_INPUT_DEVICE}")

        if np is None or sd is None:
            log(
                "WARN",
                f"Beat sync disabled: {AUDIO_IMPORT_ERROR}; install numpy + sounddevice",
            )
            beat_tracker = None
        elif beat_tracker and beat_tracker.start():
            if BEAT_INPUT_DEVICE is None:
                log("INFO", "Beat input device opened: default system input (mic)")
            else:
                log("INFO", f"Beat input device opened: {BEAT_INPUT_DEVICE}")
            log("INFO", "Beat sync enabled")
            beat_thread = threading.Thread(
                target=beat_worker,
                args=(stop_event, bulb, beat_tracker, color_state, color_lock),
                daemon=True,
            )
            beat_thread.start()
        else:
            log(
                "WARN",
                "Beat sync disabled: could not open audio input device",
            )
            beat_tracker = None

    track_queue: Queue = Queue()
    bridge_server = start_track_bridge_server(track_queue)
    fallback_thread = threading.Thread(
        target=fallback_poll_worker,
        args=(stop_event, track_queue),
        daemon=True,
    )
    fallback_thread.start()

    last_signature = ""
    nowplaying_thread = None
    log(
        "INFO",
        "Starting bridge + nowplaying monitor + fallback polling... press Ctrl+C to stop.",
    )

    nowplaying_bin = resolve_nowplaying_binary()
    if nowplaying_bin:
        nowplaying_thread = threading.Thread(
            target=nowplaying_worker,
            args=(stop_event, track_queue, nowplaying_bin),
            daemon=True,
        )
        nowplaying_thread.start()
    else:
        log("WARN", "nowplaying binary not found; relying on bridge + fallback only")

    try:
        while True:
            try:
                now_playing = track_queue.get(timeout=1.0)
            except Empty:
                continue

            try:
                state = str(now_playing.get("state") or "unknown").lower()
                if state == "paused":
                    log("INFO", "Playback paused")
                    continue

                sig = "|".join(
                    [
                        str(now_playing.get("source", "")),
                        str(now_playing.get("title", "")),
                        str(now_playing.get("artist", "")),
                        str(now_playing.get("album", "")),
                    ]
                )
                if sig == last_signature:
                    continue

                log(
                    "DETECT",
                    f"Detected song: {now_playing['title']} - {now_playing['artist']} [{now_playing['source']}]",
                )

                artwork_url = now_playing.get("artwork_url", "")
                if not artwork_url:
                    log(
                        "SKIP",
                        f"No artwork URL for {now_playing['title']} - {now_playing['artist']}",
                    )
                    last_signature = sig
                    continue

                image_data = download_image(artwork_url)
                rgb = pick_dominant_rgb(image_data)
                log(
                    "TRACK",
                    f"Applying color for: {now_playing['title']} - {now_playing['artist']} ({now_playing['source']})",
                )

                with color_lock:
                    current_h = color_state.get("h")
                    current_s = color_state.get("s")
                    current_v = color_state.get("v")

                new_h, new_s, new_v = transition_bulb_to_album_color(
                    bulb,
                    rgb,
                    int(current_h) if current_h is not None else None,
                    int(current_s) if current_s is not None else None,
                    int(current_v) if current_v is not None else None,
                )

                with color_lock:
                    color_state["h"] = new_h
                    color_state["s"] = new_s
                    color_state["v"] = new_v
                    color_state["last_brightness"] = new_v

                last_signature = sig
            except Exception as exc:
                log("WARN", f"Track handling error: {exc}")
                continue
    except KeyboardInterrupt:
        log("INFO", "Stopped")
    except Exception as exc:
        log("ERROR", str(exc))
    finally:
        stop_event.set()
        if nowplaying_thread and nowplaying_thread.is_alive():
            nowplaying_thread.join(timeout=1.0)
        if beat_thread and beat_thread.is_alive():
            beat_thread.join(timeout=1.0)
        if fallback_thread.is_alive():
            fallback_thread.join(timeout=1.0)
        if beat_tracker:
            beat_tracker.stop()
        bridge_server.shutdown()
        bridge_server.server_close()


if __name__ == "__main__":
    main()
