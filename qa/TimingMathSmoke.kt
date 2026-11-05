import com.iraqtranslate.SubtitleTimingMath

fun main() {
    check(SubtitleTimingMath.effectiveMediaTime(12_000, 1_000) == 11_000L)
    check(SubtitleTimingMath.effectiveMediaTime(12_000, -500) == 12_500L)
    check(SubtitleTimingMath.effectiveMediaTime(100, 2_000) == -1_900L)
    check(SubtitleTimingMath.effectiveMediaTime(0, 1_000) < 0L)
    check(SubtitleTimingMath.clamp(20_000L) == 15_000L)
    check(SubtitleTimingMath.clamp(-20_000L) == -15_000L)
    check("تأخير" in SubtitleTimingMath.format(500))
    println("PASS: SRT manual timing offset, leading/lagging semantics and bounded range")
}
