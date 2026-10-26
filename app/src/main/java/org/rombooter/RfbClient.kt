package org.rombooter

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal RFB 3.8 client for the loopback TigerVNC server: VncAuth (or None), Raw + CopyRect.
 * Raw over loopback is fine for a phone-sized desktop; add Tight/ZRLE later if CPU matters.
 */
class RfbClient(private val password: String, private val port: Int = 5901) {

    @Volatile var bitmap: Bitmap? = null; private set
    private val _frames = MutableStateFlow(0)
    val frames: StateFlow<Int> = _frames

    @Volatile private var sock: Socket? = null
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var px = 0
    private var py = 0

    /** Blocks until the connection ends. [onReady] fires after the handshake (first frame follows). */
    suspend fun run(onReady: () -> Unit) = withContext(Dispatchers.IO) {
        val s = Socket().also { sock = it }
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), 3000)
            val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            val os = BufferedOutputStream(s.getOutputStream())
            handshake(inp, os)
            onReady()
            coroutineScope {
                val writer = launch { for (m in outbox) { os.write(m); os.flush() } }
                try { readLoop(inp) } finally { writer.cancel() }
            }
        } finally {
            runCatching { s.close() }
        }
    }

    fun close() { runCatching { sock?.close() } }

    // ---------- handshake ----------
    private fun OutputStream.sendNow(b: ByteArray) { write(b); flush() }

    private fun handshake(inp: DataInputStream, os: OutputStream) {
        val ver = ByteArray(12).also { inp.readFully(it) }
        check(String(ver, Charsets.US_ASCII).startsWith("RFB ")) { "Not an RFB server" }
        os.sendNow("RFB 003.008\n".toByteArray(Charsets.US_ASCII))

        val n = inp.readUnsignedByte()
        if (n == 0) error("Server refused: " + String(ByteArray(inp.readInt()).also { inp.readFully(it) }))
        val types = ByteArray(n).also { inp.readFully(it) }
        when {
            types.contains(2.toByte()) -> {
                os.sendNow(byteArrayOf(2))
                os.sendNow(vncAuth(ByteArray(16).also { inp.readFully(it) }))
            }
            types.contains(1.toByte()) -> os.sendNow(byteArrayOf(1))
            else -> error("No supported security type")
        }
        if (inp.readInt() != 0) error("VNC authentication failed")

        os.sendNow(byteArrayOf(1)) // ClientInit: shared
        val w = inp.readUnsignedShort(); val h = inp.readUnsignedShort()
        inp.readFully(ByteArray(16))                                   // server pixel format
        inp.readFully(ByteArray(inp.readInt().also { check(it in 0..4096) })) // desktop name
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // SetPixelFormat: 32bpp, depth 24, little-endian, true colour, R<<16 G<<8 B
        os.sendNow(ByteBuffer.allocate(20).apply {
            put(0); put(0); put(0); put(0)
            put(32); put(24); put(0); put(1)
            putShort(255); putShort(255); putShort(255)
            put(16); put(8); put(0); put(0); put(0); put(0)
        }.array())
        // SetEncodings: CopyRect(1), Raw(0)
        os.sendNow(ByteBuffer.allocate(12).apply {
            put(2); put(0); putShort(2); putInt(1); putInt(0)
        }.array())
    }

    private fun vncAuth(challenge: ByteArray): ByteArray {
        val pw = password.toByteArray(Charsets.ISO_8859_1)
        val key = ByteArray(8) { if (it < pw.size) reverseBits(pw[it]) else 0 }
        val c = Cipher.getInstance("DES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return c.doFinal(challenge)
    }

    private fun reverseBits(b: Byte): Byte {
        var x = b.toInt() and 0xFF; var r = 0
        repeat(8) { r = (r shl 1) or (x and 1); x = x shr 1 }
        return r.toByte()
    }

    // ---------- main loop ----------
    private fun readLoop(inp: DataInputStream) {
        val bmp = bitmap!!
        val canvas = Canvas(bmp)
        var buf = ByteArray(0)
        post(updateReq(false, bmp.width, bmp.height))
        while (true) {
            when (val t = inp.readUnsignedByte()) {
                0 -> {
                    inp.readUnsignedByte()
                    repeat(inp.readUnsignedShort()) {
                        val x = inp.readUnsignedShort(); val y = inp.readUnsignedShort()
                        val w = inp.readUnsignedShort(); val h = inp.readUnsignedShort()
                        val enc = inp.readInt()
                        check(x + w <= bmp.width && y + h <= bmp.height) { "Rect out of bounds" }
                        when (enc) {
                            0 -> {
                                val n = w * h * 4
                                if (buf.size < n) buf = ByteArray(n)
                                inp.readFully(buf, 0, n)
                                val pix = IntArray(w * h)
                                ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pix)
                                for (i in pix.indices) pix[i] = pix[i] or -0x1000000
                                bmp.setPixels(pix, 0, w, x, y, w, h)
                            }
                            1 -> {
                                val sx = inp.readUnsignedShort(); val sy = inp.readUnsignedShort()
                                val tmp = Bitmap.createBitmap(bmp, sx, sy, w, h)
                                canvas.drawBitmap(tmp, x.toFloat(), y.toFloat(), null)
                                tmp.recycle()
                            }
                            else -> error("Unsupported encoding $enc")
                        }
                    }
                    _frames.update { it + 1 }
                    post(updateReq(true, bmp.width, bmp.height))
                }
                2 -> Unit // Bell
                3 -> {    // ServerCutText
                    inp.readFully(ByteArray(3))
                    val len = inp.readInt(); check(len in 0..1_000_000)
                    inp.readFully(ByteArray(len))
                }
                else -> error("Unexpected server message $t")
            }
        }
    }

    // ---------- input (non-blocking, safe from the main thread) ----------
    private fun post(m: ByteArray) { outbox.trySend(m) }

    private fun updateReq(inc: Boolean, w: Int, h: Int) = ByteBuffer.allocate(10).apply {
        put(3); put(if (inc) 1 else 0); putShort(0); putShort(0); putShort(w.toShort()); putShort(h.toShort())
    }.array()

    private fun pointerMsg(x: Int, y: Int, mask: Int) = ByteBuffer.allocate(6).apply {
        put(5); put(mask.toByte()); putShort(x.toShort()); putShort(y.toShort())
    }.array()

    private fun keyMsg(sym: Int, down: Boolean) = ByteBuffer.allocate(8).apply {
        put(4); put(if (down) 1 else 0); putShort(0); putInt(sym)
    }.array()

    /** mask: 1 = left, 2 = middle, 4 = right, 0 = release. */
    fun pointer(o: Offset, view: IntSize, mask: Int) {
        val b = bitmap ?: return
        val s = minOf(view.width.toFloat() / b.width, view.height.toFloat() / b.height)
        val ox = (view.width - b.width * s) / 2f
        val oy = (view.height - b.height * s) / 2f
        px = ((o.x - ox) / s).toInt().coerceIn(0, b.width - 1)
        py = ((o.y - oy) / s).toInt().coerceIn(0, b.height - 1)
        post(pointerMsg(px, py, mask))
    }

    fun click(o: Offset, view: IntSize, mask: Int) { pointer(o, view, mask); release() }
    fun release() = post(pointerMsg(px, py, 0))

    fun key(sym: Int) { post(keyMsg(sym, true)); post(keyMsg(sym, false)) }

    fun type(s: String) {
        s.codePoints().forEach { cp ->
            key(when {
                cp == '\n'.code -> 0xFF0D
                cp < 0x100      -> cp
                else            -> 0x01000000 or cp
            })
        }
    }

    companion object {
        const val KEY_ESC = 0xFF1B
        const val KEY_TAB = 0xFF09
        const val KEY_ENTER = 0xFF0D
        const val KEY_BACKSPACE = 0xFF08
    }
}
