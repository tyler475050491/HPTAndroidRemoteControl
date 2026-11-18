# HPT 移动端 ADB 助手 · 设计说明与交接记录

> 纯应用层 ADB 客户端：不依赖 adb 可执行文件、不推送 scrcpy-server、目标设备**无需 root**。
> 工程目录：`E:\豆包\HPTADB`　产物：`app\build\outputs\apk\debug\app-debug.apk`（根目录同名副本 `HPT移动端ADB助手.apk`）

---

## 1. 交付内容

| 内容 | 说明 |
| --- | --- |
| APK | debug 签名，可直接安装；applicationId `com.hpt.adbtool`，versionName 1.0.0，minSdk 24 / targetSdk 34 / compileSdk 34 |
| 内置 APK | `app\src\main\assets\builtin.apk.enc`（构建期由 `apk\ConsumerMachine.apk` 自动同步并 **AES-256-GCM 分块加密**，APK 里不再有明文安装包） |
| 内置 APK 元信息 | `assets\builtin_info.properties`（构建期 aapt 解析后写入：包名 / 应用名） |
| 内置应用 | 包名 `com.hpt.newconsumer`，应用名「人脸识别团餐系统实时版」，versionName 6.2.2，仅 armeabi-v7a |

### 界面结构（自上而下）

1. 标题栏：`HPT移动端ADB助手`（单行，无副标题）
2. 投屏预览画布（右下角「全屏」图标）
3. 设备地址输入框（支持域名/IP）＋ 端口框（默认 5555）＋ `连接设备` ＋ `刷新` ＋ `搜索`
4. 设备表格（无表头）。每行：左侧复选框（单选）＋ 设备名称/状态 ＋ 投屏/连接、断开、重启 三个按钮 ＋ 下面两行完整连接信息
5. `退出桌面` ｜ `安装软件` ｜ `卸载软件`
6. 彩色操作日志（可清空）

### 交互约定

| 功能 | 行为 |
| --- | --- |
| 连接设备 | 按输入地址+端口建立 ADB 会话，成功后新增一行（自动勾选第一台） |
| 刷新 | **先清空整个列表**，再按原有 host:port 逐台重连并回填；期间所有操作按钮禁用 |
| 搜索 | 取本机所连网段的 `/24`（如 `192.168.0.1~254`），用**端口框里的端口**逐个探测 ADB 握手；弹窗显示 `正在扫描 x/254 已发现 n 台` 与进度条；期间所有操作按钮禁用；完成后把发现的设备作为「已发现（未连接）」行加入表格，点该行「连接」才建立会话 |
| 复选框 | 只能选中一台（点另一台会自动改选）；连接成功的第一台自动选中 |
| 安装/卸载 | 只对**已勾选且已连接**的设备执行；未勾选、勾选的是未连接设备都会提示 |
| 退出桌面 | `moveTaskToBack`，回到系统桌面，APP 仍在后台 |
| 全屏 | 预览框右下角图标 → 画面铺满全屏（隐藏状态栏/导航栏）；顶部有「旋转」和「退出全屏」；旋转每点一次转 90°（竖向/横向切换）；返回键也可退出全屏 |
| 卸载软件 | 弹窗列出**第三方应用**（`pm list packages -3`，系统应用不显示），每行显示包名+版本（内置应用显示中文应用名）＋ 一个「卸载」按钮，点击二次确认后卸载，成功后该行从弹窗移除 |

---

## 2. 技术路线

| 项 | 选择 | 原因 |
| --- | --- | --- |
| 语言 / 依赖 | 纯 Java，**零第三方依赖** | 规避依赖解析、离线环境风险；不引入 Kotlin / androidx |
| ADB 协议 | 全部手写（socket + 报文 + RSA 签名） | 手机上不需要 adb 可执行文件，也无需 root |
| 授权 | `AdbCrypto`：2048 位 RSA 密钥持久化在 `filesDir`，SHA1withRSA 签名，公钥按 Android 私有 `RSAPublicKey` 结构编码 | 兼容 adbd 的 AUTH 流程（目标 adbd 关闭授权时直接跳过） |
| 投屏 | `screencap -p` 截图流（PNG）+ Bitmap 解码 | 无需推送任何二进制；`exec:`/`shell:` 双服务回退 |
| 网段搜索 | `AdbConnection.probe()`：TCP 连上后发 CNXN，收到 CNXN/AUTH 即判定为 ADB 设备；16 线程并发，单机 600ms 连接超时 + 1200ms 读超时 | 254 个地址约 6~10 秒扫完；只握手不长连，不占设备资源 |
| 安装/卸载 | `exec:cat > /data/local/tmp/...` 上传 → `wc -c` 校验 → `pm install -r -d -t` → `rm`；卸载 `pm uninstall <pkg>` | 走 adbd shell，root/非 root 都可用 |
| 构建 | Gradle 9.8.2 + AGP 9.1.0 + Android Studio 自带 JBR（JDK 25） | 系统 JDK 27 对 AGP 太新；中文路径需 `android.overridePathCheck=true` |

### 内置 APK 加密（防直接提取）

目标：别人拿到本项目 APK 后 `unzip` 出来的只是密文，不能直接把内置安装包拷走。

- **构建期**（`app/build.gradle` 的 `syncBuiltinApk` 任务）：
  取 `apk/` 下最新 APK → 按 **4 MB 分块**用 AES-256-GCM 加密（每块随机 12 字节 IV）→ 写入 `assets/builtin.apk.enc` → **删除** `assets/builtin.apk`。
  容器格式：`"HPTA"(4) | version(1) | chunkSize(4) | chunkCount(4) | { iv(12) | ctLen(4) | ciphertext }`。
  同时把包名/应用名/明文大小/明文 sha256/块数写进 `builtin_info.properties`。
- **运行期**（`BuiltinApkStream` + `MainActivity.openBuiltinApkStream`）：
  逐块解密后直接写进 ADB 流，**明文只存在于内存**，不在手机私有目录落盘（老版本的 `cacheDir/builtin.apk` 明文缓存已在新版启动时删除）。
- **设备侧**（`AdbDevice.installApk`）：
  临时文件用随机名 `/data/local/tmp/.hpt_<随机 10 位>.apk`，`chmod 600`，装完**无论成功失败都 `rm`**，并在日志里回读确认（`已删除设备临时文件：…`）。
- **完整性**：GCM 认证标签校验，密文被改动会直接报「内置包解密失败（口令不匹配或文件被修改）」，不会装出坏包。

为什么分块：Android（Conscrypt）的 AES/GCM 解密会把整段密文缓冲到 `doFinal` 才输出明文，用单个 GCM 块 + `CipherInputStream` 时首字节永远等不到（实测上传卡在 0 字节、设备侧文件一直是 0）。改成 4 MB 分块后内存占用 ≈ 一个块，进度条与上传速度恢复正常。

**能力边界（务必知道）**：密钥是从代码里的常量派生的（`Secret.java`，字符串做了分片拼接、构建脚本里用同样的拼法），本质是**提高门槛的混淆**——能挡住"解压 APK 直接拷包"，挡不住专业逆向（反编译 dex 找到口令即可解出）。要再上一层，需要把密钥藏进 native 库/服务端下发，或对 dex 做 R8/加固混淆（当前 `minifyEnabled=false`）。

