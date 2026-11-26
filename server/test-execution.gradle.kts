import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

// Every test-client interaction with /api/ is validated against the OpenAPI spec (see
// OpenApiConformance.kt). `-Dopenapi.conformance=warn|off` relaxes it for drift triage.
tasks.withType<Test> {
    systemProperty("openapi.conformance", System.getProperty("openapi.conformance", "fail"))
    // The repo root, so tests that read repo files (PostgresImagePinTest) do not depend on the test cwd.
    systemProperty("repo.root", rootDir.absolutePath)
    // OpenApiSpecTest pins documentation.yaml's info.version to APP_VERSION: a bump alone must re-run the tests.
    inputs.file(rootDir.resolve("web/src/changelog/version.ts")).withPropertyName("appVersion")
}

// The candidate rule of the `test` task (`include(TEST_CLASS_GLOB)`) and of `balancedCandidateOrder`: ONE definition, so
// the planned order can never drift from the set Gradle runs. `TestClassNamingTest` pins the naming rule itself.
val TEST_CLASS_SUFFIX = "Test.class"
val TEST_CLASS_GLOB = "**/*$TEST_CLASS_SUFFIX"

// The fork-level warm-up classes (`ForkWarmup1Test`..`ForkWarmup8Test`, `ForkWarmup.kt` explains why eight): the `test` task
// pins `ForkWarmup<k>Test` as the FIRST class of fork k for k <= -Pforks, so the one-time Postgres + Flyway + first-boot
// cost lands in a `@BeforeClass` (untimed) instead of inside the first `testApplication` (hard 60 s limit). They are
// removed from every ordering below and re-added at the head by the `testClassesDirs` Callable; a surplus one (k > forks)
// is never a candidate.
val FORK_WARMUP_GLOB = "ch/nokillswit/ForkWarmup*$TEST_CLASS_SUFFIX"
fun forkWarmupClassFile(slot: Int) = "ch/nokillswit/ForkWarmup$slot$TEST_CLASS_SUFFIX"
fun isForkWarmup(relativePath: String) = relativePath.startsWith("ch/nokillswit/ForkWarmup") && relativePath.endsWith(TEST_CLASS_SUFFIX)
fun isForkWarmupClass(className: String) = isForkWarmup(className.replace('.', '/') + ".class")

// The default of `-PtestStallMinutes` (the stall watchdog, `TestStallWatchdog` below). Measured 2026-10-21, one full
// `-Pforks=2` local run (199 classes, 3 min): the longest quiet gap in either fork was 13 s (a class's `@BeforeClass`
// fixture warm-up: Postgres container) and the slowest single test 7 s. A CI runner is ~1.75x
// slower (23 s; `test-fixtures.md` puts the cold warm-up at ~45 s, and `testApplication` itself caps a test at 60 s),
// so 5 minutes is ~20x the local worst gap and >6x the worst CI figure, while 5x under the 25-minute job timeout. Raise
// it only with a measured gap (every executor logs its own longest one when it finishes).
val DEFAULT_TEST_STALL_MINUTES = 5

// The test classes in the order the `Test` task should see them when it runs several forks (`-Pforks=N`, N > 1).
// Why: Gradle's MaxNParallelTestDefinitionProcessor deals candidate classes to the forks ROUND-ROBIN in visit order
// (position p -> fork p % forks), all up front, with no work stealing; an alphabetical visit let one fork draw most
// of the heavy classes and run alone for the second half of the suite (build-times.md WHY 18). So the order we emit
// IS the fork assignment: classes sorted by weight (`test-weights.tsv`, seconds per class) are dealt greedily
// (LPT) to the least loaded fork that still has a free slot, and each fork's queue is then laid out at the
// positions p % forks == fork. The per-fork slot count is capped at its round-robin share, so the interleaving
// reproduces the plan exactly. Ties break by name, so the order is the same on every machine; a class missing from
// the file weighs the average. Returns an EMPTY list when there is nothing to plan yet (no candidate class files: the
// task graph is resolved before `compileTestKotlin` ran, and the files are looked up again when the task executes) and
// null when the weights file is missing or unparseable; either way the caller falls back to the plain, alphabetical
// tree, and only the null case is worth a warning.
fun balancedCandidateOrder(classesDirs: Set<File>, weightsFile: File, forks: Int): List<Pair<File, String>>? {
    val candidates = classesDirs.filter { it.isDirectory }.flatMap { dir ->
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(TEST_CLASS_SUFFIX) }
            .map { dir to it.relativeTo(dir).invariantSeparatorsPath }.toList()
    }.filterNot { isForkWarmup(it.second) }.sortedBy { it.second }
    if (candidates.isEmpty()) return emptyList()
    val weights = runCatching {
        weightsFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore('\t') to it.substringAfter('\t').trim().toDouble() }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    val average = weights.values.average()
    fun weightOf(relativePath: String) = weights[relativePath.removeSuffix(".class").replace('/', '.')] ?: average
    val slots = IntArray(forks) { fork -> (candidates.size - fork + forks - 1) / forks }
    val queues = List(forks) { mutableListOf<Pair<File, String>>() }
    val loads = DoubleArray(forks)
    for (candidate in candidates.sortedWith(compareByDescending<Pair<File, String>> { weightOf(it.second) }.thenBy { it.second })) {
        val fork = (0 until forks).filter { queues[it].size < slots[it] }.minBy { loads[it] }
        queues[fork] += candidate
        loads[fork] += weightOf(candidate.second)
    }
    return candidates.indices.map { position -> queues[position % forks][position / forks] }
}

