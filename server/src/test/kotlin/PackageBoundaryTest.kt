package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one-way package layering (CLAUDE.md "Package layout", checkup D5): everything under `infra/` is the foundation every feature
 * builds on, so it never references a feature package (`teams`, `users`, `auth`, `authz`, `telemetry`,
 * `workflows`, `rooms`, `threads`, `live`). `audit` (the cross-cutting audit trail) and `plugins` (Ktor wiring: `respondProblem`) are not
 * features and stay open to `infra`. The composition root is the one
 * documented exception ([infraCompositionRoot]): the DB/bootstrap wiring MUST construct the feature services it publishes on
 * `Application.attributes`, so exactly those two files may name exactly those packages. `telemetry/` is the top layer (an observer of
 * every lower one): nothing else names it. A plain source scan (no Docker, no classpath tricks): any non-comment line of a file in
 * the lower package that mentions an upper package by its qualified name (an import, a wildcard import or an inline reference) is a
 * violation.
 */
class PackageBoundaryTest {
    private val sourceRoot: File by lazy {
        val root = File(System.getProperty("repo.root") ?: "..", "server/src/main/kotlin")
        assertTrue(root.isDirectory, "${root.absolutePath} not found")
        root
    }

    private val mainSources: List<File> by lazy { sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** The file's path under `server/src/main/kotlin` (`infra/db/Database.kt`): exemptions are keyed by it, never by a bare file name. */
    private fun File.relativePath(): String = relativeTo(sourceRoot).invariantSeparatorsPath

    /**
     * Composition-root exceptions to "infra never references a feature": `infra/db/Database.kt` builds and publishes the feature
     * services (`teams`, `users`, `auth`, `workflows`, `rooms`), `infra/db/ChatWiring.kt` the chat's (`threads`, `notifications`) and
     * `infra/db/Bootstrap.kt` seeds the admin through `users`/`auth`.
     * Keyed by path under `server/src/main/kotlin`, valued by the feature packages that file may import;
     * a listed package the file no longer uses fails the test (a stale exception is dead permission).
     */
    private val infraCompositionRoot: Map<String, Set<String>> = mapOf(
        "infra/db/Database.kt" to setOf("auth", "rooms", "teams", "users", "workflows"),
        "infra/db/ChatWiring.kt" to setOf("notifications", "rooms", "threads"),
        "infra/db/Bootstrap.kt" to setOf("auth", "users"),
    )

    /** The files that construct the notification service (the only non-notifications files that may name it). */
    private val notificationsWiring = setOf("infra/db/Database.kt", "infra/db/ChatWiring.kt")

    private val chatPackages = listOf("workflows", "rooms", "threads", "live", "notifications")

    private val featurePackages = listOf(
        "teams", "users", "auth", "authz", "telemetry", "workflows", "rooms", "threads", "live", "notifications",
    )

    private fun packageOf(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()

    private fun isComment(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** `line number: line` of every code line in [text] that names one of [forbidden] (`metrics`, `reports`, ...) by qualified package. */
    private fun forbiddenReferences(text: String, forbidden: List<String>): List<String> {
        val pattern = Regex("""\bch\.nokillswit\.(${forbidden.joinToString("|")})\b""")
        return text.lineSequence().withIndex()
            .filter { (_, line) -> !isComment(line) && pattern.containsMatchIn(line) }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }
            .toList()
    }

    @Test
    fun `no package outside telemetry references telemetry`() {
        // telemetry/ is the top layer (an observer of every lower one): it reaches `plugins` and `infra/db` through attribute
        // keys, so those seams never name it. The scan must see the package, or an empty result proves nothing.
        assertTrue(mainSources.any { packageOf(it.readText()) == "ch.nokillswit.telemetry" }, "no source declares ch.nokillswit.telemetry")
        val violations = mainSources.flatMap { file ->
            val text = file.readText()
            if (packageOf(text) == "ch.nokillswit.telemetry") {
                emptyList()
            } else {
                forbiddenReferences(text, listOf("telemetry")).map { "${file.name} $it" }
            }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `no package outside live references live`() {
        // live/ is a top layer like telemetry/ (the socket channel observes the chat domain, never the reverse): the chat writes reach it
        // only through infra/notify (a NOTIFY on the wire), and application.yaml names its module by string. The scan must see the
        // package, or an empty result proves nothing.
        assertTrue(mainSources.any { packageOf(it.readText()) == "ch.nokillswit.live" }, "no source declares ch.nokillswit.live")
        val violations = mainSources.flatMap { file ->
            val text = file.readText()
            if (packageOf(text) == "ch.nokillswit.live") {
                emptyList()
            } else {
                forbiddenReferences(text, listOf("live")).map { "${file.name} $it" }
            }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `no package outside notifications references notifications but the composition root`() {
        // notifications/ is a top layer above threads/ (it implements the `ThreadNotices` port threads/ declares and reads threads/,
        // rooms/ and users/): the chat reaches it only through that port, `infra/db/ChatWiring.kt` constructs it, and application.yaml
        // names its modules by string. (One exception is SQL text, not a reference: the boot purge `threads/StaleDerivedRows.kt` deletes
        // from the `notifications` table for messages a 0.5.x rollback deleted behind the port's back; `persistence.md` names it.) The
        // scan must see the package, or an empty result proves nothing.
        val declared = mainSources.any { packageOf(it.readText()) == "ch.nokillswit.notifications" }
        assertTrue(declared, "no source declares ch.nokillswit.notifications")
        val violations = mainSources.flatMap { file ->
            val text = file.readText()
            if (packageOf(text) == "ch.nokillswit.notifications" || file.relativePath() in notificationsWiring) {
                emptyList()
            } else {
                forbiddenReferences(text, listOf("notifications")).map { "${file.name} $it" }
            }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `threads and rooms never reference notifications`() {
        // The chat tells the notification feature what happened through `ThreadNotices` and imports nothing of it (the `WorkflowUsage`
        // precedent, mirrored): the dependency points one way, notifications -> threads -> rooms.
        val chat = mainSources.filter { packageOf(it.readText()) in setOf("ch.nokillswit.threads", "ch.nokillswit.rooms") }
        assertTrue(chat.size >= 20, "the scan saw only ${chat.size} chat sources")
        val violations = chat.flatMap { file ->
            forbiddenReferences(file.readText(), listOf("notifications")).map { "${file.name} $it" }
        }
        assertEquals(emptyList(), violations)
    }

    private fun infraSources(): List<Pair<File, String>> = mainSources.mapNotNull { file ->
        val text = file.readText()
        if (packageOf(text)?.startsWith("ch.nokillswit.infra") == true) file to text else null
    }

    @Test
    fun `infra never references a feature package outside the composition root`() {
        val infra = infraSources()
        assertTrue(infra.size > 20, "the scan saw only ${infra.size} infra sources")
        val violations = infra.flatMap { (file, text) ->
            val allowed = infraCompositionRoot[file.relativePath()].orEmpty()
            forbiddenReferences(text, featurePackages - allowed).map { "${file.name} $it" }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `workflows never references rooms or threads`() {
        // rooms/ imports workflows/ (the template read and the shared rules kernel), never the reverse: the workflow vocabulary and
        // the template registry stay usable without the chat domain. The scan must see both packages, or an empty result proves nothing.
        assertTrue(mainSources.any { packageOf(it.readText()) == "ch.nokillswit.rooms" }, "no source declares ch.nokillswit.rooms")
        val workflows = mainSources.filter { packageOf(it.readText()) == "ch.nokillswit.workflows" }
        assertTrue(workflows.size >= 4, "the scan saw only ${workflows.size} workflows sources")
        val violations = workflows.flatMap { file ->
            forbiddenReferences(file.readText(), listOf("rooms", "threads")).map { "${file.name} $it" }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `rooms never references threads`() {
        // threads/ imports rooms/ (the guards' standing, the room lock, the workflow snapshot), never the reverse: the one way back is
        // the `WorkflowUsage` port rooms declares and threads implements. The scan must see both packages, or an empty result proves
        // nothing.
        assertTrue(mainSources.any { packageOf(it.readText()) == "ch.nokillswit.threads" }, "no source declares ch.nokillswit.threads")
        val rooms = mainSources.filter { packageOf(it.readText()) == "ch.nokillswit.rooms" }
        assertTrue(rooms.size >= 8, "the scan saw only ${rooms.size} rooms sources")
        val violations = rooms.flatMap { file ->
            forbiddenReferences(file.readText(), listOf("threads")).map { "${file.name} $it" }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `authz references no feature package but users`() {
        // authz is the guard layer every feature imports: it may know the user roles and feature flags (`users`: the caller principal
        // carries them) and nothing else. In particular it must not name `rooms`, which imports authz: the room guards take plain
        // values (`RoomStanding`, declared in authz) precisely so this stays one-way.
        val authz = mainSources.filter { packageOf(it.readText()) == "ch.nokillswit.authz" }
        assertTrue(authz.size >= 4, "the scan saw only ${authz.size} authz sources")
        val violations = authz.flatMap { file ->
            forbiddenReferences(file.readText(), featurePackages - setOf("authz", "users")).map { "${file.name} $it" }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `the foundation never references a chat package outside the composition root`() {
        // users, teams, auth, authz, audit, plugins and infra are Flow's generic core (0.1.0): the chat domain is built on them and they
        // name none of it. The only door is the composition root, which must construct the chat services it publishes.
        val foundation = setOf("users", "teams", "auth", "authz", "audit", "plugins", "infra")
        val scanned = mainSources.filter { file ->
            val pkg = packageOf(file.readText())?.removePrefix("ch.nokillswit.")
            pkg != null && foundation.contains(pkg.substringBefore('.'))
        }
        assertTrue(scanned.size > 60, "the scan saw only ${scanned.size} foundation sources")
        val violations = scanned.flatMap { file ->
            val allowed = infraCompositionRoot[file.relativePath()].orEmpty()
            forbiddenReferences(file.readText(), chatPackages - allowed).map { "${file.relativePath()} $it" }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `every composition-root exception is still used`() {
        val infra = infraSources().associate { (file, text) -> file.relativePath() to text }
        for ((name, allowed) in infraCompositionRoot) {
            val text = infra[name] ?: error("composition-root file $name is not under infra/")
            for (pkg in allowed) {
                assertTrue(forbiddenReferences(text, listOf(pkg)).isNotEmpty(), "$name no longer references $pkg: drop the exception")
            }
        }
    }

    @Test
    fun `the scan flags imports, wildcard imports and inline references but not comments`() {
        val source = """
            package ch.nokillswit.infra

            import ch.nokillswit.teams.TeamService
            import ch.nokillswit.users.*
            // import ch.nokillswit.teams.InComment
             * ch.nokillswit.users.InKdoc
            val x = ch.nokillswit.teams.Other()
            import ch.nokillswit.teamsx.NotThePackage
        """.trimIndent()
        assertEquals(
            listOf(
                "3: import ch.nokillswit.teams.TeamService",
                "4: import ch.nokillswit.users.*",
                "7: val x = ch.nokillswit.teams.Other()",
            ),
            forbiddenReferences(source, listOf("teams", "users")),
        )
    }
}
