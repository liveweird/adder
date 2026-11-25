package ch.nokillswit

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the Flyway checksum of every migration file. An APPLIED migration's bytes may never
 * change — comments included: Flyway validates stored checksums against the files at startup
 * (`validateOnMigrate`, the default), so any edit to an already-applied migration makes every
 * existing database refuse to boot with a checksum mismatch. This gate turns that silent
 * production-only failure into a red test: editing a listed file fails here, and the fix is
 * to revert the edit (a comment clarification belongs in `.claude/docs/persistence.md`), never
 * to update the pinned value. The one exception is a migration no RELEASED version has applied yet (`persistence.md` "unreleased
 * migration"): it may change and be re-pinned in the same commit. Adding a NEW migration adds one line — compute it with the same
 * algorithm below, or read it from `flyway_schema_history` after the first local run.
 */
class MigrationChecksumTest {
    private val pinned = mapOf(
        "V1__init.sql" to -1123925117,
        "V2__create_revoked_tokens.sql" to 794001362,
        "V3__seed_admin.sql" to 1706132310,
        "V4__enable_unaccent_extension.sql" to 242547752,
        "V5__user_disabled_features.sql" to -466290471,
        "V6__create_teams.sql" to -2104258111,
        "V7__user_credential_revision.sql" to 289120118,
        "V8__password_reset_tokens.sql" to 1662294880,
        "V9__create_ops_telemetry.sql" to 739000151,
        "V10__create_team_members.sql" to -539116013,
        "V11__create_workflow_templates.sql" to -1579402334,
        "V12__seed_workflow_templates.sql" to -1012377143,
        "V13__create_rooms.sql" to -108017395,
        "V14__create_threads.sql" to 35270631,
        "V15__create_message_tags.sql" to -717285948,
        "V16__create_search.sql" to 1149414218,
        "V17__create_message_mentions.sql" to -1206880786,
        "V18__create_notifications.sql" to 366113831,
        "V19__create_read_state.sql" to -328121061,
        "V20__create_message_reactions.sql" to 412426361,
        "V21__create_room_pins.sql" to 311388058,
        "V22__create_notification_email.sql" to -155169524,
        "V23__scrub_history_values.sql" to -61957710,
        "V24__create_notification_email_budget.sql" to -1359655116,
        "V25__add_team_handles.sql" to 178172747,
        "V26__create_thread_watches.sql" to -1388442755,
        "V27__reshape_notifications.sql" to 1940159936,
        "V28__notification_preference_choices.sql" to 1260716694,
        "V29__notification_broadcast_budget.sql" to 164448559,
        "V30__create_message_group_mentions.sql" to -219235358,
        "V31__index_message_tags_by_thread.sql" to -861247924,
        "V32__create_tags.sql" to 1247201663,
        "V33__create_thread_tags.sql" to 975866380,
        "V34__index_notifications_unseen_by_room.sql" to 48846012,
        "V35__add_message_quotes.sql" to 2028848017,
        "V36__add_room_access.sql" to 508947737,
        "V37__create_user_room_organizations.sql" to -848673763,
        "V38__room_membership_notices.sql" to 1292978383,
        "V39__index_grouped_reply_notifications.sql" to 1972208925,
        "V40__remember_thread_unwatch.sql" to 1233017481,
        "V41__link_workflow_notices_to_events.sql" to -2093159295,
        "V42__create_thread_mutes.sql" to 944899791,
        "V43__create_message_here_mentions.sql" to 1864521402,
    )

    @Test
    fun `every migration file matches its applied Flyway checksum`() {
        val dir = Path.of("src/main/resources/db/migration")
        val files = dir.listDirectoryEntries("*.sql").associate { it.name to flywayChecksum(it) }
        assertEquals(
            pinned.keys.sorted(),
            files.keys.sorted(),
            "Migration files and the pinned manifest must list the same set — add the new file's checksum here",
        )
        for ((name, expected) in pinned) {
            assertEquals(
                expected,
                files.getValue(name),
                "$name changed after being applied — revert the edit; existing databases would fail Flyway validation",
            )
        }
    }

    // Flyway's algorithm: CRC32 over each line's UTF-8 bytes (terminators excluded), BOM stripped.
    private fun flywayChecksum(file: Path): Int {
        val crc = CRC32()
        Files.readString(file).removePrefix("﻿").lineSequence().forEach { crc.update(it.toByteArray()) }
        return crc.value.toInt()
    }
}
