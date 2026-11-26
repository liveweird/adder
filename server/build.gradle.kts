plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencies {
        constraints {
            // Ktor's Gradle plugin loads these via its Shadow and Jib integrations during the
            // build. Keep the script classpath above the reviewed advisory floors too.
            classpath("com.fasterxml.jackson.core:jackson-core:2.23.0")
            classpath("com.fasterxml.jackson.core:jackson-databind:2.23.0")
            classpath("com.fasterxml.jackson.core:jackson-annotations:2.23")
            classpath("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.23.0")
            classpath("org.apache.logging.log4j:log4j-api:2.26.1")
            classpath("org.apache.logging.log4j:log4j-core:2.26.1")
            classpath("org.codehaus.plexus:plexus-utils:4.2.0")
            // Jib's google-http-client/auth libraries pull Guava; HIGH CVE-2026-102554 is fixed in 33.7.2
            // (build-time only; the application's own floor is `libs.guava` below).
            classpath("com.google.guava:guava:33.8.0-jre")
        }
    }
}


application {
    mainClass = "io.ktor.server.netty.EngineMain"
    // Footprint tuning for this small, I/O-bound, low-traffic service. Baked into the installDist
    // launcher (bin/server -> the Docker image) and `:server:run`; `test` is unaffected. Measured
    // (in Lettuce, the same stack) on a 512 MiB Linux container: baseline G1 drifts ~345->410 MiB
    // RSS as it grows its heap; this config sits at a steady ~270 MiB - ~25% lower and
    // predictable. Startup is ~1.6 s either way; the win is memory, not startup.
    //   - UseSerialGC        : G1's concurrent threads + region metadata are pure overhead for a
    //                          small heap / few cores; SerialGC alone saved ~75 MiB here.
    //   - Xmx256m            : the app holds no large caches; 256 MiB is comfortable headroom for
    //                          light bursts (drop to 192m to trim ~25 MiB more if traffic stays low).
    //   - TieredStopAtLevel=1: C1-only JIT - trims code-cache + C2-compiler memory (~50 MiB here).
    //                          Peak CPU-bound throughput is lower, which is irrelevant for an
    //                          I/O-bound tool; REMOVE this flag if the service ever runs hot.
    //   - ExitOnOutOfMemoryError: a heap OOM used to kill single threads (a Netty event loop, a background coroutine) and
    //                          leave a half-dead process that compose/k8s never restarted; exiting lets
    //                          `restart: unless-stopped`/k8s restart it. It fires only on VM-raised OOMs (heap, metaspace,
    //                          native thread), not on Netty's `OutOfDirectMemoryError` ("Direct buffer memory"), which Java
    //                          code throws.
    //   The next five are the donor project's 2026-10-22 heap/thread sizing (measured there on a 512 MiB container), carried over as it was:
    //   - Xmn64m             : a FIXED 64 MiB young generation. SerialGC's default is a third of the COMMITTED heap, so a
    //                          small committed heap gets tiny survivors and allocation-heavy bursts overflow into the old
    //                          generation, which then ratchets up and never comes back.
    //   - SurvivorRatio=2    : two survivor spaces of 16 MiB each (eden 32), so medium-lived garbage dies in the
    //                          young generation instead of being promoted.
    //   - MinHeapFreeRatio=10 / MaxHeapFreeRatio=30 : grow the old generation only as far as needed (default 40/70) and let
    //                          a full collection shrink it back past 30 % free.
    //   - ShrinkHeapInSteps  : off, so a full collection shrinks straight to the target instead of a step at a time.
    //   - ActiveProcessorCount=2 : the JVM would size Netty (2 x cores event loops), reactor and ForkJoin pools from every
    //                          core of the node (k8s sets no CPU limit): 98 -> 38 threads at 18 visible cores. The cost is
    //                          Dispatchers.Default = 2, i.e. at most two bcrypt hashes at a time (login is rare, the rest of
    //                          the work is I/O). Raise it if login latency ever shows queueing.
    //   - MaxDirectMemorySize=48m : the cap on off-heap buffers (C-ARCH-3). Left unset, the JDK allows as much direct memory as the
    //                          max HEAP (256 MiB here) and Netty derives its own ceiling from the same value, so heap (256) + non-heap
    //                          (~175) + direct (up to 256) could pass the 512 MiB container limit and the kernel would OOM-kill the
    //                          pod with no Java exception to show for it. Netty reads this flag (the VM's value, else the JVM input
    //                          arguments), so it needs no `-Dio.netty.maxDirectMemory`. 48 MiB is sized from the allocators, NOT from a
    //                          load test: 2 x ActiveProcessorCount = 4 event loops, each with one buffer chunk of the pooled/adaptive
    //                          allocator (1-4 MiB) = 4-16 MiB, per-thread caches of small buffers a few MiB, the 512 KiB body ceiling
    //                          x a few concurrent uploads, and the JDK's temporary I/O buffers: an estimate of 20 MiB busy (the only
    //                          measurement is idle: NMT "Other" <= 5 MiB, WHY 17), so 48 is more than twice that. Worst case
    //                          256 + 175 (the ~180 non-heap budget less its 5 of direct) + 48 = 479 MiB of 512 (33 MiB spare); at the
    //                          cap Netty/the JDK throws OutOfMemoryError "Direct buffer memory" into the one request or connection
    //                          that asked, a failed call instead of a dead pod. Raise it only with a measurement (and the container
    //                          limit; JvmMemoryFlagsTest pins the sum). Checked: Netty 4.2.20's PlatformDependent.maxDirectMemory()
    //                          is 256 MiB (the heap) without the flag and 48 MiB with it.
    //   Not set on purpose: -Xss. The default 1 MiB stack stays (CanonicalJsonTest pins the margin canonicalJson/toString
    //                          can store for a deeply nested payload).
    //   AppCDS is NOT a flag here: the Docker image dumps a class-data-sharing archive and enables it through `ENV SERVER_OPTS`
    //                          (Dockerfile, build-times.md WHY 27). A missing archive file would switch CDS off altogether, which would
    //                          slow every `:server:run`, so the flag lives only where the file is guaranteed to exist. Adding
    //                          `-XX:-UseCompressedOops` / `-XX:ObjectAlignmentInBytes` (or any flag that changes pointer layout) HERE silently
    //                          disables the archive - the app still starts, just slower; CI's scripts/check-cds-archive.sh catches it.
    // Override per-deployment with the JAVA_OPTS env var (the launcher appends it after these; SERVER_OPTS is appended last but the
    // image already uses it for the AppCDS flag, so set it only to replace that).
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseSerialGC",
        "-Xmx256m",
        "-XX:TieredStopAtLevel=1",
        "-XX:+ExitOnOutOfMemoryError",
        "-Xmn64m",
        "-XX:SurvivorRatio=2",
        "-XX:MinHeapFreeRatio=10",
        "-XX:MaxHeapFreeRatio=30",
        "-XX:-ShrinkHeapInSteps",
        "-XX:ActiveProcessorCount=2",
        "-XX:MaxDirectMemorySize=48m",
    )
}

