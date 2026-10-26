package org.rombooter

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

sealed interface BootState {
    data object Idle : BootState
    data object Launching : BootState
    data object WaitingForPort : BootState
    data object Connecting : BootState
    data object Connected : BootState
    data class Failed(val message: String) : BootState
}

class BootViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<BootState>(BootState.Idle)
    val state: StateFlow<BootState> = _state

    var client: RfbClient? = null; private set
    private var job: Job? = null

    /**
     * One tap: launch start_<distro>.sh in Termux -> wait until the script reports READY (or the
     * port speaks RFB) -> connect the viewer -> UI switches to the desktop.
     */
    fun boot(d: Distro, session: Session, screenW: Int, screenH: Int) {
        if (job?.isActive == true) return
        val ctx = getApplication<Application>()
        job = viewModelScope.launch {
            try {
                if (!TermuxLauncher.isInstalled(ctx)) throw BootException("Termux is not installed")
                val pass = sessionPass(ctx)
                val res = remoteRes(screenW, screenH, session.portrait)
                startSession(ctx, d, session, res, pass, restart = false)
                try {
                    connect(d, pass)
                } catch (e: RfbException) {
                    // Blacklisted ("Server refused"), wrong/unset password: a session left over from an
                    // older setup or another password. Restart it once with our password and retry.
                    if (!e.rejected) throw e
                    startSession(ctx, d, session, res, pass, restart = true)
                    connect(d, pass)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = BootState.Failed(e.friendly(d))
            } finally {
                client = null
            }
        }
    }

    /** Runs start_<distro>.sh and returns once the script reported READY or the port speaks RFB. */
    private suspend fun startSession(ctx: Context, d: Distro, session: Session, res: String, pass: String, restart: Boolean) =
        coroutineScope {
            _state.value = BootState.Launching
            val script = async { TermuxLauncher.start(ctx, d, res, pass, session, START_TIMEOUT_MS, restart) }

            _state.value = BootState.WaitingForPort
            val begin = SystemClock.elapsedRealtime()
            val end = begin + START_TIMEOUT_MS
            var lastProbe = 0L
            while (true) {
                if (script.isCompleted) {
                    val r = script.await()
                    if (r != null && !r.ok) throw BootException(r.describe(d))
                    if (r != null || rfbReady()) break      // READY, or old Termux without result
                } else {
                    // Fallback for Termux builds that never report a result. Probe rarely: Xvnc
                    // counts every connection closed before login towards its blacklist.
                    val waited = SystemClock.elapsedRealtime() - begin
                    if (waited > PROBE_AFTER_MS && waited - lastProbe >= PROBE_EVERY_MS) {
                        lastProbe = waited
                        if (rfbReady()) break
                    }
                }
                if (SystemClock.elapsedRealtime() > end) {
                    throw BootException("Timeout waiting for 127.0.0.1:${TermuxLauncher.PORT} - see ~/.rombooter/${d.key}.log in Termux")
                }
                delay(500)
            }
            script.cancel()   // keep nothing waiting on Termux once we can connect
        }

    /**
     * The server may accept a socket and then hang up while it is still settling (or crash right
     * after start): retry the handshake a few times before calling it a failure.
     */
    private suspend fun connect(d: Distro, pass: String) {
        var last: Exception? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            _state.value = BootState.Connecting
            val c = RfbClient(pass, TermuxLauncher.PORT).also { client = it }
            var connected = false
            try {
                c.run { connected = true; _state.value = BootState.Connected }   // returns when the connection ends
                throw BootException("The desktop closed the connection")
            } catch (e: RfbException) {
                // Refused / wrong password / protocol error: retrying the same server cannot help
                if (connected || !e.handshake || e.rejected) throw e
                last = e
            } catch (e: IOException) {
                if (connected) throw BootException("Lost the desktop connection (${e.friendly(d)})")
                last = e
            }
            client = null
            if (attempt < CONNECT_ATTEMPTS - 1) delay(1500L * (attempt + 1))
        }
        throw BootException("${last?.friendly(d) ?: "Could not connect"}. Check ~/.rombooter/${d.key}.log in Termux")
    }

    fun stop(d: Distro) {
        client?.close(); client = null
        job?.cancel()
        TermuxLauncher.stop(getApplication(), d)
        _state.value = BootState.Idle
    }

    override fun onCleared() { client?.close() }

    /** True when the port answers with an RFB banner, not just accepts a socket. Counts as one failed login for Xvnc. */
    private suspend fun rfbReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", TermuxLauncher.PORT), 400)
                s.soTimeout = 2000
                val b = ByteArray(4)
                var n = 0
                while (n < 4) { val r = s.getInputStream().read(b, n, 4 - n); if (r < 0) break; n += r }
                n == 4 && String(b, Charsets.US_ASCII) == "RFB "
            }
        } catch (_: IOException) {
            false
        }
    }

    private class BootException(message: String) : Exception(message)

    private fun Throwable.friendly(d: Distro): String = when (this) {
        is BootException, is RfbException -> message.orEmpty()
        is EOFException -> "The VNC server hung up unexpectedly (session crashed or was killed)"
        is SocketTimeoutException -> "The VNC server stopped responding"
        is ConnectException -> "Nothing is listening on 127.0.0.1:${TermuxLauncher.PORT}"
        is SecurityException -> "RomBooter may not run Termux commands: allow it under App info > Permissions " +
            "and set allow-external-apps = true (rombooter-setup.sh does this)"
        else -> message ?: javaClass.simpleName
    } + if (this is EOFException || this is SocketTimeoutException) ". On Android 12+ the phantom process killer " +
        "can stop PRoot - see README. Log: ~/.rombooter/${d.key}.log" else ""

    /** Landscape (or portrait for mobile shells) size, long side capped at 1280, multiples of 8. */
    private fun remoteRes(wPx: Int, hPx: Int, portrait: Boolean, maxW: Int = 1280): String {
        val long = maxOf(wPx, hPx); val short = minOf(wPx, hPx)
        val s = minOf(1f, maxW.toFloat() / long)
        val l = ((long * s).toInt() / 8 * 8).coerceIn(320, 4096)
        val k = ((short * s).toInt() / 8 * 8).coerceIn(240, 4096)
        return if (portrait) "${k}x$l" else "${l}x$k"
    }

    /** Per-install 8-char VNC password (VNC auth uses at most 8). Stays in app-private storage. */
    private fun sessionPass(ctx: Context): String {
        val p = ctx.getSharedPreferences("rb", Context.MODE_PRIVATE)
        return p.getString("vnc", null)?.takeIf { it.matches(Regex("[A-Za-z0-9]{8}")) } ?: run {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            val r = SecureRandom()
            String(CharArray(8) { chars[r.nextInt(chars.length)] }).also { p.edit().putString("vnc", it).apply() }
        }
    }

    private companion object {
        const val START_TIMEOUT_MS = 200_000L   // start script itself gives up after 180 s
        const val CONNECT_ATTEMPTS = 3
        const val PROBE_AFTER_MS = 20_000L      // the start script normally reports READY before this
        const val PROBE_EVERY_MS = 10_000L
    }
}
