import socket
import sys

port = int(sys.argv[1])
timeout = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
try:
    s = socket.create_connection(("127.0.0.1", port), timeout)
    s.settimeout(timeout)
    data = s.recv(1)
    print("OK" if data else "CLOSED")
    s.close()
except Exception:
    print("ERR")
