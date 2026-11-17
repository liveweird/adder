package ch.nokillswit

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.validation.requireNoDuplicates
import ch.nokillswit.workflows.FieldShape
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.MAX_FIELDS
import ch.nokillswit.workflows.MAX_FIELD_KEYS_PER_ROOM
import ch.nokillswit.workflows.MAX_KEY_LENGTH
import ch.nokillswit.workflows.MAX_LABEL_LENGTH
import ch.nokillswit.workflows.MAX_OPTIONS
import ch.nokillswit.workflows.MAX_STATUSES
import ch.nokillswit.workflows.MAX_STATUS_KEYS_PER_ROOM
import ch.nokillswit.workflows.MIN_OPTIONS
import ch.nokillswit.workflows.MIN_STATUSES
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusShape
import ch.nokillswit.workflows.requireKey
import ch.nokillswit.workflows.requireKeyBudget
import ch.nokillswit.workflows.requireLabel
import ch.nokillswit.workflows.validateWorkflowShape
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pure workflow rules kernel (`workflows/WorkflowRules.kt`): every rule, both sides of every limit. No server, no database. */
class WorkflowRulesTest {
    private fun status(key: String, category: StatusCategory = StatusCategory.OPEN, initial: Boolean = false) =
        StatusShape(key, category, initial)

    /** `count` statuses `s1..sN`: the first initial and OPEN, the last CLOSED (so every structural rule holds). */
    private fun statuses(count: Int): List<StatusShape> = (1..count).map {
        status("s$it", if (it == count) StatusCategory.CLOSED else StatusCategory.OPEN, initial = it == 1)
    }

    private val minimalStatuses = statuses(MIN_STATUSES)

    private fun fields(count: Int): List<FieldShape> = (1..count).map { FieldShape("f$it", FieldType.TEXT) }

    private fun select(options: Int, type: FieldType = FieldType.SELECT, key: String = "severity") =
        FieldShape(key, type, (1..options).map { "o$it" })

    private fun assertRejected(part: String, statuses: List<StatusShape> = minimalStatuses, fields: List<FieldShape> = emptyList()) {
        val failure = assertFailsWith<BadRequestException> { validateWorkflowShape(statuses, fields) }
        assertTrue(part in failure.message.orEmpty(), "expected '$part' in: ${failure.message}")
    }

    @Test
    fun `a minimal workflow passes`() = validateWorkflowShape(minimalStatuses, emptyList())

    @Test
    fun `the status count is 2 to 20, both edges accepted and one past each rejected`() {
        validateWorkflowShape(statuses(MIN_STATUSES), emptyList())
        validateWorkflowShape(statuses(MAX_STATUSES), emptyList())
        assertRejected("2-20 statuses", statuses = listOf(status("only", StatusCategory.CLOSED, initial = true)))
        assertRejected("2-20 statuses", statuses = emptyList())
        assertRejected("2-20 statuses", statuses = statuses(MAX_STATUSES + 1))
    }

    @Test
    fun `the field count is 0 to 20`() {
        validateWorkflowShape(minimalStatuses, fields(0))
        validateWorkflowShape(minimalStatuses, fields(MAX_FIELDS))
        assertRejected("0-20 fields", fields = fields(MAX_FIELDS + 1))
    }

    @Test
    fun `exactly one status is initial`() {
        val none = listOf(status("a"), status("b", StatusCategory.CLOSED))
        val two = listOf(status("a", initial = true), status("b", initial = true), status("c", StatusCategory.CLOSED))
        assertRejected("Exactly one status must be the initial one, got 0", statuses = none)
        assertRejected("Exactly one status must be the initial one, got 2", statuses = two)
    }

    @Test
    fun `the initial status is OPEN`() {
        val closedInitial = listOf(status("a", StatusCategory.CLOSED, initial = true), status("b", StatusCategory.CLOSED))
        assertRejected("initial status must be in the OPEN category", statuses = closedInitial)
    }

    @Test
    fun `at least one status is CLOSED, more than one is fine`() {
        assertRejected("At least one status must be in the CLOSED category", statuses = listOf(status("a", initial = true), status("b")))
        val twoClosed = listOf(status("a", initial = true), status("b", StatusCategory.CLOSED), status("c", StatusCategory.CLOSED))
        validateWorkflowShape(twoClosed, emptyList())
    }

    @Test
    fun `status keys, field keys and option keys are each unique`() {
        val dupStatuses = listOf(status("a", initial = true), status("a", StatusCategory.CLOSED))
        assertRejected("Status keys must be unique", statuses = dupStatuses)
        assertRejected("Field keys must be unique", fields = listOf(FieldShape("x", FieldType.TEXT), FieldShape("x", FieldType.URL)))
        val dupOptions = FieldShape("severity", FieldType.SELECT, listOf("s1", "s1"))
        assertRejected("Option keys of field 'severity' must be unique", fields = listOf(dupOptions))
    }

    @Test
    fun `a status and a field may share a key, uniqueness is per kind`() {
        validateWorkflowShape(minimalStatuses, listOf(FieldShape("s1", FieldType.TEXT)))
    }

    @Test
    fun `a select kind needs 1 to 50 options, both edges accepted`() {
        for (type in listOf(FieldType.SELECT, FieldType.MULTI_SELECT)) {
            validateWorkflowShape(minimalStatuses, listOf(select(MIN_OPTIONS, type)))
            validateWorkflowShape(minimalStatuses, listOf(select(MAX_OPTIONS, type)))
            assertRejected("needs 1-50 options, got 0", fields = listOf(select(0, type)))
            assertRejected("needs 1-50 options, got ${MAX_OPTIONS + 1}", fields = listOf(select(MAX_OPTIONS + 1, type)))
        }
    }

