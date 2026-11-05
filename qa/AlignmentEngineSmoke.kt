import com.iraqtranslate.SubtitleAlignmentEngine
import com.iraqtranslate.AudioFingerprintEngine

fun main() {
  val engine = SubtitleAlignmentEngine()
  val cues = listOf(
    SubtitleAlignmentEngine.Cue(10_000, 12_000, "مرحبا كيف حالك اليوم"),
    SubtitleAlignmentEngine.Cue(20_000, 22_000, "انا سعيد جدا برؤيتك مجددا"),
    SubtitleAlignmentEngine.Cue(30_000, 32_000, "علينا ان نذهب الى المنزل الآن")
  )
  val a = engine.align("مرحبا كيف حالك اليوم", 11_000, 1_000, cues)
  check(a != null && a.confidence >= .68)
  val b = engine.align("انا سعيد جدا برؤيتك مجددا", 22_000, 1_000, cues)
  check(b != null && b.confidence >= .68)
  check(engine.anchorCount() == 2)
  check(b.scale in 0.90..1.10) { "unexpected drift scale ${b.scale}" }
  val playerFit = SubtitleAlignmentEngine()
  playerFit.addReferenceAnchor(10_000.0, 10_200.0)
  playerFit.addReferenceAnchor(30_000.0, 31_000.0)
  check(playerFit.currentScale() in 1.03..1.07) { "player FPS drift fit failed: ${playerFit.currentScale()}" }
  val falseMatch = engine.align("نص مختلف تماما لا يطابق اي شيء", 41_000, 1_000, cues)
  check(falseMatch == null)
  val fp = AudioFingerprintEngine()
  val samples = ShortArray(64_000) { i -> (12000.0 * kotlin.math.sin(2.0 * Math.PI * 200.0 * i / 16000.0)).toInt().toShort() }
  val observations = fp.accept(samples)
  check(observations.size == 100)
  check(observations.drop(5).any { it.repeated }) { "repeated spectral pattern was not detected" }
  println("PASS: Arabic word-sequence matching, confidence rejection, timing anchors, 40ms spectral fingerprint repetition")
}
