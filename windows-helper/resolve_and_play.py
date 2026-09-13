#!/usr/bin/env python3
"""Resolve a webpage with yt-dlp, then send the direct stream to a projector."""

import argparse
import json
import subprocess
import sys
import urllib.error
import urllib.request


# Prefer combined formats: the projector cannot merge separate video/audio URLs.
# Separate fallbacks are included only so the helper can report that limitation.
# 720p is preferred to keep the RK3128 decoder and network path comfortable.
FORMAT_SELECTOR = (
    "b[vcodec^=avc1][acodec^=mp4a][height<=720][ext=mp4]/"
    "b[vcodec^=avc1][acodec^=mp4a][height<=1080][ext=mp4]/"
    "b[vcodec^=avc1][acodec^=mp4a][height<=720]/"
    "b[vcodec^=avc1][acodec^=mp4a][height<=1080]/"
    "b[vcodec^=avc1][height<=720]/"
    "b[vcodec^=avc1][height<=1080]/"
    "b[height<=720][vcodec!*=av01][vcodec!*=vp9]/"
    "b[height<=1080][vcodec!*=av01][vcodec!*=vp9]/"
    "bv*[vcodec^=avc1][height<=720]+ba[acodec^=mp4a]/"
    "bv*[vcodec^=avc1][height<=1080]+ba[acodec^=mp4a]"
)


def projector_request(projector, payload, token=None, timeout=15):
    request = urllib.request.Request(
        projector.rstrip("/") + "/play",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            **({"X-Projector-Token": token} if token else {}),
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"projector returned HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"could not reach projector: {error.reason}") from error


def resolve(page_url, yt_dlp="yt-dlp"):
    command = [
        yt_dlp,
        "--dump-single-json",
        "--no-download",
        "--no-playlist",
        "--no-warnings",
        "--format",
        FORMAT_SELECTOR,
        page_url,
    ]
    try:
        completed = subprocess.run(command, check=True, capture_output=True, text=True)
    except FileNotFoundError as error:
        raise RuntimeError("yt-dlp was not found. Install it with: py -m pip install -U yt-dlp") from error
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout).strip()
        raise RuntimeError(f"yt-dlp could not resolve the page: {detail}") from error
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError("yt-dlp returned invalid JSON") from error


def pick_direct_stream(info):
    requested_formats = info.get("requested_formats") or []
    if len(requested_formats) > 1:
        formats = ", ".join(str(item.get("format_id", "unknown")) for item in requested_formats)
        raise RuntimeError(
            "yt-dlp selected separate audio/video formats "
            f"({formats}). This projector cannot merge them; choose a combined H.264/AAC or HLS format."
        )
    url = info.get("url")
    if not url:
        raise RuntimeError("yt-dlp did not return a direct playable URL")
    headers = dict(info.get("http_headers") or {})
    headers.pop("Host", None)
    headers.pop("Content-Length", None)
    return url, headers


def infer_type(url, info):
    protocol = str(info.get("protocol") or "").lower()
    lower = url.lower().split("?", 1)[0]
    if protocol.startswith("rtsp") or lower.startswith("rtsp://"):
        return "rtsp"
    if "m3u8" in protocol or lower.endswith(".m3u8"):
        return "hls"
    if "dash" in protocol or lower.endswith(".mpd"):
        return "dash"
    return "progressive"


def main():
    parser = argparse.ArgumentParser(description="Resolve a webpage with yt-dlp and play it directly on ProjectorReceiver.")
    parser.add_argument("--projector", required=True, help="Projector base URL, e.g. http://192.168.1.50:8080")
    parser.add_argument("--token", help="Optional X-Projector-Token value")
    parser.add_argument("--yt-dlp", default="yt-dlp", help="yt-dlp executable or full path")
    parser.add_argument("page_url", help="Normal webpage URL, not a direct stream URL")
    args = parser.parse_args()

    try:
        print("Resolving with yt-dlp…", file=sys.stderr)
        info = resolve(args.page_url, args.yt_dlp)
        url, headers = pick_direct_stream(info)
        stream_type = infer_type(url, info)
        payload = {"url": url, "type": stream_type, "headers": headers}
        print(f"Resolved {stream_type} stream; sending URL and {len(headers)} header(s) to projector…", file=sys.stderr)
        print(projector_request(args.projector, payload, args.token))
    except RuntimeError as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
