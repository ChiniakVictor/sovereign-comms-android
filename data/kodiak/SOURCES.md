# Kodiak layer sources (fetched 2026-10-11, AKDT)
- BLM AK ANCSA 17(b) easements, https://gis.blm.gov/akarcgis/rest/services/Land_Management/BLM_AK_Easements_17b/FeatureServer (layer 0 points, 1 lines),
  envelope lon -155.0..-151.8, lat 56.5..58.7, then clipped to the archipelago polygon. 110 points + 228 lines = 338 records (0 dropped).
  No width field: width from type text (25/50 ft trails, 60 ft existing road, 100 ft proposed road; sites 1 acre). Uses = standard BLM 17(b) language.
- Alaska DOT&PF Road Centerlines, https://services.arcgis.com/r4A0V7UzH9fcLVvv/ArcGIS/rest/services/AKDOTPF_Route_Data/FeatureServer/20
  (data last edited 2026-09-29): Rezanof Drive 5081007X000 (14.7 mi), Chiniak Highway 5081021X000 (34.2 mi). Management_Resp layer 11: Alaska DOT&PF.
- ROW width: no public DOT&PF ROW polygon/width dataset for Kodiak (AKDOTPF "ROW" service empty here). Measured from gaps between Kodiak Island Borough
  parcels (https://services1.arcgis.com/R5BNizttyFKxRSMm/arcgis/rest/services/KIB_Parcels/FeatureServer/0, edited 2026-06-18) every 50 m, ±400 m median,
  accepted only when consistent; else assumed nominal 100 ft. Both labelled "derived".

## 1.8 additions
- Kodiak Trails: Kodiak Island Borough KIB_Trails, https://services1.arcgis.com/R5BNizttyFKxRSMm/arcgis/rest/services/KIB_Trails/FeatureServer/0
  (public, export enabled, credit "Kodiak Island Borough", no-warranty disclaimer; data edited 2025-08-19, fetched 2026-10-11) -> raw_kib_trails.geojson.
  build_trails.py: 964 records (3 null geometry) -> name fixes (Saltrey->Saltery, Old Womens->Old Woman, Zenter->Zentner, Kalsin ... Trail->Trails,
  unnamed -> LABEL) -> merged by (name, class) into 77 features / 70 names / 265.5 mi -> Douglas-Peucker 1.5 m in UTM 5N (97,612 -> 16,805 vertices).
- Chiniak logging roads: Victor's Google Earth project export (chiniak_logging_roads.kml, bundled as assets/kodiak/logging_roads.kml, converted on load
  by core/Kml.kt): 335 roads (KML colours), 14 "Borough Property" outline segments, 11 pins. Source "Victor (kodiakking.com)".
