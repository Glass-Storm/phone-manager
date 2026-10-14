package com.glassstorm.phonemanager.adapter.ai

import android.content.Context
import android.graphics.BitmapFactory
import com.glassstorm.phonemanager.core.domain.adapter.gesture.GesturePort
import com.glassstorm.phonemanager.core.model.GestureMeaningTable
import com.glassstorm.phonemanager.core.model.GestureOutcome
import hk.edu.hkmu.sign.gesture.MediaPipeGestureRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 * @param context a `Context` the MediaPipe backend needs (application context is enough).
 * @param modelDirectory the directory holding `gesture_recognizer.task`.
 * @param meanings the configurable canned-label -> spoken-meaning table.
 */
class AiGesturePort(
    context: Context,
    modelDirectory: File,
    private val meanings: GestureMeaningTable = GestureMeaningTable.standard,
) : GesturePort,
    AutoCloseable {
    private val recognizer = MediaPipeGestureRecognizer.create(context = context, modelDir = modelDirectory)

    override suspend fun recognize(jpeg: ByteArray): GestureOutcome? =
        withContext(Dispatchers.Default) {
            if (jpeg.isEmpty()) return@withContext null
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@withContext null
            try {
                val prediction = recognizer.recognize(bitmap) ?: return@withContext null
                val text = meanings.meaningFor(prediction.label) ?: return@withContext null
                GestureOutcome(label = prediction.label, text = text, confidence = prediction.score)
            } finally {
                bitmap.recycle()
            }
        }

    /** Releases the MediaPipe native handle. Idempotent. */
    override fun close() = recognizer.close()
}