val TEST_EXECUTOR_PREFIX = "Gradle Test Executor "

// The test JVM ("Gradle Test Executor N" suite) a descriptor belongs to: shared by the stall watchdog and the fork warm-up ledger.
fun executorOf(descriptor: TestDescriptor): String? {
    var node: TestDescriptor? = descriptor
    while (node != null) {
        if (node.name.startsWith(TEST_EXECUTOR_PREFIX)) return node.name
        node = node.parent
    }
    return null
}

// The test STALL WATCHDOG (`tasks.test` below, `-PtestStallMinutes`). Why: on 2026-10-17 (CI run 39062260538) one test
// failed and the `server` job then printed NOTHING until the 25-minute job timeout cancelled it; a cancelled job
// uploads no `server-reports` artifact (`if: !cancelled()`), so the hang left no evidence. A hung test JVM is not
// something JUnit or Gradle interrupts, so this watches the one signal that always exists: the Test task's listener
// events. Each test JVM is a "Gradle Test Executor N" suite in the descriptor parent chain and every suite/test start
// or finish is progress. An executor that has started, has not finished and has been quiet for the threshold gets a
// thread dump (`jcmd <pid> Thread.print`: the evidence a cancel destroys), logged AND written under
// `build/reports/hung-tests/`, naming the class/test in flight; then its JVM is killed, so the task fails once the
// other forks have finished their queues instead of at the job timeout. The thread is a daemon and also ends once the task has executed (a failed
// task never runs `doLast`, and the Gradle daemon outlives the build). Time is `System.nanoTime`, which does not tick
// while a laptop sleeps, so a closed lid never reads as a stall.
class TestStallWatchdog(
    private val thresholdMinutes: Int,
    private val reportDir: File,
    private val jcmd: File,
    private val workerMarker: String,
    private val log: Logger,
    private val taskFinished: () -> Boolean,
) : TestListener {
    private class Progress {
        @Volatile var lastEventNanos = System.nanoTime()
        @Volatile var inFlight = "starting up"
        @Volatile var longestGapMillis = 0L
        @Volatile var longestGapAt = "-"
    }

    private val executors = ConcurrentHashMap<String, Progress>()

    // Also records the longest quiet gap per executor, printed when the executor finishes: the evidence behind the
    // default of `-PtestStallMinutes` (re-measure it from this line when the suite's slowest class warm-up changes).
    private fun touch(descriptor: TestDescriptor, inFlight: String) {
        val executor = executorOf(descriptor) ?: return
        val progress = executors.getOrPut(executor) { Progress() }
        val now = System.nanoTime()
        val gap = TimeUnit.NANOSECONDS.toMillis(now - progress.lastEventNanos)
        if (gap > progress.longestGapMillis) {
            progress.longestGapMillis = gap
            progress.longestGapAt = progress.inFlight
        }
        progress.lastEventNanos = now
        progress.inFlight = inFlight
    }

    override fun beforeSuite(suite: TestDescriptor) = touch(suite, "${suite.className ?: suite.name} (class setup, before its first test)")

    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
        if (!suite.name.startsWith(TEST_EXECUTOR_PREFIX)) return touch(suite, "${suite.className ?: suite.name} (between tests, or finishing)")
        executors.remove(suite.name)?.let { progress ->
            log.lifecycle("test watchdog: ${suite.name} longest quiet gap ${progress.longestGapMillis / 1000} s (in flight: ${progress.longestGapAt})")
        }
    }

    override fun beforeTest(test: TestDescriptor) = touch(test, "${test.className}.${test.name}")

    override fun afterTest(test: TestDescriptor, result: TestResult) = touch(test, "${test.className} (after ${test.name})")

    fun start() {
        val thresholdNanos = TimeUnit.MINUTES.toNanos(thresholdMinutes.toLong())
        val tickMillis = (TimeUnit.MINUTES.toMillis(thresholdMinutes.toLong()) / 4).coerceIn(1_000L, 10_000L)
        Thread(
            {
                while (!taskFinished()) {
                    Thread.sleep(tickMillis)
                    executors.entries.toList().forEach { (executor, progress) ->
                        if (System.nanoTime() - progress.lastEventNanos > thresholdNanos && executors.remove(executor, progress)) {
                            onStall(executor, progress)
                        }
                    }
                }
            },
            "test-stall-watchdog",
        ).apply { isDaemon = true }.start()
    }

    // The worker's command line ends in its name ('Gradle Test Executor 2'), and carries the repo-root property every Test
    // task of this build sets, so another checkout's worker (or "Executor 20" when looking for "2") never matches.
    // Fallback when no command line carries the marker (a platform that truncates it): any descendant whose command
    // line still names the executor. A platform that hides command lines entirely finds nothing; the dump file and the
    // log then say so, and the job timeout remains the backstop (killing unidentified JVMs would be worse).
    private fun workerOf(executor: String): List<ProcessHandle> {
        val name = Regex("(?<![\\w])" + Regex.escape(executor) + "(?!\\d)")
        val workers = ProcessHandle.current().descendants().toList()
        fun line(handle: ProcessHandle) = handle.info().commandLine().orElse("")
        return workers.filter { workerMarker in line(it) && name.containsMatchIn(line(it)) }.take(1)
            .ifEmpty { workers.filter { "GradleWorkerMain" in line(it) && name.containsMatchIn(line(it)) } }
    }

    // Output goes to a file, never a pipe read before waitFor: a worker too wedged to answer the attach would block a
    // pipe read forever, and with it this one watchdog thread (no kill, and no other fork watched).
    private fun threadDump(pid: Long): String = try {
        val out = File.createTempFile("jcmd-$pid-", ".txt")
        try {
            val process = ProcessBuilder(jcmd.path, pid.toString(), "Thread.print", "-l")
                .redirectErrorStream(true).redirectOutput(out).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            out.readText() + if (finished) "" else "\n(jcmd did not finish within 30 s)"
        } finally {
            out.delete()
        }
    } catch (failure: java.io.IOException) {
        "(no thread dump: ${failure.message})"
    }

    private fun onStall(executor: String, progress: Progress) {
        val quietSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - progress.lastEventNanos)
        val workers = workerOf(executor)
        val dump = when (workers.size) {
            0 -> "(no thread dump: the worker process of '$executor' was not found; nothing to kill)"
            1 -> threadDump(workers.single().pid())
            else -> "(the worker of '$executor' could not be told apart; dumping and killing all ${workers.size} candidates)\n" +
                workers.joinToString("\n") { threadDump(it.pid()) }
        }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(reportDir, "${executor.replace(' ', '-')}-$stamp.txt")
        val headline = "$executor made no progress for $quietSeconds s (limit $thresholdMinutes min, -PtestStallMinutes); in flight: ${progress.inFlight}"
        reportDir.mkdirs()
        file.writeText("$headline\n\n$dump\n")
        log.error("$headline\nthread dump: ${file.path}\n$dump\nkilling '$executor': the test task fails once the other forks finish, not at the job timeout")
        workers.forEach { it.destroyForcibly() }
    }
}

