// Generates the Protomaps layer template used for downloaded (offline) areas.
// node gen.js > pm-layers.json   (uses @protomaps/basemaps, light flavor, English labels)
const b = require('@protomaps/basemaps');
const L = b.layers('protomaps', b.namedFlavor('light'), { lang: 'en' });
const fix = (v) => JSON.parse(JSON.stringify(v).replace(/Noto Sans Medium/g, 'Noto Sans Bold'));
const out = [];
for (const l0 of L) {
  const l = fix(l0);
  if (l.id === 'roads_oneway' || l.id === 'roads_shields') continue;   // icon-only layers (no sprite offline)
  if (l.layout) { delete l.layout['icon-image']; delete l.layout['icon-size']; delete l.layout['icon-allow-overlap']; }
  out.push(l);
}
process.stdout.write(JSON.stringify({ generator: '@protomaps/basemaps ' + require('@protomaps/basemaps/package.json').version + ' light/en', layers: out }));
