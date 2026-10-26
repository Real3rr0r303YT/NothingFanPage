package org.rombooter

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

enum class Distro(val key: String, val label: String) {
    ARCH("arch", "Arch"), GENTOO("gentoo", "Gentoo"), DEBIAN("debian", "Debian"),
    UBUNTU("ubuntu", "Ubuntu"), ALPINE("alpine", "Alpine"), KALI("kali", "Kali"), FEDORA("fedora", "Fedora")
}

/** Triggers start_<distro>.sh inside Termux via RUN_COMMAND (background, no terminal UI). */
object TermuxLauncher {
    private const val HOME = "/data/data/com.termux/files/home"

    fun isInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)

    fun start(ctx: Context, d: Distro, res: String, pass: String, de: String = "xfce") =
        run(ctx, d, arrayOf("--res", res, "--pass", pass, "--de", de))

    fun stop(ctx: Context, d: Distro) = run(ctx, d, arrayOf("--stop"))

    private fun run(ctx: Context, d: Distro, args: Array<String>) {
        val i = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "$HOME/start_${d.key}.sh") // key comes from the enum, never user text
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        ContextCompat.startForegroundService(ctx, i)
    }
}
