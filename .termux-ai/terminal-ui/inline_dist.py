#!/usr/bin/env python3
from pathlib import Path
import re
import shutil

dist = Path(__file__).resolve().parent / "dist"
index = dist / "index.html"
assets = dist / "assets"

html = index.read_text(encoding="utf-8")

js_files = sorted(assets.glob("*.js"))
css_files = sorted(assets.glob("*.css"))
if len(js_files) != 1:
    raise SystemExit(f"Expected one JS bundle, found {len(js_files)}")
if len(css_files) != 1:
    raise SystemExit(f"Expected one CSS bundle, found {len(css_files)}")

js = js_files[0].read_text(encoding="utf-8")
css = css_files[0].read_text(encoding="utf-8")

# Defensive escaping for inline HTML parsing.
js = js.replace("</script", "<\\/script")
css = css.replace("</style", "<\\/style")

html, n1 = re.subn(
    r'<script\s+type="module"\s+crossorigin\s+src="[^"]+"></script>',
    lambda _: "<script defer>\n" + js + "\n</script>",
    html,
    count=1,
)
html, n2 = re.subn(
    r'<link\s+rel="stylesheet"\s+crossorigin\s+href="[^"]+">',
    lambda _: "<style>\n" + css + "\n</style>",
    html,
    count=1,
)

if n1 != 1 or n2 != 1:
    raise SystemExit(f"Could not inline Vite assets: script={n1} css={n2}")

if 'src="./assets/' in html or 'href="./assets/' in html:
    raise SystemExit("External asset reference still present after inlining")

index.write_text(html, encoding="utf-8")
shutil.rmtree(assets)

print(f"Inlined TerminalViewport: {len(js)} JS bytes + {len(css)} CSS bytes")
