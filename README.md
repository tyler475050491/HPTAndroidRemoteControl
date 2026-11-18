# HPT远控（HPT移动端ADB助手）

一个**纯 Java、零第三方依赖**的 Android 应用层 ADB 客户端：手机装了它，就能通过无线 ADB 连接同一局域网里的设备（产线终端 / 一体机 / 电视盒子等），**被控设备不需要 root** 即可完成投屏、鼠控、安装、卸载、重启。

- 桌面图标名：**HPT远控**
- APP 内标题：**HPT移动端ADB助手**
- 包名：`com.hpt.adbtool`
- 版本：`1.1.0`（versionCode 11），界面标题右下角显示 `v1.1.0 · MMdd-HHmm`（构建标记）
- 构建产物：`HPT远控.apk`

> 本文件是**项目总览**。想改代码请看 `doc\二次开发说明.md`；想了解需求和历史验证过程请看 `doc\HPT移动端ADB助手-设计说明.md`；每天的开发记录在 `会话记录\YYYY-MM-DD.md`。

---

## 1. 功能一览

| 功能 | 说明 |
|---|---|
| 连接设备 | 输入 IP / 域名 + 端口（默认 5555），一键连接；局域网无线 ADB，无需数据线 |
| 刷新 | 清空列表后重新拉取已连接设备 |
| 搜索 | 并发扫描网段内 254 个地址，按端口验证哪些是 ADB 设备，带进度；搜索期间其他操作全部禁用 |
| 设备列表 | 每台设备一行：设备名 / 状态 / 连接信息 / 分辨率，左侧复选框**单选**，安装卸载都作用于选中设备 |
| 投屏 | scrcpy-server 2.7 + MediaCodec 硬解，实测 1280x720 设备约 10~11 FPS；画布右下角有全屏图标 |
| 投屏互斥 | 画布同时只能投一台；别的设备正在投屏时点另一台会提示「先停止其他设备投屏」，不会静默抢占 |
| 停止按钮变红 | 投屏中该行按钮变红底「停止」，停下后自动恢复蓝底「投屏」 |
| 全屏鼠控 | 全屏下可点按、拖动、滚轮，直接操作被控设备；顶部可旋转画面（竖↔横）；退出全屏后只剩视频流、触控关闭 |
| 安装软件 | 点按钮先弹窗：显示目标设备 + 随包携带的安装包（应用名/包名/版本/大小；打包时 `apk\` 为空则这行不显示任何包信息）；可「从手机选择 APK」挑设备本地安装包，选完弹窗显示所选文件与大小，**再点「安装应用」才真正开始装**；两者都没有时点「安装应用」提示「请选择APK安装包文件」。安装包在 APK 资源里是**加密容器**，运行时逐块解密直接流给设备，装完删除设备侧临时文件 |
| 卸载软件 | 弹窗只列第三方应用（`pm list packages -3`），系统应用不显示，每行自带卸载按钮 |
| 退出桌面 | 一键回到系统桌面（应用退到后台，投屏继续） |
| 重启 | 远程重启被控设备 |
| 操作日志 | 彩色结构化日志（天蓝时间戳、绿色成功、红色错误、黄色警告），可一键清空 |
| 版本徽标 | 标题下方右侧显示 `v版本号 · 构建标记`，每次打包构建标记都会变，便于区分包 |
| 启动信息 | 启动时日志打印版本号与本机 IP（不打印任何内置资源信息） |

---

## 2. 环境要求

| 项 | 要求 |
|---|---|
| 开发环境 | Android Studio（用其自带的 JBR，**JDK 25**），工程通过 `gradle.properties` 的 `org.gradle.java.home` 指定 |
| Gradle / AGP | Gradle `9.8.2` + AGP `9.1.0` |
| SDK | compileSdk / targetSdk `34`，minSdk `24`，Java 17 源码级别 |
| 工程路径 | **允许中文路径**，但 `gradle.properties` 里的 `android.overridePathCheck=true` 必须保留，否则构建直接失败 |
| 依赖 | **零第三方依赖**，`android.useAndroidX=false`。ADB 协议（`AdbConnection` / `AdbCrypto` / `AdbStream`）全部手写 |
| 宿主手机 | Android 8.0+，已开启「无线调试」或 `adb tcpip 5555`，与目标设备同一局域网 |
| 目标设备 | Android 5.0+，`adbd` 可通过 TCP 5555 连上；无 root 要求（但 adbd 是 root 时更好用） |

---

## 3. 构建

### 3.1 命令行构建（推荐，出正式包）

```powershell
$gradle = "C:\Users\TNND\.gradle\wrapper\dists\gradle-9.8.2-bin\3wb4k92zt3y241x5qwqkhg6w0\gradle-9.8.2\bin\gradle.bat"
& $gradle -p "E:\豆包\HPTADB" clean :app:assembleRelease --console=plain --no-daemon *> "E:\豆包\HPTADB\build_log.txt"
```

- 产物：`app\build\outputs\apk\release\app-release.apk`
- **改内置包 / 改加密参数 / 改证书后一定要 `clean`**：增量构建有把 assets 重复打包的历史问题（体积会翻倍）
- 版本号在 `app\build.gradle` 的 `defaultConfig`：`versionCode` + `versionName`，**每次交付手动递增**；同时构建期会自动生成 `build_stamp`（`MMdd-HHmm`），显示在界面标题右下角和启动日志里，所以同一版本号的多次打包也能区分
- AGP 9 默认关闭 `resValue` 生成，`app\build.gradle` 里必须有 `buildFeatures { resValues true }`，否则构建报 `defaultConfig contains custom resource values, but the feature is disabled`
- 构建日志会打印一行关键信息，换包后先看它：

```
[内置包] ACM_YH_HDL_P8-HiV5.0.4-2026-11-17.apk 24199074 bytes -> hpt_payload.dat 24199283 bytes（AES-GCM 6 块，密钥绑定签名证书）
[内置包] package=com.hpt.face label=海底捞考勤系统
```

### 3.2 安装到手机

```powershell
$adb = "C:\Users\TNND\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s <手机序列号> install -r "E:\豆包\HPTADB\HPT远控.apk"
```

同一套证书签名的包可以直接 `-r` 覆盖，**应用数据（已保存地址、日志）保留**。换了证书则必须先 `adb uninstall com.hpt.adbtool`，否则装不上。

---

## 4. 签名与内置包加密

### 4.1 正式签名

| 项 | 值 |
|---|---|
| 证书文件 | `keystore\hpt-release.jks`（PKCS12，alias `hpt`，有效期 30 年） |
| 口令 | 写在 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`） |
| 证书信息 | `CN=HPT Mobile ADB, OU=HPT, O=HPT, L=Shenzhen, ST=Guangdong, C=CN` |
| 证书 SHA-256 | `f8b7d2021a6086551d84997870884965ba45b11706debf2a6946c7dea95333d9` |
| 签名方案 | v2 + v3（minSdk 24，不需要 v1） |

