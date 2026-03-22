import Foundation

// Load MediaRemote private framework
let bundle = CFBundleCreate(kCFAllocatorDefault, NSURL(fileURLWithPath: "/System/Library/PrivateFrameworks/MediaRemote.framework") as CFURL)

let ptrGet = CFBundleGetFunctionPointerForName(bundle, "MRMediaRemoteGetNowPlayingInfo" as CFString)
let ptrReg = CFBundleGetFunctionPointerForName(bundle, "MRMediaRemoteRegisterForNowPlayingNotifications" as CFString)

typealias MRMediaRemoteGetNowPlayingInfoFunction = @convention(c) (DispatchQueue, @escaping ([String: Any]) -> Void) -> Void
typealias MRMediaRemoteRegisterForNowPlayingNotificationsFunction = @convention(c) (DispatchQueue) -> Void

let MRMediaRemoteGetNowPlayingInfo = unsafeBitCast(
    ptrGet,
    to: MRMediaRemoteGetNowPlayingInfoFunction.self
)

let MRMediaRemoteRegisterForNowPlayingNotifications = unsafeBitCast(
    ptrReg,
    to: MRMediaRemoteRegisterForNowPlayingNotificationsFunction.self
)

var lastPrintedKey = ""

func stringFrom(_ dict: [AnyHashable: Any], keys: [String]) -> String {
    for k in keys {
        if let v = dict[k] as? String, !v.isEmpty { return v }
        if let v = dict[k] as? NSString, v.length > 0 { return v as String }
    }
    return ""
}

func emitIfChanged(title: String, artist: String, album: String) {
    let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
    let a = artist.trimmingCharacters(in: .whitespacesAndNewlines)
    let b = album.trimmingCharacters(in: .whitespacesAndNewlines)
    let key = "\(t)|\(a)|\(b)"
    if key == lastPrintedKey { return }
    lastPrintedKey = key
    let out: [String: String] = ["title": t, "artist": a, "album": b]
    if let data = try? JSONSerialization.data(withJSONObject: out),
       let str = String(data: data, encoding: .utf8) {
        print(str)
        fflush(stdout)
    }
}

func handleMusicDistributedUserInfo(_ userInfo: [AnyHashable: Any]?) {
    guard let dict = userInfo else { return }
    let state = stringFrom(dict, keys: ["Player State", "playerState"])
    if !state.isEmpty {
        let lower = state.lowercased()
        if lower.contains("pause") || lower.contains("stop") { return }
        if !lower.contains("play") { return }
    }
    let title = stringFrom(dict, keys: ["Name", "name", "Track", "track"])
    let artist = stringFrom(dict, keys: ["Artist", "artist"])
    let album = stringFrom(dict, keys: ["Album", "album"])
    if !title.isEmpty {
        emitIfChanged(title: title, artist: artist, album: album)
    }
}

