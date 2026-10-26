"""Stand-in for rb-session in the start-script tests.

FAKE_MODE=ok   serve an RFB banner to every client (default)
FAKE_MODE=die  exit immediately with an error, like a broken session
FAKE_MODE=eof  accept clients and close them without a banner (the old EOFException case)
Every accepted connection is logged to $FAKE_DIR/conns, so tests can count readiness probes.
"""
import os, socket, sys

mode = os.environ.get("FAKE_MODE", "ok")
port = int(os.environ["RB_VNC_PORT"])
if mode == "die":
    print("rb-session: Xvnc exited during startup", file=sys.stderr)
    sys.exit(1)
srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port))
srv.listen(8)
print("RB_READY x11", port, flush=True)  # marker rb-session prints once Xvnc is up
while True:
    c, _ = srv.accept()
    with open(os.path.join(os.environ["FAKE_DIR"], "conns"), "a") as f:
        f.write("x\n")
    if mode == "ok":
        c.sendall(b"RFB 003.008\n")
    c.close()
