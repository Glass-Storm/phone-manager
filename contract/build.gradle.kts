plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
    alias(libs.plugins.protobuf)
    // Publishing only — the contract is the ONE artifact the out-of-scope glasses
    // app and Ubuntu daemon consume, so it must be independently versioned.
    `maven-publish`
}

// :contract owns the FROZEN `ecosys.v1` wire contract: the .proto source of truth
// (src/main/proto/ecosys/v1/ecosys.proto) AND the codegen that turns it into the
// Kotlin/Java + gRPC-lite bindings every peer generates its client from. The
// glasses app and the Ubuntu daemon are out of scope for this repo, so this is
// the ONE artifact they consume — kept in its own module so it can be versioned
// and published independently of the hub's implementation.
//
// The Go half of the codegen lives in `tools/mockpeer/gen.sh`, which reads the
// SAME proto from here.

// NOTE on catalog accessors: the catalog declares `protoc` AND
// `protoc-gen-grpc-java` / `protoc-gen-grpc-kotlin`. Gradle nests the latter two
// under a generated `libs.protoc` GROUP which SHADOWS the leaf accessors entirely
// (`libs.protoc.get()` and `libs.protoc.protoc` are both unresolved). So the
// VERSIONS (never shadowed) are read from the catalog and the coordinates are
// assembled explicitly — the catalog stays the single source of truth for pins.
val protocVersion = libs.versions.protoc.get()
val grpcVersion = libs.versions.grpc.get()
val grpcKotlinVersion = libs.versions.grpcKotlin.get()

protobuf {
    // protoc 3.25.9 — the catalog pin, matching protobuf-javalite /
    // protobuf-kotlin-lite and grpc-protobuf-lite 1.84.0.
    protoc {
        artifact = "com.google.protobuf:protoc:$protocVersion"
    }
    plugins {
        // io.grpc:protoc-gen-grpc-java:1.84.0
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        // io.grpc:protoc-gen-grpc-kotlin:1.5.0 ONLY ships the `jdk8` classifier jar
        // (verified live against Maven Central). Gradle 8.13 version catalogs
        // REJECT a `classifier` key (T1 DEVIATION), so the classifier is attached
        // HERE, at declaration time, via the `group:name:version:classifier@jar`
        // notation.
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
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

// ---------------------------------------------------------------------------
// Publishing (T20).
//
// `:contract` is the ONLY artifact a peer consumes: the out-of-scope glasses app
// and the Ubuntu daemon generate their clients from this module's `.proto` and
// link against its compiled bindings. Cloning this monorepo is not a sane
// consumption story for them, so the module publishes a versioned artifact to
// Maven local:
//
//     com.glassstorm.phonemanager:contract:<version>
//
// TWO artifacts are published on purpose:
//   1. the default JAR (the compiled protobuf/gRPC-lite bindings), and
//   2. a `proto`-classifier JAR carrying the `.proto` SOURCE of truth, because a
//      peer generating Go/Swift/Kotlin clients needs the `.proto` ITSELF, not
//      the hub's compiled classes.
//
// Publishing targets `mavenLocal` ONLY - there is no remote repository, no
// signing, and no credentials anywhere in this build. The command is:
//
//     ./gradlew :contract:publishToMavenLocal
//
// The coordinates and the regeneration command are documented for consumers in
// `docs/protocol.md` section 11 ("Rules for changing this contract").
// ---------------------------------------------------------------------------

group = "com.glassstorm.phonemanager"
version = "1.0.0"

// Packages the `src/main/proto` tree into a JAR whose entries keep the original
// relative path (`ecosys/v1/ecosys.proto`), so a consumer can hand the extracted
// directory straight to `protoc` with `-I`. Declared BEFORE the `publishing`
// block below because that block wires it into the publication at configuration
// time.
val protoSourceJar by tasks.registering(Jar::class) {
    archiveClassifier.set("proto")
    from("src/main/proto")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // Coordinates: com.glassstorm.phonemanager:contract:1.0.0
            // `artifactId` follows the Gradle project name (`:contract` -> `contract`,
            // `:adapter:jvm` -> `adapter-jvm`); pinned explicitly so a future rename
            // of the directory cannot silently rename the published artifact.
            groupId = "com.glassstorm.phonemanager"
            artifactId = "contract"
            version = "1.0.0"

            // The compiled bindings. `from(components["java"])` also emits the
            // correct runtime dependency set in the POM (the `api(...)` deps), so a
            // consumer resolves grpc/protobuf-lite transitively.
            from(components["java"])

            // The `.proto` source of truth, shipped alongside the classes with the
            // `proto` classifier. Without this a Go/Swift peer has nothing to
            // generate from.
            artifact(protoSourceJar)
        }
    }
}

dependencies {
    // The generated bindings are the module's PUBLIC surface, so their runtime is
    // exported with `api`: any module that consumes the contract (today `:service`,
    // later `transport/grpc`) must see the generated types AND the grpc/protobuf-lite
    // classes they extend without re-declaring them.
    api(libs.grpc.stub)
    api(libs.grpc.protobuf.lite)
    api(libs.grpc.kotlin.stub)
    api(libs.protobuf.javalite)
    api(libs.protobuf.kotlin.lite)

    // The grpckt-generated coroutine stubs return `kotlinx.coroutines.flow.Flow`
    // in their PUBLIC signatures, so coroutines is part of the contract's API too.
    api(libs.kotlinx.coroutines.core)

    // Annotations used by the generated gRPC Java stubs.
    compileOnly(libs.annotations.api)
}