### 关键目录

```
E:\豆包\HPTADB
├─ app\src\main\java\com\hpt\adbtool\
│   ├─ MainActivity.java   全部 UI 与流程（连接/刷新/搜索/单选/投屏/全屏旋转/装/卸/退出桌面/彩色日志）
│   ├─ AdbConnection.java  socket、CNXN/AUTH 握手、多路流分发、读线程、probe() 设备探测
│   ├─ AdbStream.java      单条数据流（OKAY 开门、WRTE 写、CLSE 关闭、流控、超时读取）
│   ├─ AdbCrypto.java      RSA 密钥与签名、公钥结构编码
│   ├─ AdbDevice.java      shell（exec→shell 回退）、readInfo、installApk、uninstall、
│   │                      listThirdPartyApps、reboot、tap
│   ├─ BuiltinApkStream.java  分块 AES-256-GCM 内置包解密流
│   ├─ Secret.java         内置包口令/盐（与 build.gradle 同步）
│   ├─ ScreenMirror.java   截图流循环、PNG 边界解析、inSampleSize 解码、FPS 统计、失败退避
│   ├─ PreviewView.java    预览画布（等比居中 + 90° 旋转绘制 + 点击坐标反算）
│   ├─ DevInfo.java        设备信息模型
│   └─ AdbLog.java         日志接口（INFO/OK/WARN/ERR）
├─ app\src\main\res\
│   ├─ layout\activity_main.xml（含全屏层 flFull）、item_device.xml（复选框 + 两行信息）
│   └─ drawable\ic_fullscreen.xml / ic_rotate.xml / ic_close.xml
├─ app\build.gradle        syncBuiltinApk 任务（加密内置包 + aapt 解析包名，preBuild 依赖）
└─ doc\                    本说明、协议探针、抓包代理、真机截图、UI dump
```

---

## 3. ADB 协议实现要点（踩坑记录）

手写协议时被三处「约定」坑了很久，均已修正并验证：

1. **CNXN 的 arg0 是协议版本，arg1 才是 maxData**
   写成 `arg0=maxData` 时 adbd 认为版本不兼容：会回自己的 CNXN，但之后**不再处理任何 OPEN**——TCP 连通、握手看似成功、`OPEN` 永远等不到 `OKAY`。
   正确写法：`A_CNXN, version=0x01000001, maxData=256*1024, payload="host::"`。
2. **报文 arg0/arg1 是「发送方 local-id 在前，对端 remote-id 在后」**
   设备的 `OKAY` 回包形如 `a0=<设备 local-id> a1=<本机 local-id>`；本机发 `WRTE/CLSE/OKAY` 时反过来写。
3. **`CLSE` 是终止包，不需要回包**
   收到设备 `CLSE` 后再回一个 `CLSE`，adbd 视为"关闭未知流"，一分多钟后**直接 reset 整条 TCP**。

其它：`A_CLSE` 常量曾误写为 `0x45535c43`（正确 `0x45534c43`）；`exec:` 不存在时回退 `shell:` 并去掉 pty 的 `\r`。

协议自检 / 抓包工具：

```
python doc\adb_probe.py <host> <port> "exec:getprop ro.product.brand"
python doc\tcp_proxy.py 5555 <target_host> <target_port>
```

---

## 4. 真机验证结果（Redmi Note 13 Pro → HPT-R3568）

宿主手机经无线 ADB（`192.168.0.117:5555`）安装与驱动；目标设备 `192.168.0.226:5555`（HPT-R3568 / Android 11 / SDK 30 / adbd 以 root 运行 / 关闭授权校验）。

| 功能 | 结果 | 依据 |
| --- | --- | --- |
| 连接设备 | ✅ | `12:44:25 连接成功：rockchip HPT-R3568 · 1280x720 · 192.168.0.226:5555（2163 ms）`，行内自动勾选 |
| 设备表格 | ✅ | 复选框 + 名称/状态 + 三个按钮 + 两行完整信息（`192.168.0.226:5555` / `Android 11(API 30) · 1280x720`） |
| 投屏预览 | ✅ | 画布显示设备画面，状态行 `1280x720 1.5~1.6 FPS` |
| 全屏 / 旋转 / 退出全屏 | ✅ | 全屏后画面铺满、顶部显示 `1280x720 1.6 FPS 竖向`；点旋转变为横向铺满；X 图标退出并恢复列表界面 |
| 刷新 | ✅ | 先清空列表（含已发现行）再重连，日志 `刷新完成：成功 1 台，失败 0 台`，行重新出现并自动勾选 |
| 搜索 | ✅ | 扫描 `192.168.0.1~254`（端口 5555），进度弹窗 `正在扫描 114/254 已发现 3 台`，完成后新增 `192.168.0.48 / .112 / .144 / .146` 等「已发现（未连接）」行；这些行的「断开/重启」为禁用状态（`enabled=false`） |
| 复选框单选 | ✅ | 勾选另一台自动改选上一台；未选中时点安装/卸载会提示先勾选 |
| 连接已发现设备 | ✅ | 搜索后点 `192.168.0.48` 行的「连接」，日志 `连接成功：rockchip HPT-P8 · 192.168.0.48:5555（2204 ms）`，行从「已发现（未连接）」变成「已连接」，投屏/断开/重启同时解除禁用，信息回填 `Android 11(API 30) · 800x1280`；已在连的 226 仍保持勾选不受影响 |
| 加密内置包安装 | ✅ | assets 内只有 `builtin.apk.enc`（明文包已删）；13:15、13:18 两次实测：上传 80/100% → `安装包上传完成（62.0 MB）` → `已删除设备临时文件：/data/local/tmp/.hpt_xxxx.apk` → `安装成功`，目标机 `lastUpdateTime` 同步刷新，装完设备上 `ls /data/local/tmp` 已无任何 apk 残留 |
| 安装软件 | ✅ | 只对勾选设备执行；上传 40/60/80/100% 进度 → `安装包上传完成（62.0 MB），开始静默安装…` → `安装成功：人脸识别团餐系统实时版`（12:55、12:59 两次），目标机 `lastUpdateTime` 同步刷新，装完自动重连保持「已连接」 |
| 卸载软件 | ✅ | 弹窗列出 14 个第三方应用（含版本号，系统应用已过滤），每行独立「卸载」按钮；12:56 从弹窗点 `人脸识别团餐系统实时版` → 二次确认 → `卸载成功：com.hpt.newconsumer`，该行即从弹窗移除、顶部计数同步刷新；随后已用「安装软件」重新装回（`lastUpdateTime=12:59:33`） |
| 退出桌面 | ✅ | 点击后 `topResumedActivity=com.miui.home/.launcher.Launcher` |
| 重启 | ⚠️ 未实测 | 代码为 adbd root 下 `reboot`；实测会真实重启产线终端，待现场确认后再点 |

> 安装/卸载实测均在目标机上执行过，测试后已把 `com.hpt.newconsumer` 重新装回（当前已安装，versionName 6.2.2）。

