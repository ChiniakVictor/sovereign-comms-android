#!/usr/bin/env python3
"""Builds app/src/main/assets/world/ (Android 2.3 worldwide maps).

Inputs (see README "Worldwide maps"):
  pm-layers.json   - output of gen-pm-layers.js (@protomaps/basemaps, light flavour, English)
  liberty.json     - https://tiles.openfreemap.org/styles/liberty
  fonts/<Noto Sans X>/<range>.pbf - https://tiles.openfreemap.org/fonts/... (OFL)
  world-z4.pmtiles - go-pmtiles extract <protomaps v4> --maxzoom=4
Font stacks are renamed without spaces (noto-regular, ...) so the asset:// glyph URL needs no escaping.
"""
import json, os, shutil, sys
src, out = sys.argv[1], sys.argv[2]
FONTS = {"Noto Sans Regular": "noto-regular", "Noto Sans Bold": "noto-bold", "Noto Sans Italic": "noto-italic", "Noto Sans Medium": "noto-bold"}

def fix(o):
    if isinstance(o, str): return FONTS.get(o, o)
    if isinstance(o, list): return [fix(x) for x in o]
    if isinstance(o, dict): return {k: fix(v) for k, v in o.items()}
    return o

pm = fix(json.load(open(os.path.join(src, "pm-layers.json"))))
if isinstance(pm, dict): pm = pm["layers"]
# background kept: WorldStyle emits it once (ocean colour; Protomaps "earth" is the land)
json.dump(pm, open(os.path.join(out, "pm-layers.json"), "w"), separators=(",", ":"))
lib = json.load(open(os.path.join(src, "liberty.json")))
layers = [fix(l) for l in lib["layers"] if l["id"] != "building-3d"]
json.dump({"sources": lib["sources"], "sprite": lib["sprite"], "layers": layers}, open(os.path.join(out, "ofm-layers.json"), "w"), separators=(",", ":"))
for name, short in FONTS.items():
    d = os.path.join(src, "fonts", name)
    if os.path.isdir(d) and not os.path.isdir(os.path.join(out, "fonts", short)):
        shutil.copytree(d, os.path.join(out, "fonts", short))
shutil.copy(os.path.join(src, "world-z4.pmtiles"), os.path.join(out, "world-z4.pmtiles"))
print("pm", len(pm), "ofm", len(layers))
