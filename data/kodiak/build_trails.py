"""Builds the bundled Kodiak Trails layer (Sovereign Comms 1.8).
Input : raw_kib_trails.geojson  (Kodiak Island Borough KIB_Trails FeatureServer layer 0, all 964 records, outSR=4326)
        https://services1.arcgis.com/R5BNizttyFKxRSMm/arcgis/rest/services/KIB_Trails/FeatureServer/0 (data last edited 2025-08-19)
Output: ../../app/src/main/assets/kodiak/trails.geojson.gz  (+ trails section in trails_meta.json)
Steps : clean names -> group by (name, class) -> line-merge -> Douglas-Peucker 1.5 m in UTM 5N -> length from the unsimplified geometry."""
import json, gzip, os, collections
from shapely.geometry import shape, mapping, MultiLineString, LineString
from shapely.ops import linemerge, transform, unary_union
import pyproj

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '../../app/src/main/assets/kodiak')
FWD = pyproj.Transformer.from_crs(4326, 32605, always_xy=True).transform
INV = pyproj.Transformer.from_crs(32605, 4326, always_xy=True).transform
TOL_M = 1.5
MI = 1609.344

# Obvious typos / duplicate spellings in the KIB data (checked against the KIB "Kodiak Road System Trails User Map" list).
NAME_FIX = {
    'Saltrey Cove Road': 'Saltery Cove Road',
    'Old Womens Mountain': 'Old Woman Mountain',
    'Zenter Creek Spur': 'Zentner Creek Spur',
    'Kalsin Bay to Summit Lake Trail': 'Kalsin Bay to Summit Lake Trails',
}
CLS = {'double track': 'double', 'single track': 'single', 'footpath': 'single', 'route': 'route', 'shared use': 'shared'}
CLS_LABEL = {'double': 'Double track', 'single': 'Single track', 'route': 'Route (no built tread)', 'shared': 'Shared-use path'}

def clean_name(name, label):
    n = (name or '').strip()
    if n in ('', 'None', 'No Name'):
        l = (label or '').strip()
        n = {'Pillar To Devils Prongs': 'Pillar to Devils Prongs Route'}.get(l, l) or 'Unnamed trail'
    return NAME_FIX.get(n, n)

def main():
    raw = json.load(open(os.path.join(HERE, 'raw_kib_trails.geojson')))['features']
    groups = collections.defaultdict(list); skipped = 0; raw_m = 0.0; renamed = collections.Counter()
    for f in raw:
        if not f['geometry']: skipped += 1; continue
        p = f['properties']; n = clean_name(p['NAME'], p['LABEL'])
        if n != (p['NAME'] or '').strip(): renamed[f"{p['NAME']!r} -> {n}"] += 1
        c = CLS[(p['SYMBOL'] or 'single track').strip().lower()]
        g = transform(FWD, shape(f['geometry']))
        parts = list(g.geoms) if g.geom_type == 'MultiLineString' else [g]
        groups[(n, c)].extend(x for x in parts if x.length > 0)
    feats = []; vin = vout = 0; tot_m = 0.0
    for i, ((n, c), parts) in enumerate(sorted(groups.items())):
        vin += sum(len(x.coords) for x in parts)
        merged = linemerge(unary_union(parts)) if len(parts) > 1 else parts[0]
        length = merged.length
        simp = merged.simplify(TOL_M, preserve_topology=False)
        lines = [l for l in (simp.geoms if simp.geom_type == 'MultiLineString' else [simp]) if l.length > 0]
        vout += sum(len(l.coords) for l in lines)
        def dedupe(cs):   # 6-decimal rounding (~0.1 m) can collapse neighbours
            out = []
            for c in cs:
                if not out or out[-1] != c: out.append(c)
            return out
        ll = [d for d in (dedupe([[round(x, 6), round(y, 6)] for x, y in transform(INV, l).coords]) for l in lines) if len(d) >= 2]
        geom = {'type': 'LineString', 'coordinates': ll[0]} if len(ll) == 1 else {'type': 'MultiLineString', 'coordinates': ll}
        tot_m += length
        feats.append({'type': 'Feature', 'geometry': geom, 'properties': {
            'k': 'trail', 'id': f't{i + 1}', 'name': n, 'cls': c, 'type': CLS_LABEL[c],
            'miles': round(length / MI, 2), 'label': n, 'src': 'Kodiak Island Borough'}})
    fc = {'type': 'FeatureCollection', 'features': feats}
    with gzip.open(os.path.join(OUT, 'trails.geojson.gz'), 'wt', compresslevel=9) as fh: json.dump(fc, fh, separators=(',', ':'))
    meta = {'source': 'Kodiak Island Borough KIB_Trails', 'url': 'https://services1.arcgis.com/R5BNizttyFKxRSMm/arcgis/rest/services/KIB_Trails/FeatureServer/0',
            'data_edited': '2025-08-19', 'fetched': '2026-10-11', 'raw_records': len(raw), 'skipped_null_geometry': skipped,
            'features': len(feats), 'names': len({f['properties']['name'] for f in feats}), 'miles': round(tot_m / MI, 1),
            'vertices_in': vin, 'vertices_out': vout, 'tolerance_m': TOL_M, 'renamed': dict(renamed),
            'miles_by_class': {c: round(sum(f['properties']['miles'] for f in feats if f['properties']['cls'] == c), 1) for c in CLS_LABEL}}
    json.dump(meta, open(os.path.join(OUT, 'trails_meta.json'), 'w'), indent=1)
    print(json.dumps(meta, indent=1)); print('bytes', os.path.getsize(os.path.join(OUT, 'trails.geojson.gz')))

if __name__ == '__main__': main()
