package com.glassstorm.phonemanager.adapter.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.glassstorm.phonemanager.core.domain.adapter.gesture.GesturePort
import com.glassstorm.phonemanager.core.model.GestureMeaningTable
import com.glassstorm.phonemanager.core.model.GestureOutcome
import hk.edu.hkmu.core.license.LicenseKind
import hk.edu.hkmu.core.license.ModelLicense
import hk.edu.hkmu.sign.landmark.Keypoint3D
import hk.edu.hkmu.sign.shuwa.ShuwaFrame
import hk.edu.hkmu.sign.shuwa.ShuwaKnnIndex
import hk.edu.hkmu.sign.shuwa.ShuwaLandmarker
import hk.edu.hkmu.sign.shuwa.ShuwaPosePoint
import hk.edu.hkmu.sign.shuwa.ShuwaPrediction
import hk.edu.hkmu.sign.shuwa.ShuwaPreprocessor
import hk.edu.hkmu.sign.shuwa.ShuwaSpec
import hk.edu.hkmu.sign.shuwa.TfliteShuwaEmbedder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

private const val TAG = "AiShuwaGesturePort"

/**
 * [GesturePort] backed by ai-android's **sequence** recogniser (MediaPipe Holistic + Shuwa translator +
 * HKSL KNN table).
 *
 * ### Why this is stateful, and why it segments
 * The port contract takes one JPEG per call, but the model was trained on **one sign per clip**,
 * resampled to 16 frames. A fixed sliding window of 16 stills is a different input: it cuts signs in
 * half, pads fast signs with idle, and truncates slow ones. Measured against the reference data, a
 * fixed 10 fps window landed 1.7-2.1 from every HKSL class, while a sign-length segment at 30 fps
 * landed at 1.40 — under the accept ceiling.
 *
 * So the port performs the demo's record/stop automatically:
 *
 * ```
 * motion rises ──► record frames ──► motion quiet for [END_QUIET_MS] ──► close burst
 *                                                                          │
 *                                            resample the burst to 16 ─────┘
 * ```
 *
 * The burst is prepared once with [ShuwaPreprocessor.prepare] (which resamples it to 16 frames,
 * making the model invariant to the signer's speed), embedded with [TfliteShuwaEmbedder], and
 * matched against [ShuwaKnnIndex].
 *
 * ### Rejection
 * The KNN is closed-set: it always returns a nearest class. The shipped table therefore includes the
 * reference `Idle` class as a **sink**: [GestureMeaningTable.shuwa] has no entry for it, so an idle
 * or non-HKSL pose produces no subtitle instead of being forced onto an HKSL gloss. A distance
 * ceiling and the vote-confidence floor still guard the remaining matches.
 *
 * ### Threading and failure containment
 * One call runs one landmark extraction plus, at a burst close, one embedding pass, both on
 * [Dispatchers.Default]. A single recogniser instance is shared by every session pump and the native
 * handles are not safe for concurrent calls, so [recognize] serialises access with a [Mutex]. An
 * unusable still returns `null`; a genuine engine failure is logged and also returns `null`.
 *
 * ### Laziness and licensing
 * Nothing native is loaded at construction. The landmarker, translator and table are built on the first
 * call, and the translator's licence is evaluated before its interpreter exists. The artefacts are
 * host-provisioned under [modelDir]: `holistic_landmarker.task`, `shuwa-translator.tflite` and
 * `shuwa-knn-hksl.bin` (see `docs/ai-model-provisioning.md`).
 *
 * @param context Android context for MediaPipe.
 * @param modelDir directory holding the three provisioned artefacts.
 * @param meanings gloss -> spoken meaning table; an unmapped gloss yields no outcome rather than an
 *   invented meaning.
 * @param license licence attached to the host-provisioned translator.
 * @param clock capture clock, injectable for deterministic tests.
 */
