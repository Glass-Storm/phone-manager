package com.glassstorm.phonemanager.di

import android.content.Context
import android.os.Build
import com.glassstorm.phonemanager.adapter.ai.AiEnrollmentStore
import com.glassstorm.phonemanager.adapter.ai.AiGesturePort
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
        return if (supportsRealEngines() && gestureModelsReady(modelRoot)) {
            AiGesturePort(context = context, modelDirectory = File(modelRoot, GESTURE_DIRECTORY))
        } else {
            FakeGesturePort()
        }
    }

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
