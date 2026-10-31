package com.glassstorm.phonemanager.adapter.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.glassstorm.phonemanager.core.domain.adapter.gesture.GesturePort
import com.glassstorm.phonemanager.core.model.GestureMeaningTable
import com.glassstorm.phonemanager.core.model.GestureOutcome
import hk.edu.hkmu.sign.gesture.MediaPipeGestureRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "AiGesturePort"

/** One decode-and-recognize pass for a JPEG still. */
internal interface GestureEngine {
    fun recognize(jpeg: ByteArray): GestureOutcome?

    fun close()
}

/**
 * The DEVICE implementation of [GesturePort], backed by ai-android's
 * `MediaPipeGestureRecognizer`.
 *
 * It is the only place a JPEG is decoded: the relay's `FrameSink` contract forbids
 * decoding, so the still image is decoded here into an `android.graphics.Bitmap` and
 * fed to the per-image recognizer. The engine's canned label is then translated to a
 * spoken meaning through the hub-owned [GestureMeaningTable]; an unmapped label has no
 * meaning and yields `null` rather than inventing one.
 *
 * The engine is ARM64-only at runtime; it is constructed lazily by `create` (no native
 * load until the first `recognize`), so merely building the graph is safe.
 *
 * A single MediaPipe handle is shared by every session pump, and the native handle is
 * not safe for concurrent calls, so [recognize] serialises access with a [Mutex].
 *
 * @param context a `Context` the MediaPipe backend needs (application context is enough).
 * @param modelDirectory the directory holding `gesture_recognizer.task`.
 * @param meanings the configurable canned-label -> spoken-meaning table.
 */
class AiGesturePort internal constructor(
    private val engine: GestureEngine,
) : GesturePort,
    AutoCloseable {
    constructor(
        context: Context,
        modelDirectory: File,
        meanings: GestureMeaningTable = GestureMeaningTable.standard,
    ) : this(MediaPipeGestureEngine(context = context, modelDirectory = modelDirectory, meanings = meanings))

    private val engineMutex = Mutex()

    override suspend fun recognize(jpeg: ByteArray): GestureOutcome? =
        withContext(Dispatchers.Default) {
            try {
                if (jpeg.isEmpty()) return@withContext null
                engineMutex.withLock { engine.recognize(jpeg) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Bitmap decode OOM, a native MediaPipe abort, a malformed JPEG: the
                // still is simply unrecognized, never a process-killing throw.
                Log.w(TAG, "gesture recognition failed; dropping the still", failure)
                null
            }
        }

    /** Releases the MediaPipe native handle. Idempotent. */
    override fun close() {
        try {
            engine.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "releasing the gesture recognizer failed", failure)
        }
    }
}

private class MediaPipeGestureEngine(
    context: Context,
    modelDirectory: File,
    private val meanings: GestureMeaningTable,
) : GestureEngine {
    private val recognizer = MediaPipeGestureRecognizer.create(context = context, modelDir = modelDirectory)

    override fun recognize(jpeg: ByteArray): GestureOutcome? {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        try {
            val prediction = recognizer.recognize(bitmap)
            Log.d(TAG, "recognize: label=${prediction?.label} score=${prediction?.score} hands=${prediction?.handCount}")
            prediction ?: return null
            val text = meanings.meaningFor(prediction.label) ?: return null
            return GestureOutcome(label = prediction.label, text = text, confidence = prediction.score)
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() = recognizer.close()
}