---

## 5. 已知限制与后续可选项

0. **日志渲染已做合并刷新**：安装上传时协议报文（`OPEN/OKAY/CLSE`）会连续刷几百行，早期实现是「一条日志一次 `setText`」，界面会被拖到几分钟后才追上（实测日志时间停在 12:46 而系统时间已 12:52）。现在后台线程只把行塞进待渲染队列，UI 线程最多合并成一次重绘（`log()` → `pendingLogs` → `drainLogs()`），同时协议追踪单条连接上限降到 150 行，实测安装全程日志时间与系统时间同步。
1. **投屏已升级为真视频流（第三轮）**：`scrcpy-server v2.7 + MediaCodec 硬解 + TextureView 直出`，静态画面实测 10~12 FPS、动帧更高（受设备编码能力限制，`max_fps=30` 是上限）。旧的 `screencap` 截图流（约 1~1.6 FPS）只保留为视频流异常时的兜底。
2. **安装/卸载后连接会被设备回收**：设备执行 PM 操作后 dexopt 占满 CPU，adbd 1~2 分钟内不响应新 OPEN；APP 已自动重连兜底（最多 3 次、间隔 3 秒），失败时点「刷新」即可。
3. **第三方应用只显示包名**：设备端没有 `pm list packages --show-labels`，`dumpsys package` 也不含应用名，因此除内置应用（本地已知中文名）外，列表显示包名 + 版本号。若需要真实应用名，需拉取 APK 用 aapt 解析（成本高，未做）。
4. **搜索是 `/24` 网段**：以本机 `wlan0` 的 IPv4 地址推导网段，逐台探测端口框中的端口；跨网段/大网段不适用。
5. **本机 USB 直连不稳定**：Windows 侧 adb 反复 `device not found`（火绒 `hrdevmon` / 360 / vmware-usbarbitrator 干扰），因此手机侧控制统一走无线 `adb tcpip 5555`。与 APP 功能无关。
6. **重启按钮未实测**，避免远程重启产线终端后无法恢复。
7. **内置包加密只是混淆级防护**：口令在 dex 里（`Secret.java`，分片拼接），能挡住解包直接提取，挡不住有目标的逆向。另有两点工程注意：①`assets` 里的加密数据不可压缩，APK 体积≈原安装包大小（当前 65.9 MB）；②**增量构建时曾出现 asset 被打包两份、APK 变成 130 MB 的情况，`gradle clean` 后恢复正常**——改内置包或改加密逻辑后建议 clean 再打包，并用下面命令核对：`python -c "import zipfile;print([(i.filename,i.file_size) for i in zipfile.ZipFile(r'…\app-debug.apk').infolist() if 'assets' in i.filename])"`。

---

## 6. 构建与安装

```powershell
$gradle = "C:\Users\TNND\.gradle\wrapper\dists\gradle-9.8.2-bin\3wb4k92zt3y241x5qwqkhg6w0\gradle-9.8.2\bin\gradle.bat"
& $gradle -p "E:\豆包\HPTADB" :app:assembleDebug --console=plain --no-daemon

$adb = "C:\Users\TNND\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s 192.168.0.117:5555 install -r -d "E:\豆包\HPTADB\app\build\outputs\apk\debug\app-debug.apk"
& $adb -s 192.168.0.117:5555 shell am start -n com.hpt.adbtool/.MainActivity
```

