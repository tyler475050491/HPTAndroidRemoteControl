import socket
import struct
import sys
import zlib
import time

A_CNXN = 0x4e584e43
A_AUTH = 0x48545541
A_OPEN = 0x4e45504f
A_OKAY = 0x59414b4f
A_CLSE = 0x45535c43
A_WRTE = 0x45545257

NAMES = {A_CNXN: "CNXN", A_AUTH: "AUTH", A_OPEN: "OPEN", A_OKAY: "OKAY",
         A_CLSE: "CLSE", A_WRTE: "WRTE"}


def send(s, cmd, a0, a1, data=b"", use_crc=True):
    ln = len(data)
    c = (zlib.crc32(data) & 0xffffffff) if use_crc else 0
    hdr = struct.pack("<6I", cmd, a0, a1, ln, c, (cmd ^ 0xffffffff) & 0xffffffff)
    s.sendall(hdr + data)


def recv_msg(s, timeout=5.0):
    s.settimeout(timeout)
    try:
        head = b""
        while len(head) < 24:
            chunk = s.recv(24 - len(head))
            if not chunk:
                return None
            head += chunk
        cmd, a0, a1, ln, crc, magic = struct.unpack("<6I", head)
        data = b""
        while len(data) < ln:
            chunk = s.recv(ln - len(data))
            if not chunk:
                break
            data += chunk
        return cmd, a0, a1, ln, data
    except socket.timeout:
        return "TIMEOUT"


def main():
    host, port = sys.argv[1], int(sys.argv[2])
    service = sys.argv[3] if len(sys.argv) > 3 else "exec:echo HELLO_ADB"
    use_crc = not (len(sys.argv) > 4 and sys.argv[4] == "nocrc")
    s = socket.create_connection((host, port), timeout=6)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print("connected; service=%r" % service)
    send(s, A_CNXN, 0x01000001, 256 * 1024, b"host::", use_crc)
    print("sent CNXN")
    stage = "handshake"
    local_id = 1
    for _ in range(12):
        m = recv_msg(s, 6.0)
        if m == "TIMEOUT":
            print("TIMEOUT in", stage)
            return
        if m is None:
            print("EOF in", stage)
            return
        cmd, a0, a1, ln, data = m
        print("RECV %-5s a0=%s a1=%s len=%s data=%r" % (
            NAMES.get(cmd, hex(cmd)), a0, a1, ln, data[:60]))
        if cmd == A_CNXN:
            stage = "open"
            send(s, A_OPEN, local_id, 0, service.encode(), use_crc)
            print("sent OPEN %r" % service)
        elif cmd == A_OKAY:
            print("=> OKAY for stream, sending WRTE? none needed")
            time.sleep(0.5)
            print("done")
            return
        elif cmd == A_WRTE:
            print("=> got output:", data)


main()
