import com.iraqtranslate.MediaSessionPositionParser
fun main() {
 val dump = """
Sessions Stack:
  Session #0:
    package=com.example.iptv
    state=PlaybackState {state=3, position=10000, buffered position=50000, speed=1.0, updated=100000, actions=...}
  Session #1:
    package=com.example.other
    state=PlaybackState {state=2, position=40000, buffered position=50000, speed=0.0, updated=105000, actions=...}
""".trimIndent()
 val playing = MediaSessionPositionParser.parse(dump, 102000)!!
 check(playing.packageName == "com.example.iptv")
 check(playing.positionMs == 12000L)
 check(playing.speed == 1.0)
 val pausedDump = "Session #1:\n package=com.example.other\n state=PlaybackState {state=2, position=40000, buffered position=50000, speed=0.0, updated=105000}"
 val paused = MediaSessionPositionParser.parse(pausedDump, 110000)!!
 check(paused.positionMs == 40000L && paused.speed == 0.0)
 println("PASS MediaSession parser: playing extrapolation and pause freeze")
}
