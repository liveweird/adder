package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

class TestExecutionBuildScriptTest {
    private val root = File(System.getProperty("repo.root", ".."))

    @Test
    fun `the server applies and Docker packages the test execution script`() {
        val build = root.resolve("server/build.gradle.kts").readText()
        val dockerfile = root.resolve("Dockerfile").readText()
        val execution = root.resolve("server/test-execution.gradle.kts").readText()

        assertContains(build, "apply(from = \"test-execution.gradle.kts\")")
        assertContains(dockerfile, "server/test-execution.gradle.kts")
        assertContains(execution, "class TestStallWatchdog")
        assertContains(execution, "class ForkWarmupLedger")
        assertContains(execution, "reports/openapi-conformance")
        assertContains(execution, "maxParallelForks = forks")
    }
}
