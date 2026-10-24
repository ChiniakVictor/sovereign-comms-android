package com.victor.traillink.map.world

/** Where the worldwide map comes from. Shared with the Windows app (see /workspace/worldmaps-design.md). */
object WorldSources {
    /** Protomaps basemap (OpenStreetMap, ODbL), v4 tiles z0-15. Source Cooperative mirror; extracts by range request are the documented use. */
    const val PROTOMAPS_URL = "https://data.source.coop/protomaps/openstreetmap/v4.pmtiles"
    /** Fallback: Protomaps daily builds (newer data; a build is kept about a week). The newest key comes from BUILDS_JSON. */
    const val PROTOMAPS_BUILDS_JSON = "https://build-metadata.protomaps.dev/builds.json"
    const val PROTOMAPS_BUILD_BASE = "https://build.protomaps.com/"
    /** Mapterhorn terrain (Terrarium-encoded webp, 512 px), z0-12 planet archive; extracts allowed. */
    const val TERRAIN_URL = "https://download.mapterhorn.com/planet.pmtiles"
    /** Online hillshade tiles (no download). */
    const val TERRAIN_TILES = "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp"
    /** Online street map anywhere: OpenFreeMap (OpenMapTiles schema, no key, no limits on views). */
    const val OFM_TILEJSON = "https://tiles.openfreemap.org/planet"
    const val OFM_SPRITE = "https://tiles.openfreemap.org/sprites/ofm_f384/ofm"

    const val ATTR_OSM = "© OpenStreetMap contributors"
    const val ATTR_ONLINE = "OpenFreeMap © OpenMapTiles · Data © OpenStreetMap contributors"
    const val ATTR_OFFLINE = "Protomaps · Data © OpenStreetMap contributors"
    const val ATTR_TERRAIN = "Terrain: Mapterhorn (mapterhorn.com/attribution)"

    fun userAgent(version: String) = "SovereignComms/$version (Android; offline maps; +https://kodiakking.com)"
}