kotlin {
    jvmToolchain(21)
}

kover {
    reports {
        filters {
            excludes {
                // @Serializable data classes are wire shapes: kotlinx-serialization synthesizes one branch
                // per optional property (the default-value mask in the generated constructor and
                // serializer) that no test can meaningfully exercise — the reader's ~40 view DTOs alone
                // added a thousand such branches. Behavior never lives in them (services, validators and
                // renderers are plain classes and stay measured; a DTO's companion object stays measured
                // too, since the annotation sits on the class).
                annotatedBy("kotlinx.serialization.Serializable")
            }
        }
        verify {
            rule {
                // Line-coverage floor — Adder's baseline, set after trimming Flow's suite down to the
                // generic foundation (97 at 97.34% on 2026-11-17) and raised for 0.4.0 (98 at 98.44%
                // on 2026-11-19; 98.66% at 0.5.0, 2026-11-20) measured with the
                // @Serializable exclusion above. raised to 99 for 1.0.0 (99.19% on the merged follow-ups tree, 2026-11-20).
                // From here on re-measure with `:server:koverXmlReport` and RAISE, never lower.
                minBound(99)
                // Branch-coverage floor — same baseline: 81 at 81.67% on 2026-11-17, raised for 0.4.0 (85 at 86.09%
                // on 2026-11-19; Flow's 85) and again for 0.5.0 (86 at 86.69% on 2026-11-20) and 0.6.0 (87 at 87.27%, same day), then the 1.0.0 follow-ups (88 at 88.28% on the merged tree, same day); 1.2.0 measured 99.14 / 88.29, floors unchanged (integers). NOTE: `check` runs only koverVerify — run
                // `:server:koverXmlReport` for fresh actuals.
                minBound(88, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}

// Static analysis (plain rule sets only — no type resolution). Rule tuning lives in
// config/detekt/detekt.yml; the task rides `check`, so `build` gates on it.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}
dependencies {
    constraints {
        // Security floor for a vulnerable runtime transitive (scram-client, via r2dbc-postgresql).
        // Kept as a constraint because server code does not consume its API directly; see the
        // catalog note for provenance.
        implementation(libs.scram.client)
        implementation(libs.scram.common)
        // Security floor for Guava (jwks-rsa at runtime, swagger-core in tests): see the catalog note.
        implementation(libs.guava)
    }
    implementation(project(":core"))
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.autoHeadResponse)
    implementation(ktorLibs.server.bodyLimit)
    implementation(ktorLibs.server.cachingHeaders)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.compression)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.csrf)
    implementation(ktorLibs.server.defaultHeaders)
    implementation(ktorLibs.server.forwardedHeader)
    implementation(ktorLibs.server.hsts)
    implementation(ktorLibs.server.httpRedirect)
    implementation(ktorLibs.server.metrics)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.rateLimit)
    implementation(ktorLibs.server.resources)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.swagger)
    // The live-update socket (0.4.0, `live/`, `plugins/Sockets.kt`); it was test-only (via the test host) before the socket step.
    implementation(ktorLibs.server.websockets)
    implementation(libs.angus.mail)
    implementation(libs.bcrypt)
    implementation(libs.commonmark.core)
    implementation(libs.commonmark.autolink)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.r2dbc.pool)
    // Reactor alignment — see the `reactor-bom` comment in gradle/libs.versions.toml: the BOM pins
    // reactor-core/-pool/-netty to one release train (they carry different version numbers), so
    // these two declarations carry no version of their own. Guarded by checkDependencyAlignment.
    implementation(platform(libs.reactor.bom))
    implementation(libs.reactor.pool)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.logback.classic)
    implementation(libs.opentelemetry.logbackAppender)
    implementation(libs.postgresql)
    implementation(libs.r2dbc.postgresql)
    // Netty alignment — see the `netty` comment in gradle/libs.versions.toml: the BOM pins every
    // io.netty module to one version, and reactor-netty (versioned by the Reactor BOM above) wins
    // over the driver's older transitive request (Gradle picks the highest). Guarded by
    // checkDependencyAlignment below.
    implementation(platform(libs.netty.bom))
    implementation(libs.reactor.netty.core)
    // kotlin-reflect rides in transitively (Ktor loads the config modules through it) at whatever
    // version Ktor/Exposed were built with; declaring it lets the Kotlin plugin align it with the
    // stdlib. Guarded by checkDependencyAlignment below.
    implementation(kotlin("reflect"))
    // Jackson dataformat/module — see the `jackson` catalog note. The Jackson BOM pins the whole
    // com.fasterxml.jackson family.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
    // Test-only: the test HTTP clients (TestEnvironment.kt's jsonClient()/authedClient()) negotiate
    // application/json and application/problem+json bodies.
    testImplementation(ktorLibs.client.contentNegotiation)
    // Test-only: the WebSocket client of the live-channel tests (TestEnvironment.kt's `liveClient()`); never used for /api/ HTTP.
    testImplementation(ktorLibs.client.websockets)
    // Test-only: the OpenAPI conformance/spec-validation harness (OpenApiConformance.kt,
    // OpenApiSpecTest.kt) parses and validates documentation.yaml against real traffic — see the
    // `swagger-parser`/`swagger-request-validator` catalog notes.
    testImplementation(libs.swagger.parser.v3)
    testImplementation(libs.swagger.request.validator.core)
    testImplementation(libs.testcontainers.postgresql)
}

