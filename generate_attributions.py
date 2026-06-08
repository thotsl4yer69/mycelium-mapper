"""Generate image attribution metadata for the bundled species photos.

Reads the species_images/download_manifest.txt (local file -> Commons source
URL), queries the Wikimedia Commons API for each file's author and license
(extmetadata), and writes species_images/attributions.json. The app shows
these credits in Settings -> Image credits, as required by the CC licenses.
"""
import html
import json
import os
import re
import time
import urllib.parse
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
IMG_DIR = os.path.join(SCRIPT_DIR, "app", "src", "main", "assets", "species_images")
MANIFEST = os.path.join(IMG_DIR, "download_manifest.txt")
SPECIES_JSON = os.path.join(SCRIPT_DIR, "app", "src", "main", "assets", "species.json")
OUT = os.path.join(IMG_DIR, "attributions.json")

API = "https://commons.wikimedia.org/w/api.php"
UA = "MycilliyumsApp/1.0 (mazlabz.ai@gmail.com; attribution generation)"
TAG_RE = re.compile(r"<[^>]+>")


def strip_html(s):
    if not s:
        return ""
    return html.unescape(TAG_RE.sub("", s)).strip()


def api_get(params):
    url = API + "?" + urllib.parse.urlencode(dict(params, format="json"))
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def title_from_url(url):
    # .../commons/a/ab/Some_File_Name.jpg -> "File:Some File Name.jpg"
    last = urllib.parse.unquote(url.rsplit("/", 1)[-1])
    return "File:" + last


def main():
    species = {s["id"]: s for s in json.load(open(SPECIES_JSON))}
    entries = []  # (fname, sourceUrl, title)
    for line in open(MANIFEST):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        fname, url = line.split("|", 1)
        entries.append((fname.strip(), url.strip(), title_from_url(url.strip())))

    # Query extmetadata in batches of up to 50 titles.
    meta = {}
    for i in range(0, len(entries), 40):
        batch = entries[i:i + 40]
        titles = "|".join(t for _, _, t in batch)
        data = api_get({
            "action": "query", "titles": titles,
            "prop": "imageinfo", "iiprop": "extmetadata|url",
        })
        # Map normalized titles back.
        norm = {n["from"]: n["to"] for n in data.get("query", {}).get("normalized", [])}
        pages = {p.get("title"): p for p in data.get("query", {}).get("pages", {}).values()}
        for _, _, title in batch:
            page = pages.get(norm.get(title, title))
            if not page or not page.get("imageinfo"):
                continue
            em = page["imageinfo"][0].get("extmetadata", {})
            meta[title] = {
                "artist": strip_html(em.get("Artist", {}).get("value", "")) or "Unknown",
                "license": (em.get("LicenseShortName", {}).get("value", "") or "See source").strip(),
                "licenseUrl": em.get("LicenseUrl", {}).get("value", "").strip(),
                "descriptionUrl": page["imageinfo"][0].get("descriptionurl", ""),
            }
        time.sleep(1.0)

    out = []
    for fname, url, title in entries:
        sid = re.sub(r"_\d+\.jpg$", "", fname)
        sp = species.get(sid, {})
        m = meta.get(title, {})
        out.append({
            "file": fname,
            "speciesId": sid,
            "scientificName": sp.get("scientificName", sid),
            "artist": m.get("artist", "Unknown"),
            "license": m.get("license", "See source"),
            "licenseUrl": m.get("licenseUrl", ""),
            "source": m.get("descriptionUrl", "") or url,
        })

    json.dump({"source": "Wikimedia Commons", "images": out}, open(OUT, "w"), indent=2, ensure_ascii=False)
    print(f"Wrote {len(out)} attributions to {OUT}")
    missing = [e["file"] for e in out if e["artist"] == "Unknown"]
    if missing:
        print("WARNING: no author for:", missing)


if __name__ == "__main__":
    main()
