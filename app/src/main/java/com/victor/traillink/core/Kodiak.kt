package com.victor.traillink.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Kodiak archipelago outline (Kodiak, Afognak, Shuyak, Sitkalidak, Trinity Is.), excludes the Alaska Peninsula. */
object KodiakArea {
    // lon, lat (matches data/kodiak/build_layer.py)
    val POLY = arrayOf(
        doubleArrayOf(-154.95, 56.45), doubleArrayOf(-152.6, 56.45), doubleArrayOf(-151.75, 57.3), doubleArrayOf(-151.75, 58.75),
        doubleArrayOf(-153.25, 58.75), doubleArrayOf(-153.55, 58.35), doubleArrayOf(-153.95, 58.05), doubleArrayOf(-154.6, 57.75),
        doubleArrayOf(-154.95, 57.45),
    )
    const val S = 56.5; const val N = 58.7; const val W = -155.0; const val E = -151.8   // query box for the online update

    fun contains(lat: Double, lon: Double): Boolean {
        var inside = false; var j = POLY.size - 1
        for (i in POLY.indices) {
            val xi = POLY[i][0]; val yi = POLY[i][1]; val xj = POLY[j][0]; val yj = POLY[j][1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** True if the visible map box overlaps the archipelago (any corner/centre inside, or any outline vertex inside the box). */
    fun overlaps(s: Double, w: Double, n: Double, e: Double): Boolean {
        val pts = listOf(s to w, s to e, n to w, n to e, (s + n) / 2 to (w + e) / 2)
        if (pts.any { contains(it.first, it.second) }) return true
        return POLY.any { it[1] in s..n && it[0] in w..e }
    }
}

enum class EsmtKind(val label: String, val color: String) {
    TRAIL25("25-ft trail", "#FF9100"), TRAIL50("50-ft trail", "#FF3D00"), ROAD("Road", "#D500F9"), SITE("Site", "#2979FF"),
    UTILITY("Utility", "#9E9E9E"), AIR("Air/light/visibility", "#78909C"), AIRSTRIP("Aircraft landing strip", "#00B8D4"), OTHER("Other", "#BDBDBD");
    val key get() = name.lowercase()
    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: OTHER }
}

/** Mapping of BLM 17(b) easement types to the standard BLM allowed-use language (43 CFR 2650.4-7 / BLM 17(b) handbook). */
object Easements {
    const val NOTE = "Hunting, fishing, or trapping on or from the easement is not allowed without landowner permission."
    const val DISCLAIMER = "For reference only; verify with BLM before use. Easements can be relocated or vacated; stay on the easement."
    const val SITE_RADIUS_M = 35.9   // radius of a 1-acre circle
    /** Acreage from the BLM type text ("Site Easement (1-acre)", "Site Easement (2-acres)"); 1 when not stated. */
    fun siteAcres(type: String?): Double = Regex("""(\d+(?:\.\d+)?)-acre""").find(type ?: "")?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
    /** Radius (m) of a circle with the given acreage (1 acre = 4046.856 m²). */
    fun siteRadiusM(acres: Double): Double = Math.round(sqrt(acres * 4046.856 / PI) * 10) / 10.0
    val USES = mapOf(
        EsmtKind.TRAIL25 to "Travel by foot, dogsleds, animals, snowmobiles, two- and three-wheeled vehicles, and small all-terrain vehicles (less than 3,000 lbs GVW).",
        EsmtKind.TRAIL50 to "Uses allowed on a 25-ft trail, plus large all-terrain vehicles (more than 3,000 lbs GVW), tracked vehicles, and four-wheel-drive vehicles.",
        EsmtKind.ROAD to "Uses allowed on 25- and 50-ft trails, plus automobiles and trucks.",
        EsmtKind.SITE to "Vehicle parking (e.g. aircraft, boats, ATVs, snowmobiles, cars, trucks), temporary camping, and loading or unloading. Temporary camping, loading or unloading limited to 24 hours.",
        EsmtKind.UTILITY to "Existing utility easement. Not a public travel easement unless the conveyance document says so.",
        EsmtKind.AIR to "Reserved for air, light, or visibility purposes (e.g. around navigation aids). Not a public travel or camping easement.",
        EsmtKind.AIRSTRIP to "Aircraft landing strip and related uses as described in the conveyance document.",
    )

    data class Cls(val kind: EsmtKind, val widthFt: Int?, val cat: String)
    fun classify(type: String?): Cls {
        val t = type ?: ""
        return when {
            "Trail up to 25" in t -> Cls(EsmtKind.TRAIL25, 25, "Trail")
            "Trail up to 50" in t -> Cls(EsmtKind.TRAIL50, 50, "Trail")
            "Proposed Road" in t -> Cls(EsmtKind.ROAD, 100, "Road")
            "Road up to 60" in t -> Cls(EsmtKind.ROAD, 60, "Road")
            "Site Easement" in t -> Cls(EsmtKind.SITE, null, "Site")
            "utilities" in t -> Cls(EsmtKind.UTILITY, null, "Utility")
            "visability" in t || "visibility" in t -> Cls(EsmtKind.AIR, null, "Air/light/visibility")
            "landing strip" in t -> Cls(EsmtKind.AIRSTRIP, null, "Aircraft landing strip")
            else -> Cls(EsmtKind.OTHER, null, t.ifBlank { "Other" })
        }
    }

    private fun JSONObject.str(k: String) = optString(k, "").takeIf { !isNull(k) && it.isNotBlank() && it != "null" }?.trim()

    /** Normalises one raw BLM GeoJSON feature (layer 0 = points, 1 = lines) into the bundled schema. Returns 1 or 2 features. */
    fun normalize(raw: JSONObject, pointLayer: Boolean): List<JSONObject> {
        val p = raw.getJSONObject("properties"); val g = raw.optJSONObject("geometry") ?: return emptyList()
        val type = p.str("Easement_Type"); val c = classify(type); val ein = p.str("EIN")
        val pr = JSONObject().put("k", "esmt").put("kind", c.kind.key).put("cat", c.cat).put("uses", USES[c.kind] ?: "See conveyance document.").put("note", NOTE)
        fun put(k: String, v: Any?) { if (v != null) pr.put(k, v) }
        put("ein", ein); put("id", p.str("Easement_ID")); put("type", type)
        put("status", when { type?.startsWith("Proposed") == true -> "Proposed"; type?.startsWith("Existing") == true -> "Existing"; else -> null })
        put("width_ft", c.widthFt); put("owner", p.str("Land_Owner")); put("agency", p.str("Admin_Agency")); put("accessed", p.str("Land_Accessed"))
        put("restrict", p.str("Use_Restrictions")); put("name", p.str("Common_Name")); put("reserved", p.str("Easement_Status"))
        put("doc", p.str("Conveyance_Doc")?.let { d -> if (d.startsWith("http")) d else "https://sdms.ak.blm.gov/perl-bin/scanned_images/patent/disp_patent_pdf.pl?the_doctype=${p.str("Conveyance_Doc_Type") ?: "PA"}&patentnum=$d" }); put("doc_no", listOfNotNull(p.str("Conveyance_Doc_Type"), p.str("Conveyance_Doc_No")).joinToString(" ").ifBlank { null })
        if (!p.isNull("GIS_Miles") && p.has("GIS_Miles")) put("miles", p.optDouble("GIS_Miles"))
        put("label", ein?.let { "EIN $it" })
        val acres = siteAcres(type); val radius = siteRadiusM(acres)
        if (c.kind == EsmtKind.SITE) { pr.put("acres", acres); pr.put("r_m", radius) }
        val out = ArrayList<JSONObject>()
        if (pointLayer) {
            val a = if (acres % 1.0 == 0.0) acres.toInt().toString() else acres.toString()
            pr.put("approx", if (c.kind == EsmtKind.SITE) "Drawn as a $a-acre circle (radius $radius m) around the BLM point; actual boundaries are in the conveyance document." else "Point location from BLM.")
            out += JSONObject().put("type", "Feature").put("geometry", g).put("properties", JSONObject(pr.toString()).put("geom", "pt"))
            if (c.kind == EsmtKind.SITE) {
                val co = g.getJSONArray("coordinates")
                out += JSONObject().put("type", "Feature").put("geometry", circle(co.getDouble(1), co.getDouble(0), radius)).put("properties", JSONObject(pr.toString()).put("geom", "area"))
            }
        } else out += JSONObject().put("type", "Feature").put("geometry", g).put("properties", pr)
        return out
    }

    fun circle(lat: Double, lon: Double, r: Double, n: Int = 24): JSONObject {
        val ring = JSONArray()
        for (i in 0..n) {
            val a = 2 * PI * i / n
            ring.put(JSONArray().put(lon + r * sin(a) / (111_320.0 * cos(Math.toRadians(lat)))).put(lat + r * cos(a) / 110_540.0))
        }
        return JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))
    }
}

