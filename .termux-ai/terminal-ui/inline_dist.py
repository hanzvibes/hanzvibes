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

# Remove Vite's external references first.
html, n_script = re.subn(
    r'<script\s+type="module"\s+crossorigin\s+src="[^"]+"></script>',
    "",
    html,
    count=1,
)
html, n_css = re.subn(
    r'<link\s+rel="stylesheet"\s+crossorigin\s+href="[^"]+">',
    "",
    html,
    count=1,
)

if n_script != 1 or n_css != 1:
    raise SystemExit(
        f"Could not remove Vite assets: script={n_script} css={n_css}"
    )

# CSS belongs in <head>.
html = html.replace(
    "</head>",
    "<style>\n" + css + "\n</style>\n  </head>",
    1,
)

# The JS bundle MUST run after #root exists.
# Inline scripts do not honor defer, so putting it in <head> makes
# React evaluate createRoot(null) on Android WebView.
html = html.replace(
    "</body>",
    "<script>\n" + js + "\n</script>\n  </body>",
    1,
)

if 'src="./assets/' in html or 'href="./assets/' in html:
    raise SystemExit("External asset reference still present after inlining")

root_pos = html.find('<div id="root"></div>')
script_pos = html.rfind("<script>")
if root_pos < 0 or script_pos < 0 or script_pos < root_pos:
    raise SystemExit(
        f"Invalid mount order: root={root_pos}, script={script_pos}"
    )

index.write_text(html, encoding="utf-8")
shutil.rmtree(assets)

print(
    f"Inlined TerminalViewport after #root: "
    f"{len(js)} JS bytes + {len(css)} CSS bytes"
)
