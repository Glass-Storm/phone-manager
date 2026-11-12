package com.glassstorm.phonemanager.di

import android.content.Context
import android.os.Build
import com.glassstorm.phonemanager.adapter.ai.AiEnrollmentStore
import com.glassstorm.phonemanager.adapter.ai.AiGesturePort
import com.glassstorm.phonemanager.adapter.ai.AiShuwaGesturePort
import com.glassstorm.phonemanager.adapter.ai.AiSpeechPort
import com.glassstorm.phonemanager.adapter.ai.AiTtsPort
import com.glassstorm.phonemanager.adapter.android.di.ApplicationContext
import com.glassstorm.phonemanager.adapter.jvm.ai.FakeEnrollmentStore
import com.glassstorm.phonemanager.adapter.jvm.ai.FakeGesturePort
import com.glassstorm.phonemanager.adapter.jvm.ai.FakeSpeechPort
import com.glassstorm.phonemanager.adapter.jvm.ai.FakeTtsPort
import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.domain.adapter.gesture.GesturePort
import com.glassstorm.phonemanager.core.domain.adapter.speech.SpeechPort
import com.glassstorm.phonemanager.core.domain.adapter.speech.TtsPort
import com.glassstorm.phonemanager.core.model.RelayTuning
import dagger.Module
import dagger.Provides
import hk.edu.hkmu.core.config.Language
import hk.edu.hkmu.core.config.TtsConfig
import java.io.File
import javax.inject.Singleton

private const val ARM64_ABI = "arm64-v8a"
private const val MODELS_DIRECTORY = "models"
private const val ASR_DIRECTORY = "asr"
private const val ASR_MODEL_PREFIX = "model"
private const val ASR_MODEL_SUFFIX = ".onnx"
private const val ASR_TOKENS_FILE = "tokens.txt"
private const val DIARIZATION_DIRECTORY = "diarization"
private const val DIARIZATION_SEGMENTATION_DIRECTORY = "sherpa-onnx-pyannote-segmentation-3-0"
private const val DIARIZATION_SEGMENTATION_FILE = "model.int8.onnx"
private const val DIARIZATION_EMBEDDING_FILE = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
private const val GESTURE_DIRECTORY = "gesture"
private const val GESTURE_MODEL_FILE = "gesture_recognizer.task"
private const val TTS_DIRECTORY = "tts"
private const val TTS_VOICE_DIRECTORY = "vits-cantonese-hf-xiaomaiiwn"
private const val TTS_MODEL_FILE = "vits-cantonese-hf-xiaomaiiwn.onnx"
private const val TTS_TOKENS_FILE = "tokens.txt"
private const val TTS_LEXICON_FILE = "lexicon.txt"
private const val TTS_RULE_FILE = "rule.fst"
private const val ENROLLMENT_FILE = "enrollment.txt"

// --- Shuwa HKSL sequence recogniser (host-provisioned under models/shuwa) -----------------------
private const val SHUWA_DIRECTORY = "shuwa"
private const val SHUWA_TASK_FILE = "holistic_landmarker.task"
private const val SHUWA_TRANSLATOR_FILE = "shuwa-translator.tflite"
private const val SHUWA_KNN_FILE = "shuwa-knn-hksl.bin"

/**
 * Binds EACH ai port to its REAL ai-android engine only when the device can run
 * that engine AND every model file that engine needs is provisioned; otherwise it
 * binds the deterministic `:adapter:jvm` fake.
 *
 * The decision is per engine, never one marker for all four: an ASR-only install
 * must still get the real recognizer even while gesture, diarization and TTS fall
 * back to fakes. The ARM64 gate reflects ai-android's arm64-v8a-only native
 * build, so an x86 emulator with model files present still gets fakes.
 *
 * Gesture has TWO real engines: the HKSL sequence recogniser (Shuwa) when its
 * holistic bundle, translator and KNN table are provisioned, else the single-image
 * MediaPipe bundle, else the fake. Which one is bound also decides the relay's
 * tuning ([provideRelayTuning]), because a sequence recogniser needs a window of
 * stills where a single-image one wants only the newest.
 *
 * Construction is lazy in the sense that matters: a real engine is only built
 * inside a true branch, so building the Dagger graph in a test never touches
 * arm64 native code.
 */
@Module
object DeviceAiModule {
    /** Whether the current ABIs include the one ai-android's native ships. */
    internal fun supportsRealEngines(supportedAbis: Array<String>? = Build.SUPPORTED_ABIS): Boolean =
        supportedAbis?.contains(ARM64_ABI) == true

    /** `asr/model*.onnx` plus `asr/tokens.txt`. */
    internal fun asrModelsReady(modelRoot: File): Boolean {
        val asr = File(modelRoot, ASR_DIRECTORY)
        return hasModelOnnx(asr) && File(asr, ASR_TOKENS_FILE).isFile
    }

