"""Builds the bundled Kodiak offline layer (Sovereign Comms 1.7).
Inputs (raw downloads, see SOURCES.md): raw_17b_0.geojson, raw_17b_1.geojson, dot_centerlines_kodiak.geojson, raw_kib_parcels_corridor.geojson
Outputs: ../../app/src/main/assets/kodiak/{easements,state_row}.geojson.gz + meta.json"""
import json, gzip, math, statistics, os, sys, datetime, re
from shapely.geometry import shape, LineString, Point, Polygon, mapping
from shapely.ops import transform
from shapely.strtree import STRtree
import pyproj

OUT = os.path.join(os.path.dirname(__file__), '../../app/src/main/assets/kodiak')
os.makedirs(OUT, exist_ok=True)
FWD = pyproj.Transformer.from_crs(4326, 32605, always_xy=True).transform
INV = pyproj.Transformer.from_crs(32605, 4326, always_xy=True).transform
FT = 0.3048

# Kodiak archipelago (Kodiak, Afognak, Shuyak, Sitkalidak, Trinity Is.) - excludes the Alaska Peninsula across Shelikof Strait.
KODIAK = Polygon([(-154.95, 56.45), (-152.6, 56.45), (-151.75, 57.3), (-151.75, 58.75), (-153.25, 58.75), (-153.55, 58.35),
                  (-153.95, 58.05), (-154.6, 57.75), (-154.95, 57.45)])

USES = {
    'trail25': "Travel by foot, dogsleds, animals, snowmobiles, two- and three-wheeled vehicles, and small all-terrain vehicles (less than 3,000 lbs GVW).",
    'trail50': "Uses allowed on a 25-ft trail, plus large all-terrain vehicles (more than 3,000 lbs GVW), tracked vehicles, and four-wheel-drive vehicles.",
    'road': "Uses allowed on 25- and 50-ft trails, plus automobiles and trucks.",
    'site': "Vehicle parking (e.g. aircraft, boats, ATVs, snowmobiles, cars, trucks), temporary camping, and loading or unloading. Temporary camping, loading or unloading limited to 24 hours.",
    'utility': "Existing utility easement. Not a public travel easement unless the conveyance document says so.",
    'air': "Reserved for air, light, or visibility purposes (e.g. around navigation aids). Not a public travel or camping easement.",
    'airstrip': "Aircraft landing strip and related uses as described in the conveyance document.",
}
NOTE = "Hunting, fishing, or trapping on or from the easement is not allowed without landowner permission."

def classify(t):
    t = t or ''
    if 'Trail up to 25' in t: return 'trail25', 25, 'Trail'
    if 'Trail up to 50' in t: return 'trail50', 50, 'Trail'
    if 'Proposed Road' in t: return 'road', 100, 'Road'
    if 'Road up to 60' in t: return 'road', 60, 'Road'
    if 'Site Easement' in t: return 'site', None, 'Site'
    if 'utilities' in t: return 'utility', None, 'Utility'
    if 'visability' in t or 'visibility' in t: return 'air', None, 'Air/light/visibility'
    if 'landing strip' in t: return 'airstrip', None, 'Aircraft landing strip'
    return 'other', None, t

def clean(s): return (s or '').strip() or None

def site_acres(t):
    m = re.search(r'(\d+(?:\.\d+)?)-acre', t or '')
    return float(m.group(1)) if m else 1.0

def norm_ein(e): return re.sub(r'[^0-9a-z]', '', (e or '').lower())

PATENT = json.load(open(os.path.join(os.path.dirname(__file__) or '.', 'patent/patent_50860067.json')))
PAT = {norm_ein(e[1]): e for e in PATENT['easements']}
# Easements reserved in patent 50-86-0067 that BLM's GIS does not map (checked 2026-10-17). Shown as one approximate
# marker per road/site pair at the point where the named road crosses the section the patent gives. Not surveyed.
UNMAPPED = [
    {'eins': ['109 D1, D9', '109a D1, D9'], 'lonlat': None, 'road': 'Chiniak Highway (FAS 391)', 'sec': 'Sec. 32, T. 29 S., R. 19 W.', 'kind': 'road', 'width': 60,
     'title': 'EIN 109 D1, D9 road + 109a site',
     'hint': ' An unnamed OpenStreetMap track (way 238397060) leaves the highway at this spot and runs about 335 m north to the shore; it may be the existing road, not confirmed.'},
    {'eins': ['106 C4', '106a C4'], 'lonlat': None, 'road': 'Saltery Cove Road (FAS 3912)', 'sec': 'Sec. 1, T. 30 S., R. 21 W.', 'kind': 'trail25', 'width': 25,
     'title': 'EIN 106 C4 proposed trail + 106a site'},
]
UNMAPPED_PTS = json.load(open(os.path.join(os.path.dirname(__file__) or '.', 'patent/unmapped_points.json')))

