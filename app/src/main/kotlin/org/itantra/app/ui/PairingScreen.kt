package org.itantra.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Color as AndroidColor

/**
 * Pairing. Task **W6.11**, `docs/WIREFRAMES.md` section 3.
 *
 * ## One screen, not two
 *
 * Every unit shows this same screen, always: its own code above, a camera below. Whoever
 * points the camera is the one who joins. There is no "create" and no "join", because
 * that choice was the thing operators got wrong — and because the roles are symmetric,
 * two handsets on a table pair without anyone deciding who is the host.
 *
 * ## `FLAG_SECURE` is not decoration
 *
 * The QR code on screen *is* the key; without the flag it lands in the recents thumbnail,
 * in any screenshot, and in a screen recording. This screen sets the flag **itself**
 * through [SecureWindow] rather than asking its host to remember — the audit for W8.10
 * found that nothing did, because the note was addressed to an activity that does not
 * exist yet.
 *
 * The code refreshes every 120 seconds, and the countdown is shown so an operator can see
 * it is about to change rather than discovering a scan failed.
 */
@Composable
fun PairingScreen(
    codeText: String,
    secondsRemaining: Long,
    paired: List<PairedUnit>,
    scanning: Boolean,
    onScanToggle: () -> Unit,
    onEnterManually: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The QR code below is the key, so FLAG_SECURE is applied here rather than left to
    // whoever hosts this screen. Task W8.10 item 2 — see [SecureWindow].
    SecureWindow()

    Column(modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Text("ADD A UNIT", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(16.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val bitmap = remember(codeText) { qrBitmap(codeText) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Pairing code",
                    modifier = Modifier.size(QR_DP.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Show this to the other unit",
            fontSize = 16.sp,
            color = Ink,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            // Counted down rather than left to expire silently: a scan that fails
            // because the code turned over looks like a broken app.
            "Code refreshes in ${secondsRemaining / 60}:${"%02d".format(secondsRemaining % 60)}",
            fontSize = 14.sp,
            color = Muted,
        )

        Spacer(Modifier.height(16.dp))
        Text("or", fontSize = 14.sp, color = Muted, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onScanToggle,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper),
        ) {
            Text(
                if (scanning) "◼  STOP SCANNING" else "▣  POINT AT ANOTHER UNIT",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("PAIRED", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Muted)
        LazyColumn(Modifier.weight(1f)) {
            items(paired) { unit ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(if (unit.online) "●" else "○", color = if (unit.online) Ok else Muted)
                    Text(unit.name, fontSize = 16.sp, color = Ink, modifier = Modifier.weight(1f))
                    Text("node %02d".format(unit.nodeId), fontSize = 14.sp, color = Muted)
                    Text(unit.lastSeen, fontSize = 14.sp, color = Muted)
                }
            }
        }

        // The fallback. A camera fails in rain, in gloves, and in the dark; a code that
        // can only be scanned is a pairing method with a single point of failure.
        Button(
            onClick = onEnterManually,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Paper, contentColor = Ink),
        ) {
            Text("ENTER CODE MANUALLY", fontSize = 16.sp)
        }
    }
}

data class PairedUnit(
    val name: String,
    val nodeId: Int,
    val lastSeen: String,
    val online: Boolean,
)

/**
 * The window flags the pairing activity must set.
 *
 * `FLAG_SECURE` keeps the code out of screenshots, screen recordings and the recents
 * thumbnail. The QR code *is* the key, so a recents thumbnail of this screen is a key
 * sitting in the launcher.
 */
val pairingWindowFlags: Int
    get() = android.view.WindowManager.LayoutParams.FLAG_SECURE

/**
 * Renders the code.
 *
 * @return null if the text cannot be encoded, which is treated as "show nothing" rather
 *   than crashing the screen — a unit that cannot display its code can still scan one.
 */
private fun qrBitmap(
    text: String,
    size: Int = 512,
): Bitmap? =
    runCatching {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }.getOrNull()

private val Ink = Color(0xFF101010)
private val Paper = Color(0xFFFFFFFF)
private val Muted = Color(0xFF5F5F5F)
private val Ok = Color(0xFF1B7F3B)
private const val QR_DP = 240
