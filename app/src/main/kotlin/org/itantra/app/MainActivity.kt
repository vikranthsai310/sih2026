package org.itantra.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.itantra.link.LinkState
import org.itantra.link.RfcommLink
import org.itantra.proto.Flags
import org.itantra.proto.Frame
import org.itantra.proto.Language
import org.itantra.proto.MessageType
import org.itantra.proto.ScriptPacker

/**
 * Week 2 bring-up screen.
 *
 * This is deliberately not the operating screen from `docs/WIREFRAMES.md`. It exists
 * to close **gate W2**: *typed text on device A appears on device B over RFCOMM*, with
 * no recogniser and no models involved. Task W2.31 says the text field is temporary and
 * W3.12 deletes it, once speech replaces typing.
 *
 * What it does show, because they are the figures the whole project rests on, is the
 * packed byte count and the compression ratio for every message sent.
 */
class MainActivity : ComponentActivity() {
    private var adapter: BluetoothAdapter? = null
    private var link: RfcommLink? = null

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        permissions.launch(requiredPermissions())

        setContent { BringUpScreen() }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.RECORD_AUDIO,
            )
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

    @Composable
    private fun BringUpScreen() {
        var typed by remember { mutableStateOf("") }
        val log = remember { mutableStateListOf<String>() }
        var linkState by remember { mutableStateOf(LinkState.IDLE) }
        var sentBytes by remember { mutableStateOf(0L) }

        Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "iTantra — bring-up",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.Black,
                )
                Text(
                    "Gate W2: typed text across two phones over Bluetooth",
                    fontSize = 12.sp,
                    color = Color.DarkGray,
                )
                Spacer(Modifier.height(12.dp))

                Text("Link: $linkState", fontFamily = FontFamily.Monospace, color = Color.Black)
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        lifecycleScope.launch {
                            startLink(RfcommLink.Role.HOST, null) { linkState = it } .also { }
                        }
                    }) { Text("Listen") }

                    Button(onClick = {
                        lifecycleScope.launch {
                            val bonded = adapter?.bondedDevices?.firstOrNull()
                            if (bonded == null) {
                                log.add(0, "no bonded device — pair the phones in Settings first")
                            } else {
                                startLink(RfcommLink.Role.CLIENT, bonded) { linkState = it }
                                log.add(0, "dialling ${bonded.name}")
                            }
                        }
                    }) { Text("Connect") }
                }

                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Text to send") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                Button(
                    enabled = typed.isNotBlank(),
                    onClick = {
                        val frame = buildFrame(typed)
                        val wire = frame.encode()
                        sentBytes = wire.size.toLong()
                        log.add(
                            0,
                            "sent  ${wire.size} B  (${"%.0f".format(96_000.0 / wire.size)}x vs audio)  $typed",
                        )
                        lifecycleScope.launch { link?.send(wire) }
                        typed = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("SEND") }

                if (sentBytes > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "last frame $sentBytes B · 3 s of audio would be 96 000 B",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color.DarkGray,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Divider()
                LazyColumn {
                    items(log) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color.Black,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // Receive path: decode, unpack, show.
        androidx.compose.runtime.LaunchedEffect(link) {
            link?.incoming?.collect { wire ->
                val frame = Frame.decode(wire).orNull() ?: return@collect
                val text =
                    if (frame.isPacked) {
                        ScriptPacker.unpack(frame.payload, frame.language)
                    } else {
                        String(frame.payload, Charsets.UTF_8)
                    }
                log.add(0, "recv  ${frame.wireSize} B  $text")
            }
        }
    }

    /** Packs when it helps, and clears `PACKED` when it does not. */
    private fun buildFrame(text: String): Frame {
        val language = Language.HINDI
        val packed = ScriptPacker.isWorthPacking(text, language)
        val payload =
            if (packed) ScriptPacker.pack(text, language) else text.toByteArray(Charsets.UTF_8)
        return Frame(
            type = MessageType.TEXT,
            language = language,
            seq = nextSeq++,
            flags = Flags.FINAL or (if (packed) Flags.PACKED else 0) or 2,
            src = 2,
            keyId = 7,
            ttl = 3,
            payload = payload,
        )
    }

    private suspend fun startLink(
        role: RfcommLink.Role,
        target: BluetoothDevice?,
        onState: (LinkState) -> Unit,
    ) {
        val a = adapter ?: return
        link?.disconnect()
        val fresh = RfcommLink(a, role, target, lifecycleScope)
        link = fresh
        lifecycleScope.launch { fresh.state.collect(onState) }
        fresh.connect()
    }

    private var nextSeq = 0

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch { link?.disconnect() }
    }
}

private fun <T> mutableStateListOf() = androidx.compose.runtime.mutableStateListOf<T>()
