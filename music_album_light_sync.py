#!/usr/bin/env python3
import colorsys
import io
import json
import shutil
import subprocess
import time
import urllib.parse
import urllib.request

import tinytuya
from PIL import Image


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

BRIGHTNESS_FIXED = 900
MIN_SATURATION = 180
IGNORE_DARK_PIXELS_BELOW = 25


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
    script = r'''
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
'''

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
    script = r'''
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
'''
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
    script = r'''
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
'''
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


def get_now_playing() -> dict | None:
    return (
        get_system_now_playing()
        or get_yt_music_from_browser_tabs()
        or get_spotify_now_playing()
        or get_music_now_playing()
    )


def download_image(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=10) as response:
        return response.read()


def _to_int(value: object, default: int = 0) -> int:
    if isinstance(value, (int, float)):
        return int(value)
    return default


def _pixel_to_rgb(pixel: object) -> tuple[int, int, int]:
    if isinstance(pixel, tuple) and len(pixel) >= 3:
        return _to_int(pixel[0]), _to_int(pixel[1]), _to_int(pixel[2])
    if isinstance(pixel, (int, float)):
        v = int(pixel)
        return v, v, v
    return 0, 0, 0


def pick_dominant_rgb(image_data: bytes) -> tuple[int, int, int]:
    image = Image.open(io.BytesIO(image_data)).convert("RGB")
    image.thumbnail((160, 160))
    quantized = image.quantize(colors=12, method=Image.Quantize.MEDIANCUT)
    palette = quantized.getpalette()
    color_counts = sorted(quantized.getcolors() or [], reverse=True)

    if not palette:
        return _pixel_to_rgb(image.resize((1, 1)).getpixel((0, 0)))

    for count, palette_index_raw in color_counts:
        palette_index = (
            _to_int(palette_index_raw[0])
            if isinstance(palette_index_raw, tuple) and palette_index_raw
            else _to_int(palette_index_raw)
        )
        base = palette_index * 3
        if base + 2 >= len(palette):
            continue
        r = _to_int(palette[base])
        g = _to_int(palette[base + 1])
        b = _to_int(palette[base + 2])
        if count <= 0:
            continue
        if max(r, g, b) < IGNORE_DARK_PIXELS_BELOW:
            continue
        return r, g, b

    return _pixel_to_rgb(image.resize((1, 1)).getpixel((0, 0)))


def rgb_to_tuya_hsv_hex(r: int, g: int, b: int, value_override: int = BRIGHTNESS_FIXED) -> tuple[int, int, int, str]:
    h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
    h_i = max(0, min(360, int(round(h * 360))))
    s_i = max(MIN_SATURATION, min(1000, int(round(s * 1000))))
    v_i = max(10, min(1000, int(value_override)))
    payload = f"{h_i:04x}{s_i:04x}{v_i:04x}"
    return h_i, s_i, v_i, payload


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


def set_bulb_to_album_color(bulb: tinytuya.BulbDevice, rgb: tuple[int, int, int]) -> None:
    r, g, b = rgb
    h, s, v, colour_hex = rgb_to_tuya_hsv_hex(r, g, b)
    payload = {
        "20": True,
        "21": "colour",
        "24": colour_hex,
    }
    response = bulb.set_multiple_values(payload)
    log("LIGHT", f"RGB=({r},{g},{b}) -> H={h} S={s} V={v} | response={response}")


def main() -> None:
    bulb = build_bulb()
    last_signature = ""
    last_seen_track = ""
    log("INFO", "Listening for currently playing track... press Ctrl+C to stop.")

    while True:
        try:
            now_playing = get_now_playing()
            if not now_playing:
                if last_seen_track:
                    log("INFO", "No active song detected")
                    last_seen_track = ""
                time.sleep(POLL_INTERVAL_SECONDS)
                continue

            current_track = (
                f"{now_playing.get('title', '')} - {now_playing.get('artist', '')} "
                f"[{now_playing.get('source', 'Unknown')}]"
            ).strip()
            if current_track != last_seen_track:
                log("DETECT", f"Detected song: {current_track}")
                last_seen_track = current_track

            sig = "|".join(
                [
                    now_playing.get("source", ""),
                    now_playing.get("title", ""),
                    now_playing.get("artist", ""),
                    now_playing.get("album", ""),
                    now_playing.get("artwork_url", ""),
                ]
            )
            if sig == last_signature:
                time.sleep(POLL_INTERVAL_SECONDS)
                continue

            artwork_url = now_playing.get("artwork_url", "")
            if not artwork_url:
                log(
                    "SKIP",
                    f"No artwork URL for {now_playing['title']} - {now_playing['artist']}",
                )
                last_signature = sig
                time.sleep(POLL_INTERVAL_SECONDS)
                continue

            image_data = download_image(artwork_url)
            rgb = pick_dominant_rgb(image_data)
            log(
                "TRACK",
                f"Applying color for: {now_playing['title']} - {now_playing['artist']} ({now_playing['source']})",
            )
            set_bulb_to_album_color(bulb, rgb)
            last_signature = sig
        except KeyboardInterrupt:
            log("INFO", "Stopped")
            break
        except Exception as exc:
            log("ERROR", str(exc))

        time.sleep(POLL_INTERVAL_SECONDS)


if __name__ == "__main__":
    main()
