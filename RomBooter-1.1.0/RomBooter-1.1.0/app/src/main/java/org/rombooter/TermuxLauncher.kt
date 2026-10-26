package org.rombooter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

enum class Distro(val key: String, val label: String) {
    ARCH("arch", "Arch"), GENTOO("gentoo", "Gentoo"), DEBIAN("debian", "Debian"),
    UBUNTU("ubuntu", "Ubuntu"), ALPINE("alpine", "Alpine"), KALI("kali", "Kali"), FEDORA("fedora", "Fedora")
}

/**
 * Desktop session inside the distro, as registered by rb-provision in /etc/rombooter/sessions.
 * Wayland sessions run a headless wlroots compositor with wayvnc; [portrait] sessions are mobile
 * shells (postmarketOS style) and get a portrait framebuffer.
 */
enum class Session(val key: String, val label: String, val wayland: Boolean, val portrait: Boolean) {
    XFCE("xfce", "Xfce", false, false),
    I3("i3", "i3", false, false),
    SWAY("sway", "Sway (Wayland)", true, false),
    PHOSH("phosh", "Phosh (Wayland, mobile)", true, true)
}

/** Outcome of start_<distro>.sh as reported back by Termux. */
data class ScriptResult(val exitCode: Int?, val stdout: String, val stderr: String, val errmsg: String) {
    val ok get() = exitCode == 0

    /** Human-readable reason, keyed on the start script's exit codes. */
    fun describe(d: Distro): String {
        val detail = stderr.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("ERROR:") }
            ?.removePrefix("ERROR:")?.trim()
            ?: stderr.trim().lines().takeLast(3).joinToString("\n").ifBlank { errmsg.ifBlank { stdout.trim() } }
        val what = when (exitCode) {
            1 -> "The ${d.label} session exited during startup"
            2 -> "start_${d.key}.sh rejected its arguments"
            3 -> "The ${d.label} desktop did not come up in time"
            4 -> "${d.label} is not set up yet - run: bash rombooter-setup.sh --distro ${d.key}"
            5 -> "Port ${TermuxLauncher.PORT} is used by another program"
            127 -> "~/start_${d.key}.sh not found - run: bash rombooter-setup.sh --distro ${d.key}"
            null -> "Termux could not run start_${d.key}.sh"
            else -> "start_${d.key}.sh failed (exit $exitCode)"
        }
        return if (detail.isBlank()) what else "$what:\n$detail"
    }
}

/** Triggers start_<distro>.sh inside Termux via RUN_COMMAND (background, no terminal UI). */
object TermuxLauncher {
    const val PORT = 5901
    private const val HOME = "/data/data/com.termux/files/home"
    private const val ACTION_RESULT = "org.rombooter.TERMUX_RESULT"
    private const val EXTRA_ID = "org.rombooter.REQUEST_ID"
    private val ids = AtomicInteger(1)

    fun isInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)

    fun startArgs(res: String, pass: String, session: Session, restart: Boolean = false) =
        arrayOf("--res", res, "--pass", pass, "--de", session.key) + (if (restart) arrayOf("--restart") else emptyArray())

    /**
     * Runs start_<distro>.sh and suspends until Termux reports its exit status, or returns null
     * after [timeoutMs] (older Termux builds never report back; callers then rely on the port).
     */
    suspend fun start(
        ctx: Context, d: Distro, res: String, pass: String, session: Session, timeoutMs: Long, restart: Boolean = false
    ): ScriptResult? = runForResult(ctx, d, startArgs(res, pass, session, restart), timeoutMs)

    fun stop(ctx: Context, d: Distro) = send(ctx, d, arrayOf("--stop"), null)

    private suspend fun runForResult(ctx: Context, d: Distro, args: Array<String>, timeoutMs: Long): ScriptResult? {
        val app = ctx.applicationContext
        val id = ids.getAndIncrement()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (i.getIntExtra(EXTRA_ID, -1) != id) return
                        runCatching { app.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(parse(i))
                    }
                }
                ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }

                // Explicit (package-bound) and mutable: Termux fills in the "result" bundle.
                val callback = Intent(ACTION_RESULT).setPackage(app.packageName).putExtra(EXTRA_ID, id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                try {
                    send(app, d, args, PendingIntent.getBroadcast(app, id, callback, flags))
                } catch (e: Exception) {
                    runCatching { app.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(ScriptResult(null, "", "", "Termux refused the command: ${e.message}"))
                }
            }
        }
    }

    private fun parse(i: Intent): ScriptResult {
        val b = i.getBundleExtra("result") ?: return ScriptResult(null, "", "", "Termux sent no result")
        val err = b.getInt("err", -1)
        val exit = if (b.containsKey("exitCode")) b.getInt("exitCode") else null
        return ScriptResult(
            // err != RESULT_OK(-1) means Termux itself failed (e.g. allow-external-apps is off)
            exitCode = if (err == -1) exit else null,
            stdout = b.getString("stdout").orEmpty(),
            stderr = b.getString("stderr").orEmpty(),
            errmsg = b.getString("errmsg").orEmpty()
        )
    }

    private fun send(ctx: Context, d: Distro, args: Array<String>, result: PendingIntent?) {
        val i = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "$HOME/start_${d.key}.sh") // key comes from the enum, never user text
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            if (result != null) putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result)
        }
        ContextCompat.startForegroundService(ctx, i)
    }
}
