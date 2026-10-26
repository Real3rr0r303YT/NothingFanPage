package org.rombooter

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

sealed interface BootState {
    data object Idle : BootState
    data object Launching : BootState
    data object WaitingForPort : BootState
    data object Connected : BootState
    data class Failed(val message: String) : BootState
}

class BootViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<BootState>(BootState.Idle)
    val state: StateFlow<BootState> = _state

    var client: RfbClient? = null; private set
    private var job: Job? = null

    /** One tap: launch script in Termux -> poll 127.0.0.1:5901 -> connect viewer -> UI switches to desktop. */
    fun boot(d: Distro, screenW: Int, screenH: Int, de: String = "xfce") {
        if (job?.isActive == true) return
        val ctx = getApplication<Application>()
        job = viewModelScope.launch {
            try {
                if (!TermuxLauncher.isInstalled(ctx)) error("Termux is not installed")
                val pass = sessionPass(ctx)
                _state.value = BootState.Launching
                TermuxLauncher.start(ctx, d, remoteRes(screenW, screenH), pass, de)

                _state.value = BootState.WaitingForPort
                waitForPort(5901, 180_000)

                val c = RfbClient(pass).also { client = it }
                c.run { _state.value = BootState.Connected }   // returns only when the connection ends
                _state.value = BootState.Failed("Disconnected")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = BootState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun stop(d: Distro) {
        client?.close(); client = null
        job?.cancel()
        TermuxLauncher.stop(getApplication(), d)
        _state.value = BootState.Idle
    }

    override fun onCleared() { client?.close() }

    private suspend fun waitForPort(port: Int, timeoutMs: Long) = withContext(Dispatchers.IO) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }
                return@withContext
            } catch (_: IOException) {
                delay(500)
            }
        }
        error("Timeout waiting for 127.0.0.1:$port - see ~/.rombooter/<distro>.log in Termux")
    }

    /** Landscape size, capped at 1280 wide, multiples of 8. */
    private fun remoteRes(wPx: Int, hPx: Int, maxW: Int = 1280): String {
        val long = maxOf(wPx, hPx); val short = minOf(wPx, hPx)
        val s = minOf(1f, maxW.toFloat() / long)
        return "${(long * s).toInt() / 8 * 8}x${(short * s).toInt() / 8 * 8}"
    }

    /** Per-install 8-char VNC password (VNC auth uses at most 8). Stays in app-private storage. */
    private fun sessionPass(ctx: Context): String {
        val p = ctx.getSharedPreferences("rb", Context.MODE_PRIVATE)
        return p.getString("vnc", null) ?: run {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            val r = SecureRandom()
            String(CharArray(8) { chars[r.nextInt(chars.length)] }).also { p.edit().putString("vnc", it).apply() }
        }
    }
}
