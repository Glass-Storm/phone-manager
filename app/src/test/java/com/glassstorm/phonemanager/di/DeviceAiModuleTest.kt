package com.glassstorm.phonemanager.di

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeviceAiModuleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun models(): File = temporaryFolder.newFolder()

    private fun file(
        root: File,
        path: String,
    ): File = File(root, path).apply { parentFile?.mkdirs() }

    private fun write(
        root: File,
        path: String,
    ) {
        file(root, path).writeBytes(byteArrayOf(1))
    }

    @Test
    fun `an empty model root leaves every engine on the fakes`() {
        val root = models()

        assertThat(DeviceAiModule.asrModelsReady(root)).isFalse()
        assertThat(DeviceAiModule.diarizationModelsReady(root)).isFalse()
        assertThat(DeviceAiModule.gestureModelsReady(root)).isFalse()
        assertThat(DeviceAiModule.ttsModelsReady(root)).isFalse()
        assertThat(DeviceAiModule.speechModelsReady(root)).isFalse()
    }

    @Test
    fun `the abi gate requires arm64-v8a`() {
        assertThat(DeviceAiModule.supportsRealEngines(arrayOf("x86_64", "x86"))).isFalse()
        assertThat(DeviceAiModule.supportsRealEngines(arrayOf("arm64-v8a"))).isTrue()
        assertThat(DeviceAiModule.supportsRealEngines(null)).isFalse()
    }

    @Test
    fun `a directory named like the asr model is not a provisioned model`() {
        val root = models()
        File(root, "asr/model.int8.onnx").mkdirs()
        write(root, "asr/tokens.txt")

        assertThat(DeviceAiModule.asrModelsReady(root)).isFalse()
    }

    @Test
    fun `asr needs both a model star onnx and tokens`() {
        val root = models()
        write(root, "asr/model.int8.onnx")
        assertThat(DeviceAiModule.asrModelsReady(root)).isFalse()

        write(root, "asr/tokens.txt")
        assertThat(DeviceAiModule.asrModelsReady(root)).isTrue()

        val other = models()
        write(other, "asr/model.onnx")
        write(other, "asr/tokens.txt")
        assertThat(DeviceAiModule.asrModelsReady(other)).isTrue()
    }

    @Test
    fun `diarization needs both graphs`() {
        val root = models()
        write(root, "diarization/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx")
        assertThat(DeviceAiModule.diarizationModelsReady(root)).isFalse()

        write(root, "diarization/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx")
        assertThat(DeviceAiModule.diarizationModelsReady(root)).isTrue()
    }

    @Test
    fun `speech needs asr and diarization together`() {
        val root = models()
        write(root, "asr/model.int8.onnx")
        write(root, "asr/tokens.txt")

        assertThat(DeviceAiModule.speechModelsReady(root)).isFalse()

        write(root, "diarization/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx")
        write(root, "diarization/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx")

        assertThat(DeviceAiModule.speechModelsReady(root)).isTrue()
    }

    @Test
    fun `gesture needs the mediapipe task bundle`() {
        val root = models()
        assertThat(DeviceAiModule.gestureModelsReady(root)).isFalse()

        write(root, "gesture/gesture_recognizer.task")
        assertThat(DeviceAiModule.gestureModelsReady(root)).isTrue()
    }

    @Test
    fun `tts needs the voice model tokens lexicon and rule`() {
        val root = models()
        val voice = "tts/vits-cantonese-hf-xiaomaiiwn"
        write(root, "$voice/vits-cantonese-hf-xiaomaiiwn.onnx")
        write(root, "$voice/tokens.txt")
        write(root, "$voice/lexicon.txt")
        assertThat(DeviceAiModule.ttsModelsReady(root)).isFalse()

        write(root, "$voice/rule.fst")
        assertThat(DeviceAiModule.ttsModelsReady(root)).isTrue()
    }
}