def easements():
    feats, dropped, counts = [], 0, {}
    seen = set()
    for layer in (0, 1):
        for f in json.load(open(f'raw_17b_{layer}.geojson'))['features']:
            g = shape(f['geometry'])
            if not g.intersects(KODIAK): dropped += 1; continue
            p = f['properties']; kind, width, cat = classify(p['Easement_Type'])
            status = 'Proposed' if (p['Easement_Type'] or '').startswith('Proposed') else ('Existing' if (p['Easement_Type'] or '').startswith('Existing') else None)
            ein = clean(p['EIN'])
            doc_no = str(p['Conveyance_Doc_No'] or '')
            doc = clean(p['Conveyance_Doc'])
            if doc and not doc.startswith('http'):
                doc = f"https://sdms.ak.blm.gov/perl-bin/scanned_images/patent/disp_patent_pdf.pl?the_doctype={p['Conveyance_Doc_Type']}&patentnum={doc}"
            props = {
                'k': 'esmt', 'kind': kind, 'cat': cat, 'ein': ein, 'id': p['Easement_ID'],
                'type': p['Easement_Type'], 'status': status, 'width_ft': width,
                'uses': USES.get(kind, 'See conveyance document.'), 'note': NOTE,
                'owner': clean(p['Land_Owner']), 'agency': clean(p['Admin_Agency']), 'accessed': clean(p['Land_Accessed']),
                'restrict': clean(p['Use_Restrictions']), 'name': clean(p['Common_Name']), 'reserved': clean(p['Easement_Status']),
                'doc': doc, 'doc_no': f"{p['Conveyance_Doc_Type']} {p['Conveyance_Doc_No']}",
                'miles': p.get('GIS_Miles'), 'label': f"EIN {ein}" if ein else None,
            }
            if doc_no == '50860067' and norm_ein(ein) in PAT:
                e = PAT[norm_ein(ein)]; seen.add(norm_ein(ein))
                props['patent'] = f"{PATENT['doc_short']} item 2.{e[0]}: {e[3]}"
            if kind == 'site':
                props['acres'] = site_acres(p['Easement_Type'])
                props['r_m'] = round(math.sqrt(props['acres'] * 4046.856 / math.pi), 1)
            props = {k: v for k, v in props.items() if v is not None}
            if layer == 0:
                # site footprint drawn as a circle of the easement's acreage around the point: approximate, the legal location is in the conveyance doc.
                c = transform(FWD, g)
                if kind == 'site':
                    a = props['acres']; circ = transform(INV, c.buffer(props['r_m'], 24))
                    props['approx'] = f"Drawn as a {a:g}-acre circle (radius {props['r_m']:g} m) around the BLM point; actual boundaries are in the conveyance document."
                else: props['approx'] = 'Point location from BLM.'
                feats.append({'type': 'Feature', 'geometry': mapping(g), 'properties': dict(props, geom='pt')})
                if kind == 'site': feats.append({'type': 'Feature', 'geometry': mapping(circ), 'properties': dict(props, geom='area')})
            else:
                feats.append({'type': 'Feature', 'geometry': mapping(g), 'properties': props})
            counts[kind] = counts.get(kind, 0) + 1
    # patent-only easements (not in BLM GIS)
    for u in UNMAPPED:
        lon, lat = UNMAPPED_PTS[u['eins'][0]]
        texts = []
        for e_ in u['eins']:
            e = PAT[norm_ein(e_)]; seen.add(norm_ein(e_)); texts.append(f"{PATENT['doc_short']} item 2.{e[0]} (EIN {e[1]}, {e[2]}): {e[3]}")
        uses = USES[u['kind']] + ' Site: ' + USES['site']
        props = {'k': 'esmt', 'kind': u['kind'], 'cat': 'Not mapped by BLM', 'ein': ' + '.join(u['eins']), 'id': 'PAT_' + norm_ein(u['eins'][0]),
                 'type': f"{u['width']}-ft {'road' if u['kind'] == 'road' else 'trail'} + 1-acre site (from patent text)", 'width_ft': None,
                 'uses': uses, 'note': NOTE, 'owner': 'Leisnoi, Inc.', 'reserved': 'Reserved (patent)',
                 'doc': 'https://sdms.ak.blm.gov/perl-bin/scanned_images/patent/disp_patent_pdf.pl?the_doctype=PA&patentnum=50860067',
                 'doc_no': 'PA 50860067', 'label': u['title'], 'patent': '\n'.join(texts), 'unmapped': True,
                 'approx': f"NOT MAPPED BY BLM. Marker is only where {u['road']} crosses {u['sec']} (the section named in the patent). The real alignment and site are not surveyed here; check the BLM easement map in case file AA-8448-EE." + u.get('hint', ''),
                 'geom': 'pt'}
        feats.append({'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': [lon, lat]}, 'properties': props})
        counts['patent_only'] = counts.get('patent_only', 0) + 1
    missing = [e[1] for k, e in PAT.items() if k not in seen]
    if missing: print('WARNING patent EINs not matched:', missing)
    return feats, dropped, counts