> **`keystore\hpt-release.jks` 与 `keystore.properties` 务必自行备份**：丢了就无法给已装的应用做覆盖升级，只能让用户卸载重装（数据全丢）。
>
> debug 与 release **共用这套证书**。原因：内置包密钥绑定签名证书，两侧证书不一致会导致 debug 包解不出内置包。

### 4.2 内置安装包防提取

- `apk\` 目录里放你要内置的安装包（构建任务取**修改时间最新的那个 `.apk`**，建议一次只放一个）。
- 构建期 `syncBuiltinApk`（挂在 `preBuild`）会：`aapt` 解析出版本/包名/应用名 → 4MB 分块 AES-256-GCM 加密 → 输出 `app\src\main\assets\hpt_payload.dat` + `payload.idx`（只写包名/应用名/大小/密文大小/sha256，不写文件名与算法）。
- 容器格式：`HPTK(4) | ver=2(1) | chunkSize(4) | chunkCount(4) | keyCheck(4) | {iv(12) | ctLen(4) | ct}`。
- 密钥 = `SHA-256( 口令串 ‖ '|' ‖ SHA-256(签名证书DER) )`，口令串在 `Secret.java` 里以 XOR 编码的字节表存放，不出现明文字符串。
- 结论：**APK 里没有明文安装包，也解不开**——换一份签名证书、或者把 `hpt_payload.dat` 搬到别的 App 里，`keyCheck` 就过不去。

> 改加密方案必须**三处同步**：`app/build.gradle` 的 `syncBuiltinApk`、`Secret.java`、`BuiltinApkStream.java`。改完 clean 重建并真机走一次「安装软件」。

### 4.3 代码混淆

release 开启 R8（`minifyEnabled` + `shrinkResources` + `-repackageclasses 'hpt'`），类名全部被压缩重命名进 `hpt.*`，只有 Manifest 里注册的 `MainActivity` 保名。mapping 在 `app\build\outputs\mapping\release\mapping.txt`，**不随包交付**。

---

## 5. 目录结构

```
E:\豆包\HPTADB\
├─ README.md                    ← 本文件（项目总览）
├─ HPT远控.apk                   ← 交付包（正式版）
├─ apk\                         ← 放要内置的安装包（构建时取最新 .apk）
├─ keystore\                    ← 正式签名证书（务必备份）
├─ keystore.properties          ← 签名口令
├─ doc\
│   ├─ HPT移动端ADB助手-设计说明.md   ← 需求级设计 + 逐轮验证记录
│   ├─ 二次开发说明.md                ← 接手开发指南（代码地图/构建/换包/协议坑/checklist）
│   ├─ adb_open_probe.py             ← 最小 ADB 客户端探针（排查 OPEN 问题）
│   ├─ scrcpy_probe.py               ← scrcpy 视频流探针
│   └─ shot_*.png / screenshot_*.png ← 各轮真机验证截图
├─ 会话记录\
│   └─ 2026-11-17.md            ← 按日期记录每次会话（同一天更新同一份）
└─ app\src\main\
    ├─ java\com\hpt\adbtool\    ← 13 个类，全部手写
    └─ res\                    ← 布局 / 颜色 / 字符串 / 图标