// The fork warm-up TRIPWIRE: the pin (`testClassesDirs` in `tasks.test`) rests on Gradle internals (round-robin dealing of
// class files in visit order, in-order forwarding to a fork, one entry per class name), so a Gradle change must not turn
// into a silent return of the cold first class. This listener records, per test JVM, the first class suite it ran and the
// result of every `ForkWarmup<k>Test` class suite; `problem(forks)` is what a FULL run checks in `doLast`: exactly
// `ForkWarmup1..forksTest` ran, each SUCCESSFUL, in `forks` distinct JVMs. A JVM whose first class was not its warm-up is
// only warned about (a failed-first rerun, Gradle's RunPreviousFailedFirst, legitimately reorders).
class ForkWarmupLedger : TestListener {
    private val warmups = ConcurrentHashMap<String, Pair<String, TestResult.ResultType?>>()
    private val firstClass = ConcurrentHashMap<String, String>()

    // A class suite is the direct child of a "Gradle Test Executor N" suite.
    private fun classSuite(suite: TestDescriptor): String? =
        suite.className?.takeIf { suite.parent?.name?.startsWith(TEST_EXECUTOR_PREFIX) == true }

    override fun beforeSuite(suite: TestDescriptor) {
        val className = classSuite(suite) ?: return
        val executor = executorOf(suite) ?: return
        firstClass.putIfAbsent(executor, className)
        if (isForkWarmupClass(className)) warmups[className] = executor to null
    }

    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
        val className = classSuite(suite) ?: return
        warmups.computeIfPresent(className) { _, ran -> ran.first to result.resultType }
    }

    override fun beforeTest(test: TestDescriptor) = Unit

    override fun afterTest(test: TestDescriptor, result: TestResult) = Unit

    fun problem(forks: Int): String? {
        val expected = (1..forks).map { forkWarmupClassFile(it).removeSuffix(".class").replace('/', '.') }
        val ranWhere = warmups.entries.sortedBy { it.key }.joinToString("; ") { (name, ran) ->
            "${name.substringAfterLast('.')} in '${ran.first}' -> ${ran.second}"
        }.ifEmpty { "none ran" }
        val wrong = expected.filterNot { warmups[it]?.second == TestResult.ResultType.SUCCESS } + (warmups.keys - expected.toSet()).sorted()
        val executors = expected.mapNotNull { warmups[it]?.first }.distinct()
        if (wrong.isEmpty() && executors.size == forks) return null
        return "Fork warm-up check: with -Pforks=$forks exactly ${expected.joinToString(", ") { it.substringAfterLast('.') }} must each run " +
            "once, successfully, in a distinct test JVM (the first class of fork k). Ran: $ranWhere. Likely causes: a Gradle change to how " +
            "classes are dealt to forks or deduplicated, or a missing/renamed ForkWarmup<k>Test.kt (see .claude/docs/test-fixtures.md " +
            "\"Balanced fork order\"). If the previous run's warm-up class failed, Gradle runs failed classes first and this check can " +
            "fire once: rerun."
    }

    fun firstClassWarnings(): List<String> = firstClass.entries.sortedBy { it.key }.filterNot { (_, name) -> isForkWarmupClass(name) }
        .map { (executor, name) -> "'$executor' opened with $name, not a ForkWarmup<k>Test (a failed-first rerun does this; otherwise the pin is broken)" }
}

