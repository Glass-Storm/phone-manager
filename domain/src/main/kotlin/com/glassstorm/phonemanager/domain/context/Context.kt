package com.glassstorm.phonemanager.domain.context

import kotlin.reflect.KClass

/** Thrown when a type is requested from a [Context] that never registered it. */
class MissingFromContextException(
    message: String,
) : IllegalStateException(message)

/**
 * Type-keyed service registry, sing-box style.
 *
 * Usage: `Register(ctx, myImpl)` then `FromContext<MyPort>(ctx)`.
 *
 * A public `inline fun <reified T>` cannot touch a `private` member, so the
 * backing map is exposed as `@PublishedApi internal` for the reified helpers.
 */
class Context {
    @PublishedApi
    internal val registry: MutableMap<KClass<*>, Any> = mutableMapOf()

    fun <T : Any> Register(
        type: KClass<T>,
        instance: T,
    ) {
        registry[type] = instance
    }

    fun <T : Any> unregister(type: KClass<T>) {
        registry.remove(type)
    }

    @PublishedApi
    internal fun <T : Any> lookup(type: KClass<T>): T? = registry[type] as? T
}

/** Register [instance] under its reified static type. */
inline fun <reified T : Any> Register(
    ctx: Context,
    instance: T,
): Unit = ctx.Register(T::class, instance)

/** Resolve the registered instance of [T], or throw [MissingFromContextException]. */
inline fun <reified T : Any> FromContext(ctx: Context): T =
    ctx.lookup(T::class) ?: throw MissingFromContextException(
        "no ${T::class.qualifiedName} registered in Context",
    )

/** Resolve the registered instance of [T], or `null` when absent. */
inline fun <reified T : Any> FromContextOrNull(ctx: Context): T? = ctx.lookup(T::class)
