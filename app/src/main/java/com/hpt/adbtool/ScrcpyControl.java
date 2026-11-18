package com.hpt.adbtool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * scrcpy 控制通道：第二条连到 localabstract:scrcpy 的连接，按 scrcpy 2.x 的控制消息格式
 * 向设备注入触摸/滚轮事件（完全在应用层实现，设备无需 root）。
 */
public final class ScrcpyControl {

    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;

    private static final byte TYPE_INJECT_TOUCH = 2;
    private static final byte TYPE_INJECT_SCROLL = 3;

    private final AdbStream stream;
    private volatile int videoWidth;
    private volatile int videoHeight;
    private final AdbLog log;

    public ScrcpyControl(AdbStream stream, int videoWidth, int videoHeight, AdbLog log) {
        this.stream = stream;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.log = log;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    /** 视频头解析出来后再把画面尺寸补上（控制通道比视频头先建立，此时尺寸可能还是 0） */
    public void setVideoSize(int width, int height) {
        if (width > 0 && height > 0) {
            this.videoWidth = width;
            this.videoHeight = height;
        }
    }

    public void close() {
        stream.close();
    }

    /**
     * 注入一次触摸（坐标是视频像素坐标）。
     * scrcpy 2.x 报文：type(1) action(1) pointerId(8) x(4) y(4) w(2) h(2) pressure(2) actionButton(4) buttons(4)
     */
    public void injectTouch(int action, long pointerId, int x, int y, int buttons) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        bb.put(TYPE_INJECT_TOUCH);
        bb.put((byte) action);
        bb.putLong(pointerId);
        bb.putInt(x);
        bb.putInt(y);
        bb.putShort((short) videoWidth);
        bb.putShort((short) videoHeight);
        // 按下/移动给满压，抬起给 0（u16 定点，0xFFFF = 1.0）
        bb.putShort((short) (action == ACTION_UP ? 0 : 0xFFFF));
        bb.putInt(0); // actionButton
        bb.putInt(buttons);
        write(bb.array());
    }

    /** 滚轮事件：type(1) x(4) y(4) w(2) h(2) hScroll(2) vScroll(2) buttons(4) */
    public void injectScroll(int x, int y, int hScroll, int vScroll) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        bb.put(TYPE_INJECT_SCROLL);
        bb.putInt(x);
        bb.putInt(y);
        bb.putShort((short) videoWidth);
        bb.putShort((short) videoHeight);
        bb.putShort((short) hScroll);
        bb.putShort((short) vScroll);
        bb.putInt(0);
        write(bb.array());
    }

    private void write(byte[] data) throws IOException {
        stream.write(data, 0, data.length);
    }

    /** 用控制器坐标换算：nx/ny 是画面内的归一化坐标 */
    public void touch(int action, float nx, float ny) throws IOException {
        int x = (int) (nx * (videoWidth - 1));
        int y = (int) (ny * (videoHeight - 1));
        if (x < 0) {
            x = 0;
        }
        if (y < 0) {
            y = 0;
        }
        if (x > videoWidth - 1) {
            x = videoWidth - 1;
        }
        if (y > videoHeight - 1) {
            y = videoHeight - 1;
        }
        injectTouch(action, 0L, x, y, 0);
        if (action == ACTION_DOWN && log != null) {
            log.log(AdbLog.INFO, "触控下发：按下 (" + x + ", " + y + ")");
        }
    }
}
