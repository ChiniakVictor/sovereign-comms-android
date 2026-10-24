package com.victor.traillink.core

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * KML/KMZ -> GeoJSON for user-made maps (e.g. Victor's Google Earth "Chiniak logging roads" project, exported with
 * Project ⋮ → Export as KML file). Keeps names, descriptions, folder, line/icon/polygon colours (styleUrl, StyleMap and
 * inline styles), and pins. Output feature kinds: k = "<prefix>road" (lines), "<prefix>pin" (points), "<prefix>area" (polygons).
 */
object Kml {
    data class Style(val line: String? = null, val width: Double? = null, val icon: String? = null, val fill: String? = null)

    /** KML colour aabbggrr -> "#rrggbb" (alpha dropped). */
    fun color(kml: String?): String? {
        val s = kml?.trim()?.removePrefix("#") ?: return null
        if (s.length != 8 || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return "#" + (s.substring(6, 8) + s.substring(4, 6) + s.substring(2, 4)).uppercase()
    }

    /** Accepts raw KML text bytes or a KMZ (zip) holding a .kml (doc.kml preferred). */
    fun kmlBytes(data: ByteArray): ByteArray {
        if (data.size < 4 || data[0] != 'P'.code.toByte() || data[1] != 'K'.code.toByte()) return data
        var first: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(data)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith(".kml", true)) {
                    val b = z.readBytes()
                    if (e.name.equals("doc.kml", true)) return b
                    if (first == null) first = b
                }
            }
        }
        return first ?: error("KMZ contains no .kml file")
    }

    private fun Element.kids(name: String): List<Element> = (0 until childNodes.length).map { childNodes.item(it) }
        .filter { it.nodeType == Node.ELEMENT_NODE && (it.localName ?: it.nodeName).substringAfter(':') == name }.map { it as Element }
    private fun Element.kid(name: String) = kids(name).firstOrNull()
    private fun Element.text(name: String) = kid(name)?.textContent?.trim()?.ifBlank { null }
    private fun Element.all(name: String): List<Element> {
        val nl = getElementsByTagNameNS("*", name); return (0 until nl.length).map { nl.item(it) as Element }
    }

    const val NS = "http://www.opengis.net/kml/2.2"
    private val UNTITLED = Regex("(?i)^untitled( (path|placemark|polygon|folder|line))?$")
    /** Google Earth web icon URLs carry the pin colour: .../icon?color=1976d2&id=2000 */
    fun hrefColor(href: String?): String? = href?.let { Regex("[?&]color=([0-9a-fA-F]{6})").find(it)?.groupValues?.get(1)?.uppercase()?.let { c -> "#$c" } }
    private fun idOf(e: Element) = e.getAttribute("id").ifBlank { e.getAttributeNS(NS, "id") }.ifBlank { null }
    private fun cleanName(n: String?) = n?.takeUnless { UNTITLED.matches(it) }

    private fun parseStyle(s: Element) = Style(
        line = color(s.kid("LineStyle")?.text("color")),
        width = s.kid("LineStyle")?.text("width")?.toDoubleOrNull(),
        icon = color(s.kid("IconStyle")?.text("color")) ?: hrefColor(s.kid("IconStyle")?.kid("Icon")?.text("href")),
        fill = color(s.kid("PolyStyle")?.text("color")),
    )

    fun stripHtml(s: String?): String? = s?.replace(Regex("(?i)<br\\s*/?>"), "\n")?.replace(Regex("<[^>]+>"), "")
        ?.replace("&nbsp;", " ")?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.trim()?.ifBlank { null }

    /** Parses "lon,lat[,alt] lon,lat..." into a GeoJSON position array. */
    fun coords(text: String?): JSONArray {
        val out = JSONArray()
        text?.trim()?.split(Regex("\\s+"))?.forEach { t ->
            val p = t.split(',')
            if (p.size >= 2) { val lon = p[0].toDoubleOrNull(); val lat = p[1].toDoubleOrNull(); if (lon != null && lat != null) out.put(JSONArray().put(lon).put(lat)) }
        }
        return out
    }

    /** @param areaName lines whose name matches are outlines (k = "<prefix>area"), e.g. property boundaries drawn as paths. */
    fun toGeoJson(data: ByteArray, prefix: String = "log", source: String? = null, areaName: Regex? = null): JSONObject {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(kmlBytes(data))).documentElement
        val styles = HashMap<String, Style>()
        // plain <Style id> and Google Earth's <gx:CascadingStyle kml:id><Style/></gx:CascadingStyle>
        doc.all("Style").forEach { s -> (idOf(s) ?: (s.parentNode as? Element)?.takeIf { (it.localName ?: "") == "CascadingStyle" }?.let { idOf(it) })?.let { styles[it] = parseStyle(s) } }
        val maps = HashMap<String, String>()
        doc.all("StyleMap").forEach { m ->
            val id = idOf(m) ?: return@forEach
            val normal = m.kids("Pair").firstOrNull { it.text("key") == "normal" } ?: m.kids("Pair").firstOrNull()
            normal?.text("styleUrl")?.let { maps[id] = it.substringAfterLast('#') }
        }
        fun styleOf(pm: Element): Style {
            var s = Style()
            pm.text("styleUrl")?.substringAfterLast('#')?.let { id -> styles[maps[id] ?: id]?.let { s = it } }
            pm.kid("Style")?.let { val i = parseStyle(it); s = Style(i.line ?: s.line, i.width ?: s.width, i.icon ?: s.icon, i.fill ?: s.fill) }
            return s
        }
        fun folderOf(pm: Element): String? {
            var n: Node? = pm.parentNode
            while (n is Element) { if ((n.localName ?: n.nodeName).substringAfter(':') == "Folder") return cleanName(n.text("name")); n = n.parentNode }
            return null
        }
        val feats = JSONArray(); var i = 0
        for (pm in doc.all("Placemark")) {
            val st = styleOf(pm); val name = cleanName(pm.text("name")); val desc = stripHtml(pm.text("description")); val folder = folderOf(pm)
            fun add(kind: String, geom: JSONObject, color: String?) {
                val p = JSONObject().put("k", prefix + kind).put("id", "$prefix${++i}")
                name?.let { p.put("name", it).put("label", it) }; desc?.let { p.put("desc", it) }; folder?.let { p.put("folder", it) }
                color?.let { p.put("color", it) }; if (kind == "road") st.width?.let { p.put("width", it) }; source?.let { p.put("src", it) }
                feats.put(JSONObject().put("type", "Feature").put("geometry", geom).put("properties", p))
            }
            for (ls in pm.all("LineString")) { val c = coords(ls.text("coordinates")); if (c.length() >= 2) {
                val area = areaName != null && name != null && areaName.containsMatchIn(name)
                add(if (area) "area" else "road", JSONObject().put("type", "LineString").put("coordinates", c), st.line)
            } }
            for (pt in pm.all("Point")) { val c = coords(pt.text("coordinates")); if (c.length() >= 1) add("pin", JSONObject().put("type", "Point").put("coordinates", c.getJSONArray(0)), st.icon) }
            for (pg in pm.all("Polygon")) {
                val outer = pg.kid("outerBoundaryIs")?.kid("LinearRing")?.text("coordinates") ?: continue
                val c = coords(outer); if (c.length() >= 4) add("area", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(c)), st.line ?: st.fill)
            }
        }
        return JSONObject().put("type", "FeatureCollection").put("features", feats)
    }
}