class AiShuwaGesturePort(
    private val context: Context,
    private val modelDir: File,
    private val meanings: GestureMeaningTable = GestureMeaningTable.shuwa,
    private val license: ModelLicense = ModelLicense(LicenseKind.UserProvided),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : GesturePort,
    AutoCloseable {
    /** Frames of the burst currently being recorded. */
    private val burst = ArrayDeque<ShuwaFrame>(MAX_BURST_FRAMES)

    /** Previous frame, for the motion signal that opens and closes a burst. */
    private var previous: ShuwaFrame? = null

    @Volatile
    private var active = false
    private var lastMotionAtMs = 0L
    private var burstStartedAtMs = 0L

    override val retainsSequence: Boolean
        get() = active

    /** Duplicate suppression: a held sign closes several bursts in a row. */
    private val policy = ShuwaWindowPolicy()

    private val components: Lazy<Components> =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Components.open(context, modelDir, license) }

    private val engineMutex = Mutex()

    override suspend fun recognize(jpeg: ByteArray): GestureOutcome? =
        withContext(Dispatchers.Default) {
            try {
                if (jpeg.isEmpty()) return@withContext null
                engineMutex.withLock { recognizeLocked(jpeg) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Bitmap decode OOM, a native abort, a malformed JPEG: the still is simply
                // unrecognized, never a process-killing throw.
                Log.w(TAG, "Shuwa recognition failed for one still; dropping it", failure)
                null
            }
        }

    /**
     * Loads the native landmarker, translator and KNN table, then primes the landmark graph.
     *
     * The image pump calls this before reading the session queue. Without it the first
     * [recognize] pays the whole cold-start cost while the queue drops stills, so every sign
     * made during the load is lost.
     */
    override suspend fun warmUp() {
        withContext(Dispatchers.Default) {
            try {
                engineMutex.withLock {
                    val started = clock()
                    val loaded = components.value
                    val bitmap = Bitmap.createBitmap(WARMUP_SIZE, WARMUP_SIZE, Bitmap.Config.ARGB_8888)
                    try {
                        loaded.landmarker.detect(bitmap, clock())
                    } finally {
                        bitmap.recycle()
                    }
                    Log.d(TAG, "warm-up took ${clock() - started}ms")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "warm-up failed; the first still will retry", failure)
            }
        }
    }

    private suspend fun recognizeLocked(jpeg: ByteArray): GestureOutcome? {
        val frame = extract(jpeg) ?: return null
        val previousFrame = previous
        val motion = previousFrame?.let { meanMotion(it, frame) } ?: 0f
        previous = frame
        val now = frame.timestampMillis

        val hasHand =
            frame.leftHand.size >= MIN_HAND_POINTS || frame.rightHand.size >= MIN_HAND_POINTS
        if (hasHand && motion > START_MOTION) {
            if (!active) {
                active = true
                burstStartedAtMs = now
                burst.clear()
            }
            lastMotionAtMs = now
        }
        if (!active) return null

        burst.addLast(frame)
        while (burst.size > MAX_BURST_FRAMES) {
            burst.removeFirst()
        }

        val quietFor = now - lastMotionAtMs
        val tooLong = now - burstStartedAtMs >= MAX_BURST_MS
        if (quietFor < END_QUIET_MS && !tooLong) return null

        active = false
        val frames = burst.toList().takeWhile { it.timestampMillis <= lastMotionAtMs }
        burst.clear()
        return classifyBurst(frames)
    }

    /** Decodes the still, applies the reference square crop, and extracts the Shuwa frame. */
    private fun extract(jpeg: ByteArray): ShuwaFrame? {
        val decoded: Bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        // The reference pipeline runs Holistic on a SQUARE image (`crop_utils.crop_square`), because
        // MediaPipe normalises x and y independently. A 640x480 still without the crop stretches the
        // pose horizontally relative to every training sample.
        val bitmap = decoded.centerSquare()
        if (bitmap !== decoded) decoded.recycle()
        return try {
            components.value.landmarker.detect(bitmap, clock())
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Resamples one completed burst to 16 frames and classifies it.
     *
     * The burst is the sign, whatever its duration: a 0.6 s and a 2.0 s performance both become 16
     * uniformly spaced frames, so the signer's speed does not matter.
     */
    private suspend fun classifyBurst(frames: List<ShuwaFrame>): GestureOutcome? {
        if (frames.size < MIN_BURST_FRAMES) return null
        // A sign needs a hand. MediaPipe reports an EMPTY hand list when it finds none, and the
        // preprocessor zero-fills that block; without this gate a head turn or a shoulder shift can
        // be classified from pose/face alone. Require at least one hand (>= MIN_HAND_POINTS
        // landmarks) in at least HALF of the burst's frames; the gate tolerates the hand detector
        // dropping a frame during fast motion and rejects pose-only fragments.
        val leftFrames = frames.count { it.leftHand.size >= MIN_HAND_POINTS }
        val rightFrames = frames.count { it.rightHand.size >= MIN_HAND_POINTS }
        val handFrames =
            frames.count {
                it.leftHand.size >= MIN_HAND_POINTS || it.rightHand.size >= MIN_HAND_POINTS
            }
        Log.d(TAG, "burst frames=${frames.size} hands left=$leftFrames right=$rightFrames any=$handFrames")
        if (handFrames * 2 < frames.size) return null

        // Alignment search: the burst boundaries are motion-derived, so the same sign can land a
        // little long, short or shifted on each attempt. Try the full burst first, then trimmed
        // variants, and stop at the first one that clears the gates. This is what turns "sign it
        // 2-3 times" into one attempt, without emitting anything the gates would have rejected.
        for (candidate in candidateWindows(frames)) {
            val candidateStarted = clock()
            val prediction = classifyWindow(candidate)
            Log.d(
                TAG,
                "candidate frames=${candidate.size} label=${prediction.label} " +
                    "conf=${prediction.confidence} dist=${prediction.distance} " +
                    "low=${prediction.lowConfidence} took=${clock() - candidateStarted}ms",
            )
            if (prediction.lowConfidence || prediction.distance > MAX_DISTANCE) continue
            val text = meanings.meaningFor(prediction.label) ?: continue
            if (!policy.accept(prediction.label, prediction.windowEndMillis)) return null
            return GestureOutcome(label = prediction.label, text = text, confidence = prediction.confidence)
        }
        return null
    }

    /**
     * Prepares and classifies one candidate burst.
     *
     * Calling [ShuwaPreprocessor.prepare] directly on [frames] runs `sampleIndices` **once** (matching
     * the reference `translator_manager.get_feats`), whereas pre-resampling to 16 frames and passing
     * the 16-frame window through `ShuwaRecognizer` ran `sampleIndices(16, 16)` a second time,
     * dropping the first/last frames and duplicating slots 4 and 11.
     */
    private fun classifyWindow(frames: List<ShuwaFrame>): ShuwaPrediction {
        val loaded = components.value
        val sanitized = frames.map(::sanitizeForPreprocessor)
        val inputs = ShuwaPreprocessor.prepare(sanitized)
        val embedding = applyPartWeights(loaded.embedder.embed(inputs))
        val match = loaded.index.match(embedding)
        return ShuwaPrediction(
            label = match.label,
            confidence = match.confidence,
            distance = match.distance,
            windowStartMillis = frames.first().timestampMillis,
            windowEndMillis = frames.last().timestampMillis,
            lowConfidence = match.confidence < MIN_CONFIDENCE,
        )
    }

    /**
     * Scales the 4 concatenated embedding parts (`pose [0:96]`, `face [96:144]`, `lh [144:240]`,
     * `rh [240:336]`) to match the pruned KNN table: person-specific face features are down-weighted
     * and the dominant right hand is emphasised so `welcome`/`write` and `how_are_you`/`soft_drink`
     * separate cleanly.
     */
    private fun applyPartWeights(embedding: FloatArray): FloatArray {
        for (index in FACE_START until FACE_END) {
            embedding[index] *= FACE_WEIGHT
        }
        for (index in RIGHT_HAND_START until ShuwaSpec.EMBEDDING_DIMENSIONS) {
            embedding[index] *= RIGHT_HAND_WEIGHT
        }
        return embedding
    }

    /**
     * Masks absent hands on the raw BlazePose hand indices and keeps a detected hand even when the
     * coarse pose visibility dips.
     *
     * The translator's preprocessor masks a hand when BlazePose's wrist/finger visibility is low, but
     * MediaPipe's 21-point hand landmarker can still have detected it. For a present hand, force the
     * four pose points visible so the hand block survives; for an absent hand, write the sentinel so
     * the shoulder/elbow scale is untouched.
     */
    internal fun sanitizeForPreprocessor(frame: ShuwaFrame): ShuwaFrame {
        if (frame.pose.size < ShuwaSpec.POSE_POINTS) return frame
        val missingLeftHand =
            frame.leftHand.size < MIN_HAND_POINTS ||
                frame.leftHand.first().x == ShuwaSpec.MISSING_VALUE
        val missingRightHand =
            frame.rightHand.size < MIN_HAND_POINTS ||
                frame.rightHand.first().x == ShuwaSpec.MISSING_VALUE
        val pose =
            frame.pose.mapIndexed { index, point ->
                when {
                    missingLeftHand && index in REAL_LEFT_HAND_IN_POSE -> IGNORED_POSE_POINT
                    missingRightHand && index in REAL_RIGHT_HAND_IN_POSE -> IGNORED_POSE_POINT
                    !missingLeftHand && index in REAL_LEFT_HAND_IN_POSE -> point.copy(visibility = 1f)
                    !missingRightHand && index in REAL_RIGHT_HAND_IN_POSE -> point.copy(visibility = 1f)
                    else -> point
                }
            }
        return ShuwaFrame(
            pose = pose,
            face = frame.face,
            leftHand = if (missingLeftHand) IGNORED_HAND else frame.leftHand,
            rightHand = if (missingRightHand) IGNORED_HAND else frame.rightHand,
            timestampMillis = frame.timestampMillis,
        )
    }

    /**
     * Burst variants ordered by preference: the full burst, then gentle trims that remove a long
     * approach or a trailing retreat.
     */
    private fun candidateWindows(frames: List<ShuwaFrame>): List<List<ShuwaFrame>> {
        val candidates = mutableListOf(frames)
        val n = frames.size
        val trim = maxOf(2, n / 7)
        if (n - trim >= MIN_BURST_FRAMES) {
            candidates += frames.subList(trim, n)
            candidates += frames.subList(0, n - trim)
            if (n - 2 * trim >= MIN_BURST_FRAMES) {
                candidates += frames.subList(trim, n - trim)
            }
        }
        return candidates.distinctBy {
            it.size to it.first().timestampMillis
        }
    }

    /**
     * Burst start/stop signal: the strongest per-point displacement across body pose and both
     * hands. Measuring pose alone missed hand/wrist signs when the torso stayed still, splitting one
     * sign into several short fragments.
     */
    private fun meanMotion(
        previous: ShuwaFrame,
        current: ShuwaFrame,
    ): Float =
        maxOf(
            poseMotion(previous, current),
            handMotion(previous.leftHand, current.leftHand),
            handMotion(previous.rightHand, current.rightHand),
        )

    private fun poseMotion(
        previous: ShuwaFrame,
        current: ShuwaFrame,
    ): Float {
        val count = minOf(previous.pose.size, current.pose.size)
        if (count == 0) return 0f
        var total = 0.0
        for (index in 0 until count) {
            val dx = (current.pose[index].x - previous.pose[index].x).toDouble()
            val dy = (current.pose[index].y - previous.pose[index].y).toDouble()
            total += sqrt(dx * dx + dy * dy)
        }
        return (total / count).toFloat()
    }

    private fun handMotion(
        previous: List<Keypoint3D>,
        current: List<Keypoint3D>,
    ): Float {
        val count = minOf(previous.size, current.size)
        if (count < MIN_HAND_POINTS) return 0f
        var total = 0.0
        for (index in 0 until count) {
            val dx = (current[index].x - previous[index].x).toDouble()
            val dy = (current[index].y - previous[index].y).toDouble()
            total += sqrt(dx * dx + dy * dy)
        }
        return (total / count).toFloat()
    }

    /**
     * The square crop the reference pipeline applies before landmark extraction.
     *
     * Landscape frames are centre-cropped horizontally; portrait frames keep the bottom square
     * (`start_y = height - width`), matching `crop_utils.crop_square` exactly.
     */
    private fun Bitmap.centerSquare(): Bitmap {
        if (width == height) return this
        return if (width > height) {
            Bitmap.createBitmap(this, (width - height) / 2, 0, height, height)
        } else {
            Bitmap.createBitmap(this, 0, height - width, width, width)
        }
    }

    /** Releases the native landmarker and translator. Idempotent. */
    override fun close() {
        if (components.isInitialized()) {
            components.value.close()
        }
    }

    /** The three native pieces, created together on the first still. */
    private class Components(
        val landmarker: ShuwaLandmarker,
        val embedder: TfliteShuwaEmbedder,
        val index: ShuwaKnnIndex,
    ) : AutoCloseable {
        override fun close() {
            embedder.close()
            landmarker.close()
        }

        companion object {
            fun open(
                context: Context,
                modelDir: File,
                license: ModelLicense,
            ): Components {
                val landmarker = ShuwaLandmarker.create(context = context, modelDir = modelDir)
                val embedder =
                    TfliteShuwaEmbedder.load(
                        modelFile = File(modelDir, TRANSLATOR_FILE),
                        license = license,
                    )
                val index = ShuwaKnnIndex.load(File(modelDir, KNN_FILE))
                return Components(landmarker = landmarker, embedder = embedder, index = index)
            }
        }
    }

    private companion object {
        /** Provisioned translator file name inside `modelDir`. */
        const val TRANSLATOR_FILE = "shuwa-translator.tflite"

        /** Provisioned KNN table file name inside `modelDir`. */
        const val KNN_FILE = "shuwa-knn-hksl.bin"

        /** Mean pose or hand displacement that opens a burst. */
        const val START_MOTION = 0.008f

        /**
         * Silence after the last motion that closes a burst.
         *
         * 180 ms split a real sign into fragments (Idle/chef/high-heels); 300 ms gives the segmenter a
         * real boundary and costs only 120 ms more.
         */
        const val END_QUIET_MS = 300L

        /** Embedding slice bounds (`pose=96, face=48, lh=96, rh=96`) and part weights. */
        const val FACE_START = 96
        const val FACE_END = 144
        const val RIGHT_HAND_START = 240
        const val FACE_WEIGHT = 0.2f
        const val RIGHT_HAND_WEIGHT = 1.4f

        /** Hard cap on one burst so a continuously moving scene cannot grow without bound. */
        const val MAX_BURST_MS = 2_500L

        /** Shortest burst worth classifying (about 0.4 s at 10-15 fps). */
        const val MIN_BURST_FRAMES = 6

        /** Hand landmarks MediaPipe returns per detected hand; fewer means the hand is absent. */
        const val MIN_HAND_POINTS = 10

        /** Ring-buffer cap: 2.5 s at up to 38 fps. */
        const val MAX_BURST_FRAMES = 96

        /** Square edge of the synthetic bitmap used to prime the landmark graph. */
        const val WARMUP_SIZE = 64

        /** BlazePose indices corresponding to `SELECTED_POSE[7, 9, 11, 13]` (left hand in pose). */
        val REAL_LEFT_HAND_IN_POSE = intArrayOf(15, 17, 19, 21)

        /** BlazePose indices corresponding to `SELECTED_POSE[8, 10, 12, 14]` (right hand in pose). */
        val REAL_RIGHT_HAND_IN_POSE = intArrayOf(16, 18, 20, 22)

        val IGNORED_POSE_POINT =
            ShuwaPosePoint(
                x = ShuwaSpec.IGNORE_VALUE,
                y = ShuwaSpec.IGNORE_VALUE,
                z = ShuwaSpec.IGNORE_VALUE,
                visibility = 1f,
            )

        val IGNORED_HAND: List<Keypoint3D> =
            List(ShuwaSpec.HAND_POINTS) {
                Keypoint3D(
                    x = ShuwaSpec.IGNORE_VALUE,
                    y = ShuwaSpec.IGNORE_VALUE,
                    z = ShuwaSpec.IGNORE_VALUE,
                )
            }

        /**
         * Vote floor: the winning gloss must own at least this share of the 5 nearest neighbours
         * (at least 4 of the 5 nearest neighbours agreeing is ~0.79).
         */
        const val MIN_CONFIDENCE = 0.78f

        /**
         * Loose sanity ceiling on the absolute nearest-neighbour distance.
         *
         * The vote, not distance, is the primary criterion. The weighted table's in-distribution
         * 1-NN distance has p99 1.64 and a maximum of 2.15, while valid live matches have measured
         * 2.27; 3.0 still rejects a wildly out-of-domain embedding without dropping a real sign.
         */
        const val MAX_DISTANCE = 3.0f
    }
}
