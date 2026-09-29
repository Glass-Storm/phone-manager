package com.glassstorm.phonemanager.testing.architecture

/** Package vocabulary and the permitted-importers table for the boundary laws. */
object ModuleBoundaries {
    const val ROOT_PACKAGE = "com.glassstorm.phonemanager"
    const val CONTRACT_PACKAGE = "$ROOT_PACKAGE.contract"
    const val ADAPTER_PACKAGE = "$ROOT_PACKAGE.adapter"
    const val CORE_SERVICE_PACKAGE = "$ROOT_PACKAGE.core.service"
    const val TRANSPORT_GRPC_PACKAGE = "$ROOT_PACKAGE.transport.grpc"
    const val GRPC_PACKAGE = "io.grpc"
    const val ECOSYS_PACKAGE = "ecosys.v1"

    val FORBIDDEN_DI_IMPORT_PREFIXES = listOf("dagger.", "javax.inject.")

    val FORBIDDEN_FRAMEWORK_ANNOTATIONS =
        setOf(
            "Module",
            "Binds",
            "BindsInstance",
            "Provides",
            "Inject",
            "Singleton",
            "Component",
            "Component.Factory",
            "Factory",
            "Subcomponent",
            "Qualifier",
            "MapKey",
            "IntoMap",
            "IntoSet",
        )

    /**
     * The modules allowed to import each "composition" layer.
     *
     * Each entry names the layer's owner plus `app` (the composition root), and
     * — where a layer genuinely sits above another — its one legitimate consumer.
     * A module importing its OWN subpackage is always allowed; the table never
     * needs to restate that.
     */
    private val PERMITTED_IMPORTERS: Map<String, Set<String>> =
        mapOf(
            CORE_SERVICE_PACKAGE to
                setOf(ArchitectureScope.CORE_SERVICE, ArchitectureScope.TRANSPORT_GRPC, ArchitectureScope.APP),
            TRANSPORT_GRPC_PACKAGE to
                setOf(ArchitectureScope.TRANSPORT_GRPC, ArchitectureScope.APP),
            "$ADAPTER_PACKAGE.jvm" to
                setOf(ArchitectureScope.ADAPTER_JVM, ArchitectureScope.APP),
            "$ADAPTER_PACKAGE.android" to
                setOf(ArchitectureScope.ADAPTER_ANDROID, ArchitectureScope.APP),
        )

    private val NON_APP_MODULE_ROOTS = setOf("core", "adapter", "transport", "contract", "testing")

    /**
     * Whether [importName] names a symbol owned by the `app` module.
     *
     * App owns the package root and every subpackage that is NOT one of the
     * other modules' roots, so an import under `...core` / `...adapter` /
     * `...transport` / `...contract` / `...testing` is NOT app-owned.
     */
    fun isAppImport(importName: String): Boolean =
        importName.startsWith("$ROOT_PACKAGE.") &&
            importName.removePrefix("$ROOT_PACKAGE.").substringBefore('.') !in NON_APP_MODULE_ROOTS

    /**
     * The modules allowed to import the composition layer [importName] belongs
     * to, or `null` when the import names the domain/model/testkit language that
     * every module may speak.
     */
    fun permittedImportersOf(importName: String): Set<String>? =
        PERMITTED_IMPORTERS.entries
            .firstOrNull { (layer, _) -> importName.startsWith("$layer.") }
            ?.value
}
