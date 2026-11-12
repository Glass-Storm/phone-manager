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
import hk.edu.hkmu.sign.shuwa.ShuwaFrame
import hk.edu.hkmu.sign.shuwa.ShuwaKnnIndex
import hk.edu.hkmu.sign.shuwa.ShuwaLandmarker
import hk.edu.hkmu.sign.shuwa.ShuwaPrediction
import hk.edu.hkmu.sign.shuwa.ShuwaRecognizer
import hk.edu.hkmu.sign.shuwa.TfliteShuwaEmbedder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "AiShuwaGesturePort"

/**
 * [GesturePort] backed by ai-android's **sequence** recogniser (MediaPipe Holistic + Shuwa translator +
 * HKSL KNN table).
 *
 * ### Why this is stateful
 * The port contract takes one JPEG still per call, but the model needs a **window** of stills. The port
 * therefore keeps the last [ShuwaWindowPolicy.windowFrames] extracted frames and reports a gloss only
 * when [ShuwaWindowPolicy.onFrame] says a window is complete and [ShuwaWindowPolicy.accept] says the
 * result is new. Everything above it (relay, transport, contract) stays unchanged: the hub configures a
 * deeper image queue (`RelayTuning.sequence`) so the intermediate stills reach this port instead of
 * being conflated away.
 *
 * The window policy lives here, not in ai-android, only because the port is a pull API (one still per
 * call) while the engine exposes a push API (a `Flow` of frames). Each completed window is handed to
 * [ShuwaRecognizer] as a one-window flow, so the embedding, the KNN lookup, the motion gate and the
 * confidence flag all remain the engine's.
 *
 * ### Threading and failure containment
 * One call runs one landmark extraction plus, at a window boundary, one embedding pass, both on
 * [Dispatchers.Default]. A single recogniser instance is shared by every session pump and the native
 * handles are not safe for concurrent calls, so [recognize] serialises access with a [Mutex]. An
 * unusable still returns `null`; a genuine engine failure is logged and also returns `null`, so one
 * bad frame never kills a pump.
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
 * @param policy windowing and duplicate-suppression rules.
 * @param license licence attached to the host-provisioned translator.
 * @param clock capture clock, injectable for deterministic tests.
 */
class AiShuwaGesturePort(
    private val context: Context,
    private val modelDir: File,
    private val meanings: GestureMeaningTable = GestureMeaningTable.shuwa,
    private val policy: ShuwaWindowPolicy = ShuwaWindowPolicy(),
    private val license: ModelLicense = ModelLicense(LicenseKind.UserProvided),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : GesturePort,
    AutoCloseable {
    private val window = ArrayDeque<ShuwaFrame>(policy.windowFrames)

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

    private suspend fun recognizeLocked(jpeg: ByteArray): GestureOutcome? {
        val bitmap: Bitmap =
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val frame =
            try {
                components.value.landmarker.detect(bitmap, clock())
            } finally {
                // The landmarker copies what it needs out of the bitmap; the relay's still is
                // transient, so release it immediately instead of waiting for the collector.
                bitmap.recycle()
            }
        window.addLast(frame)
        if (window.size > policy.windowFrames) {
            window.removeFirst()
        }
        if (!policy.onFrame()) {
            return null
        }
        val prediction = classifyWindow(window.toList()) ?: return null
        if (prediction.lowConfidence) return null
        if (!policy.accept(prediction.label, prediction.windowEndMillis)) return null
        val text = meanings.meaningFor(prediction.label) ?: return null
        return GestureOutcome(label = prediction.label, text = text, confidence = prediction.confidence)
    }

    /**
     * Runs the engine over exactly one window.
     *
     * `ShuwaRecognizer.recognize` consumes a flow and emits at most one prediction for a full window,
     * so a one-window flow yields the prediction for the stills the port just completed (or nothing
     * when the motion gate considers the window idle).
     */
    private suspend fun classifyWindow(frames: List<ShuwaFrame>): ShuwaPrediction? {
        val recognizer = components.value.recognizer
        return recognizer.recognize(frames.asFlow()).firstOrNull()
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
        val recognizer: ShuwaRecognizer,
    ) : AutoCloseable {
        override fun close() {
            recognizer.close()
            landmarker.close()
        }

        companion object {
            fun open(
                context: Context,
                modelDir: File,
                license: ModelLicense,
            ): Components {
                val landmarker = ShuwaLandmarker.create(context = context, modelDir = modelDir)
                val recognizer =
                    ShuwaRecognizer(
                        embedder =
                            TfliteShuwaEmbedder.load(
                                modelFile = File(modelDir, TRANSLATOR_FILE),
                                license = license,
                            ),
                        index = ShuwaKnnIndex.load(File(modelDir, KNN_FILE)),
                    )
                return Components(landmarker = landmarker, recognizer = recognizer)
            }
        }
    }

    private companion object {
        /** Provisioned translator file name inside `modelDir`. */
        const val TRANSLATOR_FILE = "shuwa-translator.tflite"

        /** Provisioned HKSL KNN table file name inside `modelDir`. */
        const val KNN_FILE = "shuwa-knn-hksl.bin"
    }
}