def state_row():
    cl = json.load(open('dot_centerlines_kodiak.geojson'))
    parcels = [transform(FWD, shape(f['geometry'])).buffer(0) for f in json.load(open('raw_kib_parcels_corridor.geojson'))['features'] if f['geometry']]
    bounds = [p.boundary for p in parcels]; tree = STRtree(parcels)
    DEFAULT_FT = 100.0
    feats, summary = [], []
    for f in cl['features']:
        name = f['properties']['Route_Name']; rid = f['properties']['Route_ID']
        line = transform(FWD, LineString([q[:2] for q in f['geometry']['coordinates']]))
        L = line.length; step = 50.0; samples = []
        s = 0.0
        while s <= L:
            p = line.interpolate(s); a = line.interpolate(max(0, s - 5)); b = line.interpolate(min(L, s + 5))
            dx, dy = b.x - a.x, b.y - a.y; n = math.hypot(dx, dy) or 1; nx, ny = -dy / n, dx / n
            inside = {i for i in tree.query(p) if parcels[i].contains(p)}
            side = []
            for sg in (1, -1):
                ray = LineString([(p.x, p.y), (p.x + sg * nx * 60, p.y + sg * ny * 60)]); best = None
                for i in tree.query(ray):
                    if i in inside: continue
                    x = ray.intersection(bounds[i])
                    if not x.is_empty: d = p.distance(x); best = d if best is None else min(best, d)
                side.append(best)
            samples.append((s, side[0], side[1], bool(inside)))
            s += step
        # smoothed half-widths: median of both-side measurements within +-400 m, needs >= 6 and consistent (IQR <= 25% of total)
        per = []
        for i, (s, l, r, ins) in enumerate(samples):
            win = [(ll, rr) for (ss, ll, rr, ii) in samples if abs(ss - s) <= 400 and ll is not None and rr is not None and not ii and 6 <= ll + rr <= 120]
            ok = False
            if len(win) >= 6:
                tot = sorted(ll + rr for ll, rr in win); q1 = tot[len(tot) // 4]; q3 = tot[(3 * len(tot)) // 4]; med = statistics.median(tot)
                if q3 - q1 <= 0.25 * med:
                    ok = True; hl = statistics.median(ll for ll, _ in win); hr = statistics.median(rr for _, rr in win)
                    # snap total to nearest 10 ft for display, keep asymmetry ratio
                    tot_ft = round((hl + hr) / FT / 10) * 10; k = tot_ft * FT / (hl + hr); hl *= k; hr *= k
                    per.append((s, hl, hr, 'measured', len(win)))
            if not ok: per.append((s, DEFAULT_FT * FT / 2, DEFAULT_FT * FT / 2, 'assumed', 0))
        # group runs of equal (basis, width) into segments
        runs = []
        for rec in per:
            key = (rec[3], round((rec[1] + rec[2]) / FT))
            if runs and runs[-1][0] == key: runs[-1][1].append(rec)
            else: runs.append([key, [rec]])
        # merge tiny runs (< 300 m) into the previous run
        merged = []
        for key, recs in runs:
            if merged and (len(recs) * step < 300): merged[-1][1].extend(recs)
            else: merged.append([key, list(recs)])
        # recompute each run's basis by majority, then coalesce neighbours with the same basis and width
        fixed = []
        for key, recs in merged:
            m = sum(1 for r in recs if r[3] == 'measured'); basis = 'measured' if m * 2 > len(recs) else 'assumed'
            keep = [r for r in recs if r[3] == basis] or recs
            w = round(statistics.median(r[1] + r[2] for r in keep) / FT / 10) * 10
            if fixed and fixed[-1][0] == (basis, w): fixed[-1][1].extend(recs); fixed[-1][2].extend(keep)
            else: fixed.append([(basis, w), list(recs), list(keep)])
        merged = [[k, recs, keep] for k, recs, keep in fixed]
        for key, recs, keep in merged:
            s0 = max(0, recs[0][0] - step / 2); s1 = min(L, recs[-1][0] + step / 2)
            hl = statistics.median(r[1] for r in keep); hr = statistics.median(r[2] for r in keep)
            basis = key[0]; n = sum(r[4] for r in keep)
            pts = [line.interpolate(x) for x in [s0 + i * (s1 - s0) / max(1, int((s1 - s0) / 10)) for i in range(int((s1 - s0) / 10) + 1)]]
            seg = LineString([(q.x, q.y) for q in pts]) if len(pts) > 1 else None
            if seg is None: continue
            left = seg.offset_curve(hl); right = seg.offset_curve(-hr)
            band = seg.buffer(max(hl, hr), cap_style='flat') if left.is_empty or right.is_empty else Polygon(list(left.coords) + list(right.coords)[::-1]).buffer(0)
            if band.is_empty: band = seg.buffer((hl + hr) / 2, cap_style='flat')
            width_ft = key[1]
            mp0 = s0 / 1609.344; mp1 = s1 / 1609.344
            props = {'k': 'row', 'route': name, 'route_id': rid, 'width_ft': width_ft, 'left_ft': round(hl / FT), 'right_ft': round(hr / FT),
                     'mp': f"{mp0:.1f}-{mp1:.1f}", 'owner': 'State of Alaska DOT&PF', 'basis': basis,
                     'basis_text': (f"Derived: width measured from the gap between Kodiak Island Borough parcels ({n} samples, rounded to 10 ft)." if basis == 'measured'
                                    else "Derived: no ROW plat width found in public data; assumed nominal 100 ft (50 ft each side of centerline). Verify with DOT&PF ROW maps."),
                     'source': 'Centerline: Alaska DOT&PF Road Centerlines (AKDOTPF_Route_Data). Width: see basis.'}
            feats.append({'type': 'Feature', 'geometry': mapping(transform(INV, band.simplify(0.5))), 'properties': props})
            summary.append((name, props['mp'], width_ft, basis, n))
        feats.append({'type': 'Feature', 'geometry': mapping(transform(INV, line.simplify(0.5))), 'properties': {'k': 'rowcl', 'route': name, 'route_id': rid}})
    return feats, summary

def rnd(o):
    if isinstance(o, float): return round(o, 6)
    if isinstance(o, (list, tuple)): return [rnd(x) for x in o]
    if isinstance(o, dict): return {k: rnd(v) for k, v in o.items()}
    return o

def write(name, feats):
    feats = [dict(f, geometry=rnd(f['geometry'])) for f in feats]
    data = json.dumps({'type': 'FeatureCollection', 'features': feats}, separators=(',', ':')).encode()
    with gzip.open(os.path.join(OUT, name), 'wb', 9) as z: z.write(data)
    return len(data), os.path.getsize(os.path.join(OUT, name))

if __name__ == '__main__':
    e, dropped, counts = easements(); r, summary = state_row()
    print('easements', len(e), 'dropped outside archipelago', dropped, counts, write('easements.geojson.gz', e))
    print('row', len(r), write('state_row.geojson.gz', r))
    for s in summary: print('  ', s)
    fetched = open('fetched_utc.txt').read().strip()
    meta = {'built': datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='minutes'), 'fetched': fetched, 'counts': counts,
            'row_segments': [{'route': a, 'mp': b, 'width_ft': c, 'basis': d} for a, b, c, d, _ in summary]}
    json.dump(meta, open(os.path.join(OUT, 'meta.json'), 'w'), indent=1)
