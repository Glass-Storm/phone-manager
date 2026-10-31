package com.glassstorm.phonemanager.adapter.jvm.di

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
import javax.inject.Singleton

/**
 * Dagger bindings for the ai-android harness: gesture, speech+diarization, TTS,
 * and the hub-owned enrollment directory.
 *
 * The DEFAULT graph binds the DETERMINISTIC JVM fakes, which is what every unit
 * and JVM test build resolves. This is a hard requirement, not a convenience:
 * ai-android's gesture/diarization native is **arm64-only**, so a JVM test can
 * never load it. The on-device implementations live in the isolated `:adapter:ai`
 * module, which the app graph does not depend on by default (that isolation is
 * also what keeps ai-android's full protobuf-java off the hub classpath — see the
 * module's build file).
 *
 * Both bindings are `@Provides` rather than `@Binds` because the fakes are
 * constructed with their own defaults, and `@Singleton` because a relay session
 * must talk to ONE engine and ONE directory for its whole life.
 */
@Module
object AiModule {
    /** The gesture engine (deterministic JVM fake by default). */
    @Provides
    @Singleton
    fun provideGesturePort(): GesturePort = FakeGesturePort()

    /** The speech + diarization engine (deterministic JVM fake by default). */
    @Provides
    @Singleton
    fun provideSpeechPort(): SpeechPort = FakeSpeechPort()

    /** The text-to-speech engine (deterministic JVM fake by default). */
    @Provides
    @Singleton
    fun provideTtsPort(): TtsPort = FakeTtsPort()

    /** The hub-owned voiceprint enrollment directory (in-memory fake by default). */
    @Provides
    @Singleton
    fun provideEnrollmentStore(): EnrollmentStore = FakeEnrollmentStore()
}
