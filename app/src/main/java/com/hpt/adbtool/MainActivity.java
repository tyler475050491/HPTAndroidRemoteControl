package com.hpt.adbtool;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity implements AdbLog {

    private static final int MAX_LOG_LINES = 400;
    private static final int DEFAULT_PORT = 5555;
    /** 从手机本地选 APK 的 requestCode */
    private static final int REQ_PICK_APK = 0x1001;
    /** 网段扫描并发数 */
    private static final int SCAN_THREADS = 16;
    /** 扫描的网段范围（x.x.x.1 ~ x.x.x.254） */
    private static final int SCAN_TOTAL = 254;
    /** scrcpy 视频参数：最长边 / 帧率上限 / 码率 */
    private static final int VIDEO_MAX_SIZE = 1024;
    private static final int VIDEO_MAX_FPS = 30;
    private static final int VIDEO_BIT_RATE = 8000000;

    /** 内置安装包的加密资源名（故意不带 apk 字样，避免一眼看出是什么） */
    private static final String PAYLOAD_FILE = "hpt_payload.dat";
    /** 内置包元信息（包名/应用名/大小/校验值），不含任何密钥线索 */
    private static final String PAYLOAD_IDX = "payload.idx";

    private EditText etHost;
    private EditText etPort;
    private Button btnConnect;
    private Button btnRefresh;
    private Button btnSearch;
    private Button btnInstall;
    private Button btnUninstall;
    private Button btnExitDesktop;
    private LinearLayout llDevices;
    private TextView tvNoDevice;
    private ScrollView svDevices;
    private FrameLayout previewBox;
    private FrameLayout flFull;
    private PreviewView previewView;
    private TextView tvPreviewHint;
    private TextView tvPreviewState;
    private TextView tvFullState;
    private ImageView btnFullscreen;
    private TextView tvLog;
    private ScrollView svLog;
    /** 标题栏右侧的版本徽标 */
    private TextView tvVersion;
    /** 卸载弹窗里的计数标题与列表容器（卸载成功一行后刷新计数） */
    private TextView appListTip;
    private LinearLayout appListBox;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Session> sessions = new ArrayList<Session>();
    private final List<LogLine> logs = new ArrayList<LogLine>();
    private final List<LogLine> pendingLogs = new ArrayList<LogLine>();
    private boolean logFlushPosted;

    private ScrcpyMirror scrcpy;
    /** 画布还没就绪时暂存的会话，SurfaceTexture 可用后再启动视频流 */
    private Session pendingSession;
    /** 当前解码输出绑定的 SurfaceTexture（全屏切换时用于判断是否需要重绑） */
    private SurfaceTexture boundTexture;
    private ScreenMirror mirror;
    private Session activeSession;
    private Session selectedSession;
    /** 全屏触控的按下位置（回退到 shell 输入时用来做 swipe） */
    private float touchDownNx = -1f;
    private float touchDownNy = -1f;
    private long touchDownAt;

    private boolean busy;
    private boolean fullscreen;
    private boolean suppressCheck;
    private String lastFrameInfo = "";
    private AlertDialog progressDialog;
    /** 从手机选 APK 期间记住目标设备（文件选择器会离开当前界面） */
    private Session pendingInstallSession;
    /** 安装弹窗与其内容视图：用户选完本地 APK 后回到界面继续在此弹窗里确认安装 */
    private AlertDialog installDialog;
    private TextView installInfoView;
    private Session installSession;
    private Uri pickedUri;
    private String pickedName = "";
    private long pickedSize = -1;

    // ------------------------------------------------------------------ model

    static class Session {
        String host;
        int port;
        AdbDevice dev;
        DevInfo info;
        View row;
        CheckBox cb;
        TextView tvName;
        TextView tvState;
        TextView tvInfo;
        TextView tvInfo2;
        Button btnMirror;
        Button btnDisconnect;
        Button btnReboot;
        boolean mirroring;
        /** 网段搜索发现但尚未建立会话 */
        boolean discovered;
    }

    static class LogLine {
        String time;
        String level;
        String msg;

        LogLine(String t, String l, String m) {
            time = t;
            level = l;
            msg = m;
        }
    }

    static class BuiltinMeta {
        String pkg = "";
        String label = "未命名应用";
        String versionName = "";
        String versionCode = "";
        String source = "";
        long size;
        boolean enc;
        String iv = "";
        long encSize;
        String sha256 = "";
    }

    // --------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        btnConnect = findViewById(R.id.btnConnect);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnSearch = findViewById(R.id.btnSearch);
        btnInstall = findViewById(R.id.btnInstall);
        btnUninstall = findViewById(R.id.btnUninstall);
        btnExitDesktop = findViewById(R.id.btnExitDesktop);
        llDevices = findViewById(R.id.llDevices);
        tvNoDevice = findViewById(R.id.tvNoDevice);
        svDevices = findViewById(R.id.svDevices);
        previewBox = findViewById(R.id.previewBox);
        flFull = findViewById(R.id.flFull);
        previewView = findViewById(R.id.previewView);
        tvPreviewHint = findViewById(R.id.tvPreviewHint);
        tvPreviewState = findViewById(R.id.tvPreviewState);
        tvFullState = findViewById(R.id.tvFullState);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        tvLog = findViewById(R.id.tvLog);
        svLog = findViewById(R.id.svLog);
        tvVersion = findViewById(R.id.tvVersion);
        tvVersion.setText("v" + versionName() + " · " + getString(R.string.build_stamp));

        findViewById(R.id.btnRotate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rotatePreview();
            }
        });
        findViewById(R.id.btnExitFull).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitFullscreen();
            }
        });
        btnFullscreen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enterFullscreen();
            }
        });

        SharedPreferences sp = getSharedPreferences("hpt_adb", MODE_PRIVATE);
        etHost.setText(sp.getString("host", ""));
        etPort.setText(String.valueOf(sp.getInt("port", DEFAULT_PORT)));

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startConnect();
            }
        });

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRefreshClicked();
            }
        });

        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSearchClicked();
            }
        });

        btnExitDesktop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                log(INFO, "退出到系统桌面");
                moveTaskToBack(true);
            }
        });

        etHost.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    startConnect();
                    return true;
                }
                return false;
            }
        });

        findViewById(R.id.btnClearLog).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logs.clear();
                synchronized (pendingLogs) {
                    pendingLogs.clear();
                }
                renderLogs();
            }
        });

        btnInstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onInstallClicked();
            }
        });

        btnUninstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onUninstallClicked();
            }
        });

        previewView.setOnGestureListener(new PreviewView.OnGestureListener() {
            @Override
            public void onDown(float nx, float ny) {
                if (!fullscreen) {
                    // 非全屏只显示视频流，不下发任何触控
                    return;
                }
                ScrcpyMirror sm = scrcpy;
                if (sm != null && sm.isControlReady()) {
                    sm.touch(ScrcpyControl.ACTION_DOWN, nx, ny);
                } else {
                    touchDownNx = nx;
                    touchDownNy = ny;
                }
                touchDownAt = SystemClock.elapsedRealtime();
            }

            @Override
            public void onMove(float nx, float ny) {
                if (!fullscreen) {
                    return;
                }
                ScrcpyMirror sm = scrcpy;
                if (sm != null && sm.isControlReady()) {
                    sm.touch(ScrcpyControl.ACTION_MOVE, nx, ny);
                }
            }

            @Override
            public void onUp(float nx, float ny) {
                if (!fullscreen) {
                    return;
                }
                ScrcpyMirror sm = scrcpy;
                if (sm != null && sm.isControlReady()) {
                    sm.touch(ScrcpyControl.ACTION_UP, nx, ny);
                    return;
                }
                // 控制通道不可用时的兜底：走 shell 的 input 命令
                final Session s = activeSession;
                if (s == null || s.info == null) {
                    return;
                }
                final float dx = touchDownNx;
                final float dy = touchDownNy;
                final int w = s.info.width();
                final int h = s.info.height();
                if (w <= 0 || h <= 0) {
                    return;
                }
                final int x1 = (int) (dx * w);
                final int y1 = (int) (dy * h);
                final int x2 = (int) (nx * w);
                final int y2 = (int) (ny * h);
                touchDownNx = -1f;
                touchDownNy = -1f;
                final boolean drag = dx >= 0 && (Math.abs(x2 - x1) > 8 || Math.abs(y2 - y1) > 8);
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (drag) {
                                s.dev.swipe(x1, y1, x2, y2, 300);
                                log(INFO, "触控下发：滑动 (" + x1 + "," + y1 + ") → (" + x2 + "," + y2 + ")");
                            } else {
                                s.dev.tap(x2, y2);
                                log(INFO, "触控下发：点击 (" + x2 + ", " + y2 + ")");
                            }
                        } catch (Exception e) {
                            log(ERR, "触控失败：" + msg(e));
                        }
                    }
                });
            }

            @Override
            public void onCancel() {
                touchDownNx = -1f;
                touchDownNy = -1f;
            }
        });

        log(INFO, "HPT移动端ADB助手 已启动 —— 纯应用层 ADB 客户端");
        log(INFO, "版本 v" + versionName() + "（versionCode " + versionCode() + " · 构建 "
                + getString(R.string.build_stamp) + "）");
        log(INFO, "本机 IP：" + localIp());
        // 内置包元信息只做内部清理与安装弹窗使用，不再往日志窗口输出
        prepareBuiltin();
    }

    @Override
    protected void onDestroy() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
            progressDialog = null;
        }
        stopMirror();
        for (Session s : sessions) {
            try {
                if (s.dev != null) {
                    s.dev.close();
                }
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            exitFullscreen();
            return;
        }
        super.onBackPressed();
    }

    // ----------------------------------------------------------------- 连接

    private int inputPort() {
        int port = DEFAULT_PORT;
        String portText = etPort.getText().toString().trim();
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (Exception ignored) {
            }
        }
        if (port <= 0 || port > 65535) {
            port = DEFAULT_PORT;
        }
        return port;
    }

    private void startConnect() {
        if (busy) {
            toast("正在搜索或刷新，请稍候");
            return;
        }
        String rawHost = etHost.getText().toString().trim();
        if (rawHost.isEmpty()) {
            toast("请输入设备地址（域名或 IP）");
            return;
        }
        int port = inputPort();

        String host = rawHost;
        if (host.toLowerCase().startsWith("adb://")) {
            host = host.substring(6);
        }
        if (host.contains(":")) {
            String[] parts = host.split(":");
            host = parts[0].trim();
            if (parts.length > 1) {
                try {
                    port = Integer.parseInt(parts[1].trim());
                } catch (Exception ignored) {
                }
            }
        }
        if (host.isEmpty()) {
            toast("设备地址不合法");
            return;
        }
        if (port <= 0 || port > 65535) {
            toast("端口号需在 1 - 65535 之间");
            return;
        }

        // 同一台设备已经连着：直接选中它，不重复建连接
        for (Session s : sessions) {
            if (s.host.equals(host) && s.port == port && s.dev != null && !s.discovered
                    && s.dev.isAlive()) {
                select(s);
                log(INFO, "该设备已处于连接状态：" + host + ":" + port);
                toast("该设备已连接");
                return;
            }
        }

        final String fHost = host;
        final int fPort = port;
        etHost.setText(fHost);
        etPort.setText(String.valueOf(fPort));
        getSharedPreferences("hpt_adb", MODE_PRIVATE).edit()
                .putString("host", fHost).putInt("port", fPort).apply();

        btnConnect.setEnabled(false);
        btnConnect.setText("连接中…");
        log(INFO, "正在连接 " + fHost + ":" + fPort + " …");

        worker.execute(new Runnable() {
            @Override
            public void run() {
                final long start = System.currentTimeMillis();
                try {
                    AdbCrypto crypto = AdbCrypto.loadOrCreate(getFilesDir());
                    AdbDevice dev = AdbDevice.connect(fHost, fPort, crypto, MainActivity.this);
                    final DevInfo info = dev.readInfo();
                    final long cost = System.currentTimeMillis() - start;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            resetConnectButton();
                            addRow(fHost, fPort, dev, info, false, cost);
                        }
                    });
                } catch (Exception e) {
                    final String m = msg(e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            resetConnectButton();
                            log(ERR, "连接失败：" + m);
                            toast("连接失败：" + m);
                        }
                    });
                }
            }
        });
    }

    private void resetConnectButton() {
        btnConnect.setEnabled(!busy);
        btnConnect.setText(R.string.btn_connect);
    }

    /** 新增一行设备（已连接 或 搜索发现未连接） */
    private void addRow(String host, int port, AdbDevice dev, DevInfo info, boolean discovered,
                        long costMs) {
        for (Session s : sessions) {
            if (s.host.equals(host) && s.port == port) {
                if (dev == null) {
                    log(WARN, "该设备已在列表中：" + host + ":" + port);
                    return;
                }
                // 已有这一行但连接是新的：直接换上新连接（旧连接往往已经断了）
                AdbDevice old = s.dev;
                if (old != null && old != dev) {
                    try {
                        old.close();
                    } catch (Throwable ignored) {
                    }
                }
                s.dev = dev;
                s.info = info;
                s.discovered = false;
                suppressCheck = true;
                s.cb.setChecked(true);
                suppressCheck = false;
                select(s);
                applyRowUi(s, true);
                registerClosedListener(s, dev);
                String infoLine = info != null && !info.line2().equals("-") ? " · " + info.line2() : "";
                log(OK, "已重新连接：" + (info == null ? host : info.name) + infoLine
                        + " · " + host + ":" + port + (costMs >= 0 ? "（" + costMs + " ms）" : ""));
                return;
            }
        }

        final Session session = new Session();
        session.host = host;
        session.port = port;
        session.dev = dev;
        session.info = info;

        View row = LayoutInflater.from(this).inflate(R.layout.item_device, llDevices, false);
        session.row = row;
        session.cb = row.findViewById(R.id.cbSelect);
        session.tvName = row.findViewById(R.id.tvName);
        session.tvState = row.findViewById(R.id.tvState);
        session.tvInfo = row.findViewById(R.id.tvInfo);
        session.tvInfo2 = row.findViewById(R.id.tvInfo2);
        session.btnMirror = row.findViewById(R.id.btnRowMirror);
        session.btnDisconnect = row.findViewById(R.id.btnRowDisconnect);
        session.btnReboot = row.findViewById(R.id.btnRowReboot);

        session.cb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (suppressCheck) {
                    return;
                }
                if (selectedSession == session) {
                    session.cb.setChecked(true);
                    return;
                }
                select(session);
            }
        });

        session.btnMirror.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    return;
                }
                if (session.discovered) {
                    connectRow(session);
                } else {
                    toggleMirror(session);
                }
            }
        });
        session.btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    return;
                }
                disconnect(session);
            }
        });
        session.btnReboot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    return;
                }
                confirmReboot(session);
            }
        });

        sessions.add(session);
        llDevices.addView(row);
        tvNoDevice.setVisibility(View.GONE);

        applyRowUi(session, !discovered);

        if (discovered) {
            log(OK, "发现 ADB 设备：" + host + ":" + port + "（未连接）");
        } else {
            registerClosedListener(session, dev);
            String infoLine = info != null && !info.line2().equals("-") ? " · " + info.line2() : "";
            log(OK, "连接成功：" + (info == null ? host : info.name) + infoLine
                    + " · " + host + ":" + port + (costMs >= 0 ? "（" + costMs + " ms）" : ""));
            if (selectedSession == null) {
                select(session);
            }
        }
    }

    /** 按「已连接 / 已发现未连接」刷新行内文案与按钮状态 */
    private void applyRowUi(final Session s, boolean connected) {
        s.discovered = !connected;
        if (s.tvName != null) {
            s.tvName.setText(connected && s.info != null ? s.info.name : s.host);
        }
        if (s.tvInfo != null) {
            s.tvInfo.setText(connected && s.info != null ? s.info.line1() : s.host + ":" + s.port);
        }
        if (s.tvInfo2 != null) {
            s.tvInfo2.setText(connected && s.info != null
                    ? s.info.line2() : "未连接 · 点「连接」建立会话");
        }
        if (s.tvState != null) {
            s.tvState.setText(connected ? "已连接" : "已发现（未连接）");
            s.tvState.setTextColor(getResources().getColor(
                    connected ? R.color.ok_green : R.color.warn_amber, getTheme()));
        }
        if (s.btnMirror != null) {
            applyMirrorButton(s, s.mirroring);
        }
        refreshRowButtons(s);
    }

    /**
     * 行内投屏按钮的两种外观：
     * 未投屏 → 蓝底「投屏」（发现但未连接时是「连接」）；投屏中 → 红底「停止」，颜色随状态来回切。
     */
    private void applyMirrorButton(Session s, boolean mirroring) {
        if (s == null || s.btnMirror == null) {
            return;
        }
        if (mirroring) {
            s.btnMirror.setText(R.string.btn_mirror_stop);
            s.btnMirror.setBackgroundTintList(ColorStateList.valueOf(
                    getResources().getColor(R.color.danger, getTheme())));
        } else {
            s.btnMirror.setText(s.discovered ? R.string.btn_link : R.string.btn_mirror);
            s.btnMirror.setBackgroundTintList(ColorStateList.valueOf(
                    getResources().getColor(R.color.primary, getTheme())));
        }
    }

    private void refreshRowButtons(Session s) {
        if (s.btnMirror != null) {
            s.btnMirror.setEnabled(!busy);
        }
        boolean connectedActionable = !busy && !s.discovered && s.dev != null;
        if (s.btnDisconnect != null) {
            s.btnDisconnect.setEnabled(connectedActionable);
        }
        if (s.btnReboot != null) {
            s.btnReboot.setEnabled(connectedActionable);
        }
        if (s.cb != null) {
            s.cb.setEnabled(true);
        }
    }

    /** 搜索 / 刷新期间禁用所有操作按钮 */
    private void setBusy(boolean value) {
        busy = value;
        btnConnect.setEnabled(!value);
        btnRefresh.setEnabled(!value);
        btnSearch.setEnabled(!value);
        btnInstall.setEnabled(!value);
        btnUninstall.setEnabled(!value);
        btnExitDesktop.setEnabled(!value);
        etHost.setEnabled(!value);
        etPort.setEnabled(!value);
        if (!value) {
            btnConnect.setText(R.string.btn_connect);
        }
        for (Session s : sessions) {
            refreshRowButtons(s);
        }
    }

    private void select(Session s) {
        selectedSession = s;
        suppressCheck = true;
        for (Session x : sessions) {
            if (x.cb != null) {
                x.cb.setChecked(x == s);
            }
        }
        suppressCheck = false;
        if (s != null) {
            log(INFO, "已选中设备：" + rowName(s));
        }
    }

    private String rowName(Session s) {
        if (s.info != null && !s.info.name.isEmpty()) {
            return s.info.name;
        }
        return s.host + ":" + s.port;
    }

    /** 对「搜索发现的设备」发起连接 */
    private void connectRow(final Session session) {
        session.tvState.setText("连接中…");
        log(INFO, "正在连接 " + session.host + ":" + session.port + " …");
        final long start = System.currentTimeMillis();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    AdbCrypto crypto = AdbCrypto.loadOrCreate(getFilesDir());
                    final AdbDevice dev = AdbDevice.connect(session.host, session.port, crypto,
                            MainActivity.this);
                    final DevInfo info = dev.readInfo();
                    final long cost = System.currentTimeMillis() - start;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            session.dev = dev;
                            session.info = info;
                            applyRowUi(session, true);
                            registerClosedListener(session, dev);
                            log(OK, "连接成功：" + info.name + " · " + session.host + ":"
                                    + session.port + "（" + cost + " ms）");
                            if (selectedSession == null) {
                                select(session);
                            }
                        }
                    });
                } catch (Exception e) {
                    final String m = msg(e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            applyRowUi(session, false);
                            log(ERR, "连接失败：" + session.host + ":" + session.port + "：" + m);
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------- 刷新 / 搜索

    private void onRefreshClicked() {
        if (busy) {
            toast("正在处理中，请稍候");
            return;
        }
        final List<String> hosts = new ArrayList<String>();
        final List<Integer> ports = new ArrayList<Integer>();
        for (Session s : sessions) {
            if (!s.discovered) {
                hosts.add(s.host);
                ports.add(s.port);
            }
        }
        log(INFO, "刷新：先清空列表，再重连 " + hosts.size() + " 台已连接设备…");
        clearAllRows();
        if (hosts.isEmpty()) {
            log(WARN, "刷新完成：列表已清空（原本没有已连接的设备）");
            return;
        }
        setBusy(true);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                int okCount = 0;
                int failCount = 0;
                for (int i = 0; i < hosts.size(); i++) {
                    final String h = hosts.get(i);
                    final int p = ports.get(i);
                    try {
                        AdbCrypto crypto = AdbCrypto.loadOrCreate(getFilesDir());
                        AdbDevice dev = AdbDevice.connect(h, p, crypto, MainActivity.this);
                        DevInfo info = dev.readInfo();
                        okCount++;
                        final AdbDevice fd = dev;
                        final DevInfo fi = info;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                addRow(h, p, fd, fi, false, -1);
                            }
                        });
                    } catch (Exception e) {
                        failCount++;
                        final String m = msg(e);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                log(ERR, "刷新重连失败 " + h + ":" + p + "：" + m);
                            }
                        });
                    }
                }
                final int okF = okCount;
                final int failF = failCount;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        log(failF == 0 ? OK : WARN, "刷新完成：成功 " + okF + " 台，失败 " + failF + " 台");
                    }
                });
            }
        });
    }

    private void clearAllRows() {
        stopMirror();
        for (Session s : sessions) {
            try {
                if (s.dev != null) {
                    s.dev.close();
                }
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
        llDevices.removeAllViews();
        selectedSession = null;
        tvNoDevice.setVisibility(View.VISIBLE);
    }

    private void onSearchClicked() {
        if (busy) {
            toast("正在处理中，请稍候");
            return;
        }
        final int port = inputPort();
        final String[] net = localNetwork();
        if (net == null) {
            log(ERR, "未获取到本机 IP，无法确定搜索网段");
            toast("请确认手机已连接 WiFi 后重试");
            return;
        }
        final String base = net[1];
        final String selfIp = net[0];
        log(INFO, "开始搜索网段 " + base + ".1 ~ " + base + "." + SCAN_TOTAL
                + "（端口 " + port + "，本机 " + selfIp + "）");
        setBusy(true);

        final TextView tvProgress = new TextView(this);
        tvProgress.setTextSize(12);
        tvProgress.setTextColor(getResources().getColor(R.color.text_main, getTheme()));
        tvProgress.setText("准备扫描…");

        final ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(SCAN_TOTAL);
        bar.setProgress(0);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        content.setPadding(pad, dp(14), pad, dp(6));
        content.addView(tvProgress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        blp.topMargin = dp(10);
        content.addView(bar, blp);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("搜索 ADB 设备")
                .setView(content)
                .setCancelable(false)
                .create();
        dlg.show();
        progressDialog = dlg;

        worker.execute(new Runnable() {
            @Override
            public void run() {
                final Set<String> found = Collections.synchronizedSet(new LinkedHashSet<String>());
                final AtomicInteger done = new AtomicInteger(0);
                ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
                for (int i = 1; i <= SCAN_TOTAL; i++) {
                    final String ip = base + "." + i;
                    if (ip.equals(selfIp)) {
                        done.incrementAndGet();
                        continue;
                    }
                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (AdbConnection.probe(ip, port, 600, 1200)) {
                                    found.add(ip);
                                }
                            } catch (Throwable ignored) {
                            }
                            final int d = done.incrementAndGet();
                            if (d % 6 == 0 || d >= SCAN_TOTAL) {
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        bar.setProgress(d);
                                        tvProgress.setText("正在扫描 " + d + "/" + SCAN_TOTAL
                                                + "　已发现 " + found.size() + " 台");
                                    }
                                });
                            }
                        }
                    });
                }
                pool.shutdown();
                try {
                    pool.awaitTermination(120, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                final List<String> hosts = new ArrayList<String>(found);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (progressDialog != null) {
                            progressDialog.dismiss();
                            progressDialog = null;
                        }
                        setBusy(false);
                        if (hosts.isEmpty()) {
                            log(WARN, "搜索完成：" + base + ".0/24 网段未发现 ADB 设备（端口 " + port + "）");
                            toast("未发现 ADB 设备");
                            return;
                        }
                        log(OK, "搜索完成：发现 " + hosts.size() + " 台 ADB 设备");
                        for (String ip : hosts) {
                            addRow(ip, port, null, null, true, -1);
                        }
                    }
                });
            }
        });
    }

    /** 返回 {本机 IP, 网段前缀}，例如 {"192.168.0.117","192.168.0"} */
    private String[] localNetwork() {
        String fallbackIp = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (!(addr instanceof Inet4Address)) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (TextUtils.isEmpty(ip) || !ip.contains(".")) {
                        continue;
                    }
                    String base = ip.substring(0, ip.lastIndexOf('.'));
                    if (base.startsWith("192.168.") || base.startsWith("10.")
                            || base.startsWith("172.")) {
                        return new String[]{ip, base};
                    }
                    if (fallbackIp == null) {
                        fallbackIp = ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (fallbackIp != null) {
            return new String[]{fallbackIp, fallbackIp.substring(0, fallbackIp.lastIndexOf('.'))};
        }
        return null;
    }

    // ----------------------------------------------------------------- 投屏

    private void registerClosedListener(final Session session, AdbDevice dev) {
        if (dev == null) {
            return;
        }
        dev.setOnClosedListener(new Runnable() {
            @Override
            public void run() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!sessions.contains(session)) {
                            return;
                        }
                        if (session.mirroring) {
                            stopMirror();
                        }
                        if (session.tvState != null) {
                            session.tvState.setText("已断开");
                            session.tvState.setTextColor(getResources().getColor(
                                    R.color.err_red, getTheme()));
                        }
                        log(WARN, "连接已断开（设备侧关闭连接）：" + session.host + ":" + session.port
                                + "，可点「刷新」重连或点「断开」移除该行");
                    }
                });
            }
        });
    }

    /** 连接已失效时自动重连（设备在执行 pm install / uninstall 期间会短暂回收 adbd 连接） */
    private boolean ensureAlive(final Session session) {
        if (session.dev != null && session.dev.isAlive()) {
            return true;
        }
        log(WARN, "连接已失效，正在自动重连 " + session.host + ":" + session.port + " …");
        for (int i = 0; i < 3; i++) {
            try {
                AdbCrypto crypto = AdbCrypto.loadOrCreate(getFilesDir());
                AdbDevice dev = AdbDevice.connect(session.host, session.port, crypto, MainActivity.this);
                DevInfo info = dev.readInfo();
                session.dev = dev;
                session.info = info;
                registerClosedListener(session, dev);
                final DevInfo fi = info;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (session.tvName != null) {
                            session.tvName.setText(fi.name);
                        }
                        if (session.tvInfo != null) {
                            session.tvInfo.setText(fi.line1());
                        }
                        if (session.tvInfo2 != null) {
                            session.tvInfo2.setText(fi.line2());
                        }
                        updateState(session, "已连接");
                    }
                });
                log(OK, "自动重连成功：" + info.name + " · " + session.host + ":" + session.port);
                return true;
            } catch (Exception e) {
                log(WARN, "自动重连第 " + (i + 1) + " 次失败：" + msg(e));
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        log(ERR, "自动重连失败，请稍后手动重新连接");
        return false;
    }

    private void toggleMirror(Session session) {
        if (session.mirroring) {
            stopMirror();
            return;
        }
        // 画布同时只能显示一台设备：别的设备正在投屏时不允许抢占，先让用户自己停掉
        Session cur = activeSession;
        if (cur != null && cur != session && (cur.mirroring || scrcpy != null || mirror != null)) {
            toast("先停止其他设备投屏");
            log(WARN, "无法投屏：" + rowName(cur) + " 正在投屏，请先停止其他设备投屏");
            return;
        }
        if (scrcpy != null || mirror != null) {
            stopMirror();
        }
        activeSession = session;
        session.mirroring = true;
        applyMirrorButton(session, true);
        session.tvState.setText("投屏中");
        tvPreviewHint.setVisibility(View.GONE);
        tvPreviewState.setVisibility(View.VISIBLE);
        tvPreviewState.setText("正在启动视频流…");
        startVideoMirror(session);
    }

    /** scrcpy-server + MediaCodec 视频流（主路径） */
    private void startVideoMirror(final Session session) {
        // 先把可能还在跑的截图流停掉，避免两条链路同时抢预览画布
        ScreenMirror old = mirror;
        mirror = null;
        if (old != null) {
            try {
                old.stop();
            } catch (Throwable ignored) {
            }
        }
        TextureView tv = previewView.getOrCreateTexture();
        previewView.setVideoMode(true, 0, 0);
        tv.setSurfaceTextureListener(videoSurfaceListener);
        if (tv.isAvailable() && tv.getSurfaceTexture() != null) {
            launchScrcpy(session, new Surface(tv.getSurfaceTexture()));
        } else {
            pendingSession = session;
            log(INFO, "预览画布准备中，就绪后自动启动视频流…");
        }
    }

    /** TextureView 就绪 / 重建（全屏切换）时回调 */
    private final TextureView.SurfaceTextureListener videoSurfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                    Session pending = pendingSession;
                    pendingSession = null;
                    if (pending != null) {
                        launchScrcpy(pending, new Surface(st));
                        return;
                    }
                    ScrcpyMirror m = scrcpy;
                    log(INFO, "画面 Surface 已重建（" + width + "x" + height + "），交给解码器");
                    if (m != null) {
                        boundTexture = st;
                        m.updateSurface(new Surface(st));
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    // 返回 false：保留 SurfaceTexture，不全屏/非全屏切换时重新挂载可继续复用，
                    // 避免解码器输出 Surface 被释放导致解码线程 Invalid to call at Released state
                    log(INFO, "画面 Surface 已分离（保留复用）");
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            };

    /** 真正把 scrcpy 链路拉起来（Surface 已就绪） */
    private void launchScrcpy(final Session session, Surface surface) {
        final AssetManager am = getAssets();
        ScrcpyMirror.Listener listener = new ScrcpyMirror.Listener() {
            @Override
            public void onStarted(int width, int height, String codec, boolean control) {
                final String text = codec.toUpperCase(Locale.US) + " " + width + "x" + height
                        + (control ? " · 可触控" : " · 仅观看");
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        log(OK, "视频流已连接：" + text + "（scrcpy-server v" + ScrcpyServer.VERSION
                                + " · MediaCodec 硬解）");
                        previewView.setVideoMode(true, width, height);
                        if (tvPreviewState.getVisibility() != View.GONE) {
                            tvPreviewState.setText(text);
                        }
                    }
                });
            }

            @Override
            public void onFrame(final Bitmap bmp, final float fps) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Session s = activeSession;
                        String res = s != null ? (scrcpy != null
                                ? scrcpy.getVideoWidth() + "x" + scrcpy.getVideoHeight() : "") : "";
                        lastFrameInfo = res + "  " + String.format(Locale.US, "%.1f FPS", fps);
                        previewView.setFrame(bmp, false);
                        if (tvPreviewState.getVisibility() != View.GONE) {
                            tvPreviewState.setText(lastFrameInfo);
                        }
                        if (fullscreen) {
                            updateFullStateText();
                        }
                        tvPreviewHint.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onVideoFrame(final float fps) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        String res = scrcpy != null
                                ? scrcpy.getVideoWidth() + "x" + scrcpy.getVideoHeight() : "";
                        lastFrameInfo = res + "  " + String.format(Locale.US, "%.1f FPS", fps);
                        if (tvPreviewState.getVisibility() != View.GONE) {
                            tvPreviewState.setText(lastFrameInfo);
                        }
                        if (fullscreen) {
                            updateFullStateText();
                        }
                        tvPreviewHint.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onError(final String m) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        log(WARN, "投屏：" + m);
                    }
                });
            }

            @Override
            public void onEnded(final String reason) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        finishMirror(reason);
                    }
                });
            }

            @Override
            public void onFallback(final String reason) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        log(WARN, "视频流不可用：" + reason);
                        log(INFO, "已回退到 screencap 截图流（画面会明显变慢）");
                        ScrcpyMirror sm = scrcpy;
                        scrcpy = null;
                        if (sm != null) {
                            sm.stop();
                        }
                        startScreencapMirror(session);
                    }
                });
            }

            @Override
            public void onServerLog(String line) {
                final String clean = line.trim();
                if (clean.isEmpty()) {
                    return;
                }
                String low = clean.toLowerCase(Locale.US);
                final boolean important = low.contains("error") || low.contains("exception")
                        || low.contains("warn") || low.contains("fail");
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        log(important ? WARN : INFO, "scrcpy-server：" + clean);
                    }
                });
            }
        };

        ScrcpyServer.JarSource jar = new ScrcpyServer.JarSource() {
            @Override
            public InputStream open() throws IOException {
                return am.open("scrcpy-server.jar");
            }

            @Override
            public long size() {
                return assetSize(am, "scrcpy-server.jar");
            }
        };

        // 视频画面直接画到预览画布里内嵌的 TextureView（MediaCodec 直出，零拷贝）
        ScrcpyMirror m = new ScrcpyMirror(session.dev, this, jar, VIDEO_MAX_SIZE, VIDEO_MAX_FPS,
                VIDEO_BIT_RATE, surface, listener);
        scrcpy = m;
        TextureView cv = previewView.getOrCreateTexture();
        boundTexture = cv != null ? cv.getSurfaceTexture() : null;
        m.start();
        log(INFO, "开始投屏：" + rowName(session) + "（scrcpy-server v" + ScrcpyServer.VERSION
                + " · H.264 " + VIDEO_MAX_FPS + "FPS）");
    }

    private static long assetSize(AssetManager am, String name) {
        InputStream in = null;
        try {
            in = am.open(name);
            long n = 0;
            byte[] b = new byte[4096];
            int r;
            while ((r = in.read(b)) > 0) {
                n += r;
            }
            return n;
        } catch (Exception e) {
            return 0;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 老的截图流（scrcpy 起不来时兜底） */
    private void startScreencapMirror(final Session session) {
        mirror = new ScreenMirror(session.dev, this, new ScreenMirror.Listener() {
            @Override
            public void onFrame(final Bitmap bmp, final float fps) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Session s = activeSession;
                        String res = s != null && s.info != null ? s.info.resolution : "";
                        lastFrameInfo = res + "  " + String.format(Locale.US, "%.1f FPS", fps);
                        previewView.setFrame(bmp);
                        if (tvPreviewState.getVisibility() != View.GONE) {
                            tvPreviewState.setText(lastFrameInfo);
                        }
                        if (fullscreen) {
                            updateFullStateText();
                        }
                        tvPreviewHint.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onError(final String m) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        log(WARN, "投屏：" + m);
                    }
                });
            }

            @Override
            public void onEnded(final String reason) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        finishMirror(reason);
                    }
                });
            }
        }, 500);
        mirror.start();
    }

    private void stopMirror() {
        ScrcpyMirror sm = scrcpy;
        scrcpy = null;
        if (sm != null) {
            sm.stop();
        }
        ScreenMirror m = mirror;
        mirror = null;
        if (m != null) {
            m.stop();
        }
        finishMirror("已停止");
    }

    private void finishMirror(String reason) {
        Session s = activeSession;
        boolean wasMirroring = s != null && s.mirroring;
        if (s != null) {
            s.mirroring = false;
            if (s.btnMirror != null) {
                applyMirrorButton(s, false);
                if (s.tvState != null && s.dev != null) {
                    s.tvState.setText("已连接");
                    s.tvState.setTextColor(getResources().getColor(R.color.ok_green, getTheme()));
                }
            }
        }
        activeSession = null;
        previewView.setVideoMode(false, 0, 0);
        previewView.setFrame(null);
        boundTexture = null;
        touchDownNx = -1f;
        touchDownNy = -1f;
        if (fullscreen) {
            exitFullscreen();
        }
        tvPreviewHint.setVisibility(View.VISIBLE);
        tvPreviewState.setVisibility(View.GONE);
        if (wasMirroring && reason != null && !reason.isEmpty()) {
            log(INFO, "投屏结束：" + reason);
        }
    }

    // -------------------------------------------------------------- 全屏 / 旋转

    private void enterFullscreen() {
        if (activeSession == null) {
            toast("请先点「投屏」开始预览，再进入全屏");
            return;
        }
        if (!previewView.hasPicture()) {
            toast("正在等待首帧画面…");
            return;
        }
        fullscreen = true;
        ViewGroup parent = (ViewGroup) previewView.getParent();
        if (parent != null) {
            parent.removeView(previewView);
        }
        previewView.setRotationDegrees(0);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        flFull.addView(previewView, 0, lp);
        flFull.setVisibility(View.VISIBLE);
        hideSystemBars();
        updateFullStateText();
        rebindVideoSurface();
        boolean touchable = scrcpy != null && scrcpy.isControlReady();
        log(INFO, "进入全屏预览" + (touchable ? "（可触控/拖动画面直接操作设备）" : "（仅观看，无法触控）"));
    }

    /** 预览画布被搬进/搬出全屏容器后，TextureView 的 Surface 会重建，重新挂上监听即可自动交给解码器 */
    private void rebindVideoSurface() {
        if (scrcpy == null) {
            return;
        }
        final TextureView tv = previewView.getOrCreateTexture();
        tv.setSurfaceTextureListener(videoSurfaceListener);
        if (tv.isAvailable() && tv.getSurfaceTexture() != null) {
            SurfaceTexture st = tv.getSurfaceTexture();
            if (st == boundTexture) {
                // 同一个 SurfaceTexture，画面沿用，无需切解码输出
                return;
            }
            boundTexture = st;
            scrcpy.updateSurface(new Surface(st));
        }
    }

    private void exitFullscreen() {
        if (!fullscreen) {
            flFull.setVisibility(View.GONE);
            return;
        }
        fullscreen = false;
        ViewGroup parent = (ViewGroup) previewView.getParent();
        if (parent != null) {
            parent.removeView(previewView);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        previewBox.addView(previewView, 0, lp);
        previewView.setRotationDegrees(0);
        flFull.setVisibility(View.GONE);
        showSystemBars();
        rebindVideoSurface();
        boolean running = activeSession != null;
        tvPreviewHint.setVisibility(running ? View.GONE : View.VISIBLE);
        tvPreviewState.setVisibility(running ? View.VISIBLE : View.GONE);
        log(INFO, "退出全屏");
    }

    private void rotatePreview() {
        int deg = previewView.getRotationDegrees();
        previewView.setRotationDegrees((deg + 90) % 360);
        updateFullStateText();
        log(INFO, "画面旋转：" + (previewView.getRotationDegrees() % 180 == 0 ? "竖向" : "横向"));
    }

    private void updateFullStateText() {
        int deg = previewView.getRotationDegrees();
        boolean touchable = scrcpy != null && scrcpy.isControlReady();
        tvFullState.setText((TextUtils.isEmpty(lastFrameInfo) ? "画面" : lastFrameInfo)
                + "　" + (deg % 180 == 0 ? "竖向" : "横向")
                + (touchable ? "　可触控" : "　仅观看"));
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @SuppressWarnings("deprecation")
    private void showSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    // ------------------------------------------------------------- 断开/重启

    private void disconnect(final Session session) {
        if (session.mirroring) {
            stopMirror();
        }
        try {
            if (session.dev != null) {
                session.dev.close();
            }
        } catch (Exception ignored) {
        }
        sessions.remove(session);
        llDevices.removeView(session.row);
        if (selectedSession == session) {
            selectedSession = null;
        }
        if (sessions.isEmpty()) {
            tvNoDevice.setVisibility(View.VISIBLE);
            selectedSession = null;
        } else if (selectedSession == null) {
            for (Session s : sessions) {
                if (!s.discovered) {
                    select(s);
                    break;
                }
            }
        }
        log(OK, "已断开连接：" + rowName(session));
    }

    private void confirmReboot(final Session session) {
        String name = rowName(session);
        new AlertDialog.Builder(this)
                .setTitle("重启设备")
                .setMessage("确定重启「" + name + "」吗？设备会立即重启并断开当前连接。")
                .setPositiveButton("重启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        log(WARN, "正在重启设备：" + name);
                        worker.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    session.dev.reboot();
                                } catch (Exception e) {
                                    log(WARN, "重启指令：" + msg(e));
                                }
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        disconnect(session);
                                        toast("已下发重启指令");
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // -------------------------------------------------------------- 安装卸载

    /** 取当前勾选的设备；未选中时给出提示 */
    private Session requireSelected() {
        if (sessions.isEmpty()) {
            log(ERR, "请先连接设备");
            toast("请先连接设备");
            return null;
        }
        Session s = selectedSession;
        if (s == null) {
            log(WARN, "未选中设备：请先勾选列表左侧的复选框");
            toast("请先在列表中勾选一台设备");
            return null;
        }
        if (s.discovered) {
            log(WARN, "选中的设备尚未连接：" + s.host + ":" + s.port);
            toast("该设备尚未连接，请先点「连接」");
            return null;
        }
        return s;
    }

    private void onInstallClicked() {
        final Session session = requireSelected();
        if (session == null) {
            return;
        }
        installSession = session;
        // 已选过的本地安装包在重连/重新打开弹窗后继续保留，换设备时也可见（由用户自行确认）
        showInstallDialog(session);
    }

    /** 弹窗正文：待安装应用信息 + 已选本地安装包（不出现任何「内置」字样） */
    private String installDialogText(Session session, BuiltinMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标设备：").append(rowName(session)).append("\n\n");
        if (hasBuiltin(meta)) {
            sb.append("待安装应用\n");
            sb.append("应用名称：").append(meta.label.isEmpty() ? "-" : meta.label).append('\n');
            sb.append("包名：").append(meta.pkg.isEmpty() ? "-" : meta.pkg).append('\n');
            sb.append("版本：").append(versionText(meta)).append('\n');
            sb.append("大小：").append(AdbDevice.human(meta.size));
        } else {
            sb.append("本次未随应用打包安装包文件，");
            sb.append("请点「从手机选择 APK」选择安装包。");
        }
        sb.append("\n\n");
        if (pickedUri != null) {
            sb.append("已选择安装包：").append(pickedName);
            if (pickedSize > 0) {
                sb.append("（").append(AdbDevice.human(pickedSize)).append("）");
            }
        } else {
            sb.append("已选择安装包：无");
        }
        return sb.toString();
    }

    private void showInstallDialog(final Session session) {
        final BuiltinMeta meta = readBuiltinMeta();
        // 每次都用新的 TextView：旧弹窗关闭后视图仍挂在原来的 Dialog 上，复用会抛
        // IllegalStateException: The specified child already has a parent
        installInfoView = new TextView(this);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        installInfoView.setPadding(pad, pad, pad, 0);
        installInfoView.setTextSize(15f);
        installInfoView.setTextColor(0xFF222222);
        installInfoView.setTextIsSelectable(true);
        installInfoView.setText(installDialogText(session, meta));

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("安装软件")
                .setView(installInfoView)
                .setPositiveButton("安装应用", null)
                .setNeutralButton("从手机选择 APK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pickApkFromPhone(session);
                    }
                })
                .setNegativeButton("取消", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        installDialog = null;
                    }
                });
        installDialog = b.create();
        installDialog.show();
        // 自己接管点击：没有可选安装包时只提示，不关闭弹窗
        installDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pickedUri != null) {
                    Session s = installSession;
                    Uri u = pickedUri;
                    pickedUri = null;
                    pickedName = "";
                    pickedSize = -1;
                    if (installDialog != null) {
                        installDialog.dismiss();
                    }
                    if (s == null || s.dev == null) {
                        toast("设备已断开，请重新连接后再安装");
                        return;
                    }
                    installFromUri(s, u);
                    return;
                }
                BuiltinMeta m = readBuiltinMeta();
                if (hasBuiltin(m)) {
                    Session s = installSession;
                    if (installDialog != null) {
                        installDialog.dismiss();
                    }
                    if (s == null || s.dev == null) {
                        toast("设备已断开，请重新连接后再安装");
                        return;
                    }
                    doInstall(s, m);
                    return;
                }
                toast("请选择APK安装包文件");
            }
        });
    }


    /** 只有在打包含成功写过 payload.idx（size>0）时才算有内置包 */
    private boolean hasBuiltin(BuiltinMeta meta) {
        return meta != null && meta.size > 0;
    }

    private String versionText(BuiltinMeta meta) {
        String name = meta.versionName == null ? "" : meta.versionName.trim();
        String code = meta.versionCode == null ? "" : meta.versionCode.trim();
        if (name.isEmpty() && code.isEmpty()) {
            return "-";
        }
        if (code.isEmpty()) {
            return name;
        }
        return name.isEmpty() ? "versionCode " + code : name + "（versionCode " + code + "）";
    }

    // --------------------------------------------------------- 从手机本地选 APK

    /** 打开系统文件选择器，让用户从手机本地目录挑一个 APK */
    private void pickApkFromPhone(Session session) {
        pendingInstallSession = session;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/vnd.android.package-archive");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择 APK 安装包"), REQ_PICK_APK);
        } catch (Exception e) {
            pendingInstallSession = null;
            log(ERR, "打开文件选择器失败：" + msg(e));
            toast("没有找到可用的文件管理器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK) {
            return;
        }
        Session session = pendingInstallSession;
        pendingInstallSession = null;
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (uri == null) {
            return;
        }
        if (session == null || session.dev == null) {
            toast("设备已断开，请重新连接后再安装");
            return;
        }
        // 只记录所选安装包，由用户点「安装应用」后才真正开始安装
        String[] info = queryPickInfo(uri);
        pickedUri = uri;
        pickedName = info[0];
        pickedSize = parseLong(info[1]);
        installSession = session;
        log(INFO, "已选择安装包：" + pickedName + (pickedSize > 0 ? "（" + AdbDevice.human(pickedSize) + "）" : ""));
        if (installDialog != null && installInfoView != null) {
            installInfoView.setText(installDialogText(session, readBuiltinMeta()));
        } else {
            showInstallDialog(session);
        }
        toast("已选择：" + pickedName + "，请点「安装应用」开始安装");
    }

    /** 读取用户选中的 APK 并安装：边读边传，装完删除设备侧临时文件 */
    private void installFromUri(final Session session, final Uri uri) {
        final String[] info = queryPickInfo(uri);   // [0]=文件名 [1]=大小(可能为空)
        final String fileName = info[0];
        final long declared = parseLong(info[1]);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String name = rowName(session);
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) {
                        throw new IOException("无法读取所选文件");
                    }
                    ensureAlive(session);
                    long total = declared > 0 ? declared : -1;
                    log(INFO, "开始安装「" + fileName + "」→ " + name
                            + (total > 0 ? "（" + AdbDevice.human(total) + "）" : ""));
                    updateState(session, "上传中");
                    final int[] lastPct = {-1};
                    String result = session.dev.installApk(in, total, new AdbDevice.ProgressListener() {
                        @Override
                        public void onProgress(long sent, long tot) {
                            if (tot <= 0) {
                                updateState(session, "上传 " + AdbDevice.human(sent));
                                return;
                            }
                            final int pct = (int) (sent * 100 / tot);
                            if (pct >= lastPct[0] + 20 || pct >= 100) {
                                lastPct[0] = pct;
                                log(INFO, "上传安装包 " + pct + "%");
                            }
                            updateState(session, "上传 " + pct + "%");
                        }
                    });
                    final String res = result;
                    ensureAlive(session);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateState(session, "已连接");
                            if (res.toLowerCase().contains("success")) {
                                log(OK, "安装成功：" + fileName);
                                toast("安装成功");
                            } else {
                                log(ERR, "安装失败：" + res);
                                toast("安装失败，详见日志");
                            }
                        }
                    });
                } catch (Exception e) {
                    final String m = msg(e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateState(session, "已连接");
                            log(ERR, "安装异常：" + m);
                            toast("安装失败，详见日志");
                        }
                    });
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        });
    }

    /** 用 ContentResolver 查文件名与大小（不同文件管理器返回的不一样，拿不到就用兜底名） */
    private String[] queryPickInfo(Uri uri) {
        String fileName = "所选安装包";
        String size = "";
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0 && !c.isNull(ni)) {
                    fileName = c.getString(ni);
                }
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (si >= 0 && !c.isNull(si)) {
                    size = String.valueOf(c.getLong(si));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        return new String[]{fileName, size};
    }

    private long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private void doInstall(final Session session, final BuiltinMeta meta) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String name = rowName(session);
                try {
                    ensureAlive(session);
                    // 旧的明文缓存（老版本落过盘）不再使用，顺手清掉
                    File stale = new File(getCacheDir(), "builtin.apk");
                    if (stale.isFile() && stale.delete()) {
                        log(INFO, "已清理旧版缓存文件：" + stale.getName());
                    }
                    InputStream apk = openBuiltinApkStream(meta);
                    log(INFO, "开始安装「" + meta.label + "」→ " + name
                            + "（" + AdbDevice.human(meta.size) + "）");
                    updateState(session, "上传中");
                    final int[] lastPct = {-1};
                    String result = session.dev.installApk(apk, meta.size, new AdbDevice.ProgressListener() {
                        @Override
                        public void onProgress(long sent, long total) {
                            final int pct = (int) (sent * 100 / total);
                            if (pct >= lastPct[0] + 20 || pct >= 100) {
                                lastPct[0] = pct;
                                log(INFO, "上传安装包 " + pct + "%");
                            }
                            updateState(session, "上传 " + pct + "%");
                        }
                    });
                    final String res = result;
                    // 设备执行安装后常会短暂回收 adbd 连接，这里主动恢复
                    ensureAlive(session);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateState(session, "已连接");
                            if (res.toLowerCase().contains("success")) {
                                log(OK, "安装成功：" + meta.label);
                                toast("安装成功");
                            } else {
                                log(ERR, "安装失败：" + res);
                                toast("安装失败，详见日志");
                            }
                        }
                    });
                } catch (Exception e) {
                    final String m = msg(e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateState(session, "已连接");
                            log(ERR, "安装异常：" + m);
                        }
                    });
                }
            }
        });
    }

    private void onUninstallClicked() {
        final Session session = requireSelected();
        if (session == null) {
            return;
        }
        final String name = rowName(session);
        log(INFO, "正在读取「" + name + "」上的第三方应用清单…");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureAlive(session);
                    final List<String[]> apps = session.dev.listThirdPartyApps();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            log(INFO, "第三方应用：" + apps.size() + " 个（系统应用已过滤）");
                            showAppListDialog(session, apps);
                        }
                    });
                } catch (Exception e) {
                    final String m = msg(e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            log(ERR, "读取应用列表失败：" + m);
                            toast("读取应用列表失败");
                        }
                    });
                }
            }
        });
    }

    /** 卸载弹窗：只列第三方应用，每行一个「卸载」按钮 */
    private void showAppListDialog(final Session session, final List<String[]> apps) {
        final String name = rowName(session);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(4), dp(14), dp(6));

        TextView tip = new TextView(this);
        tip.setText("共 " + apps.size() + " 个第三方应用（系统应用不显示、不支持卸载）");
        tip.setTextSize(11);
        tip.setTextColor(getResources().getColor(R.color.text_sub, getTheme()));
        tip.setPadding(0, dp(6), 0, dp(6));
        list.addView(tip);
        appListTip = tip;
        appListBox = list;

        final AlertDialog[] holder = new AlertDialog[1];

        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("该设备上没有可卸载的第三方应用");
            empty.setTextSize(12);
            empty.setPadding(0, dp(16), 0, dp(16));
            empty.setTextColor(getResources().getColor(R.color.text_sub, getTheme()));
            list.addView(empty);
        }

        BuiltinMeta builtin = readBuiltinMeta();
        for (String[] app : apps) {
            list.addView(buildAppRow(session, app[0], app[1], builtin, holder));
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(list);

        holder[0] = new AlertDialog.Builder(this)
                .setTitle("卸载软件 · " + name)
                .setView(sv)
                .setNegativeButton("关闭", null)
                .create();
        holder[0].show();
    }

    private View buildAppRow(final Session session, final String pkg, final String ver,
                             BuiltinMeta builtin, final AlertDialog[] holder) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        TextView nameTv = new TextView(this);
        if (!builtin.pkg.isEmpty() && builtin.pkg.equals(pkg)) {
            nameTv.setText(builtin.label + "（" + pkg + "）");
        } else {
            nameTv.setText(pkg);
        }
        nameTv.setTextSize(12);
        nameTv.setTextColor(getResources().getColor(R.color.text_main, getTheme()));
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);

        TextView subTv = new TextView(this);
        subTv.setText(TextUtils.isEmpty(ver) ? "版本未知" : "版本 " + ver);
        subTv.setTextSize(10);
        subTv.setTextColor(getResources().getColor(R.color.text_sub, getTheme()));

        block.addView(nameTv);
        block.addView(subTv);

        LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(block, blockLp);

        Button uninstallBtn = new Button(this);
        uninstallBtn.setText("卸载");
        uninstallBtn.setTextSize(11);
        uninstallBtn.setAllCaps(false);
        uninstallBtn.setTextColor(Color.WHITE);
        uninstallBtn.setMinWidth(0);
        uninstallBtn.setMinimumWidth(0);
        uninstallBtn.setMinHeight(0);
        uninstallBtn.setMinimumHeight(0);
        uninstallBtn.setPadding(0, 0, 0, 0);
        uninstallBtn.setBackgroundTintList(ColorStateList.valueOf(
                getResources().getColor(R.color.uninstall, getTheme())));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(64), dp(34));
        btnLp.leftMargin = dp(8);
        row.addView(uninstallBtn, btnLp);
        uninstallBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmUninstallPkg(session, pkg, row, holder);
            }
        });

        View divider = new View(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        divider.setBackgroundColor(getResources().getColor(R.color.divider, getTheme()));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        wrap.addView(divider, divLp);
        return wrap;
    }

    private void updateAppListCount() {
        if (appListTip == null || appListBox == null) {
            return;
        }
        int n = Math.max(0, appListBox.getChildCount() - 1);
        appListTip.setText("共 " + n + " 个第三方应用（系统应用不显示、不支持卸载）");
    }

    private void confirmUninstallPkg(final Session session, final String pkg, final View rowView,
                                     final AlertDialog[] holder) {
        new AlertDialog.Builder(this)
                .setTitle("卸载应用")
                .setMessage("确定从「" + rowName(session) + "」卸载：\n" + pkg + " ?")
                .setPositiveButton("卸载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doUninstallPkg(session, pkg, rowView, holder);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doUninstallPkg(final Session session, final String pkg, final View rowView,
                                final AlertDialog[] holder) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    log(INFO, "开始卸载：" + pkg);
                    ensureAlive(session);
                    String res = session.dev.uninstall(pkg);
                    final String r = res;
                    ensureAlive(session);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (r.toLowerCase().contains("success")) {
                                log(OK, "卸载成功：" + pkg);
                                toast("卸载成功");
                                ViewGroup parent = (ViewGroup) rowView.getParent();
                                if (parent != null) {
                                    parent.removeView(rowView);
                                }
                                updateAppListCount();
                            } else {
                                log(ERR, "卸载失败：" + pkg + "：" + r);
                                toast("卸载失败，详见日志");
                            }
                        }
                    });
                } catch (Exception e) {
                    final String m = msg(e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            log(ERR, "卸载异常：" + pkg + "：" + m);
                        }
                    });
                }
            }
        });
    }

    private void updateState(final Session session, final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (session.tvState != null) {
                    session.tvState.setText(text);
                    session.tvState.setTextColor(getResources().getColor(
                            "已连接".equals(text) || "投屏中".equals(text)
                                    ? R.color.ok_green : R.color.warn_amber, getTheme()));
                }
            }
        });
    }

    // --------------------------------------------------------- 内置 APK 资源

    private BuiltinMeta readBuiltinMeta() {
        BuiltinMeta meta = new BuiltinMeta();
        meta.enc = true;
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = getAssets().open(PAYLOAD_IDX);
            // properties 文件是 UTF-8 编码，必须用 Reader 读，否则中文会乱码
            p.load(new java.io.InputStreamReader(in, "UTF-8"));
            meta.pkg = p.getProperty("package", "").trim();
            meta.label = p.getProperty("label", "").trim();
            meta.versionName = p.getProperty("versionName", "").trim();
            meta.versionCode = p.getProperty("versionCode", "").trim();
            try {
                meta.size = Long.parseLong(p.getProperty("size", "0").trim());
            } catch (Exception ignored) {
            }
            meta.sha256 = p.getProperty("sha256", "").trim();
            try {
                meta.encSize = Long.parseLong(p.getProperty("encSize", "0").trim());
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            meta.label = "";
            meta.size = 0;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
        return meta;
    }

    /**
     * 打开内置安装包的明文字节流：assets 里只有加密容器 hpt_payload.dat（分块 AES-256-GCM），
     * 密钥由「口令与盐 + 本应用签名证书」派生，逐块解密后送出明文，
     * GCM 认证标签保证完整性（被改过会直接报错），明文全程不落盘。
     */
    private InputStream openBuiltinApkStream(BuiltinMeta meta) throws Exception {
        byte[] keyBytes = Secret.key(this);
        BuiltinApkStream stream = new BuiltinApkStream(getAssets().open(PAYLOAD_FILE),
                new SecretKeySpec(keyBytes, "AES"), Secret.keyCheck(keyBytes));
        return stream;
    }

    /**
     * 启动时的内置包准备：只清掉历史版本遗留的明文缓存，
     * 不再往日志窗口输出包名/大小等信息（避免提示「App 里藏了资源」）。
     */
    private void prepareBuiltin() {
        // 老版本会把解密后的 APK 缓存在应用私有目录，这里统一清掉
        File stale = new File(getCacheDir(), "builtin.apk");
        if (stale.isFile()) {
            stale.delete();
        }
    }

    // --------------------------------------------------------------- 版本与网络

    private String versionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    @SuppressWarnings("deprecation")
    private int versionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 本机 IPv4：优先 wlan 网卡（手机对外通信就是这张），其次任意非回环、非链路本地地址。
     * 拿不到时返回提示语，不抛异常（没有 Wi-Fi 也能正常工作，只是不能扫网段）。
     */
    private String localIp() {
        String fallback = null;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()
                            || addr.isLinkLocalAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    String name = nif.getName();
                    if (name.startsWith("wlan")) {
                        return ip + "（" + name + "）";
                    }
                    if (fallback == null) {
                        fallback = ip + "（" + name + "）";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback == null ? "未获取到本机 IP（请确认手机已连接 Wi-Fi）" : fallback;
    }

    // ------------------------------------------------------------------- 日志

    /** 后台线程先塞进待渲染队列，UI 线程最多合并成一次重绘，避免逐条 setText 拖慢界面 */
    @Override
    public void log(final String level, final String msg) {
        // 同时写 logcat，方便用 adb logcat -s HPT 复盘整条链路
        try {
            android.util.Log.i("HPT", level + " " + msg);
        } catch (Throwable ignored) {
        }
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (pendingLogs) {
            pendingLogs.add(new LogLine(time, level, msg));
            while (pendingLogs.size() > MAX_LOG_LINES) {
                pendingLogs.remove(0);
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            drainLogs();
            return;
        }
        boolean needPost;
        synchronized (pendingLogs) {
            needPost = !logFlushPosted;
            logFlushPosted = true;
        }
        if (needPost) {
            ui.post(logFlushTask);
        }
    }

    private final Runnable logFlushTask = new Runnable() {
        @Override
        public void run() {
            synchronized (pendingLogs) {
                logFlushPosted = false;
            }
            drainLogs();
        }
    };

    /** 仅在主线程调用：把待渲染的日志并入列表后整体重绘一次 */
    private void drainLogs() {
        int added = 0;
        synchronized (pendingLogs) {
            for (LogLine l : pendingLogs) {
                logs.add(l);
                added++;
            }
            pendingLogs.clear();
        }
        while (logs.size() > MAX_LOG_LINES) {
            logs.remove(0);
        }
        if (added > 0) {
            renderLogs();
        }
    }

    private void renderLogs() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (LogLine l : logs) {
            int tStart = sb.length();
            sb.append(l.time).append("  ");
            sb.setSpan(new ForegroundColorSpan(0xFF4FB8E8), tStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            int lStart = sb.length();
            sb.append(padLevel(l.level)).append("  ");
            sb.setSpan(new ForegroundColorSpan(levelColor(l.level)), lStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), lStart, lStart + l.level.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            int mStart = sb.length();
            sb.append(l.msg).append('\n');
            sb.setSpan(new ForegroundColorSpan(msgColor(l.level)), mStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvLog.setText(sb);
        svLog.post(new Runnable() {
            @Override
            public void run() {
                svLog.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private static String padLevel(String level) {
        if (level.length() >= 4) {
            return level;
        }
        StringBuilder sb = new StringBuilder(level);
        while (sb.length() < 4) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static int levelColor(String level) {
        if (OK.equals(level)) {
            return 0xFF66BB6A;
        }
        if (WARN.equals(level)) {
            return 0xFFFFB74D;
        }
        if (ERR.equals(level)) {
            return 0xFFEF5350;
        }
        return 0xFF9FC3E3;
    }

    private static int msgColor(String level) {
        if (ERR.equals(level)) {
            return 0xFFFF8A80;
        }
        if (WARN.equals(level)) {
            return 0xFFFFCC80;
        }
        if (OK.equals(level)) {
            return 0xFFA5D6A7;
        }
        return 0xFFD6DCE4;
    }

    // ------------------------------------------------------------------ 工具

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String msg(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String m = t.getMessage();
        if (m == null || m.isEmpty()) {
            m = t.getClass().getSimpleName();
        }
        return m;
    }
}
