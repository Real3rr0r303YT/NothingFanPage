// RfbProbe - speaks the same RFB 3.8 handshake as the app's RfbClient (VncAuth or None, ClientInit,
// SetPixelFormat, SetEncodings Raw+CopyRect+DesktopSize, full FramebufferUpdateRequest) and checks
// that a first framebuffer update arrives. Run with: java RfbProbe.java PORT PASSWORD [WxH]
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class RfbProbe {
    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        String pass = a.length > 1 ? a[1] : "";
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            s.setSoTimeout(30000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            OutputStream out = s.getOutputStream();
            byte[] ver = new byte[12]; in.readFully(ver);
            System.out.println("server: " + new String(ver, "US-ASCII").trim());
            out.write("RFB 003.008\n".getBytes("US-ASCII"));
            int n = in.readUnsignedByte();
            if (n == 0) throw new IOException("refused: " + reason(in));
            byte[] types = new byte[n]; in.readFully(types);
            boolean vnc = false, none = false;
            for (byte t : types) { vnc |= t == 2; none |= t == 1; }
            if (vnc) {
                out.write(2);
                byte[] ch = new byte[16]; in.readFully(ch);
                out.write(des(pass, ch));
            } else if (none) {
                out.write(1);
            } else throw new IOException("no supported security type");
            if (in.readInt() != 0) throw new IOException("auth failed: " + reason(in));
            System.out.println("security: " + (vnc ? "VncAuth" : "None"));
            out.write(1);
            int w = in.readUnsignedShort(), h = in.readUnsignedShort();
            in.readFully(new byte[16]);
            byte[] name = new byte[in.readInt()]; in.readFully(name);
            System.out.println("desktop: " + w + "x" + h + " '" + new String(name, "UTF-8") + "'");
            if (a.length > 2 && !a[2].equals(w + "x" + h)) throw new IOException("expected " + a[2]);
            out.write(ByteBuffer.allocate(20).put(new byte[]{0, 0, 0, 0, 32, 24, 0, 1})
                    .putShort((short) 255).putShort((short) 255).putShort((short) 255)
                    .put(new byte[]{16, 8, 0, 0, 0, 0}).array());
            out.write(ByteBuffer.allocate(16).put((byte) 2).put((byte) 0).putShort((short) 3)
                    .putInt(1).putInt(0).putInt(-223).array());
            out.write(ByteBuffer.allocate(10).put((byte) 3).put((byte) 0).putShort((short) 0).putShort((short) 0)
                    .putShort((short) w).putShort((short) h).array());
            out.flush();
            while (true) {
                int t = in.readUnsignedByte();
                if (t == 0) {
                    in.readUnsignedByte();
                    int rects = in.readUnsignedShort();
                    System.out.println("OK first framebuffer update, " + rects + " rect(s)");
                    return;
                } else if (t == 2) {
                    continue;
                } else if (t == 3) {
                    in.readFully(new byte[3]); in.readFully(new byte[in.readInt()]);
                } else throw new IOException("unexpected message " + t);
            }
        }
    }

    static String reason(DataInputStream in) throws IOException {
        byte[] r = new byte[in.readInt()]; in.readFully(r); return new String(r, "UTF-8");
    }

    static byte[] des(String pass, byte[] ch) throws Exception {
        byte[] pw = pass.getBytes("ISO-8859-1"), key = new byte[8];
        for (int i = 0; i < 8; i++) key[i] = i < pw.length ? (byte) (Integer.reverse(pw[i] & 0xFF) >>> 24) : 0;
        Cipher c = Cipher.getInstance("DES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
        return c.doFinal(ch);
    }
}
