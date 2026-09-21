package com.glassstorm.phonemanager.testing.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.provider.KoAnnotationProvider
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test

/**
 * The module-boundary laws, enforced by Konsist at test time.
 *
 * Each rule is UNCONDITIONAL: there is no baseline file, no `@Suppress`, and no
 * Gradle-side escape hatch. Konsist honours a `@Suppress("<testName>")`
 * annotation, so the suppression guard below fails if anyone ever adds one.
 *
 * The rules encode the hexagonal boundaries the README describes, not the Gradle
 * dependency edges (Gradle already fails those). Each one can see something
 * Gradle cannot: a source-level import or annotation, in either the main or the
 * test source set, plus whether a "class" is really still an interface.
 */
class ArchitectureTest {
    // --- Law (a): the contract package is consumed by transport/grpc alone ---

    @Test
    fun `contract is imported only by transport-grpc and the contract module itself`() {
        ArchitectureScope.all().imports.assertFalse(
            additionalMessage =
                "Law (a): :contract may be imported ONLY by :transport:grpc (the module that maps " +
                    "the wire format to the domain's own DTOs) and by :contract's own sources.",
        ) { import ->
            import.name.startsWith("${ModuleBoundaries.CONTRACT_PACKAGE}.") &&
                import.moduleName !in setOf(ArchitectureScope.TRANSPORT_GRPC, ArchitectureScope.CONTRACT)
        }
    }

    // --- Law (b): ecosys.v1 is confined to transport/grpc and contract ---

    @Test
    fun `ecosys-v1 is imported only by transport-grpc and the contract module itself`() {
        ArchitectureScope.all().imports.assertFalse(
            additionalMessage =
                "Law (b): `ecosys.v1.*` is the FROZEN wire contract. Only :transport:grpc (which " +
                    "owns the DTO mapping) and :contract's own tests may name it. It must never " +
                    "reach the domain, the use cases, or an adapter.",
        ) { import ->
            import.name.startsWith("${ModuleBoundaries.ECOSYS_PACKAGE}.") &&
                import.moduleName !in setOf(ArchitectureScope.TRANSPORT_GRPC, ArchitectureScope.CONTRACT)
        }
    }

    // --- Law (c): core:service stays gRPC-free, contract-free and adapter-free ---

    @Test
    fun `core-service imports no grpc, no ecosys-v1 and no adapter`() {
        ArchitectureScope.of(ArchitectureScope.CORE_SERVICE).imports.assertFalse(
            additionalMessage =
                "Law (c): :core:service holds the use-case implementations. It must not know about " +
                    "gRPC (`io.grpc.*`), the wire contract (`ecosys.v1.*`), or any concrete adapter " +
                    "(`com.glassstorm.phonemanager.adapter.*`) — it speaks DTOs only.",
        ) { import ->
            import.name.startsWith("${ModuleBoundaries.GRPC_PACKAGE}.") ||
                import.name.startsWith("${ModuleBoundaries.ECOSYS_PACKAGE}.") ||
                import.name.startsWith("${ModuleBoundaries.ADAPTER_PACKAGE}.")
        }
    }

    // --- Law (d): core:domain is annotation-free and holds no port implementations ---

    @Test
    fun `core-domain imports no DI framework and declares no framework annotation`() {
        val domain = ArchitectureScope.mainOf(ArchitectureScope.CORE_DOMAIN)

        domain.imports.assertFalse(
            additionalMessage =
                "Law (d): :core:domain is pure. It must not import a DI framework " +
                    "(`dagger.*` / `javax.inject.*`); only classes that are CONSTRUCTED carry " +
                    "Dagger annotations, and the domain constructs nothing.",
        ) { import ->
            ModuleBoundaries.FORBIDDEN_DI_IMPORT_PREFIXES.any { import.name.startsWith(it) }
        }

        domain
            .declarations()
            .filterIsInstance<KoAnnotationProvider>()
            .flatMap { it.annotations }
            .assertFalse(
                additionalMessage =
                    "Law (d): :core:domain declares no framework annotation " +
                        "(@Module/@Binds/@Provides/@Inject/@Singleton/@Component/@Qualifier/...). " +
                        "The domain is interfaces, data, and pure functions.",
            ) { annotation -> annotation.name in ModuleBoundaries.FORBIDDEN_FRAMEWORK_ANNOTATIONS }
    }