// The OpenAPI COVERAGE gate: each test JVM's OpenApiCoverage shutdown hook writes its own
// `exercised-<pid>-<uuid>.txt` and re-merges every fork's file into ONE coverage.md + gaps.txt
// (OpenApiCoverageMerge — a pair exercised by ANY fork is covered, so it is fork-safe). gaps.txt lists every
// declared (operation, status) pair no fork exercised — minus the statuses shared plugins produce for every
// route alike (400/401/413/415/429, pinned once each) and the unforceable 500/default. A non-empty file
// fails the task, but only when the WHOLE suite ran (a `--tests` filter legitimately leaves most of the
// spec unexercised). The directory is cleared first so a previous run's per-fork files never leak in.
tasks.named<Test>("test") {
    // CdsClassListTest reads this file: declared so a regenerated class list reruns it instead of being served from the build cache.
    inputs.file("cds/classlist.txt").withPropertyName("cdsClassList")
    // RollbackRunbookTest runs the SQL blocks of this doc: an edit to the runbook reruns it instead of being served from the build cache.
    inputs.file(rootDir.resolve(".claude/docs/persistence.md")).withPropertyName("rollbackRunbook")
    // Parallel JVM forks (`-Pforks=2` in CI; default 1 = the classic single fork). Every fork is its own JVM, so it
    // gets its OWN Testcontainers Postgres and its own `object` fixture singletons — all the shared-database
    // hazards (withSoloAdmins, restoreSeedAccounts) stay intra-fork.
    // `forkEvery` is deliberately NOT set (one JVM per fork for the whole run). Measured in build-times.md WHY 5.
    // `-Pforks` must be an integer 1..8 (more forks only add a container each, WHY 5); anything else
    // fails any build that configures the test task, instead of a NumberFormatException or an unbounded fan-out.
    val forks: Int = (findProperty("forks") as String?).let { raw ->
        if (raw == null) {
            1
        } else {
            raw.trim().toIntOrNull()?.takeIf { it in 1..8 }
                ?: throw GradleException("-Pforks must be an integer from 1 to 8, got '$raw'")
        }
    }
    maxParallelForks = forks
    // The test fork HEAP stays at Gradle's default (512 MiB): a fork's post-GC floor is flat at ~130-290 MiB over a full run since the
    // OpenAPI validator's schema cache was capped (`OpenApiConformance.kt` SCHEMA_CACHE_ENTRIES; build-times.md WHY 62). Do not raise
    // it to hide growth - a floor that climbs again means something else is retaining data; measure it with `-Xlog:gc` first.
    // Candidates are selected by FILE NAME, not by scanning class files for @Test (Gradle's documented pairing: with
    // scanning off, `include` patterns decide). A later change relies on the exact candidate set; the naming rule
    // behind it is pinned by `TestClassNamingTest`.
    isScanForTestClasses = false
    include(TEST_CLASS_GLOB)
    // Replace the plain class tree by one component per candidate class, in the planned order (a composite file
    // collection is visited in declaration order and keeps package-relative paths). Layout, for EVERY `-Pforks`:
    //   head  `ForkWarmup1Test`..`ForkWarmup<forks>Test`, one file tree each: positions 0..forks-1, so Gradle's round-robin
    //         deal gives fork k its own warm-up as the first class it runs (`ForkWarmup.kt`, the ledger tripwire above);
    //   body  forks > 1 with a weights file: `balancedCandidateOrder` (warm-ups already removed), plus a per-dir catch-all
    //         of everything that is not `**/*Test.class` (that `exclude(TEST_CLASS_GLOB)` is what keeps the surplus
    //         ForkWarmup<forks+1..8>Test out); forks == 1 or an unusable weights file: the plain trees minus every warm-up
    //         class (`exclude(FORK_WARMUP_GLOB)`, alphabetical, no directory walk).
    // Only the class files are task inputs; refreshing test-weights.tsv never invalidates a test run. The Callable reads
    // project state at resolution time, which is fine while the configuration cache is off (revisit if it is ever
    // enabled). Before `compileTestKotlin` ran (the task graph is resolved first; the compiled tree may even be STALE,
    // built before the warm-up classes existed) it returns the plain dirs as today, and so it does whenever a needed
    // `ForkWarmup<k>Test.class` is missing - it NEVER throws, a throw here would break task-graph resolution (`--dry-run`).
    // It is evaluated again when the task executes, after the compile; the doFirst below then fails a run whose warm-up
    // class is still missing. An unusable weights file warns once (no candidates yet does not) and keeps the alphabetical order.
    val plainDirs = testClassesDirs
    val weightsFile = file("test-weights.tsv")
    var warned = false
    testClassesDirs = files(
        Callable<Any> {
            val dirs = plainDirs.files
            if (dirs.none { it.isDirectory && !it.list().isNullOrEmpty() }) return@Callable plainDirs
            val head = (1..forks).map { slot ->
                val relativePath = forkWarmupClassFile(slot)
                val dir = dirs.firstOrNull { it.resolve(relativePath).isFile } ?: return@Callable plainDirs
                objects.fileTree().from(dir).include(relativePath)
            }
            val order = if (forks > 1) balancedCandidateOrder(dirs, weightsFile, forks) else emptyList()
            if (forks > 1 && order == null && !warned) {
                warned = true
                logger.warn("test fork balancing skipped: ${weightsFile.name} missing or unparseable (run: node scripts/timings/local-times.mjs test-weights); classes run in alphabetical order")
            }
            if (order.isNullOrEmpty()) {
                head + dirs.map { dir -> objects.fileTree().from(dir).exclude(FORK_WARMUP_GLOB) }
            } else {
                head + order.map { (dir, relativePath) -> objects.fileTree().from(dir).include(relativePath) } +
                    dirs.map { dir -> objects.fileTree().from(dir).exclude(TEST_CLASS_GLOB) }
            }
        },
    )
    // The stall watchdog (class above): `-PtestStallMinutes` is the longest an UNFINISHED test JVM may go without a
    // single suite/test event before it is thread-dumped and killed. An integer 1..60 like `-Pforks` (the floor so a
    // typo cannot kill a healthy run at once, the ceiling so it can never outlast the CI job's own timeout).
    // Default: DEFAULT_TEST_STALL_MINUTES, see its comment for the measurement behind it.
    val stallMinutes: Int = (findProperty("testStallMinutes") as String?).let { raw ->
        if (raw == null) {
            DEFAULT_TEST_STALL_MINUTES
        } else {
            raw.trim().toIntOrNull()?.takeIf { it in 1..60 }
                ?: throw GradleException("-PtestStallMinutes must be an integer from 1 to 60, got '$raw'")
        }
    }
    val hungTestsDir = layout.buildDirectory.dir("reports/hung-tests")
    val repoRootProperty = "-Drepo.root=${rootDir.absolutePath}"
    val taskLogger = logger
    var warmupLedger: ForkWarmupLedger? = null
    // Installed at execution time: the toolchain's `jcmd` is resolved then (`javaLauncher` is the JVM the workers run
    // on, so its `jcmd` can attach to them), and every run gets a fresh watchdog with no state in the configuration.
    doFirst {
        // After the compile, so a stale tree (built before the warm-up classes existed) was rebuilt by now: a still-missing
        // class is a real problem (a deleted/renamed ForkWarmup<k>Test.kt), or a stale build when the source is present.
        val compiledDirs = plainDirs.files
        (1..forks).map(::forkWarmupClassFile).filter { relativePath -> compiledDirs.none { it.resolve(relativePath).isFile } }.let { missing ->
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "fork warm-up class(es) ${missing.joinToString()} missing from the compiled test classes: -Pforks=$forks pins " +
                        "ForkWarmup1Test..ForkWarmup${forks}Test as the first class of each fork (server/src/test/kotlin/ForkWarmup<k>Test.kt, " +
                        ".claude/docs/test-fixtures.md \"Balanced fork order\"); if the sources exist this is a stale build - run clean",
                )
            }
        }
        hungTestsDir.get().asFile.deleteRecursively() // only this run's dumps
        val jcmd = javaLauncher.get().metadata.installationPath.file("bin/jcmd").asFile
        val watchdog = TestStallWatchdog(stallMinutes, hungTestsDir.get().asFile, jcmd, repoRootProperty, taskLogger) { state.executed }
        addTestListener(watchdog)
        watchdog.start()
        warmupLedger = ForkWarmupLedger().also { addTestListener(it) }
    }
    val reportDir = layout.buildDirectory.dir("reports/openapi-conformance")
    val gapsFile = layout.buildDirectory.file("reports/openapi-conformance/gaps.txt")
    doFirst {
        reportDir.get().asFile.let { dir -> dir.deleteRecursively(); dir.mkdirs() }
    }
    // `--tests` lands in the start parameter's task arguments (the filter's command-line patterns are
    // internal API); a filtered run is not the whole suite, so the gate stays quiet.
    val filtered = gradle.startParameter.taskRequests.any { request -> "--tests" in request.args }
    doLast {
        if (filtered || filter.includePatterns.isNotEmpty()) return@doLast
        // The fork warm-up tripwire (ForkWarmupLedger): a full run must have opened every fork with its own warm-up class.
        warmupLedger?.let { ledger ->
            ledger.firstClassWarnings().forEach { taskLogger.warn("fork warm-up: $it") }
            ledger.problem(forks)?.let { throw GradleException(it) }
        }
        // A whole-suite run that produced NO gaps file means no fork published its coverage (the shutdown hook
        // broke, or nothing exercised the API) — that must fail, never pass as "no gaps".
        val file = gapsFile.get().asFile
        check(file.exists()) { "OpenAPI coverage gate: ${file.path} is missing — no test fork published its coverage" }
        val gaps = file.readLines().filter { it.isNotBlank() }
        check(gaps.isEmpty()) {
            "OpenAPI coverage gate: ${gaps.size} declared (operation, status) pair(s) were never exercised by the suite — " +
                "add a test per declared status, or trim the spec to what the route can answer:\n  " + gaps.joinToString("\n  ")
        }
    }
}
