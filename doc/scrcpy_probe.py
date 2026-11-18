import socket, struct, sys, time

host = '127.0.0.1'
port = int(sys.argv[1]) if len(sys.argv) > 1 else 27183

def recv_all(s, n, timeout=8.0):
    buf = b''
    s.settimeout(timeout)
    while len(buf) < n:
        try:
            d = s.recv(n - len(buf))
        except socket.timeout:
            break
        if not d:
            break
        buf += d
    return buf

v = socket.socket()
v.settimeout(6)
v.connect((host, port))
dummy = v.recv(1)
print('dummy byte:', dummy)

c = socket.socket()
c.settimeout(6)
c.connect((host, port))
print('control connected')

head = recv_all(v, 76, 8)
print('header bytes:', len(head))
print('name:', head[:64].rstrip(b'\x00'))
print('fourcc:', head[64:68], 'w,h =', struct.unpack('>ii', head[68:76]))
w, h = struct.unpack('>ii', head[68:76])

frames = 0
t0 = time.time()
for i in range(60):
    hdr = recv_all(v, 12, 8)
    if len(hdr) < 12:
        print('no more frames (got %d bytes)' % len(hdr))
        break
    pts, size = struct.unpack('>qi', hdr)
    cfg = bool((pts >> 63) & 1)
    key = bool((pts >> 62) & 1)
    data = recv_all(v, size, 8)
    frames += 1
    if i < 4 or cfg:
        print('frame %d: pts=%d size=%d config=%s key=%s data=%s' %
              (i, pts, size, cfg, key, data[:8].hex()))
    if i == 1:
        # 注入一次触摸：点屏幕正中
        buf = struct.pack('>BBqiiHHHii', 2, 0, 0, w // 2, h // 2, w, h, 0xFFFF, 0, 0)
        c.sendall(buf)
        print('sent touch (%d bytes) at (%d,%d) screen %dx%d' % (len(buf), w // 2, h // 2, w, h))
        buf2 = struct.pack('>BBqiiHHHii', 2, 1, 0, w // 2, h // 2, w, h, 0, 0, 0)
        time.sleep(0.05)
        c.sendall(buf2)
        print('sent touch up')

elapsed = time.time() - t0
print('frames:', frames, 'in %.1fs -> %.1f fps' % (elapsed, frames / elapsed if elapsed else 0))
v.close()
c.close()
print('done')
