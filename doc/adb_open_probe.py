"""最小 ADB 客户端探针：只做 CNXN + OPEN，用来判断设备 adbd 是否接受 localabstract:scrcpy。

用法：python adb_open_probe.py 192.168.0.226 5555 localabstract:scrcpy
"""
import socket, struct, sys, time

CNXN, AUTH, OPEN, OKAY, CLSE, WRTE = 0x4E584E43, 0x4E585448, 0x4E45504F, 0x4E59414B, 0x4E53454C, 0x45545257


def pkt(cmd, a0, a1, payload=b""):
    magic = cmd ^ 0xFFFFFFFF
    return struct.pack("<IIIII", cmd, a0, a1, len(payload), _crc(payload)) + struct.pack("<I", magic) + payload


def _crc(data):
    crc = 0
    for b in data:
        crc = (crc >> 8) ^ TAB[(crc ^ b) & 0xFF]
    return crc


TAB = []
for i in range(256):
    c = i
    for _ in range(8):
        c = (c >> 1) ^ 0xEDB88320 if c & 1 else c >> 1
    TAB.append(c)


def read_pkt(s):
    hdr = b""
    while len(hdr) < 24:
        d = s.recv(24 - len(hdr))
        if not d:
            raise EOFError("socket closed")
        hdr += d
    cmd, a0, a1, ln, crc, magic = struct.unpack("<IIIIII", hdr)
    data = b""
    while len(data) < ln:
        d = s.recv(ln - len(data))
        if not d:
            raise EOFError("socket closed in payload")
        data += d
    return cmd, a0, a1, data


def name(cmd):
    return {CNXN: "CNXN", AUTH: "AUTH", OPEN: "OPEN", OKAY: "OKAY", CLSE: "CLSE", WRTE: "WRTE"}.get(cmd, hex(cmd))


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "192.168.0.226"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 5555
    svc = sys.argv[3] if len(sys.argv) > 3 else "localabstract:scrcpy"
    s = socket.create_connection((host, port), timeout=8)
    s.settimeout(8)
    s.sendall(pkt(CNXN, 0x01000001, 262144, b"host::features=shell_v2,cmd\0"))
    for _ in range(6):
        cmd, a0, a1, data = read_pkt(s)
        print("<-", name(cmd), "a0", a0, "a1", a1, "len", len(data), data[:40])
        if cmd == CNXN:
            break
    for variant in ("nul", "nonul"):
        payload = svc + ("\0" if variant == "nul" else "")
        s.sendall(pkt(OPEN, 1, 0, payload.encode()))
        print("-> OPEN", variant, repr(payload))
        try:
            cmd, a0, a1, data = read_pkt(s)
            print("<-", name(cmd), "a0", a0, "a1", a1, "len", len(data), data[:80])
            if cmd == OKAY:
                time.sleep(1.2)
                s.settimeout(3)
                try:
                    cmd2, _, _, d2 = read_pkt(s)
                    print("<- next", name(cmd2), "len", len(d2), d2[:40].hex())
                except Exception as e:
                    print("<- next none:", e)
                s.close()
                return
            if cmd == CLSE:
                print("!! 设备拒绝该服务")
        except Exception as e:
            print("!! error", e)
            s.close()
            return
    s.close()


main()
