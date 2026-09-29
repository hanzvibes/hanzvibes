#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(sys.argv[1]).resolve()
JAVA = ROOT / "app" / "src" / "main" / "java"

def load(rel):
    p = JAVA / rel
    if not p.exists():
        raise SystemExit(f"missing source: {rel}")
    return p, p.read_text(encoding="utf-8")

def save(p, s):
    p.write_text(s, encoding="utf-8", newline="\n")

def once(s, old, new, label):
    if old not in s:
        raise SystemExit(f"HD2x patch anchor missing: {label}")
    return s.replace(old, new, 1)

# SmartTexture: physical bitmap can be 2x while callers continue using legacy logical pixels.
p, s = load("com/watabou/gltextures/SmartTexture.java")
s = once(s,
    "\tpublic int width;\n\tpublic int height;",
    "\tpublic int width;\n\tpublic int height;\n\tpublic int scale = 1;",
    "SmartTexture scale")
s = once(s,
    "\tpublic RectF uvRect( int left, int top, int right, int bottom ) {\n\t\treturn new RectF(\n\t\t\t(float)left\t\t/ width,\n\t\t\t(float)top\t\t/ height,\n\t\t\t(float)right\t/ width,\n\t\t\t(float)bottom\t/ height );\n\t}",
    "\tpublic int logicalWidth() {\n\t\treturn width / scale;\n\t}\n\n\tpublic int logicalHeight() {\n\t\treturn height / scale;\n\t}\n\n\tpublic RectF uvRect( int left, int top, int right, int bottom ) {\n\t\treturn new RectF(\n\t\t\t(float)(left * scale)\t\t/ width,\n\t\t\t(float)(top * scale)\t\t/ height,\n\t\t\t(float)(right * scale)\t/ width,\n\t\t\t(float)(bottom * scale)\t/ height );\n\t}",
    "SmartTexture logical UV")
save(p, s)

# Asset PNG textures are 2x. Generated solid/gradient/Bitmap textures remain 1x.
p, s = load("com/watabou/gltextures/TextureCache.java")
s = once(s,
    "\t\t\tSmartTexture tx = new SmartTexture( getBitmap( src ) );\n\t\t\tall.put( src, tx );",
    "\t\t\tSmartTexture tx = new SmartTexture( getBitmap( src ) );\n\t\t\tif (src instanceof String && ((String)src).endsWith( \".png\" ) && !((String)src).startsWith( \"font\" )) {\n\t\t\t\ttx.scale = 2;\n\t\t\t}\n\t\t\tall.put( src, tx );",
    "TextureCache PNG scale")
save(p, s)

p, s = load("com/watabou/noosa/TextureFilm.java")
if s.count("texWidth = texture.width;") != 2 or s.count("texHeight = texture.height;") != 2:
    raise SystemExit("unexpected TextureFilm texture dimension anchors")
s = s.replace("texWidth = texture.width;", "texWidth = texture.logicalWidth();")
s = s.replace("texHeight = texture.height;", "texHeight = texture.logicalHeight();")
s = once(s, "this( texture, width, texture.height );",
         "this( texture, width, texture.logicalHeight() );", "TextureFilm one-axis constructor")
save(p, s)

p, s = load("com/watabou/noosa/Image.java")
s = once(s,
    "\t\twidth = frame.width() * texture.width;\n\t\theight = frame.height() * texture.height;",
    "\t\twidth = frame.width() * texture.logicalWidth();\n\t\theight = frame.height() * texture.logicalHeight();",
    "Image logical frame size")
save(p, s)

p, s = load("com/watabou/noosa/NinePatch.java")
s = once(s,
    "\t\tw = w == 0 ? texture.width : w;\n\t\th = h == 0 ? texture.height : h;",
    "\t\tw = w == 0 ? texture.logicalWidth() : w;\n\t\th = h == 0 ? texture.logicalHeight() : h;",
    "NinePatch logical default size")
save(p, s)

p, s = load("com/watabou/noosa/SkinnedBlock.java")
s = s.replace("texture.width", "texture.logicalWidth()")
s = s.replace("texture.height", "texture.logicalHeight()")
save(p, s)

p, s = load("com/watabou/gltextures/Atlas.java")
s = once(s, "grid( width, tx.height );", "grid( width, tx.logicalHeight() );", "Atlas default grid height")
s = once(s, "grid( 0, 0, width, height, tx.width / width );",
         "grid( 0, 0, width, height, tx.logicalWidth() / width );", "Atlas grid cols")
s = once(s,
    "\t\tuvLeft\t= (float)left\t/ tx.width;\n\t\tuvTop\t= (float)top\t/ tx.height;\n\t\tuvWidth\t= (float)width\t/ tx.width;\n\t\tuvHeight= (float)height\t/ tx.height;",
    "\t\tuvLeft\t= (float)left\t/ tx.logicalWidth();\n\t\tuvTop\t= (float)top\t/ tx.logicalHeight();\n\t\tuvWidth\t= (float)width\t/ tx.logicalWidth();\n\t\tuvHeight= (float)height\t/ tx.logicalHeight();",
    "Atlas logical grid UV")
s = once(s, "return rect.width() * tx.width;", "return rect.width() * tx.logicalWidth();", "Atlas width")
s = once(s, "return rect.height() * tx.height;", "return rect.height() * tx.logicalHeight();", "Atlas height")
s = once(s,
    "\t\treturn new RectF(\n\t\t\t(float)left\t\t/ tx.width,\n\t\t\t(float)top\t\t/ tx.height,\n\t\t\t(float)right\t/ tx.width,\n\t\t\t(float)bottom\t/ tx.height );",
    "\t\treturn tx.uvRect( left, top, right, bottom );",
    "Atlas static uvRect")
save(p, s)

# Font constructor can still receive regular 1x textures, but make its dimensions scale-aware.
p, s = load("com/watabou/noosa/BitmapText.java")
s = once(s, "this( tx, width, tx.height, chars );",
         "this( tx, width, tx.logicalHeight(), chars );", "BitmapText Font height")
s = once(s,
    "\t\t\tfloat uw = (float)width / tx.width;\n\t\t\tfloat vh = (float)height / tx.height;",
    "\t\t\tfloat uw = (float)width / tx.logicalWidth();\n\t\t\tfloat vh = (float)height / tx.logicalHeight();",
    "BitmapText Font UV")
save(p, s)

p, s = load("com/watabou/pixeldungeon/ui/Archs.java")
s = s.replace("arcsBg.texture.width", "arcsBg.texture.logicalWidth()")
s = s.replace("arcsFg.texture.width", "arcsFg.texture.logicalWidth()")
save(p, s)

p, s = load("com/watabou/pixeldungeon/windows/WndBag.java")
s = once(s, "icon.texture.height;", "icon.texture.logicalHeight();", "WndBag clipped icon UV")
save(p, s)

p, s = load("com/watabou/pixeldungeon/sprites/HeroSprite.java")
s = once(s, "new TextureFilm( texture, texture.width, FRAME_HEIGHT );",
         "new TextureFilm( texture, texture.logicalWidth(), FRAME_HEIGHT );", "HeroSprite armor tiers")
save(p, s)

print("HD2x renderer mapping enabled: physical textures 2x, logical gameplay/UI geometry unchanged.")