替换内置 APK：把新安装包放进 `apk\`（只保留一个 `.apk`），重新构建会自动复制到 `assets\builtin.apk` 并用 aapt 解析包名/应用名写入 `builtin_info.properties`。

---

## 7. 供后续接手的位置

- 连接失败但设备在线 → 先跑 `doc\adb_probe.py`，再对照 APP 日志里 `[adb] → OPEN / ← OKAY / ← CLSE` 报文行。
- 投屏：`ScreenMirror.SERVICES` 顺序即服务回退顺序，`mirrorService` 记忆可用服务；`PreviewView.rotationDeg` 控制 90° 步进旋转，点击坐标会反向旋转后再换算。
- 搜索：`MainActivity.onSearchClicked()` 内 `SCAN_THREADS=16`、`probe(600,1200)` 可调；`localNetwork()` 负责取本机网段。
- 安装：`AdbDevice.installApk()` 内 `wc -c` 校验是防止上传截断的关键，不要去掉。
- 卸载列表：`AdbDevice.listThirdPartyApps()` 的 shell 单行命令，改动后注意 `2>/dev/null` 否则会出现 `Broken pipe` 噪音。

---

## 8. 附录：真机截图（doc 目录）

| 文件 | 内容 |
| --- | --- |
| `screenshot_home.png` | 新首屏（无副标题、无表头，连接设备/刷新/搜索 + 退出桌面 + 全屏图标） |
| `screenshot_connected.png` | 连接成功后的设备行（复选框已勾选、三行完整信息） |
| `screenshot_mirror.png` | 投屏预览（`1280x720 1.5 FPS`，右下角全屏图标） |
| `screenshot_fullscreen.png` | 全屏竖向（顶栏 `1280x720 1.6 FPS 竖向` + 旋转 + 关闭） |
| `screenshot_fullscreen_rotate.png` | 全屏点旋转后（`横向`，画面转 90° 铺满） |
| `screenshot_search.png` | 网段搜索进度弹窗（`正在扫描 114/254 已发现 3 台`，按钮全部禁用） |
| `screenshot_search_result.png` | 搜索完成的「已发现（未连接）」行（断开/重启禁用、按钮变「连接」） |
| `screenshot_uninstall_list.png` | 卸载弹窗：14 个第三方应用 + 逐行卸载按钮 |
| `screenshot_uninstall.png` | 弹窗内卸载成功（`卸载成功：com.hpt.newconsumer`，该行已消失） |
| `screenshot_install_progress.png` | 选中设备安装中（上传 55%） |
| `screenshot_install.png` | 安装成功（上传 100% → `安装成功：人脸识别团餐系统实时版`） |
| `screenshot_connect_remote.png` | 点发现的 `.48` 行「连接」后变成已连接（HPT-P8 2204 ms） |
| `screenshot_enc_install.png` | 加密内置包安装：上传 100% → 删除设备临时文件 → 安装成功 |
| `screenshot_refresh.png` | 刷新：清空后重连 1 台成功 |
| `screenshot_exit_desktop.png` | 退出桌面后回到系统桌面 |
---

## 9. 第三轮：scrcpy-server + MediaCodec 视频流与全屏鼠控

### 9.1 目标与结论

- 目标：把投屏从 `screencap` 截图流（1~1.6 FPS 卡顿）升级为**真视频流**，并支持**全屏时的鼠标/触摸控制**；退出全屏只显示视频、不下发触控。
- 结论：**已完成并在真机跑通**。Redmi Note 13 Pro 上画面为实时 H.264 视频（右上角显示 `1024x576 11~15 FPS`），进全屏后可点按/拖动直接操作 HPT-R3568，旋转按钮可横/竖切换，退出全屏视频继续、触控自动关闭。

### 9.2 架构（纯应用层、零三方依赖、设备无需 root）

三层数据通道，全部由 APP 自己用 ADB 协议（`socket + CNXN/OPEN/OKAY/WRTE/CLSE`）打开：

1. 视频通道：`OPEN localabstract:scrcpy` → 读 1 字节哑字节 → 读 76 字节头 → 循环读帧包 → 喂给 MediaCodec。
2. 控制通道：第二条 `OPEN localabstract:scrcpy` → 32 字节大端触摸报文 / 20 字节滚轮报文。
3. 服务进程：`shell:` 拉起 `app_process`，stdout 由 APP 的日志读取线程转成界面上带 `scrcpy-server：` 前缀的日志。

渲染链路：`MediaCodec（video/avc，surface 输出）` → `SurfaceTexture` → `TextureView`。**不再走 ImageReader → ARGB Bitmap 拷贝**，因此没有中间帧拷贝，也不再出现 JNI `Image_createSurfacePlanes` 崩溃。

新增/重写文件：

| 文件 | 职责 |
| --- | --- |
| `ScrcpyServer.java` | 每次投屏强制推 jar（`wc -c` 校验 71200B）、`killOld()` 扫 `/proc` 杀掉残留 `app_process`、`shell:` 拉起服务、`cleanup=false`、stdout 读取 |
| `ScrcpyControl.java` | 控制报文编码：`INJECT_TOUCH_EVENT`(32B 大端：type/action/pointerId(8)/x/y/w/h/pressure/actionButton/buttons)、`INJECT_SCROLL_EVENT`(20B) |
| `ScrcpyMirror.java` | 全流程：推送→拉起→杀旧→连视频→哑字节→连控制→读头→首帧 csd→配解码器→读包线程 + 解码线程 + 帧率统计 + `updateSurface()` |
| `PreviewView.java` | `FrameLayout` 子类，`MODE_EMPTY/BITMAP/VIDEO`；视频模式下内嵌 `TextureView` 并手工 `layout` + `setRotation`；`toNormalized()` 把触点反算成画面归一化坐标（含旋转） |
| `MainActivity.java` | `startVideoMirror/launchScrcpy`、`videoSurfaceListener`、`enterFullscreen/exitFullscreen` + `rebindVideoSurface()`、全屏顶栏（尺寸/FPS/方向/可触控 + 旋转 + 关闭） |

### 9.3 协议实现要点（本轮踩的坑，按顺序）

1. **选项名是 `video_bit_rate`**：写成 `bit_rate` 服务端直接 `Unknown server option: bit_rate` 退出。
2. **jar 会被服务端自己删掉**：scrcpy 默认 `cleanup=true`，异常路径会删 `/data/local/tmp/scrcpy-server.jar`，之后 `app_process` 找不到类 → `SIGABRT`。必须显式 `cleanup=false`，并且**每次投屏都重推 + `wc -c` 校验**。
3. **握手顺序（PC 探针逐字节确认）**：先 1 字节哑字节 `0x00` → 设备名 64B → fourcc 4B(`h264`) → 宽 4B → 高 4B（共 76B）；之后每包 = `8B pts + 4B size + data`（Annex-B）。**必须先连控制通道，服务端才继续发设备名/头**。
4. **pts 高两位是标志位**：bit63=config、bit62=key，必须 `pts & 0x3FFF_FFFF_FFFF_FFFF` 再喂解码器，否则 QTI 解码器报 `err 0xffffffe0 / DEAD_OBJECT`；另加单调保护（回退或跳变过大时按 `+33000us` 递推）。
5. **`releaseOutputBuffer(idx, false)` 不渲染**，必须传 `true`。
6. **读包循环不能放在 `finally` 前面跑完**：早期实现「起完读包线程就走到 finally → stop()」，表现为视频秒断。
7. **渲染从 ImageReader 改 TextureView**：ImageReader 路径在 `getPlanes()` 间歇 `SIGABRT`（`non-zero capacity for nullptr pointer`），改 `codec.configure(fmt, surface, null, 0)` 直出后彻底消失。

### 9.4 全屏与鼠控（本轮新解决的两个关键 bug）

- **全屏黑屏（根因：SurfaceTexture 被释放 + 解码线程自杀）**：
  - 现象：进入全屏后顶栏信息正常、视频区全黑，日志无「解码输出已切到新的画面 Surface」。
  - 根因链：`PreviewView` 被搬到 `flFull` → `onSurfaceTextureDestroyed` 返回 `true` → SurfaceTexture 被释放 → 解码线程仍在往死 Surface 上 `releaseOutputBuffer` → `IllegalStateException: Invalid to call at Released state` → 解码线程 `break` 退出 → 永久黑屏。
  - 修复：①`onSurfaceTextureDestroyed` 改 **返回 `false`**（保留 SurfaceTexture 复用，日志记「画面 Surface 已分离（保留复用）」）；②解码循环里把 `releaseOutputBuffer` 包进 try/catch，丢帧等待重挂，**绝不让解码线程退出**；③`rebindVideoSurface()` 重挂监听，并用 `boundTexture` 比对避免对同一个 SurfaceTexture 重复 `setOutputSurface`；④退出全屏反向执行同一套逻辑。实测日志序列：`画面 Surface 已分离（保留复用）→ 进入全屏预览 → 画面 Surface 已重建（1220x686）→ 解码输出已切到新的画面 Surface`。
- **全屏触控打崩 APP（根因：主线程写 socket）**：
  - 现象：点一下画面，APP 直接 `FATAL EXCEPTION: main / android.os.NetworkOnMainThreadException`，触控无效。
  - 根因：`ScrcpyControl.write()` 在 UI 线程直接写 socket。
  - 修复：`ScrcpyMirror` 内置**单线程 `touchExec`**，触控事件排队由它写 socket（`stop()` 里 `shutdownNow()`），顺序天然保持 DOWN→MOVE→UP。
  - 第二个坑：控制通道比视频头先建立，此时 `videoWidth/videoHeight` 还是 0，报文里 w/h 写成 0 → 坐标算成 `(-1,-1)`，服务端回 `Ignore touch event, it was generated for a different device size`。修复：读完视频头后 `control.setVideoSize(videoWidth, videoHeight)`。

### 9.5 真机验证表（Redmi Note 13 Pro → HPT-R3568，Android 11，1280x720）

| 验证项 | 结果 | 证据 |
| --- | --- | --- |
| 视频流建立 | 通过 | 日志 `视频端口已连上（第 4 次尝试，耗时 22ms）→ 哑字节 0x00 → 控制通道已就绪 → H264 1024x576 → 解码器已启动（直出 TextureView）` |
| 内嵌画布实时画面 | 通过 | `doc\shot_1_inline.png`，画布内可见目标机「人脸特征提取系统」实时界面 + `1024x576 11.2 FPS` |
| 进入全屏出画面 | 通过 | `doc\shot_2_fullscreen.png`，全屏顶栏 `1024x576 11.5 FPS 竖向 可触控`，视频按 16:9 居中（黑边为画幅比例所致） |
| 全屏触控（点按） | 通过 | `doc\shot_3_fullscreen_touch.png`：在 APP 全屏画面里点「参数设置」，目标机弹出参数设置弹窗；日志 `触控下发：按下 (955, 548)` |
| 全屏旋转 | 通过 | `doc\shot_4_rotated.png`，顶栏 `横向`，画面与触控坐标同步旋转 |
| 退出全屏 | 通过 | `doc\shot_5_exit.png`，视频继续 11+ FPS；日志 `退出全屏 → 画面 Surface 已重建（1120x630）→ 解码输出已切到新的画面 Surface` |
| 非全屏不下发触控 | 通过 | 手势回调里 `if (!fullscreen) return;` 直接拦截 |
| 崩溃/黑屏回归 | 通过 | `logcat -b crash` 无新记录；连续多次进出全屏画面不黑 |

### 9.6 已知限制（本轮相关）

1. **设备端 `localabstract:scrcpy` 偶发连不上（环境性，非本 APP 逻辑）**：表现为连续 `CLSE`、日志「设备拒绝打开该服务」，短则几秒长则数十秒后自行恢复；同一时刻 PC 侧 `adb forward tcp:xxxxx localabstract:scrcpy` 却能连通，说明是设备 adbd 的 abstract-socket connect 时序问题。APP 已做兜底：视频端口 35s 内每 0.9s 重试、控制通道 25s 重试，仍失败则重启服务端再试一轮，最后才回退截图流。**每次投屏前在设备上 `pkill -9 -f com.genymobile.scrcpy.Server` 可显著提高成功率**（APP 已自动执行 `killOld()`）。
2. 分辨率固定 `max_size=1024`、`max_fps=30`、`8 Mbps`（`MainActivity` 的 `VIDEO_MAX_SIZE/VIDEO_MAX_FPS/VIDEO_BIT_RATE`）。设备原生 1280x720，如需更清晰可改 `max_size=1280`，代价是解码与带宽上升。
3. 触控只做单指（点按/拖动）与滚轮报文编码；多指缩放未接入。
4. 全屏画面按原比例居中，手机 20:9 屏会有上下黑边（属预期，不做裁剪拉伸）。

---

## 10. 正式版打包与加固（第 4 轮）

### 10.1 目标与结论

诉求：交付的 `HPT移动端ADB助手.apk` 不能被别人直接从里面拷出内置安装包，正式版打包 + 防逆向加固 + 编译后代码混淆。

做法与结论（均已落地并真机验证）：

1. **正式版签名**：自建 keystore 出包（v2 + v3 签名），不再是 debug 证书。
2. **代码混淆**：release 开 `minifyEnabled + shrinkResources`，R8 把 53 个类改名并压进 `hpt` 短包名，方法名/字段名同步重命名。
3. **内置包防提取**：assets 里不再有任何 `*.apk` / `*.apk.enc`，只剩一个 64.99 MB 的 `hpt_payload.dat`（分块 AES-256-GCM 密文，前 4 字节是容器魔数 `HPTK`），直接拷出去既不是 zip、也不是能装的包。
4. **密钥与签名证书绑定**：密钥 = SHA-256(口令与盐 ‖ '|' ‖ 本 APK 签名证书 SHA-256)。资源被搬到别处、或外壳被换证书重打包，派生的密钥都对不上，容器头部的 `keyCheck` 会先报错，拿不到明文。
5. **口令不再以明文字符串存在**：`Secret` 里改成 XOR 编码的字节表（`BLOB` 逐字节 XOR 0x5A），运行时还原；R8 再把类名/方法名抹掉，反编译看不到可用常量。

### 10.2 签名与打包配置

| 项 | 值 |
|---|---|
| keystore | `E:\豆包\HPTADB\keystore\hpt-release.jks`（PKCS12，alias `hpt`，有效期 10950 天 ≈ 30 年） |
| 口令 | 写在 `E:\豆包\HPTADB\keystore.properties`（与 jks 一同保管，**丢了这个 jks 后续升级包无法覆盖安装**） |
| 证书 DN | `CN=HPT Mobile ADB, OU=HPT, O=HPT, L=Shenzhen, ST=Guangdong, C=CN` |
| 证书 SHA-256 | `f8b7d2021a6086551d84997870884965ba45b11706debf2a6946c7dea95333d9` |
| 签名方案 | v2（APK Signature Scheme v2）✔、v3 ✔；minSdk 24 不需要 v1 |
| 版本 | versionCode `2` / versionName `1.0.1` |
| debug 分支 | 也用同一套证书签名（否则内置包密钥对不上，debug 包解不出内置包） |

> 换签名后，旧 debug 版无法覆盖安装（签名冲突）。本次已 `adb uninstall com.hpt.adbtool` 后再装正式包，**应用内数据（地址、日志）会被清空**，之后同证书升级可正常 `-r` 覆盖。

### 10.3 代码混淆（R8）

`app/build.gradle`：

```gradle
release {
    minifyEnabled true
    shrinkResources true
    proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
    signingConfig signingConfigs.release
    debuggable false
}
```

`app/proguard-rules.pro` 关键项：`-allowaccessmodification`、`-repackageclasses 'hpt'`、`-overloadaggressively`、`-optimizationpasses 5`，以及用 `-assumenosideeffects` 去掉 `Log.v/Log.d`。

实测（`app/build/outputs/mapping/release/mapping.txt`，交付包**不含**该文件）：

```
com.hpt.adbtool.AdbConnection      -> hpt.c
com.hpt.adbtool.AdbCrypto          -> hpt.d
com.hpt.adbtool.AdbDevice          -> hpt.e
com.hpt.adbtool.BuiltinApkStream   -> hpt.g
com.hpt.adbtool.Secret             -> hpt.*（同步改名）
com.hpt.adbtool.MainActivity       -> 保留原名（AndroidManifest 注册组件，R8 强制保留）
```

`MainActivity` 保名但不保留内部成员名，`$10`、`$12` 等内部类全部改名。

### 10.4 内置安装包的防提取设计（容器 v2）

构建期任务 `syncBuiltinApk`（`app\build.gradle`，`preBuild` 依赖它）：

1. 取 `apk\` 下最新 `.apk`（当前 `ConsumerMachine.apk`，64,992,266 B）。
2. 从 `keystore.properties` 指定的 jks 里取出证书 DER，算 `SHA-256`；与口令串拼后派生 AES-256 密钥（与运行期同一算法）。
3. 4 MB 分块 AES-256-GCM（单块独立 12 B IV），写成 `assets\hpt_payload.dat`。
4. 只写 `assets\payload.idx`（包名/应用名/明文大小/密文大小/sha256），**不写算法、不写源文件名**。
5. 删除旧资源 `builtin.apk` / `builtin.apk.enc` / `builtin_info.properties`（避免残留线索）。

容器格式：

```
"HPTK"(4) | version(1)=2 | chunkSize(4)=4194304 | chunkCount(4)=16 | keyCheck(4) |
{ iv(12) | ctLen(4) | ciphertext }  × chunkCount
```

- `keyCheck` = `SHA-256(密钥)` 的前 4 字节；本次打包实测 `0x37 0x5E 0x40 0x54`。客户端解密前先比对，不匹配直接报「内置包密钥与本机签名不一致（APK 可能被重打包或资源被搬走）」。
- 运行期密钥派生在 `Secret`（R8 后为 `hpt.*`）：`SHA-256( BLOB解 XOR 后的口令 ‖ '|' ‖ SHA-256(APK 签名证书) )`。
- 读取端 `BuiltinApkStream` 逐块 `doFinal` 解密 → `AdbDevice.installApk` 边解密边上传 → 设备临时文件（`/data/local/tmp/.hpt_xxxxxxxx.apk`）安装后立即 `rm`。**明文全程不落宿主盘、不落设备持久盘。**

### 10.5 加固强度说明（说实话的部分）

| 攻击手法 | 是否被挡住 |
|---|---|
| 解压交付 APK，直接从 assets 拷内置安装包 | ✅ 挡住：assets 里只有 `hpt_payload.dat` 密文，不是 zip、不是 APK |
| 把 `hpt_payload.dat` 改名后尝试安装/解压 | ✅ 挡住：AES-256-GCM 密文，`BadZipFile` |
| jadx 反编译找口令 | ✅ 基本挡住：无明文字符串口令（XOR 字节表 + R8 改名 + 无 `Secret` 类名）；但专业逆向仍可从运行时还原字节表 |
| 换证书重打包（改包名/加广告/植入后门） | ✅ 挡住：密钥绑定签名证书，`keyCheck` 直接失败 |
| 在原 APP 运行时从内存 dump 解密后的明文 | ❌ 挡不住（任何纯应用层方案都做不到） |
| 反编译后改掉校验逻辑再运行 | ⚠️ 只能提高门槛（R8 + 无符号名），不能根治 |

结论：当前方案能稳妥解决「别人从交付包里拷出内置 APK」这一诉求，也能挡住重打包；若后续需要「抗内存 dump / 抗动态调试」级别的商业壳（虚拟化壳、抽取壳），需要引入第三方加固服务，与「零三方依赖、纯 Java 手写协议」的约束冲突，**需你决定是否接入**。

### 10.6 真机验证（Redmi Note 13 Pro，1220x2712）

| 验证项 | 结果 | 证据 |
|---|---|---|
| 正式包安装 | 通过 | `adb install` 成功（先 uninstall 旧 debug 包）；`apksigner verify` 显示 v2/v3 签名、证书 DN `CN=HPT Mobile ADB` |
| 混淆后功能可用 | 通过 | 连接设备 → 投屏 `1024x576 10.8 FPS` → 进全屏 → 退出全屏，日志链路完整 |
| 内置包解密 + 上传 + 安装 | 通过 | 日志 `上传安装包 100% → 开始静默安装… → 已删除设备临时文件：/data/local/tmp/.hpt_j6edkgpvat.apk → 安装成功：人脸识别团餐系统实时版`；设备侧 `pm list packages` 可见 `com.hpt.newconsumer` |
| assets 无明文安装包 | 通过 | zip 列表：`assets/hpt_payload.dat`(64,992,795) / `assets/payload.idx`(171) / `assets/scrcpy-server.jar`(71,200)，无任何 `.apk` |
| 密文不是 zip | 通过 | 读取前 4 字节 `HPTK`，python `zipfile` 抛 `BadZipFile` |
| 崩溃回归 | 通过 | `logcat -b crash` 无 FATAL |

### 10.7 本轮踩的坑

1. **资源名混用**：把 `payload.idx` 的常量同时用来打开密文，报「内置包容器格式未知」。改为 `PAYLOAD_FILE = hpt_payload.dat` 与 `PAYLOAD_IDX = payload.idx` 两个常量。
2. **首次安装必须卸载**：新证书与旧 debug 证书不同，`install -r` 会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
3. **调试时输入框残留文本**：`pm clear` 后重新输入，否则地址会拼成 `192.168168...` 连不上。
4. 换签名后 `Secret` 派生的密钥变了，**内置包必须重新生成**（构建任务的 `preBuild` 依赖已保证这一点），旧 `builtin.apk.enc` 无法再解开。

---

## 11. 界面细节调整（第 4 轮）

### 11.1 设备地址 / 端口输入框

原状：`etHost` / `etPort` 没有 `android:background`，走系统默认的下划线样式，看着只有一条横线。现在新增 `res/drawable/bg_input.xml`（selector）：

| 状态 | 样式 |
|---|---|
| 正常 | 圆角 8dp、填充 `#F7FAFE`、描边 1.5dp `#C9D6E6` |
| 按下 | 圆角 8dp、填充 `#EEF4FC`、描边 1.5dp `primary_light` |
| 聚焦 | 圆角 8dp、纯白填充、描边 2dp `primary`（`#1565C0`） |

