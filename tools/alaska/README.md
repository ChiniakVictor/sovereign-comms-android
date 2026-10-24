# Alaska easements data (Sovereign Comms 2.3)
Download the sources (GeoJSON, outSR 4326, paged) into a folder with these names, then run
`python3 build_alaska_easements.py <folder> ../../app/src/main/assets/alaska`.

| File | Service |
|---|---|
| blm17b_0.geojson / blm17b_1.geojson | https://gis.blm.gov/akarcgis/rest/services/lands/BLM_AK_Easements_17b/FeatureServer/0 and /1 (all) |
| rs2477.geojson | https://arcgis.dnr.alaska.gov/arcgis/rest/services/OpenData/Infrastructure_RS2477Trails/FeatureServer/0 (all) |
| dnr_pt/line/poly.geojson | https://arcgis.dnr.alaska.gov/arcgis/rest/services/OpenData/LandActivity_Easement/FeatureServer/0,1,2 where `CSTYPDSCRP='PUBLIC EASEMENT (581)' AND SPCLCDDSCR='ACCESS (8003)'` |

`fetch_sources.py` has the `fetch()` helper used (send a real User-Agent). Grantee/customer names are not kept.