func parseYtMusicTabTitle(_ tabTitle: String) -> (String, String) {
    var clean = tabTitle.trimmingCharacters(in: .whitespacesAndNewlines)
    // Chrome tab/window: "Track | YouTube Music – Audio playing - Google Chrome – …"
    if let r = clean.range(of: " | YouTube Music", options: .caseInsensitive) {
        clean = String(clean[..<r.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    let suffix = " - YouTube Music"
    if clean.hasSuffix(suffix) {
        clean = String(clean.dropLast(suffix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    for sep in [" • ", " · ", " — ", " | "] {
        if let r = clean.range(of: sep) {
            let left = String(clean[..<r.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
            let right = String(clean[r.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
            return (left, right)
        }
    }
    return (clean, "")
}

/// Scans Chromium-family browsers + Safari for an open `music.youtube.com` tab (matches `music_album_light_sync.py`).
func findYtMusicInBrowserTabs() -> (title: String, artist: String)? {
    let script = """
    on findYtMusicInChromium(appName)
        tell application "System Events"
            set appRunning to exists (processes where name is appName)
        end tell
        if appRunning is false then
            return ""
        end if

        tell application appName
            repeat with w in windows
                try
                    set t to active tab of w
                    set u to URL of t
                    if (u contains "music.youtube.com") or (u contains "youtube.com/music") then
                        set ttl to title of t
                        return appName & "|||" & ttl & "|||" & u
                    end if
                end try
                try
                    repeat with t in tabs of w
                        set u to URL of t
                        if (u contains "music.youtube.com") or (u contains "youtube.com/music") then
                            set ttl to title of t
                            return appName & "|||" & ttl & "|||" & u
                        end if
                    end repeat
                end try
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
                    if (u contains "music.youtube.com") or (u contains "youtube.com/music") then
                        set ttl to name of t
                        return "Safari" & "|||" & ttl & "|||" & u
                    end if
                end repeat
            end repeat
        end tell
        return ""
    end findYtMusicInSafari

    on findYtMusicPWAStandalone()
        tell application "System Events"
            try
                tell process "YouTube Music"
                    repeat with wx in windows
                        try
                            set wt to (name of wx) as string
                            if (count of wt) > 2 then
                                set skipTitle to false
                                ignoring case
                                    if wt is "youtube music" then set skipTitle to true
                                    if wt is "new tab" then set skipTitle to true
                                end ignoring
                                if skipTitle is false then
                                    return "YouTube Music" & "|||" & wt & "|||axPwa"
                                end if
                            end if
                        end try
                    end repeat
                end tell
            end try
        end tell
        return ""
    end findYtMusicPWAStandalone

    on findYtMusicViaAXWindowTitles()
        tell application "System Events"
            set browserNames to {"Google Chrome", "Chromium", "Brave Browser", "Microsoft Edge", "Arc", "Opera", "Vivaldi"}
            repeat with pn in browserNames
                set pname to pn as string
                try
                    tell process pname
                        repeat with wx in windows
                            try
                                set wt to (name of wx) as string
                                ignoring case
                                    if wt contains "youtube music" then
                                        return pname & "|||" & wt & "|||axScan"
                                    end if
                                end ignoring
                            end try
                        end repeat
                    end tell
                end try
            end repeat
        end tell
        return ""
    end findYtMusicViaAXWindowTitles

    try
        set resultLine to findYtMusicPWAStandalone()
        if resultLine is not "" then return resultLine
    end try

    try
        set resultLine to findYtMusicInChromium("Google Chrome")
        if resultLine is not "" then return resultLine
    end try

    try
        set resultLine to findYtMusicInChromium("Brave Browser")
        if resultLine is not "" then return resultLine
    end try

    try
        set resultLine to findYtMusicInChromium("Microsoft Edge")
        if resultLine is not "" then return resultLine
    end try

    try
        set resultLine to findYtMusicInChromium("Arc")
        if resultLine is not "" then return resultLine
    end try

    try
        set resultLine to findYtMusicInChromium("Chromium")
        if resultLine is not "" then return resultLine
    end try

    try
        set resultLine to findYtMusicInChromium("Google Chrome Canary")
        if resultLine is not "" then return resultLine
    end try

    try
        set resultLine to findYtMusicInChromium("Opera")
        if resultLine is not "" then return resultLine
    end try

    try
        set resultLine to findYtMusicInChromium("Vivaldi")
        if resultLine is not "" then return resultLine
    end try

    try
        set resultLine to findYtMusicInChromium("YouTube Music")
        if resultLine is not "" then return resultLine
    end try

    try
        set resultLine to findYtMusicInSafari()
        if resultLine is not "" then return resultLine
    end try

    set resultLine to findYtMusicViaAXWindowTitles()
    if resultLine is not "" then return resultLine

    return ""
    """

    var err: NSDictionary?
    guard let sc = NSAppleScript(source: script) else { return nil }
    let r = sc.executeAndReturnError(&err)
    let line = r.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if !line.isEmpty, let hit = parseYtBrowserScanLine(line) {
        return hit
    }
    return findYtMusicFromFrontmostWindow()
}

private func parseYtBrowserScanLine(_ line: String) -> (String, String)? {
    let parts = line.components(separatedBy: "|||")
    guard parts.count == 3 else { return nil }
    let tabTitle = parts[1]
    let parsed = parseYtMusicTabTitle(tabTitle)
    if parsed.0.isEmpty { return nil }
    return parsed
}

/// Standalone / Web App windows often do not expose Chromium `tabs` to AppleScript; front window title still matches YT Music.
private func findYtMusicFromFrontmostWindow() -> (String, String)? {
    let script = """
    tell application "System Events"
        set frontProc to name of first application process whose frontmost is true
        set wt to ""
        try
            tell process frontProc
                set wt to name of front window
            end tell
        end try
    end tell
    if wt is not "" then
        if frontProc is "YouTube Music" then
            if (count of wt) > 2 then
                set skipTitle to false
                ignoring case
                    if wt is "youtube music" then set skipTitle to true
                    if wt is "new tab" then set skipTitle to true
                end ignoring
                if skipTitle is false then
                    return frontProc & "|||" & wt & "|||frontWindow"
                end if
            end if
        else
            if wt contains "YouTube Music" then
                return frontProc & "|||" & wt & "|||frontWindow"
            end if
        end if
    end if
    return ""
    """

    var err: NSDictionary?
    guard let sc = NSAppleScript(source: script) else { return nil }
    let r = sc.executeAndReturnError(&err)
    let line = r.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if line.isEmpty { return nil }
    return parseYtBrowserScanLine(line)
}

func appleScriptPlaying(app: String) -> (String, String, String)? {
    let esc = app.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
    let src = """
    tell application "\(esc)"
      if not running then return ""
      if player state is playing then
        return (name of current track) & "|||" & (artist of current track) & "|||" & (album of current track)
      end if
    end tell
    return ""
    """
    var err: NSDictionary?
    guard let script = NSAppleScript(source: src) else { return nil }
    let r = script.executeAndReturnError(&err)
    guard let s = r.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
    let parts = s.components(separatedBy: "|||")
    let title = parts.count > 0 ? parts[0] : ""
    let artist = parts.count > 1 ? parts[1] : ""
    let album = parts.count > 2 ? parts[2] : ""
    if title.isEmpty { return nil }
    return (title, artist, album)
}

func refreshAppleScriptSources(trigger: String) {
    if let m = appleScriptPlaying(app: "Music") {
        emitIfChanged(title: m.0, artist: m.1, album: m.2, source: "appleScriptMusic")
        return
    }
    if let s = appleScriptPlaying(app: "Spotify") {
        emitIfChanged(title: s.0, artist: s.1, album: s.2, source: "appleScriptSpotify")
        return
    }
    if let yt = findYtMusicInBrowserTabs() {
        emitIfChanged(title: yt.title, artist: yt.artist, album: "", source: yt.source)
        return
    }
    // #region agent log
    agentLog(
        hypothesisId: "verify",
        location: "nowplaying.swift:refreshAppleScriptSources",
        message: "AppleScript poll (no track from Music, Spotify, or YT Music tab)",
        data: ["trigger": trigger]
    )
    // #endregion
}

/// YouTube Music in a browser does not post distributed notifications; poll when native players are idle.
func refreshYoutubeMusicBrowserOnly(trigger: String) {
    if appleScriptPlaying(app: "Music") != nil { return }
    if appleScriptPlaying(app: "Spotify") != nil { return }
    if let yt = findYtMusicInBrowserTabs() {
        emitIfChanged(title: yt.title, artist: yt.artist, album: "", source: yt.source)
    }
}

func fetchNowPlaying(trigger: String) {
    fetchSeq += 1
    let seq = fetchSeq
    MRMediaRemoteGetNowPlayingInfo(DispatchQueue.main) { info in
        let keysSorted = info.keys.map { "\($0)" }.sorted()
        let keysJoined = keysSorted.joined(separator: ",")
        let titleKey = "kMRMediaRemoteNowPlayingInfoTitle"
        let artistKey = "kMRMediaRemoteNowPlayingInfoArtist"
        let albumKey = "kMRMediaRemoteNowPlayingInfoAlbum"
        let rawTitle = info[titleKey]
        let rawArtist = info[artistKey]
        let rawAlbum = info[albumKey]
        // #region agent log
        agentLog(
            hypothesisId: "H2_H3_H5",
            location: "nowplaying.swift:callback",
            message: "MRMediaRemoteGetNowPlayingInfo callback",
            data: [
                "trigger": trigger,
                "fetchSeq": seq,
                "keyCount": info.count,
                "keysSample": String(keysJoined.prefix(4000)),
                "titlePresent": rawTitle != nil,
                "titleSwiftType": rawTitle.map { String(describing: Swift.type(of: $0)) } ?? "nil",
                "titleAsStringOk": (rawTitle as? String) != nil,
                "artistAsStringOk": (rawArtist as? String) != nil,
                "albumAsStringOk": (rawAlbum as? String) != nil,
                "keysContainingTitle": keysSorted.filter { $0.contains("Title") || $0.contains("title") }.joined(separator: ";"),
            ]
        )
        // #endregion
        let title  = info[titleKey] as? String ?? ""
        let artist = info[artistKey] as? String ?? ""
        let album  = info[albumKey] as? String ?? ""
        if !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            emitIfChanged(title: title, artist: artist, album: album, source: "mediaRemote")
        } else if lastPrintedKey.isEmpty {
            lastPrintedKey = "||"
            let out: [String: String] = ["title": "", "artist": "", "album": ""]
            if let data = try? JSONSerialization.data(withJSONObject: out),
               let str = String(data: data, encoding: .utf8) {
                print(str)
                fflush(stdout)
            }
        }
    }
}

MRMediaRemoteRegisterForNowPlayingNotifications(DispatchQueue.main)
// #region agent log
agentLog(hypothesisId: "H4", location: "nowplaying.swift:after-register", message: "Registered for now playing notifications", data: [:])
// #endregion

NotificationCenter.default.addObserver(
    forName: NSNotification.Name("kMRMediaRemoteNowPlayingInfoDidChangeNotification"),
    object: nil,
    queue: .main
) { _ in
    // #region agent log
    agentLog(hypothesisId: "H4", location: "nowplaying.swift:notification", message: "kMRMediaRemoteNowPlayingInfoDidChangeNotification fired", data: [:])
    // #endregion
    fetchNowPlaying(trigger: "notification")
}

let dist = DistributedNotificationCenter.default()
let musicName = Notification.Name("com.apple.Music.playerInfo")
dist.addObserver(forName: musicName, object: nil, queue: .main) { n in
    handleMusicDistributedUserInfo(n.userInfo)
}
dist.addObserver(forName: Notification.Name("com.apple.iTunes.playerInfo"), object: nil, queue: .main) { n in
    handleMusicDistributedUserInfo(n.userInfo)
}
dist.addObserver(forName: Notification.Name("com.spotify.client.PlaybackStateChanged"), object: nil, queue: .main) { _ in
    refreshAppleScriptSources(trigger: "spotifyDist")
}

fetchNowPlaying(trigger: "immediate")

DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
    refreshAppleScriptSources(trigger: "startup-delay")
}

Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { _ in
    refreshYoutubeMusicBrowserOnly(trigger: "interval")
}

RunLoop.main.run()