配合改动：`etHost` 左右内边距 10dp（原来 6/4dp，圆角框里更居中），`etPort` 宽 60dp→64dp、左右 4dp，两框间距 6dp；`hint_host` 文案由「设备地址（域名或 IP）」缩短为「IP / 域名」，避免在窄框里被截断成「设备地址（域名」。

### 11.2 「搜索」按钮改紫

新增 `colors.xml`：`search_purple = #7B4FD3`、`search_purple_dark = #6034B6`（备用按下态）。`btnSearch` 的 `backgroundTint` 由 `@color/accent`（青绿）改为 `@color/search_purple`，只影响「搜索」，「刷新」仍是青绿，两者一眼可分。

### 11.3 截图

- `doc\shot_a1_ui.png`：新版输入框（圆角描边）+ 紫色「搜索」
- `doc\shot_a2_connected.png`：连上 HPT-R3568
- `doc\shot_a3_mirror.png`：投屏 `1024x576 10.8 FPS`
- `doc\shot_a4_install_dialog.png`：安装确认弹窗（包名/目标设备）
- `doc\shot_a5_fullscreen.png`：全屏（混淆版回归）
- `doc\shot_a6_final.png`：退出全屏后视频继续

---

## 12. 交付件（第 4 轮最新）

| 文件 | 大小 | SHA-256 |
|---|---|---|
| `E:\豆包\HPTADB\HPT远控.apk`（正式版，release + R8 + 正式签名，应用名 `HPT远控`，内置包 `ACM_YH_HDL_P8-HiV5.0.4`） | 24,358,043 B | `4221FC60EB75A67C22A7614AFE0F56998996C828E482AF43238B97DBCE3D4F15` |
| `E:\豆包\HPTADB\doc\HPT远控.apk` | 同上（同一份副本） | 同上 |
| `E:\豆包\HPTADB\doc\HPT移动端ADB助手-设计说明.md` | 本文件 | — |
| `E:\豆包\HPTADB\keystore\hpt-release.jks` + `keystore.properties` | — | **务必备份**，丢了无法升级覆盖 |