```

### 核心类

| 类 | 职责 |
|---|---|
| `MainActivity` | 界面、设备列表、投屏生命周期、安装卸载、全屏与触控分发、日志 |
| `AdbConnection` | ADB 报文编解码与流控（CNXN / AUTH / OPEN / OKAY / WRTE / CLSE） |
| `AdbCrypto` | RSA 2048 + SHA1 认证，Android 私有 `RSAPublicKey` 结构 |
| `AdbStream` / `AdbDevice` | 单条服务流；设备能力封装（shell / exec / push / 安装 / 列包 / 卸载 / 重启） |
| `ScrcpyServer` | 强推 `scrcpy-server.jar` 并拉起服务端（`shell:` + `app_process`） |
| `ScrcpyMirror` | 视频通道：读头 → MediaCodec 硬解 → `TextureView` 直出，帧率统计，触控下发 |
| `ScrcpyControl` | 控制通道：32 字节大端 `INJECT_TOUCH_EVENT` 报文 |
| `Secret` / `BuiltinApkStream` | 内置包密钥派生；加密容器逐块解密读取 |
| `PreviewView` | 预览画布容器（`TextureView` 直出 + 全屏复用） |

---

## 6. 快速上手

1. 手机开「无线调试」（或先 USB 执行 `adb tcpip 5555`），记下手机 IP。
2. 打开 APP → 输入目标设备地址（如 `192.168.0.226`）+ 端口 `5555` → 点「连接设备」。
3. 列表出现设备后勾选它 → 点该行「投屏」，画布开始显示画面。
4. 点画布右下角全屏图标进入全屏，可直接点按操作设备；顶部图标可旋转画面、退出全屏。
5. 「安装软件」把内置包装到选中设备；「卸载软件」只列第三方应用，逐行可卸。
6. 需要看日志定位问题：`adb -s <手机> logcat -s HPT`。

---

## 7. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 点「投屏」提示「设备拒绝打开该服务」 | 历史坑：OPEN 的服务名必须补结尾 `\0`，已在 `AdbConnection.openStream()` 统一处理。若再复现，用 `doc\adb_open_probe.py` 对照 `localabstract:scrcpy` 带/不带 NUL 的返回 |
| 视频端口第 1 次失败、第 2 次成功 | 正常：服务端需要几十~几百毫秒才开始监听，代码本身带重试 |
| 装不上（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`） | 证书换了。先 `adb uninstall com.hpt.adbtool`，注意会清数据 |
| APK 体积突然翻倍到 130MB | 没 clean。`clean` 后重打 |
| 换内置包后弹窗还是旧应用名 | 没 clean，或 `apk\` 里还留着旧的 `.apk`（构建取最新修改时间的那个） |
| 列表里全是 `adb connect` 失败 | 目标设备没开 `tcpip 5555`、或不在同一网段；手机侧需有 INTERNET 权限（已内置） |
| 日志框出现「内置安装包读取失败」 | 内置资源与当前签名不匹配（被重打包/被搬移），用正式包重装 |
| 投屏后目标设备 DPI/分辨率不同 | `max_size=1024` 按长边缩放：横屏 1280x720 → 1024x576，竖屏 800x1280 → 640x1024 |

---

## 8. 版本记录

| 版本 | 日期 | 主要内容 |
|---|---|---|
| 1.0.0 | 2026-11-17 | 首个可用版本：连接 / 设备列表 / 投屏 / 安装 / 卸载 / 日志 |
| 1.0.1 | 2026-11-17 | 9 项 UI 改造；scrcpy 真视频流 + 全屏鼠控；正式签名 + R8 混淆 + 内置包加密容器 v2；输入框加框 + 紫色搜索；显示名 `HPT远控`；投屏互斥、日志脱敏、界面标题复原 |
| 1.0.2 | 2026-11-18 | 启动日志去掉内置资源信息；标题下方加版本徽标（含每次变化的内容构建标记）；启动日志打印本机 IP |
| 1.0.3 | 2026-11-18 | 行内「停止」按钮投屏中变红，停止后恢复蓝色 |
| 1.0.4 | 2026-11-18 | 「安装软件」改弹窗：显示随包携带的应用名/包名/版本/大小（打包时 `apk\` 为空则整段不显示），并支持「从手机选择 APK」安装设备本地安装包 |
| 1.0.5 | 2026-11-18 | 自选 APK 支持「大小未知」的来源（按 1MB 汇报进度、只校验实际收到字节）；实测自选安装端到端通过 |
| 1.0.6 | 2026-11-18 | 弹窗去掉所有「内置」字样；按钮统一为「安装应用」；选完 APK 不自动安装，需再点「安装应用」；无可用包时提示「请选择APK安装包文件」 |
| 1.0.7 | 2026-11-18 | 修复「从手机选择 APK」返回后弹窗重建崩溃（视图复用导致 `child already has a parent`） |
| 1.0.8 | 2026-11-18 | 已选 APK 在重连/重新打开弹窗后保留，不再每次清空 |
| 1.0.9 | 2026-11-18 | 「取消」不再清空已选 APK；真机验证：选包→重连→点「安装应用」→ 静默安装成功并清理设备临时文件 |
| 1.1.0 | 2026-11-18 | 全界面 / 启动日志去掉「无需 root」字样（安装弹窗目标设备行、启动横幅） |

当前交付包：`HPT远控.apk`，**24,361,486 B**，SHA-256 `DBE10477BA67F57A180C8298F2E68F72B33CD52FC3FB5952D34D81CE439A1098`
当前内置包：`ACM_YH_HDL_P8-HiV5.0.4-2026-11-17.apk`（24,199,074 B，`com.hpt.face` / 海底捞考勤系统）

---

## 9. 文档维护规则

| 目录 / 文件 | 什么时候更新 |
|---|---|
| `README.md` | 功能、构建方式、签名/加密方案、目录结构有变化时 |
| `doc\二次开发说明.md` | 代码结构、构建流程、换包流程、协议坑有新增时 |
| `doc\HPT移动端ADB助手-设计说明.md` | 每完成一轮需求追加一章（含验证结果与截图名） |
| `会话记录\YYYY-MM-DD.md` | 每次会话结束；**同一天多轮更新同一份，跨天新建** |
| `doc\shot_*.png` | 每轮验证截图，命名 `shot_<轮次><序号>_<场景>.png` |

---

## 10. 已知限制

1. 视频默认 `max_size=1024`（清晰度与带宽的折中），如需 1280 可改 `MainActivity.VIDEO_MAX_SIZE`。
2. 鼠控目前只有单指点按 / 拖动 / 滚轮，没有多指缩放。
3. 「重启」按钮未在产线设备上实测（远程重启若回不来会失去设备，谨慎使用）。
4. 设备 `adbd` 偶发拒绝 `localabstract:scrcpy`（环境性问题），代码有 35 秒重试 + 重启服务端兜底。
5. 内置包只支持**单个完整 APK**；拆分包（base + config）或 AAB 需先合并。
6. 未接入商业加固壳（虚拟化/抽取壳）：能防「从 APK 里拷资源」，但防不住运行时的内存 dump——这与「零第三方依赖」的约束冲突，需要的话得单独评估。
