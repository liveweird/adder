package ch.nokillswit

import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.threads.StatusTransition
import ch.nokillswit.threads.statusTransition
import ch.nokillswit.workflows.StatusCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The pure status rules (`threads/ThreadStatusRules.kt`, D1 option 1): every category pair crossed with steward or not. */
class ThreadStatusRulesTest {
    private val open = StatusCategory.OPEN
    private val closed = StatusCategory.CLOSED

    @Test
    fun `a move between two open statuses is open to every reader`() {
        for (steward in listOf(false, true)) {
            assertEquals(StatusTransition.MOVED, statusTransition("a", open, "b", open, steward))
        }
    }

    @Test
    fun `closing, reopening and a closed to closed move need a steward`() {
        val cases = listOf(
            Triple(open, closed, StatusTransition.CLOSED),
            Triple(closed, open, StatusTransition.REOPENED),
            Triple(closed, closed, StatusTransition.MOVED),
        )
        for ((from, to, transition) in cases) {
            assertEquals(transition, statusTransition("a", from, "b", to, steward = true), "$from to $to by a steward")
            assertFailsWith<ForbiddenException>("$from to $to by a member") { statusTransition("a", from, "b", to, steward = false) }
        }
    }

    @Test
    fun `the same status again is no change, but a member still cannot touch a closed thread's status`() {
        assertNull(statusTransition("a", open, "a", open, steward = false))
        assertNull(statusTransition("a", open, "a", open, steward = true))
        assertNull(statusTransition("done", closed, "done", closed, steward = true))
        assertFailsWith<ForbiddenException> { statusTransition("done", closed, "done", closed, steward = false) }
    }
}