构建命令（正式版）：

```powershell
$gradle = "C:\Users\TNND\.gradle\wrapper\dists\gradle-9.8.2-bin\3wb4k92zt3y241x5qwqkhg6w0\gradle-9.8.2\bin\gradle.bat"
& $gradle -p "E:\豆包\HPTADB" clean :app:assembleRelease --console=plain --no-daemon
```

产物：`app\build\outputs\apk\release\app-release.apk`。
如需排查线上问题，用同一次构建的 `app\build\outputs\mapping\release\mapping.txt` 反混淆（该文件不随包交付）。
---

## 13. 换包自检：`apk\` 换成"包名不同的程序"是否影响加密 / 安装 / 卸载（第 5 轮）

### 13.1 自检动机

`apk\` 目录里的内置安装包由客户提供，随时可能换成别的程序，包名、应用名、体积、签名都会变。要确认：换包之后 ①加密打包是否自动适配、②安装是否还能装、③卸载列表是否还认得这个包。

### 13.2 自检方法（真机实测，非纸面推演）

1. 从目标设备拉一个包名完全不同的第三方 APK：`pm path com.android.adbkeyboard` → `adb pull .../base.apk`（17,374 B，包名 `com.android.adbkeyboard`，应用名 `ADBKeyBoard`）。
2. 把 `apk\ConsumerMachine.apk` 移到临时目录，放入 `adbkeyboard.apk`，执行 `:app:assembleRelease`。
3. 装到宿主手机 → 连接 HPT-R3568 → 走「安装软件」和「卸载软件」两条流程。
4. 结束后恢复 `ConsumerMachine.apk`，重新 clean 打包并装机，确认回到原包。

### 13.3 结果

| 检查点 | 结果 | 证据 |
|---|---|---|
| 构建自动适配 | 通过 | 构建日志 `[内置包] adbkeyboard.apk 17374 bytes -> hpt_payload.dat 17423 bytes（AES-GCM 1 块）`；`payload.idx` 变为 `package=com.android.adbkeyboard / label=ADBKeyBoard / size=17374` |
| 密文格式 | 通过 | `hpt_payload.dat` 头 4 字节 `HPTK`，`keyCheck` 重新生成，与证书一致 |
| 安装弹窗 | 通过 | `doc\shot_b1_install_dialog.png`：「将安装「ADBKeyBoard」 包名：com.android.adbkeyboard 目标设备：rockchip HPT-R3568」 |
| 解密 + 上传 + 安装 | 通过 | 日志 `上传安装包 100% → 静默安装 → 已删除设备临时文件：/data/local/tmp/.hpt_tcrlp40fyx.apk → 安装成功：ADBKeyBoard`；设备侧 `pm list packages com.android.adbkeyboard` 有输出 |
| 卸载列表标签匹配 | 通过 | `doc\shot_b2_uninstall_list.png`：列表里显示 `ADBKeyBoard（com.android.adbkeyboa…） 版本 2.0`，说明 label 是按新包的 `package` 匹配上的 |
| 设备无残留 | 通过 | `ls /data/local/tmp \| grep hpt_` 无输出（临时文件已删） |
| 恢复原包 | 通过 | 恢复后 `payload.idx` 回到 `com.hpt.newconsumer / 人脸识别团餐系统实时版`，`size=64992266 / encSize=64992795`；装机后启动日志显示「内置 APK：人脸识别团餐系统实时版 包名 com.hpt.newconsumer 62.0 MB」 |

### 13.4 结论与代码依据

**结论：换包不需要改任何代码，clean 重新打包即可；包名全程没有硬编码。**

依据：
- 包名/应用名由构建期 `aapt dump badging` 解析后写入 `payload.idx`，运行期 `readBuiltinMeta()` 只读这个文件。
- 安装走 `adb install -r -d <设备临时文件>`，**不依赖包名**。
- 卸载列表来自设备侧 `pm list packages -3`，`package` 是设备返回的；`payload.idx` 的 `package` 只用于把 `label` 贴到对应行，匹配不上时降级显示包名，不影响卸载功能。
- 加密只看字节和证书，与应用包名无关。

### 13.5 换包时的注意点（已写进 `doc\二次开发说明.md` 第 4 节）

1. 只支持**单文件 APK**；拆分 APK（base + config）或 AAB 需先合并。
2. 多个 `.apk` 同时存在时取**修改时间最新**的那个，建议一次只放一个，避免拿到旧包。
3. 换包后必须 `clean` 打包（assets 会重新生成）；同版本号换包，装机 `-r` 覆盖即可。
4. 换包会改变 `payload.idx` 的 `sha256`，属于正常现象（构建日志会打印新值）。
5. 如果新包解析不出包名（aapt 失败或包损坏），构建日志会打印警告，APP 里会显示「内置应用」，安装仍可用；建议换包后看一眼构建日志的 `[内置包] package=...` 一行。

---

## 14. 交付规范（第 5 轮新增）

| 目录 / 文件 | 用途 | 维护规则 |
|---|---|---|
| `E:\豆包\HPTADB\会话记录\YYYY-MM-DD.md` | 每次会话的开发/对话记录（诉求、落地、验证、踩坑、未决项） | 同一天多轮 → **更新同一份文件**；跨天 → 新建当天文件 |
| `E:\豆包\HPTADB\doc\二次开发说明.md` | 接手开发指南：工程结构、代码地图、构建、换包、加密三处同步、UI 改动、协议坑、调试命令、加固分发、checklist | 架构或流程变更时同步更新 |
| `E:\豆包\HPTADB\doc\HPT移动端ADB助手-设计说明.md` | 需求级设计与逐轮验证记录（本文件） | 每轮需求完成即追加章节 |
| `E:\豆包\HPTADB\doc\shot_*.png` | 真机验证截图 | 与新章节对应，命名 `shot_<轮次>_<场景>.png` |
## 15. 投屏失败排障：OPEN 服务名必须补结尾 NUL（第 7 轮）

### 15.1 现象

用户反馈「投屏不成功」。`adb logcat -s HPT` 抓到关键序列：

```
INFO 启动设备端 scrcpy 服务：CLASSPATH=... app_process / com.genymobile.scrcpy.Server 2.7 ...
INFO scrcpy-server：[server] INFO: Device: [rockchip] rockchip HPT-R3568 (Android 11)   ← 服务端起来了
WARN 视频端口连接失败（第 1 次…第 30 次）：打开服务失败：localabstract:scrcpy（设备拒绝打开该服务（可能不支持该 adb service））
WARN 视频端口未就绪，重启设备端服务后重试：连不上设备端 scrcpy 视频端口
```

服务端进程活得好好的，`/proc/net/unix` 里也能看到 `@scrcpy` 处于监听状态，但本机每次 OPEN 都被 adbd 用 `CLSE` 拒。

### 15.2 定位过程

1. `ps -A | grep scrcpy` → 设备端 `app_process` 进程存在，`cat /proc/net/unix | grep scrcpy` → `@scrcpy` 状态 listening，说明**服务端没问题**。
2. `adb forward tcp:27183 localabstract:scrcpy` + python 连 `127.0.0.1:27183` → 立刻收到视频头第一个哑字节 `0x00`，说明 **adbd 的 localabstract 通道也没问题**，问题在「我们自己发的 OPEN 报文」。
3. 用 `doc\adb_open_probe.py` 复刻自家客户端，对同一服务名发两种 OPEN：
   - `b"localabstract:scrcpy\0"`（带 NUL，21 字节）→ adbd 回 `OKAY`
   - `b"localabstract:scrcpy"`（不带 NUL，20 字节）→ adbd 回 `CLSE`
   
   对照日志里我们发的 OPEN 长度正好是 20 → **确认就是少了结尾 NUL**。

### 15.3 修复

`AdbConnection.openStream()` 统一补 NUL，所有服务（shell/exec/sync/localabstract）都走同一条干净路径：

```java
String svc = service.endsWith("\0") ? service : service + "\0";
send(A_OPEN, id, 0, svc.getBytes("UTF-8"));
```

### 15.4 验证（真机 HPT-R3568）

| 项目 | 结果 |
|---|---|
| 视频端口 | `OPEN a0=6 len=21` 第 1 次失败（服务端还没 listen）→ 第 2 次 `OK 视频端口已连上（第 2 次尝试，耗时 23ms）` |
| 视频流 | `OK 视频流已建立：H264 1024x576`，解码输出 `color-format=2130708361`，稳定 10.5~10.8 FPS（截图 `doc\shot_d1_mirror.png`） |
| 控制通道 | `OK 控制通道已就绪（第 1 次尝试，耗时 3ms）` |
| 全屏 | 进入全屏、Surface 重建 1220x686、视频继续（`doc\shot_d2_fullscreen.png`） |
| 旋转 | 全屏内点旋转 → `画面旋转：横向`，画面横置（`doc\shot_d3_rotate.png`） |
| 退出全屏 | `退出全屏` → Surface 重建 1120x630 → 视频继续 10.7 FPS（`doc\shot_d4_exitfull.png`） |
| 安装软件 | 回归验证：上传 62.0 MB → 静默安装 → `已删除设备临时文件 .hpt_dy5w1rawgt.apk` → `安装成功：人脸识别团餐系统实时版` |
| 连接/握手 | `OPEN a0=1 len=129`（补 NUL 后长度 +1），握手与设备信息解析正常 |

### 15.5 排障方法论（下次照这个顺序走）

1. 先看 `logcat -s HPT`：区分「服务端没起」/「端口连不上」/「解码器报错」三类。
2. 设备端 `ps` + `/proc/net/unix | grep scrcpy` 判断服务端到底有没有 listen。
3. `adb forward` + 裸 socket 连一下，判断是 adbd 通道问题还是我们自己的 OPEN 报文问题。
4. 报文问题就用 `doc\adb_open_probe.py`（纯 Python，只发 CNXN + OPEN），逐字节比对长度——**长度差 1 往往就是 NUL 这类终止符**。

> 顺带说明：`a0` 长度变化（20 → 21、129 → 130…）是本次修复的可见特征；日志里 OPEN 长度不带 NUL 时应立刻怀疑此类问题。
## 16. 投屏互斥 / 日志脱敏 / 界面标题（第 8 轮）

### 16.1 需求

1. 画布同时只能投一台设备：别的设备正在投屏时，点另一台的「投屏」要提示「先停止其他设备投屏」，不能被静默抢占。
2. 日志框不要出现加密相关的敏感字样（原「资源已加密（签名绑定密钥，安装时边解密边上传）」这类）。
3. APP 界面顶端标题恢复原样 `HPT移动端ADB助手`（桌面图标名继续叫 `HPT远控`）。

### 16.2 落地

**① 投屏互斥**：`MainActivity.toggleMirror()` 进入前先判断当前正在投屏的设备：

```java
Session cur = activeSession;
if (cur != null && cur != session && (cur.mirroring || scrcpy != null || mirror != null)) {
    toast("先停止其他设备投屏");
    log(WARN, "无法投屏：" + rowName(cur) + " 正在投屏，请先停止其他设备投屏");
    return;
}
```

设计取舍：**不做自动抢占**。原来的实现是静默 `stopMirror()` 再启动新的，用户看不出发生了什么；现在改为拒绝 + 提示，主动权交给用户。`activeSession` 在 `finishMirror()` 里会清空，所以这个判断不会误伤「已停止」的状态。

**② 日志脱敏**：删掉/改写所有会暴露保护方案措辞的日志行：

| 位置 | 原来 | 现在 |
|---|---|---|
| `logBuiltinInfo()` | `内置 APK：xxx 包名 xxx 62.0 MB 资源已加密（签名绑定密钥，安装时边解密边上传）` | `内置 APK：xxx 包名 xxx 62.0 MB` |
| `doInstall()` 起始日志 | `开始安装「xxx」→ 设备（62.0 MB，AES-256-GCM 边解密边上传）` | `开始安装「xxx」→ 设备（62.0 MB）` |
| `doInstall()` 清理缓存 | `已清理旧版明文缓存：builtin.apk` | `已清理旧版缓存文件：builtin.apk` |
| `BuiltinApkStream.ERR_SIGN` | `内置包密钥与本机签名不一致（APK 可能被重打包或资源被搬走）` | `内置安装包不可用，请使用正式版安装包后重试` |
| `BuiltinApkStream` 解密失败 | `内置包解密失败（口令不匹配或文件被修改）` | `内置安装包读取失败（文件可能已损坏，请重新安装正式版）` |

原则：日志只讲**用户关心的结果**（装了什么、装到哪台、成功没成功），不讲「怎么藏、用什么算法藏」。加密实现本身没有改动，只是不再往外说。

**③ 界面标题**：拆成两个字符串，各管一处（放在 `res\values\strings.xml`）：

```xml
<string name="app_name">HPT远控</string>              <!-- 桌面图标名 / Manifest android:label -->
<string name="title_in_app">HPT移动端ADB助手</string>   <!-- APP 内顶部标题 -->
```

`activity_main.xml` 顶部 TextView 改用 `@string/title_in_app`；启动日志首行也同步改回「HPT移动端ADB助手 已启动」。

### 16.3 真机验证（宿主 Redmi + HPT-R3568 + HPT-P8）

| 项目 | 结果 | 证据 |
|---|---|---|
| 界面标题 | 恢复为「HPT移动端ADB助手」 | `doc\shot_e1_twodev_s.jpg` |
| 日志首行 | `内置 APK：人脸识别团餐系统实时版  包名 com.hpt.newconsumer  62.0 MB`（无加密字样） | 同上 + logcat |
| 互斥拦截 | 226 投屏中 → 点 P8 的「投屏」→ Toast「先停止其他设备投屏」+ 日志 `WARN 无法投屏：rockchip HPT-R3568 正在投屏，请先停止其他设备投屏`，226 视频继续 10.8 FPS（截图 `doc\shot_e2_blocked_s.jpg`） | 是 |
| 反向切换 | 点 226 的「停止」→ 再点 P8 的「投屏」→ `OK 视频流已连接：H264 640x1024`（P8 竖屏 800x1280，max_size 1024 后为 640x1024） | 是 |
| 两台同时在线 | 列表里 226（投屏中）/ P8（已连接）各自独立按钮，互不影响 | 是 |

> 注意：`max_size=1024` 对竖屏设备（800x1280）出的画面是 `640x1024`，对横屏（1280x720）是 `1024x576`——都是按长边缩放到 1024 的结果，属预期行为。