    @Test
    fun `only the select kinds carry options`() {
        for (type in FieldType.entries) {
            val withOptions = FieldShape("f", type, listOf("a"))
            if (type.takesOptions) {
                validateWorkflowShape(minimalStatuses, listOf(withOptions))
            } else {
                assertRejected("cannot have options", fields = listOf(withOptions))
                validateWorkflowShape(minimalStatuses, listOf(FieldShape("f", type)))
            }
        }
        assertEquals(setOf(FieldType.SELECT, FieldType.MULTI_SELECT), FieldType.entries.filter { it.takesOptions }.toSet())
    }

    @Test
    fun `a key is a slug of at most 40 characters, both sides of the length`() {
        requireKey("a".repeat(MAX_KEY_LENGTH), "Key")
        assertFailsWith<BadRequestException> { requireKey("a".repeat(MAX_KEY_LENGTH + 1), "Key") }
        assertFailsWith<BadRequestException> { requireKey("", "Key") }
    }

    @Test
    fun `the key grammar accepts slugs and rejects everything else`() {
        for (good in listOf("a", "open", "in-progress", "s1", "jira-link", "a1-b2-c3", "x9")) requireKey(good, "Key")
        val bad = listOf(
            "Open", "OPEN", "1st", "-open", "open-", "in--progress", "in_progress", "in progress", "in.progress", " open", "open ",
            "open\n", "ścieżka", "a/b", "9", "-", "a-",
        )
        for (key in bad) assertFailsWith<BadRequestException>("'$key'") { requireKey(key, "Key") }
    }

    @Test
    fun `a bad key anywhere in the document is rejected with its place named`() {
        assertRejected("Status key 'Bad'", statuses = listOf(status("Bad", initial = true), status("b", StatusCategory.CLOSED)))
        assertRejected("Field key 'Bad'", fields = listOf(FieldShape("Bad", FieldType.TEXT)))
        assertRejected("Option key 'Bad' of field 'severity'", fields = listOf(FieldShape("severity", FieldType.SELECT, listOf("Bad"))))
    }

    @Test
    fun `an overlong rejected key is echoed truncated`() {
        val huge = listOf(FieldShape("X".repeat(5000), FieldType.TEXT))
        val failure = assertFailsWith<BadRequestException> { validateWorkflowShape(minimalStatuses, huge) }
        assertTrue(failure.message.orEmpty().length < 400, "message length ${failure.message?.length}")
    }

    @Test
    fun `a label is 1 to 60 characters and not blank`() {
        requireLabel("a", "Label")
        requireLabel("a".repeat(MAX_LABEL_LENGTH), "Label")
        assertFailsWith<BadRequestException> { requireLabel("a".repeat(MAX_LABEL_LENGTH + 1), "Label") }
        assertFailsWith<BadRequestException> { requireLabel("", "Label") }
        assertFailsWith<BadRequestException> { requireLabel("   ", "Label") }
    }

    @Test
    fun `a label counts code points, so 60 emoji pass and 61 fail`() {
        requireLabel("\uD83D\uDE00".repeat(MAX_LABEL_LENGTH), "Label")
        assertFailsWith<BadRequestException> { requireLabel("\uD83D\uDE00".repeat(MAX_LABEL_LENGTH + 1), "Label") }
    }

    @Test
    fun `a rejected key is echoed by code point with an ellipsis only when truncated`() {
        fun message(key: String) =
            assertFailsWith<BadRequestException> { validateWorkflowShape(minimalStatuses, listOf(FieldShape(key, FieldType.TEXT))) }
                .message.orEmpty()
        val emoji = "\uD83D\uDE00"
        val truncated = message(emoji.repeat(MAX_KEY_LENGTH + 10))
        assertTrue("Field key '${emoji.repeat(MAX_KEY_LENGTH)}\u2026'" in truncated, truncated)
        val exact = message("A".repeat(MAX_KEY_LENGTH))
        assertTrue("Field key '${"A".repeat(MAX_KEY_LENGTH)}'" in exact && "\u2026" !in exact, exact)
    }

    @Test
    fun `the key budget counts the union of retired and incoming keys, 200 is the edge`() {
        fun keys(range: IntRange) = range.map { "k$it" }
        // 150 stored (live or retired) + 50 new = exactly the cap; the same keys re-sent revive rather than add.
        requireKeyBudget(keys(1..150).toSet(), keys(101..200), MAX_STATUS_KEYS_PER_ROOM, "status")
        requireKeyBudget(keys(1..MAX_FIELD_KEYS_PER_ROOM).toSet(), keys(1..MAX_FIELD_KEYS_PER_ROOM), MAX_FIELD_KEYS_PER_ROOM, "field")
        val over = assertFailsWith<ConflictException> {
            requireKeyBudget(keys(1..150).toSet(), keys(101..201), MAX_STATUS_KEYS_PER_ROOM, "status")
        }
        assertTrue("at most 200 status keys" in over.message.orEmpty() && "make 201" in over.message.orEmpty(), over.message)
        assertEquals("about:blank", over.problemType)
    }

    @Test
    fun `requireNoDuplicates rejects a repeat with the callers message and honours a fold`() {
        requireNoDuplicates(listOf("a", "b"), "dup")
        requireNoDuplicates(emptyList(), "dup")
        requireNoDuplicates(listOf("a", "A"), "dup")
        assertEquals("dup", assertFailsWith<BadRequestException> { requireNoDuplicates(listOf("a", "a"), "dup") }.message)
        assertFailsWith<BadRequestException> { requireNoDuplicates(listOf("a", "A"), "dup", fold = String::lowercase) }
    }
}
