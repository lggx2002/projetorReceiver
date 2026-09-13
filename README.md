# ProjectorReceiver

Minimal Android TV/projector receiver for direct local-network playback. A Windows PC resolves a webpage into a playable stream URL, then the projector fetches that URL itself. This is not screen mirroring or screencasting.

## Build

```sh
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Use from Windows

1. Install Python 3 and `yt-dlp` on the Windows PC:

   ```powershell
   py -m pip install -U yt-dlp
   ```

2. Start ProjectorReceiver on the projector. The connection screen shows its LAN control URL, for example `http://192.168.1.50:8080`.

3. Resolve a normal webpage URL and play it directly on the projector:

   ```powershell
   py windows-helper/resolve_and_play.py `
     --projector http://192.168.1.50:8080 `
     "https://example.com/watch/123"
   ```

   The helper asks yt-dlp for a combined H.264/AAC stream, prefers up to 720p, avoids AV1/VP9, does not download the movie, and does not proxy or transcode it through Windows.

4. For an already-resolved URL:

   ```powershell
   py windows-helper/send_to_projector.py `
     --projector http://192.168.1.50:8080 `
     --url "https://cdn.example.com/master.m3u8" `
     --header "Referer: https://example.com/" `
     --header "User-Agent: Mozilla/5.0" `
     --header "Cookie: session=..."
   ```

   The helper also supports `status`, `pause`, `resume`, `stop`, `seek`, and `volume` commands:

   ```powershell
   py windows-helper/send_to_projector.py --projector http://192.168.1.50:8080 status
   py windows-helper/send_to_projector.py --projector http://192.168.1.50:8080 pause
   py windows-helper/send_to_projector.py --projector http://192.168.1.50:8080 resume
   py windows-helper/send_to_projector.py --projector http://192.168.1.50:8080 stop
   py windows-helper/send_to_projector.py --projector http://192.168.1.50:8080 --position-ms 3600000 seek
   py windows-helper/send_to_projector.py --projector http://192.168.1.50:8080 --volume 0.75 volume
   ```

The same controls are available from the lightweight browser page at the projector URL. The page uses plain HTML, CSS, and JavaScript and is served by the Android app itself.

## HTTP API

- `GET /status` returns state, URL, position, duration, buffered position, decoder, error, and video dimensions.
- `POST /play` accepts `{ "url": "...", "type": "auto", "headers": {} }`.
- `POST /pause`, `/resume`, and `/stop` control playback.
- `POST /seek` accepts `{ "positionMs": 3600000 }`.
- `POST /volume` accepts `{ "volume": 0.75 }`.

Supported types are `auto`, `hls`, `dash`, `progressive`, and `rtsp`. In auto mode the app recognizes `.m3u8`, `.mpd`, common MP4 URLs, and `rtsp://`; Media3 handles content-type inference for signed URLs without recognizable extensions. Headers are applied through Media3's HTTP data source to manifests, segments, and redirects. RTSP keeps the existing RTP-over-TCP path; HTTP headers do not apply to RTSP.

The URL is passed through unchanged, including long signed query strings. The embedded server listens on port 8080 and uses no cloud service. An optional `X-Projector-Token` check is structured in `ControlServer.kt` and disabled by default for simple LAN setup; enable `CONTROL_TOKEN_ENABLED` for a LAN that needs it. The generated token is stored in SharedPreferences and logged with the `ProjectorReceiver` tag.

## Playback limitations

Playback uses Android MediaCodec through Media3 ExoPlayer. Hardware decoders are preferred, with decoder selection logged as `video/avc decoder initialized`. The app does not include FFmpeg, VLC, software decoder libraries, a WebView, transcoding, or screencasting.

`resolve_and_play.py` refuses yt-dlp results that require separate video/audio merging because the current projector implementation cannot merge them. Use a combined H.264/AAC or directly playable HLS/DASH stream instead. DRM-protected streams are not supported merely by extracting their URL; the projector has no DRM license/session integration.
