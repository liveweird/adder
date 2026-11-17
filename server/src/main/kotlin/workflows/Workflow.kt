package ch.nokillswit.workflows

/**
 * The workflow vocabulary shared by the ADMIN template registry and a room's own copy (`chat-model.md`): the wire enums
 * and the limits. A workflow is an ordered list of statuses and an ordered list of typed fields; `WorkflowRules.kt` is the
 * pure kernel that enforces the shape. The Kotlin enums ARE the whitelist: no CHECK constraint backs them (the V5 rule).
 */

/** The semantic colour vocabulary a status may carry (UPPER_CASE on the wire; the SPA lowercases it to the Mantine hue). */
enum class StatusColour { GRAY, RED, ORANGE, BLUE, GRAPE, TEAL }

/** A status in a CLOSED category closes its thread: no further activity until it is reopened. */
enum class StatusCategory { OPEN, CLOSED }

/** The type of a custom field. Only the select kinds carry options. */
enum class FieldType(val takesOptions: Boolean = false) {
    TEXT,
    NUMBER,
    DATE,
    SELECT(takesOptions = true),
    MULTI_SELECT(takesOptions = true),
    USER,
    URL,
}

const val MIN_STATUSES = 2
const val MAX_STATUSES = 20
const val MAX_FIELDS = 20
const val MIN_OPTIONS = 1
const val MAX_OPTIONS = 50

/** A key is an immutable slug: at most this many characters matching [KEY_GRAMMAR]. */
const val MAX_KEY_LENGTH = 40
const val MAX_LABEL_LENGTH = 60

/** Keys are reserved for the life of a room, retired ones included, so a room's key set is bounded separately. */
const val MAX_STATUS_KEYS_PER_ROOM = 200
const val MAX_FIELD_KEYS_PER_ROOM = 200

/** Lower-case slug: starts with a letter, then letters/digits, single hyphens between groups (`in-progress`, `s1`). */
val KEY_GRAMMAR = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