/** A parsed bundled feature for tap/near checks. Lines are lists of [lat, lon]. */
class KFeature(val props: JSONObject, val lines: List<List<DoubleArray>>, val polys: List<List<DoubleArray>>, val point: DoubleArray?) {
    val k: String get() = props.optString("k")
    val widthM: Double? get() = if (props.has("width_ft")) props.getDouble("width_ft") * 0.3048 else null
    val title: String get() = when (k) {
        "row" -> "${props.optString("route")} right-of-way"
        "trail" -> props.optString("name").ifBlank { "Trail" }
        "rs2477" -> props.optString("name").ifBlank { "RS 2477 trail" }.let { n -> if (n.contains("RS 2477")) n else "$n (RS 2477)" }
        "dnr" -> "State access easement" + (props.optString("id").ifBlank { null }?.let { " · $it" } ?: "")
        "logroad", "logpin", "logarea" -> props.optString("name").ifBlank { if (k == "logpin") "Logging-road pin" else "Logging road" }
        else -> listOfNotNull(props.optString("label").ifBlank { null }, EsmtKind.of(props.optString("kind")).label).joinToString(" · ")
    }
}

object KGeo {
    private const val R = 6_371_000.0
    /** Distance (m) from p to segment a-b and the nearest point, in a local equirectangular frame (fine at < 50 km). */
    fun toSegment(lat: Double, lon: Double, a: DoubleArray, b: DoubleArray): Pair<Double, DoubleArray> {
        val k = cos(Math.toRadians(lat))
        val ax = Math.toRadians(a[1] - lon) * k * R; val ay = Math.toRadians(a[0] - lat) * R
        val bx = Math.toRadians(b[1] - lon) * k * R; val by = Math.toRadians(b[0] - lat) * R
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy).div(l2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        return hypot(px, py) to doubleArrayOf(lat + Math.toDegrees(py / R), lon + Math.toDegrees(px / (R * k)))
    }
    fun toLine(lat: Double, lon: Double, line: List<DoubleArray>): Pair<Double, DoubleArray> {
        if (line.size == 1) return Geo.distanceM(lat, lon, line[0][0], line[0][1]) to line[0]
        var best = Double.MAX_VALUE to line[0]
        for (i in 0 until line.size - 1) { val r = toSegment(lat, lon, line[i], line[i + 1]); if (r.first < best.first) best = r }
        return best
    }
    fun inPoly(lat: Double, lon: Double, ring: List<DoubleArray>): Boolean {
        var inside = false; var j = ring.size - 1
        for (i in ring.indices) {
            val yi = ring[i][0]; val xi = ring[i][1]; val yj = ring[j][0]; val xj = ring[j][1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Result of "Am I on an easement?": centre distance, signed edge distance (negative = inside), nearest point. */
    data class Near(val f: KFeature, val centreM: Double, val edgeM: Double?, val at: DoubleArray) { val on get() = edgeM != null && edgeM <= 0 }

    fun nearestEasement(lat: Double, lon: Double, fs: List<KFeature>): Near? {
        var best: Near? = null
        for (f in fs) {
            if (f.k != "esmt" || f.props.optBoolean("unmapped")) continue
            val n: Near? = when {
                f.lines.isNotEmpty() -> {
                    var b = Double.MAX_VALUE to doubleArrayOf(0.0, 0.0)
                    for (l in f.lines) { val r = toLine(lat, lon, l); if (r.first < b.first) b = r }
                    Near(f, b.first, f.widthM?.let { b.first - it / 2 }, b.second)
                }
                f.point != null && f.props.optString("geom") == "pt" -> {
                    val d = Geo.distanceM(lat, lon, f.point[0], f.point[1])
                    Near(f, d, if (EsmtKind.of(f.props.optString("kind")) == EsmtKind.SITE) d - f.props.optDouble("r_m", Easements.SITE_RADIUS_M) else null, f.point)
                }
                else -> null
            }
            if (n != null && (best == null || (n.edgeM ?: n.centreM) < (best.edgeM ?: best.centreM))) best = n
        }
        return best
    }

    fun parse(fc: String): List<KFeature> {
        val arr = JSONObject(fc).getJSONArray("features"); val out = ArrayList<KFeature>(arr.length())
        fun ll(a: JSONArray) = (0 until a.length()).map { val p = a.getJSONArray(it); doubleArrayOf(p.getDouble(1), p.getDouble(0)) }
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i); val g = f.optJSONObject("geometry") ?: continue; val c = g.getJSONArray("coordinates")
            val lines = ArrayList<List<DoubleArray>>(); val polys = ArrayList<List<DoubleArray>>(); var pt: DoubleArray? = null
            when (g.getString("type")) {
                "Point" -> pt = doubleArrayOf(c.getDouble(1), c.getDouble(0))
                "LineString" -> lines += ll(c)
                "MultiLineString" -> for (j in 0 until c.length()) lines += ll(c.getJSONArray(j))
                "Polygon" -> polys += ll(c.getJSONArray(0))
                "MultiPolygon" -> for (j in 0 until c.length()) polys += ll(c.getJSONArray(j).getJSONArray(0))
            }
            if (pt == null && polys.isNotEmpty() && lines.isEmpty()) { val r = polys[0]; pt = doubleArrayOf(r.sumOf { it[0] } / r.size, r.sumOf { it[1] } / r.size) }
            out += KFeature(f.getJSONObject("properties"), lines, polys, pt)
        }
        return out
    }
}

/** Kodiak Trails layer (Kodiak Island Borough KIB_Trails): classes, styling constants, summary. */
object TrailsInfo {
    const val COLOR = "#00E5FF"       // cyan: distinct from easement orange/red/purple/blue and the amber ROW band
    const val SOURCE = "Kodiak Island Borough"
    const val NO_WARRANTY = "Kodiak Island Borough trail data is provided with no warranty of accuracy, completeness, or current conditions. Trails may cross private land; verify access."
    val CLASS_LABEL = mapOf("double" to "Double track", "single" to "Single track", "route" to "Route (no built tread)", "shared" to "Shared-use path")
    fun classLabel(cls: String?) = CLASS_LABEL[cls] ?: "Trail"
    fun miles(fs: List<KFeature>) = fs.filter { it.k == "trail" }.sumOf { it.props.optDouble("miles", 0.0) }
    fun summary(fs: List<KFeature>): String {
        val t = fs.filter { it.k == "trail" }; if (t.isEmpty()) return "no trails"
        return "${t.map { it.props.optString("name") }.distinct().size} trails, %.0f mi".format(java.util.Locale.US, miles(t))
    }
}
