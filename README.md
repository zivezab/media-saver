# Media Saver

Tools for saving media from a link — a post, a video page, or a direct file URL —
onto a device you control.

The repository holds two independent programs that solve the same problem for
different situations:

```
media-saver/
├── android/    Standalone Android app - extraction runs on the phone
└── server/     Self-hosted server + mobile web UI - extraction runs on a Mac
```

| | `android/` | `server/` |
| --- | --- | --- |
| Where extraction runs | On the phone | On your Mac |
| Needs a computer running | No | Yes, on the same Wi-Fi |
| Works on mobile data | Yes | No |
| Saves to | Phone's Downloads | Whichever browser you opened it in |
| Install size | Large (bundles Python + ffmpeg) | Small |
| Photos | Yes, via gallery-dl | No |

Both use [yt-dlp](https://github.com/yt-dlp/yt-dlp) to identify and fetch media,
and [ffmpeg](https://ffmpeg.org/) to combine separate video and audio streams
into one playable file.

Each directory has its own README with setup and usage:

- [`android/README.md`](android/README.md)
- [`server/README.md`](server/README.md)

## Legal notice and acceptable use

**Read this before using either program.**

These tools are provided for personal use with material you have the right to
download. You are responsible for what you do with them.

**Copyright.** Most content on social and video platforms is protected by
copyright. Downloading it without permission from the rights holder may be
unlawful where you live, regardless of whether a technical means exists to do
so. Reasonable uses include: media you created and uploaded yourself; material
published under a licence that permits redistribution, such as Creative Commons;
material in the public domain; and personal-archive or accessibility uses where
your local law provides an exception. Redistributing what you download, or using
it commercially, is a separate question and usually requires a licence.

**Platform terms.** Downloading generally violates the terms of service of the
platform serving the content, whether or not it violates copyright law. That is
a matter between you and the platform, and it can result in your account being
restricted or closed. Nothing here circumvents paywalls, DRM, or access
controls: the tools fetch what a logged-in browser could already fetch, and the
optional cookie feature in `server/` acts as *your own* logged-in session, not
as a way into anyone else's.

**Privacy.** Media of identifiable people may carry obligations beyond
copyright, and non-public posts shared with you in confidence are not yours to
redistribute.

**No warranty.** These tools are provided as is, without warranty of any kind.
The authors accept no liability for any claim or damages arising from their use,
including any consequence of your account being restricted by a platform.

**No affiliation.** This project is not affiliated with, endorsed by, or
connected to any of the platforms whose content it can retrieve.

If you are unsure whether a particular download is permitted, the safe assumption
is that it is not.

## Third-party components

| Component | Licence | Notes |
| --- | --- | --- |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Unlicense | Does the extraction in both programs |
| [ffmpeg](https://ffmpeg.org/) | LGPL / GPL depending on build | Merges separate streams |
| [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) | GPL-3.0 | Ships yt-dlp, Python and ffmpeg to the phone |
| [gallery-dl](https://github.com/mikf/gallery-dl) | GPL-2.0-only | Photos, in the Android app |
| requests, urllib3, idna, charset_normalizer, certifi | Apache-2.0, MIT, BSD-3-Clause, MIT, MPL-2.0 | Bundled with gallery-dl |

The Android app links `youtubedl-android`, which is GPL-3.0. If you distribute
that app to anyone else, the GPL's terms apply to what you distribute — including
making the corresponding source available. Keeping a build on your own phone is
not distribution.

gallery-dl is GPL-2.0-only, which is not compatible with GPL-3.0 for combining
into a single work. It is not combined: it ships as a separate program inside
`assets/gallery-dl.pyz`, runs in its own process under the Python interpreter,
and talks to the app only through command-line arguments and its output. The
licence texts of it and of each bundled dependency travel inside that archive.
Anyone distributing the APK should still make gallery-dl's source available and
satisfy both licences; this is a description of how it is built, not legal
advice.