apply(from = "test-execution.gradle.kts")

// Fails the build when a dependency FAMILY that must move as one resolves to several versions on
// the server runtime classpath — the mixed Netty 4.1/4.2 set Ktor + reactor-netty produced, the
// OpenTelemetry incubator drifting from the SDK, kotlin-reflect lagging the stdlib, a Jackson 2
// module drifting from the BOM, or a Reactor module drifting from the reactor-bom's release train
// (the catalog notes explain each pin). Docker-free, rides `check` like detekt.
val checkDependencyAlignment = tasks.register("checkDependencyAlignment") {
    group = "verification"
    description = "Asserts one version per aligned dependency family (Reactor: the BOM's versions) on the server runtime classpath."
    val runtimeClasspath = configurations.runtimeClasspath
    doLast {
        val ids = runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }
        // The Reactor modules are the exception to one-version-per-family (reactor-core, reactor-pool
        // and reactor-netty legitimately carry different version numbers as ONE release train), so
        // they are checked against the Reactor BOM's own constraints instead — upstream's pairing
        // table. Read off the platform node of the resolution graph, so no extra artifact is fetched
        // and the documented `--write-locks` command records all it needs.
        val bomCoordinates = "io.projectreactor:reactor-bom"
        val bomNode = runtimeClasspath.get().incoming.resolutionResult.allComponents.singleOrNull {
            (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)
                ?.let { id -> "${id.group}:${id.module}" == bomCoordinates } == true
        } ?: error("$bomCoordinates is not on the runtime classpath — is the Reactor check still needed?")
        val managed = bomNode.dependencies
            .filter { it.isConstraint }
            .mapNotNull { it.requested as? org.gradle.api.artifacts.component.ModuleComponentSelector }
            .associate { "${it.group}:${it.module}" to it.version }
        val reactorIds = ids.filter { it.group.startsWith("io.projectreactor") }
        check(reactorIds.isNotEmpty()) { "No io.projectreactor module on the runtime classpath — is the Reactor check still needed?" }
        val reactorDrift = reactorIds.mapNotNull { id ->
            when (val declared = managed["${id.group}:${id.name}"]) {
                null -> "${id.group}:${id.name}:${id.version} is not in reactor-bom"
                id.version -> null
                else -> "${id.group}:${id.name} resolved ${id.version}, reactor-bom declares $declared"
            }
        }
        if (reactorDrift.isEmpty()) {
            logger.lifecycle(
                "io.projectreactor aligned with reactor-bom ${libs.versions.reactor.bom.get()} (" +
                    reactorIds.joinToString { "${it.name} ${it.version}" } + ")",
            )
        }
        // family label -> (member predicate, version normalizer)
        val families = mapOf(
            "io.netty" to Pair({ g: String, _: String -> g == "io.netty" }, { v: String -> v }),
            // The alpha/incubator artifacts carry a "-alpha" suffix on the same version number.
            "io.opentelemetry" to Pair({ g: String, _: String -> g == "io.opentelemetry" }, { v: String -> v.removeSuffix("-alpha") }),
            // stdlib-jdk7/jdk8 are empty relocation jars since Kotlin 1.8 — only these two matter.
            "kotlin stdlib/reflect" to Pair(
                { g: String, n: String -> g == "org.jetbrains.kotlin" && (n == "kotlin-stdlib" || n == "kotlin-reflect") },
                { v: String -> v },
            ),
            // The test-only swagger-parser/swagger-request-validator stack rides Jackson 2 too; the
            // BOM keeps every module together. Since 2.22 jackson-annotations is versioned by MINOR
            // only ("2.22" beside "2.22.1"), so the family compares major.minor — a patch drift
            // inside one minor is the BOM's business.
            "com.fasterxml.jackson" to Pair(
                { g: String, _: String -> g.startsWith("com.fasterxml.jackson") },
                { v: String -> v.split('.').take(2).joinToString(".") },
            ),
        )
        val drift = families.mapNotNull { (label, spec) ->
            val (member, normalize) = spec
            val versions = ids.filter { member(it.group, it.name) }.groupBy({ normalize(it.version) }, { it.name })
            if (versions.size == 1) {
                logger.lifecycle("$label aligned at ${versions.keys.single()} (${versions.values.single().size} modules)")
                null
            } else {
                "$label: " + versions.entries.joinToString("; ") { (v, names) -> "$v -> ${names.sorted()}" }
            }
        }
        check(drift.isEmpty() && reactorDrift.isEmpty()) {
            "Dependency families must resolve to ONE version each on the runtime classpath, and Reactor modules " +
                "to the reactor-bom's versions — " + (drift + reactorDrift).joinToString(" | ")
        }
        // Jackson 3 (tools.jackson, a different Java package from com.fasterxml.jackson) rides in
        // through Flyway 13 and coexists with the Jackson 2 line the validators use — the two never
        // share a type. Logged, not failed: what matters is that OUR libraries stay on ONE of them
        // (networknt 3.x would put JsonNode-incompatible trees next to swagger-parser's — see the
        // catalog note), which the family check above guards.
        val jackson3 = ids.filter { it.group.startsWith("tools.jackson") }.map { "${it.name}:${it.version}" }.sorted()
        if (jackson3.isNotEmpty()) logger.lifecycle("tools.jackson (Jackson 3, via Flyway) present alongside Jackson 2: $jackson3")
    }
}
tasks.named("check") { dependsOn(checkDependencyAlignment) }
