package com.glassstorm.phonemanager.testing.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope

/**
 * The source roots every boundary law is checked against.
 *
 * Konsist resolves its project root by walking UP from the test JVM's working
 * directory (Gradle sets it to the module directory, `testing/architecture`) to
 * the first directory containing `gradlew`. So `SCOPE_PATHS` are repo-root
 * relative and `Konsist.scopeFromDirectories` prepends that resolved root.
 *
 * The paths are listed EXPLICITLY rather than using `scopeFromProject()`, which
 * would silently follow a module rename and could drift onto generated sources.
 * With an explicit list a missing module is a FAILING assertion (see
 * `ArchitectureScopeTest`) rather than a vacuously green rule.
 *
 * Every path points at a module's `src` directory, which holds exactly the
 * authored Kotlin sources and test sources — the module-level `build/` and
 * scratch `bin/` directories sit BESIDE `src`, not inside it, so they are never
 * scanned.
 */
object ArchitectureScope {
    const val APP = "app"
    const val CONTRACT = "contract"
    const val CORE_MODEL = "core/model"
    const val CORE_DOMAIN = "core/domain"
    const val CORE_SERVICE = "core/service"
    const val TRANSPORT_GRPC = "transport/grpc"
    const val ADAPTER_JVM = "adapter/jvm"
    const val ADAPTER_ANDROID = "adapter/android"
    const val TESTING_TESTKIT = "testing/testkit"
    const val TESTING_ARCHITECTURE = "testing/architecture"

    /** Authored sources of one module, as a repo-root relative directory. */
    fun sources(module: String): String = "$module/src"

    /** The MAIN source set of one module, as a repo-root relative directory. */
    fun main(module: String): String = "$module/src/main"

    /** Every authored source set in the repository, production and test. */
    val SCOPE_PATHS: List<String> =
        listOf(
            APP,
            CONTRACT,
            CORE_MODEL,
            CORE_DOMAIN,
            CORE_SERVICE,
            TRANSPORT_GRPC,
            ADAPTER_JVM,
            ADAPTER_ANDROID,
            TESTING_TESTKIT,
            TESTING_ARCHITECTURE,
        ).map(::sources)

    /** Every authored Kotlin file in the repository, production and test. */
    fun all(): KoScope = Konsist.scopeFromDirectories(SCOPE_PATHS)

    /** The authored sources of one module. */
    fun of(module: String): KoScope = Konsist.scopeFromDirectory(sources(module))

    /** The authored MAIN (production) sources of one module. */
    fun mainOf(module: String): KoScope = Konsist.scopeFromDirectory(main(module))
}
