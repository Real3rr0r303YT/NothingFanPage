package org.rombooter

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private const val BMC_URL = "https://buymeacoffee.com/3rr0r303YT"
private const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

@Composable
fun RomBooterApp(vm: BootViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    var distro by rememberSaveable { mutableStateOf(Distro.DEBIAN) }
    var session by rememberSaveable { mutableStateOf(Session.XFCE) }
    var showSupport by remember { mutableStateOf(false) }

    val dm = ctx.resources.displayMetrics
    val boot = { vm.boot(distro, session, dm.widthPixels, dm.heightPixels) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) boot()
    }

    val connected = state is BootState.Connected
    LaunchedEffect(connected, session) {
        (ctx as? Activity)?.requestedOrientation = when {
            !connected -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            session.portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT   // mobile shell
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            val c = vm.client
            if (connected && c != null) {
                VncScreen(c, onStop = { vm.stop(distro) }, onSupport = { showSupport = true })
            } else {
                BootScreen(
                    state = state, distro = distro, onPick = { distro = it },
                    session = session, onPickSession = { session = it },
                    onBoot = { askPermission.launch(RUN_COMMAND_PERMISSION) },
                    onSupport = { showSupport = true }
                )
            }
        }
        if (showSupport) SupportDialog { showSupport = false }
    }
}

@Composable
private fun BootScreen(
    state: BootState, distro: Distro, onPick: (Distro) -> Unit,
    session: Session, onPickSession: (Session) -> Unit,
    onBoot: () -> Unit, onSupport: () -> Unit
) {
    val busy = state is BootState.Launching || state is BootState.WaitingForPort || state is BootState.Connecting
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("RomBooter", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Distro.entries) { d ->
                FilterChip(selected = d == distro, enabled = !busy, onClick = { onPick(d) }, label = { Text(d.label) })
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Session.entries) { s ->
                FilterChip(selected = s == session, enabled = !busy, onClick = { onPickSession(s) }, label = { Text(s.label) })
            }
        }
        if (session.wayland) {
            Text(
                "Experimental: needs rombooter-setup.sh --de ${session.key}; wayvnc has no password, loopback only.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBoot, enabled = !busy) { Text(if (state is BootState.Failed) "Retry" else "Boot") }
        Spacer(Modifier.height(16.dp))
        when (state) {
            BootState.Launching -> Status("Starting ${distro.label} in Termux...")
            BootState.WaitingForPort -> Status("Waiting for desktop on 127.0.0.1:${TermuxLauncher.PORT}...")
            BootState.Connecting -> Status("Connecting to the desktop...")
            is BootState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onSupport) { Text("Support RomBooter") }
    }
}

@Composable
private fun Status(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text)
    }
}

@Composable
private fun VncScreen(client: RfbClient, onStop: () -> Unit, onSupport: () -> Unit) {
    var showKb by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        VncCanvas(client)
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(onClick = { client.key(RfbClient.KEY_ESC) }) { Text("Esc") }
            FilledTonalButton(onClick = { client.key(RfbClient.KEY_TAB) }) { Text("Tab") }
            FilledTonalButton(onClick = { showKb = true }) { Text("Keys") }
            FilledTonalButton(onClick = onSupport) { Text("\u2615") }
            FilledTonalButton(onClick = onStop) { Text("Stop") }
        }
    }
    if (showKb) {
        AlertDialog(
            onDismissRequest = { showKb = false },
            title = { Text("Send text") },
            text = { OutlinedTextField(text, { text = it }, singleLine = false) },
            confirmButton = {
                Button(onClick = { client.type(text); text = "" ; showKb = false }) { Text("Send") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { client.key(RfbClient.KEY_BACKSPACE) }) { Text("\u232B") }
                    TextButton(onClick = { client.key(RfbClient.KEY_ENTER) }) { Text("Enter") }
                }
            }
        )
    }
}

@Composable
private fun VncCanvas(client: RfbClient) {
    val tick by client.frames.collectAsState()
    var box by remember { mutableStateOf(IntSize.Zero) }
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(client) {
                detectTapGestures(
                    onTap = { client.click(it, size, 1) },
                    onLongPress = { client.click(it, size, 4) } // right click
                )
            }
            .pointerInput(client) {
                detectDragGestures(
                    onDragStart = { client.pointer(it, size, 1) },
                    onDrag = { change, _ -> change.consume(); client.pointer(change.position, size, 1) },
                    onDragEnd = { client.release() },
                    onDragCancel = { client.release() }
                )
            }
    ) {
        tick // subscribe to frame updates
        val bmp = client.bitmap ?: return@Canvas
        val s = minOf(box.width.toFloat() / bmp.width, box.height.toFloat() / bmp.height)
        val dw = (bmp.width * s).toInt(); val dh = (bmp.height * s).toInt()
        drawImage(
            bmp.asImageBitmap(),
            dstOffset = IntOffset((box.width - dw) / 2, (box.height - dh) / 2),
            dstSize = IntSize(dw, dh),
            filterQuality = FilterQuality.Low
        )
    }
}

@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Support RomBooter") },
        text = { Text("Free, open source (GPL-3.0), no ads, no telemetry. If it's useful to you, you can buy me a coffee.") },
        confirmButton = {
            // Opens the system browser only on this tap. The app itself makes no outbound calls.
            Button(onClick = { uri.openUri(BMC_URL); onDismiss() }) { Text("\u2615 Buy Me a Coffee") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}
