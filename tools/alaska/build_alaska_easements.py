#!/usr/bin/env python3
"""Builds assets/alaska/easements.geojson.gz (statewide Alaska easements layer, Sovereign Comms 2.3).

Inputs (download with fetch_sources.py, see README in this folder):
  blm17b_0.geojson, blm17b_1.geojson   BLM Alaska ANCSA 17(b) easements (points, lines)
  rs2477.geojson                       Alaska DNR RS 2477 trails
  dnr_pt/line/poly.geojson             Alaska DNR easements, PUBLIC EASEMENT + ACCESS only
Usage: build_alaska_easements.py <srcdir> <assets/alaska>

BLM 17(b) features that touch the Kodiak archipelago are left out: the Kodiak tab draws those (with patent notes),
so they are never drawn twice.
"""
import sys, json, gzip, math, re, datetime, collections
from shapely.geometry import shape, mapping
src, out = sys.argv[1], sys.argv[2]
TOL = 0.00004   # ~4 m north-south, ~2 m east-west at 60N

KODIAK = [(-154.95, 56.45), (-152.6, 56.45), (-151.75, 57.3), (-151.75, 58.75), (-153.25, 58.75), (-153.55, 58.35), (-153.95, 58.05), (-154.6, 57.75), (-154.95, 57.45)]
def in_kodiak(lon, lat):
    inside = False; j = len(KODIAK) - 1
    for i in range(len(KODIAK)):
        xi, yi = KODIAK[i]; xj, yj = KODIAK[j]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi: inside = not inside
        j = i
    return inside
def touches_kodiak(g):   # same rule as KodiakData.touchesKodiak (any vertex inside)
    t = g["type"]; c = g["coordinates"]
    if t == "Point": return in_kodiak(*c[:2])
    if t == "LineString": return any(in_kodiak(*p[:2]) for p in c)
    if t == "MultiLineString": return any(in_kodiak(*p[:2]) for l in c for p in l)
    return False

def rnd(o):
    if isinstance(o, float): return round(o, 5)
    if isinstance(o, (list, tuple)): return [rnd(x) for x in o]
    return o
def simp(g):
    s = shape(g)
    if g["type"] != "Point": s = s.simplify(TOL, preserve_topology=True)
    if s.is_empty: return None
    m = mapping(s); return {"type": m["type"], "coordinates": rnd(json.loads(json.dumps(m["coordinates"])))}

# ---- BLM 17(b): mirrors Easements.classify / normalize in core/Kodiak.kt
NOTE = None  # shown by the app (Easements.NOTE)
USES = {
 "trail25": "Travel by foot, dogsleds, animals, snowmobiles, two- and three-wheeled vehicles, and small all-terrain vehicles (less than 3,000 lbs GVW).",
 "trail50": "Uses allowed on a 25-ft trail, plus large all-terrain vehicles (more than 3,000 lbs GVW), tracked vehicles, and four-wheel-drive vehicles.",
 "road": "Uses allowed on 25- and 50-ft trails, plus automobiles and trucks.",
 "site": "Vehicle parking (e.g. aircraft, boats, ATVs, snowmobiles, cars, trucks), temporary camping, and loading or unloading. Temporary camping, loading or unloading limited to 24 hours.",
 "utility": "Existing utility easement. Not a public travel easement unless the conveyance document says so.",
 "air": "Reserved for air, light, or visibility purposes (e.g. around navigation aids). Not a public travel or camping easement.",
 "airstrip": "Aircraft landing strip and related uses as described in the conveyance document.",
}
def classify(t):
    t = t or ""
    if "Trail up to 25" in t: return "trail25", 25, "Trail"
    if "Trail up to 50" in t: return "trail50", 50, "Trail"
    if "Proposed Road" in t: return "road", 100, "Road"
    if "Road up to 60" in t: return "road", 60, "Road"
    if "Site Easement" in t: return "site", None, "Site"
    if "utilities" in t: return "utility", None, "Utility"
    if "visability" in t or "visibility" in t: return "air", None, "Air/light/visibility"
    if "landing strip" in t: return "airstrip", None, "Aircraft landing strip"
    return "other", None, (t or "Other")
def s(p, k):
    v = p.get(k); v = None if v is None else str(v).strip()
    return v if v and v != "null" else None
def circle(lat, lon, r, n=24):
    return {"type": "Polygon", "coordinates": [rnd([[lon + r * math.sin(2 * math.pi * i / n) / (111320.0 * math.cos(math.radians(lat))), lat + r * math.cos(2 * math.pi * i / n) / 110540.0] for i in range(n + 1)])]}

feats = []; stats = collections.Counter(); uid = 0
def add(g, props):
    global uid
    g2 = simp(g)
    if g2 is None: return
    uid += 1; props["uid"] = f"ak{uid}"; feats.append({"type": "Feature", "geometry": g2, "properties": props})

