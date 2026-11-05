import com.iraqtranslate.XmlTvGuide
import com.iraqtranslate.SubtitleSyncPolicy
import com.iraqtranslate.TitleMatchRules

fun main() {
    val xml = """<tv><channel id="ch1"><display-name>قناة أفلام</display-name></channel>
      <programme channel="ch1" start="20261105200000 +0300" stop="20261105220000 +0300">
        <title>Example Film</title><sub-title>Episode 1</sub-title>
      </programme></tv>"""
    val guide = XmlTvGuide.parse(xml.byteInputStream())
    check(guide.channels.size == 1 && guide.programmes.size == 1)
    val middle = XmlTvGuide.parseXmlTvTime("20261105210000 +0300")!!
    check(guide.now("ch1", middle)?.title == "Example Film")
    check(guide.now("ch1", middle + 3_600_000L) == null)
    val unsafe = """<!DOCTYPE tv [ <!ENTITY exploit SYSTEM "file:///etc/passwd"> ]><tv><channel id="x"><display-name>&exploit;</display-name></channel></tv>"""
    check(runCatching { XmlTvGuide.parse(unsafe.byteInputStream()) }.isFailure) { "XML external entities were accepted" }
    val imdbHits = listOf(
        TitleMatchRules.Hit("tt1", "Example Film", 2026),
        TitleMatchRules.Hit("tt2", "Example 2", 2026)
    )
    check(TitleMatchRules.uniqueExactMatch("Example Film (2026)", imdbHits)?.id == "tt1")
    check(TitleMatchRules.uniqueExactMatch("A Different Movie", imdbHits) == null)
    check(TitleMatchRules.uniqueExactMatch("Example Film", imdbHits + TitleMatchRules.Hit("tt3", "Example Film", 1990)) == null)
    val waiting = SubtitleSyncPolicy.mode(hasSrt = true, speechAnchored = false, trustedClock = false)
    check(waiting == SubtitleSyncPolicy.Mode.WAITING_FOR_SPEECH)
    check(!SubtitleSyncPolicy.shouldRenderSrt(waiting))
    check(SubtitleSyncPolicy.shouldRenderSrt(SubtitleSyncPolicy.mode(true, true, false)))
    check(SubtitleSyncPolicy.shouldRenderSrt(SubtitleSyncPolicy.mode(true, false, true)))
    println("PASS: XMLTV secure parsing, current programme, subtitle synchronization gate")
}
