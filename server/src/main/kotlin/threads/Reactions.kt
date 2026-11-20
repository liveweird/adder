package ch.nokillswit.threads

import io.ktor.server.plugins.BadRequestException
import java.text.BreakIterator
import java.util.Locale

/** The most distinct emoji one message carries. */
const val MAX_DISTINCT_REACTIONS = 20

/** The most emoji one person puts on one message. */
const val MAX_REACTIONS_PER_PERSON = 5

/**
 * The longest accepted emoji in UTF-16 units. The `emoji` column holds 32 CHARACTERS (code points), so a value of at most 32 units, which
 * has at most 32 code points, always fits (the code point bound below is much tighter).
 */
const val MAX_REACTION_UNITS = 32

/** The most code points of one accepted emoji (a family of four joined by zero-width joiners is 7). */
const val MAX_REACTION_CODE_POINTS = 10

private const val ZERO_WIDTH_JOINER = 0x200D
private const val VARIATION_SELECTOR_16 = 0xFE0F
private const val COMBINING_KEYCAP = 0x20E3
private const val ASCII_LIMIT = 0x80
private const val BLACK_FLAG = 0x1F3F4
private const val CANCEL_TAG = 0xE007F
private const val TAG_FIRST = 0xE0020
private const val TAG_LAST = 0xE007E
private const val TAG_TO_ASCII = TAG_FIRST - 0x20
private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

/** The only tag sequences the rule admits: England, Scotland and Wales, the three flags Unicode defines. A tag can spell anything else. */
private val TAG_FLAGS = setOf("gbeng", "gbsct", "gbwls")

/**
 * Whether [value] is ONE emoji in its one fully qualified spelling (`chat-model.md` "Reactions (V20)"; pure, no dependency, the JDK's own
 * Unicode data). Values are stored as sent and compared by bytes, so a rule that checked which code points occur, and not how they are
 * arranged, would let one emoji be stored as many look-alike values (a thumbs up with a selector, with a trailing joiner, with four skin
 * tones) and let four people fill a message's 20 places with copies. So the STRUCTURE is checked:
 *
 * 1. 1 to [MAX_REACTION_UNITS] UTF-16 units and at most [MAX_REACTION_CODE_POINTS] code points, and exactly one extended grapheme cluster
 *    ([BreakIterator.getCharacterInstance]);
 * 2. then exactly one of: a flag (a PAIR of regional indicators); a keycap (`0-9`, `#` or `*`, an optional variation selector, the
 *    combining keycap, last); one of the three tag flags ([TAG_FLAGS]: a black flag, its tag characters, the cancel tag); or one or
 *    more emoji ELEMENTS joined by single zero-width joiners (never first, last or doubled);
 * 3. an element is an emoji that is not ASCII, not a skin-tone modifier and not a regional indicator, followed by nothing, or by ONE skin
 *    tone (only after an emoji modifier base), or by the variation selector once (only after an emoji that has no emoji presentation of
 *    its own, where it is required, so a heart needs it and a thumbs up refuses it). A skin tone and a selector never meet.
 *
 * What it cannot check is membership of the official emoji list: a chain of up to five pictographs joined by zero-width joiners is one
 * valid arrangement and passes. A lone skin tone, a lone regional indicator and a lone joiner or selector are refused.
 */
fun isSingleEmoji(value: String): Boolean {
    if (value.isEmpty() || value.length > MAX_REACTION_UNITS) return false
    val codePoints = value.codePoints().toArray()
    if (codePoints.size > MAX_REACTION_CODE_POINTS) return false
    val clusters = BreakIterator.getCharacterInstance(Locale.ROOT).also { it.setText(value) }
    if (clusters.next() != value.length) return false
    return isFlag(codePoints) || isKeycap(codePoints) || isTagFlag(codePoints) || isJoinedElements(codePoints)
}

private fun isFlag(cps: IntArray): Boolean = cps.size == 2 && cps.all { it in REGIONAL_INDICATORS }

private fun isKeycap(cps: IntArray): Boolean {
    val short = cps.size == 2
    if (!short && !(cps.size == 3 && cps[1] == VARIATION_SELECTOR_16)) return false
    val base = cps[0]
    return (base in '0'.code..'9'.code || base == '#'.code || base == '*'.code) && cps.last() == COMBINING_KEYCAP
}

private fun isTagFlag(cps: IntArray): Boolean {
    if (cps.size < 3 || cps.first() != BLACK_FLAG || cps.last() != CANCEL_TAG) return false
    val tags = cps.slice(1 until cps.size - 1)
    if (tags.any { it !in TAG_FIRST..TAG_LAST }) return false
    return tags.joinToString("") { (it - TAG_TO_ASCII).toChar().toString() } in TAG_FLAGS
}

private fun isJoinedElements(cps: IntArray): Boolean {
    var index = 0
    while (true) {
        index = endOfElement(cps, index)
        if (index < 0) return false
        if (index == cps.size) return true
        if (cps[index] != ZERO_WIDTH_JOINER) return false
        index++ // an element MUST follow: a trailing joiner finds none
    }
}

/** The index after the emoji element starting at [start], or -1 when none starts there (see [isSingleEmoji] for the shape). */
private fun endOfElement(cps: IntArray, start: Int): Int {
    if (start >= cps.size) return -1
    val base = cps[start]
    if (base < ASCII_LIMIT || !Character.isEmoji(base) || Character.isEmojiModifier(base) || base in REGIONAL_INDICATORS) return -1
    val next = cps.getOrNull(start + 1)
    return when {
        next == VARIATION_SELECTOR_16 -> if (Character.isEmojiPresentation(base)) -1 else start + 2
        next != null && Character.isEmojiModifier(next) -> if (Character.isEmojiModifierBase(base)) start + 2 else -1
        else -> if (Character.isEmojiPresentation(base)) start + 1 else -1
    }
}

/** A `400` with a FIXED detail unless [value] is one emoji: the answer never echoes what was sent (chosen text, `security.md`). */
fun requireReactionEmoji(value: String) {
    if (!isSingleEmoji(value)) throw BadRequestException("emoji must be a single emoji")
}
