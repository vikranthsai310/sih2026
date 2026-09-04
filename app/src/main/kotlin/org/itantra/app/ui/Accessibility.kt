package org.itantra.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * The two accessibility modifiers this interface needs. Task **W7.23**.
 *
 * ## Why `spokenAs` clears rather than adds
 *
 * `Modifier.semantics { contentDescription = ... }` on a container **merges** with its
 * children, so a row that sets a description and holds three `Text`s is announced as the
 * description *and then* the three texts. On the message log that means an operator hears
 * the message, then hears "check mark check mark delivered, 13 B" as loose fragments after
 * it. [spokenAs] uses `clearAndSetSemantics`, which replaces the subtree — one row, one
 * sentence, one swipe.
 *
 * The cost is that anything genuinely interactive inside the subtree becomes unreachable,
 * so a row with its own control keeps the control outside the cleared region. That is why
 * the replay button in [MessageLogScreen] is a sibling of the described block rather than
 * inside it.
 *
 * ## Text at 200 %
 *
 * The other half of W7.23 is not a modifier at all, it is a rule: **no fixed height on
 * anything containing text**. `Modifier.size(64.dp)` on a button looks identical to
 * `heightIn(min = 64.dp)` at the default font scale and truncates its label at 200 %. Every
 * touch target in this interface is specified as a minimum, and where a fixed size was
 * used it was a defect rather than a decision.
 *
 * ---
 *
 * Replaces the semantics of a subtree with one sentence.
 *
 * @param description what a screen reader says instead of the glyphs. Use [Spoken] to
 *   build it, so the words are asserted in a test rather than written twice.
 */
fun Modifier.spokenAs(description: String): Modifier = this.clearAndSetSemantics { contentDescription = description }

/**
 * Hides a purely decorative glyph from a screen reader.
 *
 * The alert screen's `⚠` and the template buttons' icons carry real information *visually*
 * — for an operator who cannot read the label under them, the icon is the label. To a
 * listener they carry none, and the words beneath them say the same thing better. An empty
 * description is how a screen reader is told to skip an element rather than guess at it.
 */
fun Modifier.decorative(): Modifier = this.semantics { contentDescription = "" }
