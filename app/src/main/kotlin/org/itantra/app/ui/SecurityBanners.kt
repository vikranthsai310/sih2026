package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The two warnings that must never be silent. Tasks W6.14 and W6.15.
//
// Neither has a dismiss control, and that is the design rather than an omission. A banner
// an operator can dismiss is a banner an operator will dismiss, and both of these describe
// conditions where the system is doing something other than what its user believes.

/**
 * Task **W6.14**. Shown whenever frames are travelling unencrypted.
 *
 * ## Why there is no way to hide this
 *
 * An operator who believes a channel is encrypted, and speaks accordingly, is in a worse
 * position than one who knows it is not. The banner is red, permanent and undismissable,
 * and there is **no silent path** to this state — a unit cannot end up unencrypted
 * without the person holding it being told, continuously, for as long as it lasts.
 *
 * That is also why this is a banner rather than a one-time dialog: a dialog is
 * acknowledged once and forgotten, and the condition persists.
 */
@Composable
fun UnsecuredBanner(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Danger)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", fontSize = 22.sp, color = Color.White)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "UNSECURED",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                "Messages are not encrypted. Anyone in range can read them.",
                fontSize = 13.sp,
                color = Color.White,
            )
        }
    }
}

/**
 * Task **W6.15**, risk **S-06**. Shown when a peer's template profile digest differs.
 *
 * ## Why this is a safety defect and not a compatibility inconvenience
 *
 * Template alerts are one byte. The byte identifies a sentence in a table, and the
 * receiver speaks *its own* table's sentence for that byte. If two handsets hold
 * different tables, byte 4 might be "EVACUATE" on one and "ALL CLEAR" on the other — and
 * the receiving operator hears a fluent, confident instruction that is the opposite of
 * what was sent.
 *
 * Nothing about that failure looks like an error. There is no garbled audio, no
 * checksum failure, no warning. It is the most dangerous single defect the protocol can
 * produce, which is why template **sending is disabled** on a mismatch rather than merely
 * flagged: free text still works, and free text carries its own words.
 */
@Composable
fun TemplateMismatchBanner(
    peerName: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Warning)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", fontSize = 22.sp, color = Color.Black)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "TEMPLATE MISMATCH",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
            )
            Text(
                "$peerName has a different alert table. Template alerts are disabled; " +
                    "speak the message instead.",
                fontSize = 13.sp,
                color = Color.Black,
            )
        }
    }
}

/**
 * The banner stack, in severity order.
 *
 * Ordered rather than left to whichever condition happened to be detected first: an
 * operator glancing at the top of the screen should see the worst thing that is true.
 */
@Composable
fun SecurityBanners(
    unsecured: Boolean,
    templateMismatchWith: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (unsecured) {
            UnsecuredBanner()
            Spacer(Modifier.height(2.dp))
        }
        if (templateMismatchWith != null) {
            TemplateMismatchBanner(templateMismatchWith)
        }
    }
}

private val Danger = Color(0xFFB3261E)
private val Warning = Color(0xFFF2B705)
