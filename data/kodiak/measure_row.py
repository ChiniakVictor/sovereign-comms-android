import json, math, statistics
from shapely.geometry import shape, LineString, Point
from shapely.ops import transform, unary_union
from shapely.strtree import STRtree
import pyproj
fwd = pyproj.Transformer.from_crs(4326, 32605, always_xy=True).transform
cl = json.load(open('dot_centerlines_kodiak.geojson'))
parcels = [transform(fwd, shape(f['geometry'])) for f in json.load(open('raw_kib_parcels_corridor.geojson'))['features'] if f['geometry']]
parcels = [p.buffer(0) for p in parcels]
bounds = [p.boundary for p in parcels]
tree = STRtree(parcels)
out = {}
for f in cl['features']:
    name = f['properties']['Route_Name']
    line = transform(fwd, LineString([p[:2] for p in f['geometry']['coordinates']]))
    L = line.length; rows = []
    s = 25.0
    while s < L:
        p = line.interpolate(s); a = line.interpolate(max(0, s-5)); b = line.interpolate(min(L, s+5))
        dx, dy = b.x-a.x, b.y-a.y; n = math.hypot(dx, dy) or 1; nx, ny = -dy/n, dx/n
        inside = [i for i in tree.query(p) if parcels[i].contains(p)]
        sides = []
        for sg in (1, -1):
            ray = LineString([(p.x, p.y), (p.x+sg*nx*80, p.y+sg*ny*80)])
            best = None
            for i in tree.query(ray):
                if i in inside: continue
                x = ray.intersection(bounds[i])
                if x.is_empty: continue
                d = p.distance(x)
                best = d if best is None else min(best, d)
            sides.append(best)
        rows.append((s, sides[0], sides[1], bool(inside)))
        s += 50.0
    out[name] = rows
    both = [l+r for _, l, r, ins in rows if l is not None and r is not None and not ins]
    print(name, 'km %.1f samples %d measured-both-sides %d' % (L/1000, len(rows), len(both)))
    if both:
        q = statistics.quantiles(both, n=10)
        print('  width m: median %.1f  p10 %.1f p90 %.1f' % (statistics.median(both), q[0], q[-1]))
        hist = {}
        for w in both: k = round(w/0.3048/10)*10; hist[k] = hist.get(k, 0)+1
        print('  ft hist', sorted(hist.items()))
json.dump(out, open('row_measurements.json', 'w'))
