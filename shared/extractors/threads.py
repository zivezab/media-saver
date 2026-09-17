# -*- coding: utf-8 -*-
"""Photos and videos from Threads posts (threads.com, formerly threads.net).

Neither gallery-dl nor yt-dlp has a Threads extractor (checked against
gallery-dl 1.32.12 and yt-dlp 2026.08.19), so Media Saver ships this one.

It has two users. The Android app hands this file's folder to gallery-dl with
-X, which registers ThreadsPostExtractor next to the built-in extractors. The
web server has no gallery-dl and imports the plain functions below instead,
which is why nothing outside the class touches gallery-dl.

How the data is found: a Threads post page embeds the post as JSON, in
Instagram's media format, but only for some clients - and which ones has
changed from one hour to the next (a curl user agent worked, then stopped;
Googlebot worked when it did not). So each agent in AGENTS is tried in turn.

There is deliberately no fallback to the page's og:image. On a text post that
is the author's profile picture, and on a video post a still frame, and the
page's meta tags do not say which kind of post it is - so it would quietly
save the wrong thing. A clear failure is better.
"""

import json
import re

PATTERN = (r"(?:https?://)?(?:www\.)?threads\.(?:com|net)"
           r"/(?:@([\w.]+)/post|t)/([\w-]+)")

AGENTS = (
    "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
    "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
    "curl/8.7.1",
)

_JSON_SCRIPT = re.compile(
    r'<script type="application/json"[^>]*>(.*?)</script>', re.S)


def page_url(username, code):
    if username:
        return "https://www.threads.com/@{}/post/{}".format(username, code)
    return "https://www.threads.com/t/{}".format(code)


def find_post(page, code):
    """The post with this shortcode, from the JSON embedded in its page.

    A page also carries replies and "related" posts from other people, so the
    match is on the shortcode rather than on the first media object found.
    """
    def walk(node):
        if isinstance(node, dict):
            if node.get("code") == code and (
                    node.get("video_versions") or node.get("image_versions2")
                    or node.get("carousel_media")):
                return node
            children = node.values()
        elif isinstance(node, list):
            children = node
        else:
            return None
        for child in children:
            found = walk(child)
            if found is not None:
                return found
        return None

    for blob in _JSON_SCRIPT.findall(page):
        if code not in blob:
            continue
        try:
            found = walk(json.loads(blob))
        except ValueError:
            continue
        if found is not None:
            return found
    return None


def _extension(url, fallback):
    match = re.search(r"\.(\w{3,4})$", url.split("?")[0])
    return match.group(1).lower() if match else fallback


def _largest_image(media):
    candidates = (media.get("image_versions2") or {}).get("candidates") or []
    if not candidates:
        return None
    return max(candidates, key=lambda c: int(c.get("width") or 0)
               * int(c.get("height") or 0))


def _one(media, width, height):
    # Carousel children carry no media_type of their own, so a video is
    # recognised by having video versions at all.
    videos = media.get("video_versions") or []
    if videos:
        # Every version seen so far was the same file under different "type"
        # numbers; the first is Instagram's highest.
        url = videos[0]["url"]
        cover = _largest_image(media)
        return {"url": url, "extension": _extension(url, "mp4"),
                "type": "video", "width": width, "height": height,
                # The app cannot draw an MP4 as a thumbnail; this it can.
                "preview": cover["url"] if cover else ""}

    best = _largest_image(media)
    if best is None:
        return None
    url = best["url"]
    return {"url": url, "extension": _extension(url, "jpg"), "type": "image",
            "width": int(best.get("width") or width or 0),
            "height": int(best.get("height") or height or 0)}


def media_items(post):
    """Every photo and video in the post, in order."""
    if post.get("carousel_media"):
        children = post["carousel_media"]
    else:
        children = [post]
    items = []
    for child in children:
        item = _one(child, int(child.get("original_width") or 0),
                    int(child.get("original_height") or 0))
        if item:
            items.append(item)
    return items


def metadata(post, username, code):
    user = post.get("user") or {}
    caption = (post.get("caption") or {}).get("text") or ""
    return {
        "post_id": code,
        "username": user.get("username") or username or "",
        "author": {"name": user.get("username") or username or ""},
        "content": caption,
        "date": post.get("taken_at"),
    }


def extract(get, username, code):
    """(metadata, items) for a post, using get(url, user_agent) -> page text.

    Raises LookupError when the post has no photos or videos to offer.
    """
    url = page_url(username, code)
    for agent in AGENTS:
        post = find_post(get(url, agent), code)
        if post is not None:
            items = media_items(post)
            if not items:
                raise LookupError("This Threads post has no photos or videos.")
            return metadata(post, username, code), items
    raise LookupError(
        "Threads did not return this post. It may be private, deleted, "
        "or only visible when signed in.")


try:
    from gallery_dl.extractor.common import Extractor, Message
    from gallery_dl import exception
except ImportError:
    # Imported by the web server, which has no gallery-dl.
    Extractor = None


if Extractor is not None:
    class ThreadsPostExtractor(Extractor):
        """A single Threads post: one photo, one video, or a carousel."""
        category = "threads"
        subcategory = "post"
        root = "https://www.threads.com"
        directory_fmt = ("{category}", "{username}")
        # Ends in {num} so the app can sort the files back into post order.
        filename_fmt = "{username}_{post_id}_{num}.{extension}"
        archive_fmt = "{post_id}_{num}"
        pattern = PATTERN
        example = "https://www.threads.com/@USER/post/CODE"

        def items(self):
            username, code = self.groups

            def get(url, agent):
                return self.request(url, headers={"User-Agent": agent}).text

            try:
                data, items = extract(get, username, code)
            except LookupError as exc:
                raise exception.AbortExtraction(str(exc))

            yield Message.Directory, "", data
            for num, item in enumerate(items, 1):
                file = dict(data)
                file.update(item)
                file["num"] = num
                del file["url"]
                yield Message.Url, item["url"], file