    /** The pyannote segmentation graph plus the 3D-Speaker CAM++ embedding graph. */
    internal fun diarizationModelsReady(modelRoot: File): Boolean {
        val diarization = File(modelRoot, DIARIZATION_DIRECTORY)
        return File(diarization, "$DIARIZATION_SEGMENTATION_DIRECTORY/$DIARIZATION_SEGMENTATION_FILE").isFile &&
            File(diarization, DIARIZATION_EMBEDDING_FILE).isFile
    }

    /** The MediaPipe gesture bundle. */
    internal fun gestureModelsReady(modelRoot: File): Boolean = File(File(modelRoot, GESTURE_DIRECTORY), GESTURE_MODEL_FILE).isFile

    /** The Shuwa window needs the holistic bundle, the translator and the HKSL KNN table. */
    internal fun shuwaModelsReady(modelRoot: File): Boolean {
        val shuwa = File(modelRoot, SHUWA_DIRECTORY)
        return File(shuwa, SHUWA_TASK_FILE).isFile &&
            File(shuwa, SHUWA_TRANSLATOR_FILE).isFile &&
            File(shuwa, SHUWA_KNN_FILE).isFile
    }

    /** Whether this device binds the Shuwa sequence recogniser rather than the single-image one. */
    internal fun shuwaReady(context: Context): Boolean = supportsRealEngines() && shuwaModelsReady(modelRoot(context))

    /** The Cantonese VITS voice: model, tokens, lexicon and rule FST. */
    internal fun ttsModelsReady(modelRoot: File): Boolean {
        val voice = File(File(modelRoot, TTS_DIRECTORY), TTS_VOICE_DIRECTORY)
        return File(voice, TTS_MODEL_FILE).isFile &&
            File(voice, TTS_TOKENS_FILE).isFile &&
            File(voice, TTS_LEXICON_FILE).isFile &&
            File(voice, TTS_RULE_FILE).isFile
    }

    /** Speech needs BOTH engines (ASR transcription and diarization attribution). */
    internal fun speechModelsReady(modelRoot: File): Boolean = asrModelsReady(modelRoot) && diarizationModelsReady(modelRoot)

    @Provides
    @Singleton
    fun provideGesturePort(
        @ApplicationContext context: Context,
    ): GesturePort {
        val modelRoot = modelRoot(context)
        return when {
            // Shuwa is a SEQUENCE recogniser and wins when its three artefacts are provisioned.
            shuwaReady(context) -> AiShuwaGesturePort(context = context, modelDir = File(modelRoot, SHUWA_DIRECTORY))
            supportsRealEngines() && gestureModelsReady(modelRoot) ->
                AiGesturePort(context = context, modelDirectory = File(modelRoot, GESTURE_DIRECTORY))
            else -> FakeGesturePort()
        }
    }

    /**
     * Sizes the relay's queues for the gesture engine that was actually bound.
     *
     * [AiShuwaGesturePort] needs a WINDOW of stills, so the latest-wins one-slot image queue would
     * starve it; every other binding keeps the one-slot "no backlog" default. Deriving it from the
     * same [shuwaReady] decision as [provideGesturePort] is what keeps the two from drifting.
     */
    @Provides
    @Singleton
    fun provideRelayTuning(
        @ApplicationContext context: Context,
    ): RelayTuning = if (shuwaReady(context)) RelayTuning.sequence else RelayTuning.singleImage

    @Provides
    @Singleton
    fun provideSpeechPort(
        @ApplicationContext context: Context,
        enrollmentStore: EnrollmentStore,
    ): SpeechPort {
        val modelRoot = modelRoot(context)
        return if (supportsRealEngines() && speechModelsReady(modelRoot)) {
            AiSpeechPort(
                asrModelDirectory = File(modelRoot, ASR_DIRECTORY),
                diarizationModelDirectory = File(modelRoot, DIARIZATION_DIRECTORY),
                enrollmentStore = enrollmentStore,
            )
        } else {
            FakeSpeechPort()
        }
    }

    @Provides
    @Singleton
    fun provideTtsPort(
        @ApplicationContext context: Context,
    ): TtsPort {
        val modelRoot = modelRoot(context)
        return if (supportsRealEngines() && ttsModelsReady(modelRoot)) {
            AiTtsPort(
                modelRoot = File(modelRoot, TTS_DIRECTORY),
                config = TtsConfig(language = Language.Cantonese),
            )
        } else {
            FakeTtsPort()
        }
    }

    @Provides
    @Singleton
    fun provideEnrollmentStore(
        @ApplicationContext context: Context,
    ): EnrollmentStore {
        val modelRoot = modelRoot(context)
        return if (supportsRealEngines() && diarizationModelsReady(modelRoot)) {
            AiEnrollmentStore(
                modelDirectory = File(modelRoot, DIARIZATION_DIRECTORY),
                storageFile = File(context.filesDir, ENROLLMENT_FILE),
            )
        } else {
            FakeEnrollmentStore()
        }
    }

    private fun hasModelOnnx(directory: File): Boolean =
        directory
            .listFiles()
            ?.any { file ->
                file.isFile && file.name.startsWith(ASR_MODEL_PREFIX) && file.name.endsWith(ASR_MODEL_SUFFIX)
            } == true

    private fun modelRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, MODELS_DIRECTORY)
    }
}
