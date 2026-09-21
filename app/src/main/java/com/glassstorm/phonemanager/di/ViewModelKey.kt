package com.glassstorm.phonemanager.di

import androidx.lifecycle.ViewModel
import dagger.MapKey
import kotlin.reflect.KClass

/**
 * Map key for the ViewModel multibinding.
 *
 * Keyed by the ViewModel's [KClass], which Dagger resolves to the runtime `Class`
 * — a type identity, never a name string — so the generated map survives R8
 * shrinking without `-identifiernamestring` keep rules.
 */
@MapKey
@Target(AnnotationTarget.FUNCTION)
annotation class ViewModelKey(
    val value: KClass<out ViewModel>,
)
