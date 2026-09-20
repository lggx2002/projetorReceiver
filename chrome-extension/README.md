# Projector Receiver Sender

Basic unpacked Chrome Manifest V3 extension for sending a browser's captured media request directly to ProjectorReceiver.

## Install locally

1. Open `chrome://extensions` in Chrome.
2. Enable **Developer mode**.
3. Choose **Load unpacked**.
4. Select this `chrome-extension` directory.

## Use

1. Open a video page and start playback.
2. Open the extension popup.
3. Enter the projector URL, for example `http://192.168.1.50:8080`, and save it.
4. Select a detected HLS, DASH, or direct video request and choose **Play on projector**.

The extension captures the URL and useful request headers, including cookies and referrers when Chrome exposes them. Captured candidates are kept in session storage and are cleared when the browser session ends. The projector fetches the stream itself; the extension does not proxy or transcode video data.

YouTube playback requests are recognized from their `googlevideo.com/videoplayback` URL parameters even when the URL has no `.mp4` suffix. YouTube often uses separate video and audio requests; ProjectorReceiver can play the detected request only when it is a combined playable stream.

The extension requests broad host access because media URLs and their initiators can be on different domains. This is intended for local, personal use. DRM-protected streams and links that require browser-generated per-segment authorization may still need a relay or native helper.
