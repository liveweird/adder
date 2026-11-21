package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `lockingTransaction` (`infra/db/Locking.kt`, a table lock around a whole write) is for the tag registry ONLY (1.2 S6): chat writes take
 * the room's row lock (`rooms/RoomLock.kt`). A plain source scan: any main file that CALLS it must live in `tags/` (or be its own file).
 */
class LockingTransactionUsageTest {
    @Test
    fun `only the tag registry calls lockingTransaction`() {
        val root = File(System.getProperty("repo.root") ?: "..", "server/src/main/kotlin")
        assertTrue(root.isDirectory, "${root.absolutePath} not found")
        val callers = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().map { it.trimStart() }
                    .any { !it.startsWith("*") && !it.startsWith("//") && "lockingTransaction(" in it }
            }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it != "infra/db/Locking.kt" }
            .toList()
        assertTrue(callers.isNotEmpty(), "the scan saw no caller at all")
        assertEquals(emptyList(), callers.filterNot { it.startsWith("tags/") }, "lockingTransaction outside tags/")
    }
}