for layer in (0, 1):
    for f in json.load(open(f"{src}/blm17b_{layer}.geojson"))["features"]:
        g = f.get("geometry"); p = f["properties"]
        if not g: continue
        if touches_kodiak(g): stats["blm_skipped_kodiak"] += 1; continue
        t = s(p, "Easement_Type"); kind, w, cat = classify(t); ein = s(p, "EIN")
        pr = {"k": "esmt", "ak": 1, "kind": kind, "cat": cat, "uses": USES.get(kind, "See conveyance document.")}
        for k, v in (("ein", ein), ("id", s(p, "Easement_ID")), ("type", t),
                     ("status", "Proposed" if (t or "").startswith("Proposed") else "Existing" if (t or "").startswith("Existing") else None),
                     ("width_ft", w), ("owner", s(p, "Land_Owner")), ("agency", s(p, "Admin_Agency")), ("accessed", s(p, "Land_Accessed")),
                     ("restrict", s(p, "Use_Restrictions")), ("name", s(p, "Common_Name")), ("reserved", s(p, "Easement_Status"))):
            if v is not None: pr[k] = v
        d = s(p, "Conveyance_Doc")
        if d: pr["doc"] = d if d.startswith("http") else f"https://sdms.ak.blm.gov/perl-bin/scanned_images/patent/disp_patent_pdf.pl?the_doctype={s(p,'Conveyance_Doc_Type') or 'PA'}&patentnum={d}"
        dn = " ".join(x for x in (s(p, "Conveyance_Doc_Type"), s(p, "Conveyance_Doc_No")) if x)
        if dn: pr["doc_no"] = dn
        if p.get("GIS_Miles") is not None: pr["miles"] = round(float(p["GIS_Miles"]), 2)
        if ein: pr["label"] = f"EIN {ein}"
        if layer == 0:
            m = re.search(r"(\d+(?:\.\d+)?)-acre", t or ""); acres = float(m.group(1)) if m else 1.0
            r = round(math.sqrt(acres * 4046.856 / math.pi) * 10) / 10
            if kind == "site":
                pr["acres"] = acres; pr["r_m"] = r
                a = str(int(acres)) if acres % 1 == 0 else str(acres)
                pr["approx"] = f"Drawn as a {a}-acre circle (radius {r} m) around the BLM point; actual boundaries are in the conveyance document."
            else: pr["approx"] = "Point location from BLM."
            add(g, dict(pr, geom="pt"))
            if kind == "site":
                lon, lat = g["coordinates"][:2]
                feats.append({"type": "Feature", "geometry": circle(lat, lon, r), "properties": dict(pr, geom="area", uid=feats[-1]["properties"]["uid"])})
        else: add(g, pr)
        stats["blm"] += 1

def date_of(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y-%m-%d") if ms else None

RS_USES = ("Public right-of-way asserted by the State of Alaska under RS 2477 (AS 19.30.400). Open to the public travel the route "
           "historically carried: foot, dogsled, horse, snowmachine and ATV, and vehicles where the trail allows.")
rs_dates = set()
for f in json.load(open(f"{src}/rs2477.geojson"))["features"]:
    g = f.get("geometry"); p = f["properties"]
    if not g: continue
    w = p.get("WIDTH") or None
    pr = {"k": "rs2477", "cat": "RS 2477 trail", "uses": RS_USES}
    for k, v in (("name", s(p, "RSTNAME")), ("id", s(p, "CASE_ID")), ("width_ft", w), ("case", s(p, "CS_LABEL")),
                 ("review", (s(p, "SPCLCDDSCR") or "").split(" (")[0].title() or None), ("doc", s(p, "INFO_LINK"))):
        if v is not None: pr[k] = v
    if pr.get("id"): pr["label"] = pr["id"]
    rs_dates.add(date_of(p.get("RFRSHDT")))
    add(g, pr); stats["rs2477"] += 1

DNR_KEEP = {"ISSUED (35)": "Issued", "TRANSFERRED (91)": "Transferred", "INTERIM AUTHORIZATON (21)": "Interim authorization"}
DNR_USES = ("Public access easement granted or reserved by the State of Alaska (DNR). The case file sets the width and allowed uses.")
dnr_dates = set()
for n in ("pt", "line", "poly"):
    for f in json.load(open(f"{src}/dnr_{n}.geojson"))["features"]:
        g = f.get("geometry"); p = f["properties"]
        if not g: continue
        st = DNR_KEEP.get(p.get("CSSTTSDSCR"))
        if not st: stats["dnr_skipped_status"] += 1; continue
        cid = s(p, "CASE_ID")
        pr = {"k": "dnr", "cat": "State public access easement", "uses": DNR_USES, "case": st}
        if cid: pr["id"] = cid; pr["label"] = cid
        if s(p, "INFO_LINK"): pr["doc"] = s(p, "INFO_LINK")
        if n == "pt": pr["geom"] = "pt"
        elif n == "poly": pr["geom"] = "area"
        dnr_dates.add(date_of(p.get("RFRSHDT")))
        add(g, pr); stats["dnr_" + n] += 1

fc = {"type": "FeatureCollection", "features": feats}
# one feature per line: the app streams this file to MapLibre (file:// source) and finds a tapped feature by scanning lines for its uid
raw = ('{"type":"FeatureCollection","features":[\n' + ",\n".join(json.dumps(f, separators=(",", ":")) for f in feats) + "\n]}\n").encode()
with gzip.open(f"{out}/easements.geojson.gz", "wb", 9) as o: o.write(raw)
meta = {"built": datetime.date.today().isoformat(), "counts": dict(stats),
        "rs2477_refreshed": max(d for d in rs_dates if d), "dnr_refreshed": max(d for d in dnr_dates if d),
        "sources": {
          "blm17b": "https://gis.blm.gov/akarcgis/rest/services/lands/BLM_AK_Easements_17b/FeatureServer (layers 0,1)",
          "rs2477": "https://arcgis.dnr.alaska.gov/arcgis/rest/services/OpenData/Infrastructure_RS2477Trails/FeatureServer/0",
          "dnr": "https://arcgis.dnr.alaska.gov/arcgis/rest/services/OpenData/LandActivity_Easement/FeatureServer (0,1,2) where PUBLIC EASEMENT + ACCESS, status issued/transferred/interim"}}
json.dump(meta, open(f"{out}/meta.json", "w"), indent=1)
print(json.dumps(meta, indent=1)); print("features", len(feats), "raw", len(raw), "gz", __import__("os").path.getsize(f"{out}/easements.geojson.gz"))
