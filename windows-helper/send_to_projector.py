#!/usr/bin/env python3
"""Send explicit player commands to a ProjectorReceiver on the LAN."""

import argparse
import json
import sys
import urllib.error
import urllib.request


def parse_header(value):
    name, separator, header_value = value.partition(":")
    if not separator or not name.strip():
        raise argparse.ArgumentTypeError("headers must look like 'Name: value'")
    return name.strip(), header_value.strip()


def request(projector, path, payload=None, token=None, timeout=10):
    url = projector.rstrip("/") + path
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["X-Projector-Token"] = token
    request_obj = urllib.request.Request(url, data=body, headers=headers, method="POST" if body is not None else "GET")
    try:
        with urllib.request.urlopen(request_obj, timeout=timeout) as response:
            text = response.read().decode("utf-8")
            if text:
                print(json.dumps(json.loads(text), indent=2))
            else:
                print("ok")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        print(f"Projector returned HTTP {error.code}: {detail}", file=sys.stderr)
        return 1
    except urllib.error.URLError as error:
        print(f"Could not reach projector: {error.reason}", file=sys.stderr)
        return 1
    return 0


def main():
    parser = argparse.ArgumentParser(description="Control ProjectorReceiver over the local network.")
    parser.add_argument("--projector", required=True, help="Projector base URL, e.g. http://192.168.1.50:8080")
    parser.add_argument("--token", help="Optional X-Projector-Token value")
    parser.add_argument("--header", action="append", type=parse_header, default=[], help="HTTP header for play; repeatable")
    parser.add_argument("--url", help="Stream URL for play")
    parser.add_argument("--type", choices=("auto", "hls", "dash", "progressive", "rtsp"), default="auto")
    parser.add_argument("--position-ms", type=int, help="Position for the seek command")
    parser.add_argument("--volume", type=float, help="Volume from 0.0 to 1.0")
    parser.add_argument("command", nargs="?", default="play", choices=("play", "status", "pause", "resume", "stop", "seek", "volume"))
    args = parser.parse_args()

    if args.command == "play":
        if not args.url:
            parser.error("play requires --url")
        payload = {"url": args.url, "type": args.type, "headers": dict(args.header)}
        return request(args.projector, "/play", payload, args.token)
    if args.command == "seek":
        if args.position_ms is None or args.position_ms < 0:
            parser.error("seek requires --position-ms >= 0")
        return request(args.projector, "/seek", {"positionMs": args.position_ms}, args.token)
    if args.command == "volume":
        if args.volume is None or not 0.0 <= args.volume <= 1.0:
            parser.error("volume requires --volume between 0.0 and 1.0")
        return request(args.projector, "/volume", {"volume": args.volume}, args.token)
    if args.command == "status":
        return request(args.projector, "/status", token=args.token)
    return request(args.projector, "/" + args.command, {}, args.token)


if __name__ == "__main__":
    sys.exit(main())
