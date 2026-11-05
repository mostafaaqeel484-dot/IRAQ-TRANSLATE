import com.iraqtranslate.SubtitleCandidateScorer

fun main() {
    val scorer = SubtitleCandidateScorer()
    val dialogue = listOf(
        "مرحبا كيف حالك اليوم انا سعيد برؤيتك",
        "علينا ان نذهب الى المنزل قبل حلول الليل"
    )
    val matching = listOf(
        SubtitleCandidateScorer.Cue("مرحبا كيف حالك اليوم"),
        SubtitleCandidateScorer.Cue("انا سعيد برؤيتك مجددا"),
        SubtitleCandidateScorer.Cue("علينا ان نذهب الى المنزل قبل حلول الليل")
    )
    val unrelated = listOf(
        SubtitleCandidateScorer.Cue("الطقس جميل والشمس مشرقة"),
        SubtitleCandidateScorer.Cue("سوف نلتقي في مكان اخر غدا")
    )
    val good = scorer.score(dialogue, matching)
    val bad = scorer.score(dialogue, unrelated)
    check(good.confidence > bad.confidence)
    check(good.matchedWords >= 5)
    println("PASS: subtitle candidate scorer prefers dialogue-matching Arabic SRT cues")
}
