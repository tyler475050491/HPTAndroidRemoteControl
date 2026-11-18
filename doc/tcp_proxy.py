# -*- coding: utf-8 -*-
"""ADB 字节级代理：监听本地端口，把流量原样转发到目标设备，并打印每个报文的命令/参数/长度。
用法: python tcp_proxy.py <listen_port> <target_host> <target_port>
"""
import socket
import struct
import sys
import threading

CMDS = {
    0x4e584e43: "CNXN",
    0x48535354: "AUTH",
    0x4e45504f: "OPEN",
    0x59414b4f: "OKAY",
    0x45534c43: "CLSE",
    0x45545257: "WRTE",
    0x53544c53: "STLS",
}


def log(tag, text):
    print("[%s] %s" % (tag, text), flush=True)


def split_stream(data, tag):
    """把一段 TCP 数据按 ADB 报文边界解析并打印（只打印，不影响转发）"""
    i = 0
    n = len(data)
    while i + 24 <= n:
        cmd, arg0, arg1, ln, crc, magic = struct.unpack("<6I", data[i:i + 24])
        name = CMDS.get(cmd, hex(cmd))
        if magic != (cmd ^ 0xffffffff):
            log(tag, "BAD-MAGIC cmd=%s magic=%08x payload_off=%d rest=%d"
                % (name, magic, i, n - i))
            return
        payload = data[i + 24:i + 24 + ln]
        shown = payload[:120]
        log(tag, "%s a0=%d a1=%d len=%d crc=%08x payload=%r"
            % (name, arg0, arg1, ln, crc, shown))
        i += 24 + ln


def pump(src, dst, tag):
    buf = b""
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                break
            buf += chunk
            # 只在报文边界上 flush 打印，避免把未读完的报文截断
            split_stream(buf, tag)
            buf = b""
            dst.sendall(chunk)
    except Exception as e:
        log(tag, "closed: %s" % e)
    finally:
        try:
            dst.close()
        except Exception:
            pass


def handle(client, target_host, target_port):
    up = socket.create_connection((target_host, target_port), timeout=10)
    up.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    log("proxy", "=== new connection from %s ===" % (client.getpeername(),))
    t1 = threading.Thread(target=pump, args=(client, up, "APP->DEV"))
    t2 = threading.Thread(target=pump, args=(up, client, "DEV->APP"))
    t1.setDaemon(True)
    t2.setDaemon(True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    log("proxy", "=== connection ended ===")


def main():
    listen_port = int(sys.argv[1])
    target_host = sys.argv[2]
    target_port = int(sys.argv[3])
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", listen_port))
    srv.listen(5)
    log("proxy", "listening on %d -> %s:%d" % (listen_port, target_host, target_port))
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c, target_host, target_port)).start()


if __name__ == "__main__":
    main()