    @Test
    fun `core-domain declares no class in a port package`() {
        val portPackages =
            listOf(
                "${ModuleBoundaries.ROOT_PACKAGE}.core.domain.adapter",
                "${ModuleBoundaries.ROOT_PACKAGE}.core.domain.service",
                "${ModuleBoundaries.ROOT_PACKAGE}.core.domain.security",
            )

        val classesInPortPackages =
            ArchitectureScope
                .mainOf(ArchitectureScope.CORE_DOMAIN)
                .files
                .filter { file -> file.hasPackageUnder(portPackages) }
                .flatMap { it.classes(includeNested = true) }

        classesInPortPackages.assertFalse(
            additionalMessage =
                "Law (d): the port packages of :core:domain hold INTERFACES only. Konsist's " +
                    "`classes()` excludes interfaces and objects, so any class here is an " +
                    "implementation, which belongs in :core:service or an adapter. (The two " +
                    "deterministic state machines live in `core.domain.network` by design.)",
        ) { classInPortPackage -> classInPortPackage.name.isNotEmpty() }
    }

    // --- Law (e): adapters never see app ---

    @Test
    fun `no adapter imports app`() {
        ArchitectureScope.all().imports.assertFalse(
            additionalMessage =
                "Law (e): an adapter is a leaf. It must never import the `app` composition root " +
                    "(its UI, its DI package, or its platform glue) — the dependency points app -> adapter.",
        ) { import ->
            import.moduleName.startsWith("adapter") && ModuleBoundaries.isAppImport(import.name)
        }
    }

    // --- Law (f): nothing outside app imports app ---

    @Test
    fun `nothing outside app imports app`() {
        ArchitectureScope.all().imports.assertFalse(
            additionalMessage =
                "Law (f): `app` owns the composition root and every screen. NOTHING may import it. " +
                    "A module that needs app behaviour has its dependency inverted.",
        ) { import ->
            import.moduleName != ArchitectureScope.APP && ModuleBoundaries.isAppImport(import.name)
        }
    }

    // --- Law (g): app is the only module allowed to see every layer ---

    @Test
    fun `only app imports the composition layers across module boundaries`() {
        ArchitectureScope.all().imports.assertFalse(
            additionalMessage =
                "Law (g): `app` is the ONE module allowed to see the use cases, the gRPC transport, " +
                    "and the adapters together. Any OTHER module importing one of those layers is a " +
                    "boundary violation (a module importing its OWN subpackage is always allowed).",
        ) { import ->
            val permitted = ModuleBoundaries.permittedImportersOf(import.name) ?: return@assertFalse false
            import.moduleName !in permitted
        }
    }

    // --- Anti-suppression: the rules cannot be individually disabled ---

    @Test
    fun `no source silences a konsist rule`() {
        ArchitectureScope
            .all()
            .files
            .filterNot { it.moduleName == ArchitectureScope.TESTING_ARCHITECTURE }
            .assertFalse(
                additionalMessage =
                    "Konsist honours @Suppress(\"<testName>\"), which would let a violation pass. This " +
                        "architecture gate is unconditional: remove the suppression and fix the boundary.",
            ) { file -> file.text.contains("konsist.", ignoreCase = true) && file.text.contains("Suppress") }
    }

    private fun KoFileDeclaration.hasPackageUnder(prefixes: List<String>) =
        packagee?.name?.let { pkg -> prefixes.any { pkg == it || pkg.startsWith("$it.") } } ?: false
}
