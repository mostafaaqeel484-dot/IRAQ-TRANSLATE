package com.iraqtranslate

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException

/** XMLTV programmes from a user-provided guide. Not a DVB tuner, motor controller or blind scanner. */
object XmlTvGuide {
    data class Channel(val id: String, val name: String)
    data class Programme(val channelId: String, val title: String, val startMs: Long, val endMs: Long, val subtitle: String = "")
    data class Guide(val channels: List<Channel>, val programmes: List<Programme>) {
        fun now(channelId: String, nowMs: Long = System.currentTimeMillis()): Programme? =
            programmes.firstOrNull { it.channelId == channelId && nowMs >= it.startMs && nowMs < it.endMs }
    }

    /** Harden parser against XXE; the caller enforces an 8MB input cap. */
    fun parse(stream: InputStream): Guide {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) { throw exception }
            override fun error(exception: SAXParseException) { throw exception }
            override fun fatalError(exception: SAXParseException) { throw exception }
        })
        val doc = builder.parse(stream)
        val channelNodes = doc.getElementsByTagName("channel")
        val channels = ArrayList<Channel>()
        for (i in 0 until minOf(channelNodes.length, 800)) {
            val e = channelNodes.item(i) as? Element ?: continue
            val id = e.getAttribute("id").trim()
            val name = e.getElementsByTagName("display-name").item(0)?.textContent?.trim().orEmpty()
            if (id.isNotEmpty()) channels += Channel(id, name.ifBlank { id })
        }
        val programmeNodes = doc.getElementsByTagName("programme")
        val programmes = ArrayList<Programme>()
        for (i in 0 until minOf(programmeNodes.length, 35_000)) {
            val e = programmeNodes.item(i) as? Element ?: continue
            val channel = e.getAttribute("channel")
            val start = parseXmlTvTime(e.getAttribute("start")) ?: continue
            val end = parseXmlTvTime(e.getAttribute("stop")) ?: continue
            val title = e.getElementsByTagName("title").item(0)?.textContent?.trim().orEmpty()
            val subtitle = e.getElementsByTagName("sub-title").item(0)?.textContent?.trim().orEmpty()
            if (end > start && channel.isNotBlank() && title.isNotBlank())
                programmes += Programme(channel, title.take(180), start, end, subtitle.take(100))
        }
        return Guide(channels, programmes.sortedBy { it.startMs })
    }

    fun parseXmlTvTime(value: String): Long? {
        val input = value.trim()
        val match = Regex("^(\\d{12}|\\d{14})(?:\\s*([+-]\\d{4}))?").find(input) ?: return null
        val date = match.groupValues[1].let { if (it.length == 12) it + "00" else it }
        val offset = match.groupValues[2]
        return runCatching {
            val format = SimpleDateFormat(if (offset.isNotEmpty()) "yyyyMMddHHmmss Z" else "yyyyMMddHHmmss", Locale.US)
            format.isLenient = false
            format.parse(if (offset.isNotEmpty()) "$date $offset" else date)?.time
        }.getOrNull()
    }
}
