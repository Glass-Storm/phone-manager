plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.protobuf)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// NOTE on catalog accessors: the catalog declares `protoc` AND
// `protoc-gen-grpc-java` / `protoc-gen-grpc-kotlin`. Gradle nests the latter two
// under a generated `libs.protoc` GROUP which SHADOWS the leaf accessors entirely
// (`libs.protoc.get()` and `libs.protoc.protoc` are both unresolved). So the
// VERSIONS (never shadowed) are read from the catalog and the coordinates are
// assembled explicitly — the catalog stays the single source of truth for pins.
val GoProtocVersion = libs.versions.protoc.get()
val GoGrpcVersion = libs.versions.grpc.get()
val GoGrpcKotlinVersion = libs.versions.grpcKotlin.get()

protobuf {
    // protoc 3.25.9 — the catalog pin, matching protobuf-javalite /
    // protobuf-kotlin-lite and grpc-protobuf-lite 1.84.0.
    protoc {
        artifact = "com.google.protobuf:protoc:$GoProtocVersion"
    }
    plugins {
        // io.grpc:protoc-gen-grpc-java:1.84.0
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$GoGrpcVersion"
        }
        // io.grpc:protoc-gen-grpc-kotlin:1.5.0 ONLY ships the `jdk8` classifier jar
        // (verified live against Maven Central). Gradle 8.13 version catalogs
        // REJECT a `classifier` key (T1 DEVIATION), so the classifier is attached
        // HERE, at declaration time, via the `group:name:version:classifier@jar`
        // notation.
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$GoGrpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            // CRITICAL: `option("lite")` MUST be applied to BOTH the java/kotlin
            // builtins AND the grpc/grpckt plugins. Applying it to only one side
            // silently pulls full protobuf-java and breaks the Android build.
            //
            // `id(name)` == `create(name)`, and the plugin PRE-CREATES the
            // `java` builtin, so `create("java")` throws "already exists".
            // Configure the existing entry, creating only when absent.
            task.builtins.apply {
                (findByName("java") ?: create("java")).option("lite")
                (findByName("kotlin") ?: create("kotlin")).option("lite")
            }
            task.plugins.apply {
                (findByName("grpc") ?: create("grpc")).option("lite")
                (findByName("grpckt") ?: create("grpckt")).option("lite")
            }
        }
    }
}

dependencies {
    // Hexagonal boundary: :service may see the domain ports/DTOs ONLY.
    // It MUST NEVER gain a dependency edge to :adapter (enforced by the build graph).
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)

    // gRPC + protobuf LITE runtime — never full protobuf-java.
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)

    // Annotations used by the generated gRPC Java stubs.
    compileOnly(libs.annotations.api)

    testImplementation(kotlin("test"))
    // Shared E2E kit (mockpeer runner + deterministic media generators). `:app`
    // cannot see these test sources, so they live in a plain JVM module.
    testImplementation(project(":testkit"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.testing)
    // T6 transport spike: the REAL netty-shaded NIO transport. This is the
    // transport candidate for Android (issues.md R1) and the one the GO/NO-GO
    // verdict is about; it is test-scope only here because :app supplies its own
    // copy in T7.
    testImplementation(libs.grpc.netty.shaded)
}
