package com.iraqtranslate

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Automatic title discovery and Arabic subtitle retrieval. API keys are user supplied, never bundled. */
class AutomaticSubtitleManager(private val context: Context) {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)
    data class ActiveSubtitle(
        val title: String,
        val provider: String,
        val cues: List<Cue>,
        @Volatile var scale: Double,
        @Volatile var mediaAtSessionStartMs: Double,
        @Volatile var confidence: Double = 0.0,
        @Volatile var speechAnchored: Boolean = false
    )

    companion object {
        const val PREF_WYZIE_KEY = "wyzie"
        const val PREF_SUBDL_KEY = "subdl"
        const val PREF_OPENSUBTITLES_KEY = "opensubtitles"
        const val PREF_SUBSOURCE_KEY = "subsource"
        private const val TAG = "AutoSubtitles"
    }

    @Volatile private var active: ActiveSubtitle? = null
    @Volatile private var lastLookupAt = 0L
    @Volatile private var lastQuery = ""
    @Volatile private var sessionStartElapsedMs = 0L
    @Volatile private var activeIdentity = ""
    @Volatile private var lastSyncAttemptAt = 0L
    private val dialogueWindow = StringBuilder()
    private val speechLock = Any()
    private val speechSegments = ArrayList<Pair<Long, Long>>()
    private val syncAnchors = ArrayList<Pair<Double, Double>>() // capture milliseconds from session start -> media milliseconds
    private val localAligner = SubtitleAlignmentEngine()
    private val candidateScorer = SubtitleCandidateScorer()
    private val candidateDialogue = ArrayDeque<String>()
    private val playerSubtitleAligner = SubtitleAlignmentEngine()
    private val decoderSubtitleAligner = SubtitleAlignmentEngine()
    private val decoderPtsClock = VideoPtsClock()
    private val referenceFingerprintMatcher = ReferenceAudioFingerprintMatcher()
    private val recentFingerprints = ArrayDeque<AudioFingerprintEngine.Fingerprint>()
    @Volatile private var lastReferenceMatch: ReferenceAudioFingerprintMatcher.Match? = null
    @Volatile private var lastFingerprintMatchAtSample = -1L
    @Volatile private var playerPositionMs = -1L
    @Volatile private var playerPositionSpeed = 1.0
    @Volatile private var playerPositionState = -1
    @Volatile private var playerPositionObservedElapsedMs = 0L

    fun activeSubtitle(): ActiveSubtitle? = active

    /** Wait for Arabic speech alignment, unless a real player/video clock is available. */
    fun syncMode(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): SubtitleSyncPolicy.Mode {
        val current = active
        val pts = decoderPtsClock.latest()
        val decoderFresh = pts != null && nowElapsedMs - pts.observedElapsedMs in -2_000L..5_000L
        val playerFresh = playerPositionMs >= 0L && playerPositionObservedElapsedMs > 0 &&
            playerPositionState in setOf(2, 3, 6) &&
            (playerPositionState != 3 || nowElapsedMs - playerPositionObservedElapsedMs <= 5_000L)
        return SubtitleSyncPolicy.mode(current != null, current?.speechAnchored == true, decoderFresh || playerFresh)
    }
    fun canRenderExternal(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): Boolean =
        SubtitleSyncPolicy.shouldRenderSrt(syncMode(nowElapsedMs))
    fun syncLabel(): String = when (syncMode()) {
        SubtitleSyncPolicy.Mode.LIVE_TRANSLATION -> "ترجمة كلام مباشرة • لا يوجد SRT"
        SubtitleSyncPolicy.Mode.WAITING_FOR_SPEECH -> "ملف SRT جاهز • بانتظار تطابق الكلام التالي"
        SubtitleSyncPolicy.Mode.SPEECH_ANCHORED -> "SRT متزامن بمحاذاة الكلام"
        SubtitleSyncPolicy.Mode.PLAYER_CLOCK -> "SRT بالتوقيت المعلن من المشغّل (لم تُتحقق محاذاة الكلام)"
    }

    /** Recalibrate from next spoken words without deleting selected/downloaded SRT. */
    @Synchronized fun resyncFromNextSpeech(): Boolean {
        val current = active ?: return false
        localAligner.reset()
        playerSubtitleAligner.reset()
        decoderSubtitleAligner.reset()
        syncAnchors.clear()
        lastReferenceMatch = null
        current.scale = 1.0
        current.mediaAtSessionStartMs = 0.0
        current.confidence = 0.0
        current.speechAnchored = false
        return true
    }

    /** Forget a previously selected title, retaining current recording/session start time. */
    @Synchronized fun forgetExternalSubtitle() {
        if (active?.provider == "Local SRT") return
        active = null
        activeIdentity = ""
        dialogueWindow.clear()
        candidateDialogue.clear()
        syncAnchors.clear()
        localAligner.reset()
        playerSubtitleAligner.reset()
        decoderSubtitleAligner.reset()
        lastQuery = ""
        lastLookupAt = 0L
    }

    /** Import user-authorized SRT once at session start. No external provider keys are needed. */
    @Synchronized fun loadManualSubtitles(): Boolean {
        val name = ManualSubtitleRepository.name(context) ?: return false
        val raw = ManualSubtitleRepository.content(context) ?: return false
        val cues = parseSrt(raw)
        if (cues.size < 2) return false
        activeIdentity = "local-srt"
        active = ActiveSubtitle(name, "Local SRT", cues, 1.0, 0.0)
        Log.i(TAG, "Loaded user SRT cues=${cues.size}")
        return true
    }
    @Synchronized fun setSessionStart(elapsedMs: Long) { sessionStartElapsedMs = elapsedMs }
    fun addSpeechSegment(startElapsedMs: Long, endElapsedMs: Long) {
        if (endElapsedMs <= startElapsedMs || sessionStartElapsedMs <= 0L) return
        val start = (startElapsedMs - sessionStartElapsedMs).coerceAtLeast(0L)
        val end = (endElapsedMs - sessionStartElapsedMs).coerceAtLeast(start + 1L)
        synchronized(speechLock) {
            if (speechSegments.lastOrNull()?.let { start <= it.second + 250L } == true) {
                val previous = speechSegments.removeAt(speechSegments.lastIndex)
                speechSegments += previous.first to maxOf(previous.second, end)
            } else speechSegments += start to end
            while (speechSegments.size > 2200) speechSegments.removeAt(0)
        }
    }
    @Synchronized fun clear() {
        active = null; dialogueWindow.clear(); candidateDialogue.clear(); lastQuery = ""; lastLookupAt = 0L
        activeIdentity = ""; syncAnchors.clear(); lastSyncAttemptAt = 0L
        localAligner.reset()
        playerSubtitleAligner.reset()
        decoderSubtitleAligner.reset()
        playerPositionMs = -1L; playerPositionSpeed = 1.0; playerPositionState = -1
        playerPositionObservedElapsedMs = 0L
        decoderPtsClock.reset()
        synchronized(recentFingerprints) { recentFingerprints.clear() }
        lastReferenceMatch = null
        lastFingerprintMatchAtSample = -1L
        synchronized(speechLock) { speechSegments.clear() }
    }

    /** Retain translated Arabic speech so candidate SRTs can be checked before activation. */
    @Synchronized
    fun observeDialogueForCandidate(arabicDialogue: String) {
        val clean = arabicDialogue.trim().replace(Regex("\\s+"), " ")
        if (clean.length < 8 || candidateDialogue.lastOrNull() == clean) return
        candidateDialogue.add(clean)
        while (candidateDialogue.size > 8) candidateDialogue.removeFirst()
    }

    private fun candidateLooksUsable(cues: List<Cue>, title: String, provider: String): Boolean {
        val samples = synchronized(this) { candidateDialogue.toList() }
        val score = candidateScorer.score(samples, cues.map { SubtitleCandidateScorer.Cue(it.text) })
        if (score.evidenceWords < 6) return true
        val accepted = score.matchedWords >= 4 && score.confidence >= 0.30
        if (!accepted) {
            Log.w(TAG, "Rejected subtitle candidate: title=$title provider=$provider " +
                "score=${"%.3f".format(score.confidence)} matched=${score.matchedWords}/${score.evidenceWords}")
        }
        return accepted
    }

    /** Align translated Arabic dialogue to downloaded Arabic subtitle words and continuously refine timebase. */
    @Synchronized
    fun observeTranslatedDialogue(arabicDialogue: String, captureElapsedMs: Long): Double {
        val current = active ?: return 0.0
        val result = localAligner.align(
            arabicDialogue, captureElapsedMs, sessionStartElapsedMs,
            current.cues.map { SubtitleAlignmentEngine.Cue(it.startMs, it.endMs, it.text) }
        ) ?: return current.confidence
        // Ignore weak anchors and avoid a sudden visible jump when one phrase is misrecognized.
        if (result.confidence >= 0.68) {
            current.scale = result.scale
            current.mediaAtSessionStartMs = result.offsetMs
            current.confidence = result.confidence
            current.speechAnchored = true
            val decoderAtSpeech = decoderPtsClock.positionMs(captureElapsedMs)
            val decoderFresh = decoderPtsClock.latest()?.let {
                captureElapsedMs - it.observedElapsedMs in -2_000L..5_000L
            } == true
            if (decoderFresh && decoderAtSpeech != null) {
                decoderSubtitleAligner.addReferenceAnchor(decoderAtSpeech, result.subtitleAnchorMs)
            } else {
                val playerAtSpeech = playerPositionAt(captureElapsedMs)
                if (playerAtSpeech != null) playerSubtitleAligner.addReferenceAnchor(playerAtSpeech, result.subtitleAnchorMs)
            }
        }
        return current.confidence
    }

    fun updatePlayerPosition(positionMs: Long, speed: Double, state: Int, observedElapsedMs: Long) {
        if (positionMs < 0 || !speed.isFinite() || speed !in 0.0..4.0) return
        val previousProjected = playerPositionAt(observedElapsedMs)
        val jump = previousProjected != null && kotlin.math.abs(positionMs - previousProjected) > 7_000.0
        if (jump) {
            // A seek invalidates speech/session-based calibration; the player's new position remains usable.
            resyncFromNextSpeech()
            Log.i(TAG, "MediaSession seek detected; waiting for fresh speech anchor")
        }
        playerPositionMs = positionMs
        playerPositionSpeed = if (state == 2 || state == 6) 0.0 else speed
        playerPositionState = state
        playerPositionObservedElapsedMs = observedElapsedMs
    }

    /** Loads a caller-generated CSV catalogue from app-private storage. */
    fun loadReferenceFingerprintCatalogue(): Int {
        val file = java.io.File(context.filesDir, "reference_fingerprints.csv")
        if (!file.isFile || file.length() <= 0L) return 0
        val entries = ArrayList<ReferenceAudioFingerprintMatcher.Reference>()
        file.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                if (entries.size >= 400_000) return@forEach
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("mediaId", ignoreCase = true)) return@forEach
                val fields = line.split(',', limit = 3)
                if (fields.size != 3) return@forEach
                val mediaId = fields[0].trim()
                val mediaTimeMs = fields[1].trim().toLongOrNull() ?: return@forEach
                val hashText = fields[2].trim().removePrefix("0x").removePrefix("0X")
                val hash = runCatching { java.lang.Long.parseUnsignedLong(hashText, 16) }.getOrNull()
                    ?: return@forEach
                if (mediaId.isNotBlank() && mediaTimeMs >= 0L) {
                    entries += ReferenceAudioFingerprintMatcher.Reference(mediaId, mediaTimeMs, hash)
                }
            }
        }
        setReferenceFingerprintCatalogue(entries)
        Log.i(TAG, "Loaded ${entries.size} reference audio fingerprints from ${file.name}")
        return entries.size
    }

    /** Load fingerprints generated from known media; mediaId should map to a title/episode in the caller's catalogue. */
    fun setReferenceFingerprintCatalogue(entries: Collection<ReferenceAudioFingerprintMatcher.Reference>) {
        referenceFingerprintMatcher.replaceCatalogue(entries)
        synchronized(recentFingerprints) { recentFingerprints.clear() }
        lastReferenceMatch = null
        lastFingerprintMatchAtSample = -1L
    }

    /** Feed 40 ms playback landmarks. A match is returned only after several consistent reference hits. */
    fun observeReferenceFingerprint(fingerprint: AudioFingerprintEngine.Fingerprint): ReferenceAudioFingerprintMatcher.Match? {
        synchronized(recentFingerprints) {
            recentFingerprints.add(fingerprint)
            while (recentFingerprints.size > 250) recentFingerprints.removeFirst()
            if (recentFingerprints.size < 8) return null
            if (lastFingerprintMatchAtSample >= 0L && fingerprint.sampleStart - lastFingerprintMatchAtSample < 16_000L) return null
            lastFingerprintMatchAtSample = fingerprint.sampleStart
            val match = referenceFingerprintMatcher.match(recentFingerprints.takeLast(64)) ?: return null
            if (match.hits < 6 || match.confidence < 0.72 || match.coverage < 0.35) return null
            val previous = lastReferenceMatch
            if (previous != null && previous.mediaId == match.mediaId &&
                kotlin.math.abs(previous.offsetMs - match.offsetMs) < 2_000L) return null
            lastReferenceMatch = match
            return match
        }
    }

    /** Applies a reference match only when its media identity equals the currently loaded title/episode identity. */
    @Synchronized fun applyReferenceFingerprintMatch(match: ReferenceAudioFingerprintMatcher.Match): Boolean {
        if (match.confidence < 0.72 || match.coverage < 0.35 || match.hits < 6 || match.offsetMs < 0L) return false
        if (active == null || activeIdentity.isBlank() || match.mediaId != activeIdentity) return false
        localAligner.addReferenceAnchor(0.0, match.offsetMs.toDouble())
        val captureAtMatch = sessionStartElapsedMs + match.captureTimeMs
        val subtitleAtMatch = match.offsetMs + match.captureTimeMs
        decoderPtsClock.positionMs(captureAtMatch)?.let { decoderPosition ->
            decoderSubtitleAligner.addReferenceAnchor(decoderPosition, subtitleAtMatch.toDouble())
        }
        playerPositionAt(captureAtMatch)?.let { playerPosition ->
            playerSubtitleAligner.addReferenceAnchor(playerPosition, subtitleAtMatch.toDouble())
        }
        active?.let {
            it.scale = localAligner.currentScale()
            it.mediaAtSessionStartMs = localAligner.currentOffsetMs()
            it.confidence = maxOf(it.confidence, match.confidence)
        }
        return true
    }

    /** Called by an IPTV player integration with decoder/rendered video frame PTS in microseconds. */
    fun updateDecoderVideoPts(ptsUs: Long, observedElapsedMs: Long, speed: Double, playing: Boolean) {
        val snapshot = decoderPtsClock.update(ptsUs, observedElapsedMs, speed, playing)
        if (snapshot?.discontinuity == true) {
            // A seek or decoder clock jump invalidates the old player-to-subtitle calibration.
            resyncFromNextSpeech()
            playerPositionMs = -1L
            playerPositionObservedElapsedMs = 0L
            playerPositionState = -1
        }
    }

    fun expirePlayerPosition(nowElapsedMs: Long) {
        if (playerPositionObservedElapsedMs > 0L && nowElapsedMs - playerPositionObservedElapsedMs > 10_000L) {
            playerPositionMs = -1L
            playerPositionSpeed = 1.0
            playerPositionState = -1
            playerPositionObservedElapsedMs = 0L
        }
    }

    @Synchronized fun alignmentStatus(): String {
        val current = active ?: return "No external subtitle"
        return "${current.provider}: confidence=${"%.2f".format(current.confidence)}, anchors=${localAligner.anchorCount()}, playerAnchors=${playerSubtitleAligner.anchorCount()}, decoderAnchors=${decoderSubtitleAligner.anchorCount()}, decoderPTS=${decoderPtsClock.latest() != null}, fingerprintCatalogue=${referenceFingerprintMatcher.catalogueSize()}, fingerprintMatch=${lastReferenceMatch?.mediaId ?: "none"}, scale=${"%.5f".format(current.scale)}"
    }

    /** Feed recognized dialogue; Wyzie quote search identifies title/episode and media timestamp. */
    fun identifyAndLoad(dialogue: String, sourceLanguage: String, captureElapsedMs: Long): String? {
        if (active?.provider == "Local SRT" || MediaSelectionStore.get(context) != null) return null
        // Serialize only the in-memory phrase/throttle update. Network calls must not hold
        // the manager monitor, otherwise subtitle alignment and ticker reads stall.
        val phrase = synchronized(this) {
            val cleanDialogue = dialogue.trim().replace(Regex("\\s+"), " ")
            if (cleanDialogue.isNotBlank() && dialogueWindow.indexOf(cleanDialogue) < 0) {
                if (dialogueWindow.isNotEmpty()) dialogueWindow.append(' ')
                dialogueWindow.append(cleanDialogue)
                if (dialogueWindow.length > 190) dialogueWindow.delete(0, dialogueWindow.length - 190)
            }
            val candidatePhrase = (if (cleanDialogue.length >= 12) cleanDialogue
                else dialogueWindow.toString().takeLast(190)).trim()
            val now = android.os.SystemClock.elapsedRealtime()
            if (candidatePhrase.length !in 3..200 || now - lastLookupAt < 25_000L ||
                candidatePhrase == lastQuery) return null
            lastLookupAt = now
            candidatePhrase
        }

        val wyzieKey = SubtitleKeyStore.get(context, PREF_WYZIE_KEY).trim()
        if (wyzieKey.isBlank()) return "أدخل مفتاح Wyzie لتفعيل التعرف التلقائي على اسم الفيلم والحلقة"
        val quoteUrl = "https://sub.wyzie.io/quote?q=${enc(phrase)}&key=${enc(wyzieKey)}&language=${enc(sourceLanguage)}&limit=5"
        val quotes = requestJson(quoteUrl) as? JSONArray ?: return null
        if (quotes.length() == 0) return null
        val best = quotes.optJSONObject(0) ?: return null
        val imdb = best.optString("imdbId").takeIf { it.startsWith("tt") } ?: return null
        val title = best.optString("title", "Unknown title")
        val season = best.optInt("season", -1)
        val episode = best.optInt("episode", -1)
        val mediaTime = best.optLong("timeMs", -1L)
        if (mediaTime < 0L) return null

        val identity = "$imdb:$season:$episode"
        val alreadyActive = synchronized(this) {
            lastQuery = phrase
            if (activeIdentity.isNotBlank() && activeIdentity != identity) {
                active = null
                activeIdentity = ""
                // Keep only the newest translated phrase; older dialogue belongs to the previous title.
                val newestCandidatePhrase = candidateDialogue.lastOrNull()
                candidateDialogue.clear()
                if (!newestCandidatePhrase.isNullOrBlank()) candidateDialogue.add(newestCandidatePhrase)
                syncAnchors.clear()
                localAligner.reset()
                playerSubtitleAligner.reset()
                decoderSubtitleAligner.reset()
            }
            addSyncAnchor(captureElapsedMs, mediaTime)
            active != null && activeIdentity == identity
        }

        if (alreadyActive) {
            val current = active
            val nowSync = android.os.SystemClock.elapsedRealtime()
            if (current != null && current.provider != "Wyzie Synced" && speechSegmentCount() >= 12 &&
                (lastSyncAttemptAt == 0L || nowSync - lastSyncAttemptAt > 90_000L)) {
                lastSyncAttemptAt = nowSync
                // This request intentionally runs outside the manager lock.
                val corrected = syncWyzie(imdb, season, episode, wyzieKey)
                if (corrected != null) {
                    val correctedCues = parseSrt(corrected)
                    if (correctedCues.isNotEmpty() &&
                        activeIdentity == identity &&
                        activateSubtitle(title, "Wyzie Synced", correctedCues, identity)) {
                        return "$title — اكتملت مزامنة الصوت وتصحيح FPS"
                    }
                }
            }
            return "$title — تم تحديث معايرة التوقيت تلقائياً"
        }

        // Wyzie Synced performs audio-to-subtitle alignment and FPS correction (Pro key required).
        // The local VAD supplies speech intervals; if unavailable, continue with ordinary subtitle search.
        val synced = syncWyzie(imdb, season, episode, wyzieKey)
        if (synced != null) {
            val cues = parseSrt(synced)
            if (cues.isNotEmpty() && activateSubtitle(title, "Wyzie Synced", cues, identity))
                return "$title — ترجمة Wyzie متزامنة مع تصحيح FPS"
        }

        val wyzie = searchWyzie(imdb, season, episode, wyzieKey)
        if (wyzie != null) {
            val cues = parseSrt(wyzie.first)
            if (cues.isNotEmpty() && activateSubtitle(title, "Wyzie", cues, identity))
                return "$title${if (season > 0 && episode > 0) " S${season}E$episode" else ""} — ترجمة Wyzie جاهزة"
        }

        val opensubtitlesKey = SubtitleKeyStore.get(context, PREF_OPENSUBTITLES_KEY).trim()
        if (opensubtitlesKey.isNotBlank()) {
            val openSubs = searchOpenSubtitles(imdb, season, episode, opensubtitlesKey)
            if (openSubs != null) {
                val cues = parseSrt(openSubs)
                if (cues.isNotEmpty() && activateSubtitle(title, "OpenSubtitles", cues, identity))
                    return "$title — ترجمة OpenSubtitles جاهزة"
            }
        }

        val subdlKey = SubtitleKeyStore.get(context, PREF_SUBDL_KEY).trim()
        if (subdlKey.isNotBlank()) {
            val subdl = searchSubDl(title, season, episode, best.optInt("year", 0), subdlKey)
            if (subdl != null) {
                val cues = parseSrt(subdl)
                if (cues.isNotEmpty() && activateSubtitle(title, "SubDL", cues, identity))
                    return "$title — ترجمة SubDL جاهزة"
            }
        }

        // SubtitleCat has no documented public API; use its public search/detail HTML pages.
        val subtitleCat = searchSubtitleCat(title, season, episode, best.optInt("year", 0))
        if (subtitleCat != null) {
            val cues = parseSrt(subtitleCat)
            if (cues.isNotEmpty() && activateSubtitle(title, "SubtitleCat", cues, identity))
                return "$title — ترجمة SubtitleCat جاهزة"
        }
        return "$title تم التعرف عليه، لكن لم يُعثر على ملف ترجمة عربية مناسب"
    }

    /** This path works without Wyzie /quote: manually selected IMDb or current XMLTV title. */
    fun loadSelectedTitle(selection: MediaSelection): String {
        if (!selection.isUsable()) return "انتهت صلاحية اختيار البرنامج في دليل القنوات"
        if (active?.provider == "Local SRT") return "الأولوية لملف SRT المحلي"
        val identity = selection.identity
        synchronized(this) {
            if (active != null && activeIdentity == identity) return "${selection.title} • الترجمة محمّلة"
            if (activeIdentity != identity) {
                active = null
                activeIdentity = ""
                candidateDialogue.clear() // Old speech must not reject a new programme's SRT.
                syncAnchors.clear()
                localAligner.reset()
                playerSubtitleAligner.reset()
                decoderSubtitleAligner.reset()
            }
        }
        val title = selection.title
        val epgHit = if (selection.source == "epg" && selection.imdbId.isBlank()) {
            // EPG programme titles may be news, ads or unrelated shows. Accept ONLY one exact title match.
            runCatching { TitleMatchRules.uniqueExactMatch(title, ImdbTitleLookup.search(title).map {
                TitleMatchRules.Hit(it.id, it.title, it.year)
            }) }.getOrNull()
        } else null
        val imdb = (selection.imdbId.takeIf { it.matches(Regex("tt\\d+")) } ?: epgHit?.id).orEmpty()
        val season = selection.season
        val episode = selection.episode
        val year = selection.year.takeIf { it > 0 } ?: epgHit?.year ?: 0
        val wyzie = SubtitleKeyStore.get(context, PREF_WYZIE_KEY).trim()
        // Audio/VAD Wyzie sync is optional and its endpoint is not proven by the reference APK.
        if (imdb.isNotBlank() && wyzie.isNotBlank()) {
            syncWyzie(imdb, season, episode, wyzie)?.let { body ->
                val cues = parseSrt(body)
                if (cues.size >= 2 && activateSubtitle(title, "Wyzie Synced", cues, identity))
                    return "$title • تم تحميل ملف Wyzie Synced؛ بانتظار التزامن مع الصوت"
            }
            searchWyzie(imdb, season, episode, wyzie)?.let { body ->
                val cues = parseSrt(body.first)
                if (cues.size >= 2 && activateSubtitle(title, "Wyzie", cues, identity))
                    return "$title • تم تحميل ملف Wyzie؛ بانتظار محاذاة الصوت"
            }
        }
        val openKey = SubtitleKeyStore.get(context, PREF_OPENSUBTITLES_KEY).trim()
        if (imdb.isNotBlank() && openKey.isNotBlank()) {
            searchOpenSubtitles(imdb, season, episode, openKey)?.let { raw ->
                val cues = parseSrt(raw)
                if (cues.size >= 2 && activateSubtitle(title, "OpenSubtitles", cues, identity))
                    return "$title • تم تحميل OpenSubtitles؛ بانتظار محاذاة الصوت"
            }
        }
        val subdlKey = SubtitleKeyStore.get(context, PREF_SUBDL_KEY).trim()
        if (subdlKey.isNotBlank()) {
            searchSubDl(title, season, episode, year, subdlKey)?.let { raw ->
                val cues = parseSrt(raw)
                if (cues.size >= 2 && activateSubtitle(title, "SubDL", cues, identity))
                    return "$title • تم تحميل SubDL؛ بانتظار محاذاة الصوت"
            }
        }
        val sourceKey = SubtitleKeyStore.get(context, PREF_SUBSOURCE_KEY).trim()
        if (sourceKey.isNotBlank()) {
            searchSubSource(title, imdb, season, episode, sourceKey)?.let { raw ->
                val cues = parseSrt(raw)
                if (cues.size >= 2 && activateSubtitle(title, "SubSource", cues, identity))
                    return "$title • تم تحميل SubSource؛ بانتظار محاذاة الصوت"
            }
        }
        searchSubtitleCat(title, season, episode, year)?.let { raw ->
            val cues = parseSrt(raw)
            if (cues.size >= 2 && activateSubtitle(title, "SubtitleCat", cues, identity))
                return "$title • تم تحميل SubtitleCat؛ بانتظار محاذاة الصوت"
        }
        return "$title • لم يتوفر ملف SRT عربي صالح لدى المزودين المُهيئين؛ ستستمر الترجمة الحية"
    }

    @Synchronized
    private fun activateSubtitle(title: String, provider: String, cues: List<Cue>, identity: String): Boolean {
        if (!candidateLooksUsable(cues, title, provider)) return false
        activeIdentity = identity
        active = ActiveSubtitle(title, provider, cues, currentScale(), currentOffset())
        // A fingerprint can identify the media before subtitle download completes; apply its anchor now.
        lastReferenceMatch?.takeIf { it.mediaId == identity }?.let { applyReferenceFingerprintMatch(it) }
        return true
    }

    fun cueAt(captureElapsedMs: Long): String? {
        val sub = active ?: return null
        if (!canRenderExternal(captureElapsedMs)) return null
        val pts = decoderPtsClock.latest()
        val decoderPtsFresh = pts != null && captureElapsedMs - pts.observedElapsedMs in -2_000L..5_000L
        val observedAt = playerPositionObservedElapsedMs
        val playerClockFresh = playerPositionMs >= 0L && observedAt > 0L && playerPositionState in setOf(2, 3, 6) &&
            (playerPositionState == 2 || playerPositionState == 6 || captureElapsedMs - observedAt <= 5_000L)
        val mediaMs = when {
            decoderPtsFresh -> {
                val videoMs = pts!!.positionMs(captureElapsedMs)
                if (decoderSubtitleAligner.anchorCount() > 0) {
                    (decoderSubtitleAligner.currentScale() * videoMs + decoderSubtitleAligner.currentOffsetMs()).toLong()
                } else videoMs.toLong()
            }
            playerClockFresh -> {
                val playerMs = playerPositionAt(captureElapsedMs) ?: playerPositionMs.toDouble()
                if (playerSubtitleAligner.anchorCount() > 0) {
                    (playerSubtitleAligner.currentScale() * playerMs + playerSubtitleAligner.currentOffsetMs()).toLong()
                } else playerMs.toLong()
            }
            else -> ((captureElapsedMs - sessionStartElapsedMs).coerceAtLeast(0L) * sub.scale +
                sub.mediaAtSessionStartMs).toLong()
        }
        val adjustedMediaMs = SubtitleTimingMath.effectiveMediaTime(
            mediaMs, SubtitleTimingStore.get(context)
        )
        return sub.cues.firstOrNull { adjustedMediaMs >= it.startMs && adjustedMediaMs <= it.endMs }?.text
    }

    private fun playerPositionAt(elapsedMs: Long): Double? {
        val observed = playerPositionObservedElapsedMs
        val state = playerPositionState
        if (playerPositionMs < 0L || observed <= 0L || state !in setOf(2, 3, 6)) return null
        val age = elapsedMs - observed
        if (state == 3 && age !in -2_000L..5_000L) return null
        val speed = if (state == 3) playerPositionSpeed else 0.0
        return playerPositionMs + age * speed
    }

    @Synchronized
    private fun addSyncAnchor(captureElapsedMs: Long, mediaTimeMs: Long) {
        if (sessionStartElapsedMs <= 0L) sessionStartElapsedMs = captureElapsedMs
        val x = (captureElapsedMs - sessionStartElapsedMs).coerceAtLeast(0L).toDouble()
        syncAnchors += x to mediaTimeMs.toDouble()
        while (syncAnchors.size > 10) syncAnchors.removeAt(0)
        localAligner.addReferenceAnchor(x, mediaTimeMs.toDouble())
        active?.let {
            it.scale = localAligner.currentScale()
            it.mediaAtSessionStartMs = localAligner.currentOffsetMs()
        }
    }

    @Synchronized private fun currentScale(): Double = if (localAligner.anchorCount() > 0) localAligner.currentScale() else active?.scale ?: run {
        val slopes = ArrayList<Double>()
        for (i in syncAnchors.indices) for (j in i + 1 until syncAnchors.size) {
            val dx = syncAnchors[j].first - syncAnchors[i].first
            val dy = syncAnchors[j].second - syncAnchors[i].second
            if (dx >= 30_000.0 && dy > 0.0 && dy / dx in 0.94..1.06) slopes += dy / dx
        }
        if (slopes.isEmpty()) 1.0 else slopes.sorted()[slopes.size / 2]
    }

    @Synchronized private fun currentOffset(): Double {
        if (localAligner.anchorCount() > 0) return localAligner.currentOffsetMs()
        val scale = currentScale()
        val offsets = syncAnchors.map { it.second - scale * it.first }.sorted()
        return if (offsets.isEmpty()) 0.0 else offsets[offsets.size / 2]
    }

    private fun speechSegmentCount(): Int = synchronized(speechLock) { speechSegments.size }

    private fun syncWyzie(imdb: String, season: Int, episode: Int, key: String): String? {
        if (key.isBlank()) return null
        val speech = JSONArray()
        val baseMediaMs = active?.mediaAtSessionStartMs ?: currentOffset()
        val playbackScale = active?.scale ?: currentScale()
        synchronized(speechLock) {
            // Need enough distinct speech regions for a meaningful audio alignment.
            if (speechSegments.size < 12) return null
            speechSegments.takeLast(2000).forEach { (start, end) ->
                val startSeconds = (baseMediaMs + start * playbackScale) / 1000.0
                val endSeconds = (baseMediaMs + end * playbackScale) / 1000.0
                if (endSeconds > startSeconds && startSeconds >= 0.0) {
                    speech.put(JSONArray().put(startSeconds).put(endSeconds))
                }
            }
        }
        if (speech.length() < 12) return null
        val payload = JSONObject()
            .put("key", key)
            .put("id", imdb)
            .put("language", "ar")
            .put("speech", speech)
        if (season > 0 && episode > 0) payload.put("season", season).put("episode", episode)
        val response = requestJsonWithHeaders(
            "https://sub.wyzie.io/sync",
            mapOf("Content-Type" to "application/json"),
            "POST",
            payload.toString()
        ) as? JSONObject ?: return null
        val confidence = response.optDouble("confidence", 0.0)
        val url = response.optString("url")
        if (url.startsWith("https://sub.wyzie.io/c/") && confidence >= 0.35) {
            return requestText(url)?.takeIf { it.contains("-->") }
        }
        Log.i(TAG, "Wyzie Synced skipped/low confidence: confidence=$confidence")
        return null
    }

    private fun searchWyzie(imdb: String, season: Int, episode: Int, key: String): Pair<String, String>? {
        val query = buildString {
            append("https://sub.wyzie.io/search?id=").append(enc(imdb))
            append("&language=ar&format=srt&key=").append(enc(key))
            if (season > 0 && episode > 0) append("&season=$season&episode=$episode")
        }
        val rows = requestJson(query) as? JSONArray ?: return null
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val url = row.optString("url")
            if (!url.startsWith("https://sub.wyzie.io/")) continue
            val body = requestText(url) ?: continue
            if (body.contains("-->")) return body to row.optString("source", "Wyzie")
        }
        return null
    }

    private fun searchOpenSubtitles(imdb: String, season: Int, episode: Int, apiKey: String): String? {
        val numericImdb = imdb.removePrefix("tt").toLongOrNull() ?: return null
        val query = buildString {
            append("https://api.opensubtitles.com/api/v1/subtitles?imdb_id=").append(numericImdb)
            append("&languages=ara")
            if (season > 0 && episode > 0) append("&season_number=$season&episode_number=$episode")
        }
        val root = requestJsonWithHeaders(query, mapOf("Api-Key" to apiKey, "User-Agent" to "IRAQ TRANSLATE v1.0.5")) as? JSONObject ?: return null
        val rows = root.optJSONArray("data") ?: return null
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val attrs = row.optJSONObject("attributes") ?: continue
            val files = attrs.optJSONArray("files") ?: continue
            if (files.length() == 0) continue
            val fileId = files.optJSONObject(0)?.optLong("file_id", -1L) ?: -1L
            if (fileId <= 0) continue
            val payload = JSONObject().put("file_id", fileId)
            val download = requestJsonWithHeaders(
                "https://api.opensubtitles.com/api/v1/download", 
                mapOf("Api-Key" to apiKey, "User-Agent" to "IRAQ TRANSLATE v1.0.5", "Content-Type" to "application/json"),
                "POST", payload.toString()
            ) as? JSONObject ?: continue
            val link = download.optString("link")
            if (!link.startsWith("https://")) continue
            val bytes = requestBytes(link) ?: continue
            val subtitleText = extractSubtitleText(bytes, "opensubtitles")
            if (subtitleText?.contains("-->") == true) return subtitleText
            val encoded = bytes.toString(Charsets.UTF_8)
            val decoded = runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT).toString(Charsets.UTF_8) }.getOrNull()
            if (decoded?.contains("-->") == true) return decoded
        }
        return null
    }

    private fun searchSubDl(title: String, season: Int, episode: Int, year: Int, key: String): String? {
        val url = buildString {
            append("https://api.subdl.com/api/v1/subtitles?api_key=").append(enc(key))
            append("&film_name=").append(enc(title)).append("&languages=AR&subs_per_page=20")
            if (season > 0 && episode > 0) append("&type=tv&season_number=$season&episode_number=$episode")
            else append("&type=movie")
            if (year > 0) append("&year=$year")
        }
        val root = requestJson(url) as? JSONObject ?: return null
        val rows = root.optJSONArray("subtitles") ?: return null
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (!row.optString("language", "AR").equals("AR", true) &&
                !row.optString("language", "").equals("Arabic", true)) continue
            val path = row.optString("url")
            if (path.isBlank()) continue
            val download = if (path.startsWith("http")) path else "https://dl.subdl.com$path"
            val bytes = requestBytes(download) ?: continue
            val text = extractSubtitleText(bytes, row.optString("name")) ?: continue
            if (text.contains("-->")) return text
        }
        return null
    }


    /** Official SubSource v1 routes, authenticated with the user's X-API-Key.
     * Response payload schemas must be verified with a real account before production use.
     */
    private fun searchSubSource(title: String, imdb: String, season: Int, episode: Int, key: String): String? {
        val headers = mapOf("X-API-Key" to key, "Accept" to "application/json")
        val searchUrl = "https://api.subsource.net/api/v1/movies/search?query=${enc(title)}"
        val moviesRoot = requestJsonWithHeaders(searchUrl, headers) as? JSONObject ?: return null
        val movies = moviesRoot.optJSONArray("data") ?: moviesRoot.optJSONArray("results") ?: return null
        var movieId: String? = null
        for (i in 0 until minOf(movies.length(), 20)) {
            val m = movies.optJSONObject(i) ?: continue
            val candidateImdb = m.optString("imdb_id", m.optString("imdbId"))
            val candidateTitle = m.optString("title", m.optString("name"))
            if ((imdb.isNotBlank() && candidateImdb == imdb) ||
                (candidateTitle.trim().equals(title.trim(), ignoreCase = true))) {
                movieId = m.optString("id").takeIf { it.isNotBlank() }
                break
            }
        }
        val id = movieId ?: return null
        val url = buildString {
            append("https://api.subsource.net/api/v1/subtitles?movie_id=").append(enc(id))
            append("&language=ar")
            if (season > 0 && episode > 0) append("&season=$season&episode=$episode")
        }
        val root = requestJsonWithHeaders(url, headers) as? JSONObject ?: return null
        val rows = root.optJSONArray("data") ?: root.optJSONArray("subtitles") ?: return null
        for (i in 0 until minOf(rows.length(), 10)) {
            val entry = rows.optJSONObject(i) ?: continue
            val subtitleId = entry.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,60}")) } ?: continue
            val download = "https://api.subsource.net/api/v1/subtitles/$subtitleId/download"
            val bytes = requestBytesWithHeaders(download, headers) ?: continue
            extractSubtitleText(bytes, "subsource")?.takeIf { it.contains("-->") }?.let { return it }
        }
        return null
    }

    private fun searchSubtitleCat(title: String, season: Int, episode: Int, year: Int): String? {
        val episodeTag = if (season > 0 && episode > 0) " S${season}E${episode}" else ""
        val query = (title + (if (year > 0) " $year" else "") + episodeTag).trim()
        val searchUrl = "https://www.subtitlecat.com/index.php?search=${enc(query)}"
        val html = requestSubtitleCatText(searchUrl) ?: return null
        val anchorRegex = Regex(
            """(?is)<a\b[^>]*href=[\"']([^\"']*subs/\d+/[^\"']+\.html)[\"'][^>]*>(.*?)</a>"""
        )
        val candidates = anchorRegex.findAll(html).take(12).toList()
        for (candidate in candidates) {
            val href = candidate.groupValues[1]
            val detailUrl = runCatching { URL(URL("https://www.subtitlecat.com/"), href).toString() }.getOrNull() ?: continue
            val parsed = runCatching { URL(detailUrl) }.getOrNull() ?: continue
            if (parsed.host != "www.subtitlecat.com" || !parsed.path.startsWith("/subs/")) continue
            val detail = requestSubtitleCatText(detailUrl) ?: continue
            val downloadTag = Regex(
                """(?is)<a\b(?=[^>]*\bid=[\"']download_ar[\"'])[^>]*\bhref=[\"']([^\"']+\.srt(?:\?[^\"']*)?)[\"][^>]*>"""
            ).find(detail) ?: continue
            val downloadPath = downloadTag.groupValues[1]
            val downloadUrl = runCatching { URL(URL("https://www.subtitlecat.com/"), downloadPath).toString() }.getOrNull() ?: continue
            val downloadUri = runCatching { URL(downloadUrl) }.getOrNull() ?: continue
            if (downloadUri.host != "www.subtitlecat.com" || !downloadUri.path.startsWith("/subs/") || !downloadUri.path.endsWith(".srt")) continue
            val srt = requestSubtitleCatText(downloadUrl)
            if (!srt.isNullOrBlank() && srt.contains("-->")) return srt
        }
        return null
    }

    private fun requestSubtitleCatText(url: String): String? {
        return try {
            val parsed = URL(url)
            if (parsed.protocol != "https" || parsed.host != "www.subtitlecat.com") return null
            val connection = parsed.openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "text/html,application/x-subrip,text/plain,*/*")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36")
            try {
                if (connection.responseCode !in 200..299) null
                else connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally { connection.disconnect() }
        } catch (t: Throwable) {
            Log.w(TAG, "SubtitleCat request failed", t)
            null
        }
    }

    private fun extractSubtitleText(bytes: ByteArray, name: String): String? {
        val raw = bytes.toString(Charsets.UTF_8)
        if (raw.contains("-->")) return raw
        // ZIP support: choose the first SRT/ASS/VTT member without extracting to shared storage.
        return runCatching {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase() in setOf("srt", "vtt")) {
                        return@use readBounded(zip, 6_000_000).toString(Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
                null
            }
        }.getOrNull()
    }

    private fun parseSrt(raw: String): List<Cue> {
        val normalized = raw.removePrefix("\uFEFF").replace("\r", "")
        val blocks = normalized.split(Regex("\\n\\s*\\n"))
        val result = ArrayList<Cue>()
        val timestamp = "(?:\\d{1,2}:)?\\d{2}:\\d{2}[,.]\\d{3}"
        val time = Regex("($timestamp)\\s*-->\\s*($timestamp)")
        for (block in blocks) {
            val lines = block.lines()
            val index = lines.indexOfFirst { time.containsMatchIn(it) }
            if (index < 0) continue
            val match = time.find(lines[index]) ?: continue
            val start = parseTime(match.groupValues[1])
            val end = parseTime(match.groupValues[2])
            val text = lines.drop(index + 1).joinToString(" ")
                .replace(Regex("<[^>]+>"), "").replace(Regex("\\{\\\\.*?}"), "")
                .trim()
            if (start >= 0 && end > start && text.isNotBlank()) result += Cue(start, end, text)
        }
        return result.sortedBy { it.startMs }
    }

    private fun parseTime(value: String): Long {
        val p = value.replace(',', '.').split(':', '.')
        return when (p.size) {
            4 -> (((p[0].toLong() * 60 + p[1].toLong()) * 60 + p[2].toLong()) * 1000) + p[3].toLong()
            3 -> ((p[0].toLong() * 60 + p[1].toLong()) * 1000) + p[2].toLong()
            else -> -1L
        }
    }

    private fun requestJsonWithHeaders(
        url: String,
        headers: Map<String, String>,
        method: String = "GET",
        body: String? = null
    ): Any? {
        val response = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 12_000
            c.readTimeout = 20_000
            c.requestMethod = method
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> c.setRequestProperty(key, value) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            try {
                if (c.responseCode !in 200..299) null
                else c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        } catch (t: Throwable) {
            Log.w(TAG, "OpenSubtitles API request failed", t)
            null
        } ?: return null
        return runCatching { JSONObject(response) }.getOrNull()
    }

    private fun requestJson(url: String): Any? {
        val body = requestText(url) ?: return null
        return runCatching { if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body) }.getOrNull()
    }

    private fun requestBytesWithHeaders(url: String, headers: Map<String, String>): ByteArray? {
        return try {
            val parsed = URL(url)
            if (parsed.protocol != "https" || parsed.host != "api.subsource.net") null else {
                val c = parsed.openConnection() as HttpURLConnection
                c.connectTimeout = 12_000
                c.readTimeout = 20_000
                headers.forEach { (name, value) -> c.setRequestProperty(name, value) }
                try {
                    if (c.responseCode !in 200..299) null
                    else c.inputStream.use { input -> readBounded(input, 6_000_000) }
                } finally { c.disconnect() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SubSource download failed", t)
            null
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            check(out.size() + n <= limit) { "Subtitle response exceeds $limit bytes" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun requestText(url: String): String? = requestBytes(url)?.toString(Charsets.UTF_8)

    private fun requestBytes(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        c.setRequestProperty("User-Agent", "IRAQ-TRANSLATE/1.1")
        try {
            if (c.responseCode !in 200..299) {
                Log.w(TAG, "Subtitle provider HTTP ${c.responseCode}: ${URL(url).host}")
                null
            } else c.inputStream.use { readBounded(it, 6_000_000) }
        } finally { c.disconnect() }
    } catch (t: Throwable) {
        Log.w(TAG, "Subtitle request failed", t)
        null
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
