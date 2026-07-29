# 重要的事-可迁移（Android）

把自动化任务「重要的事-可迁移」落地为 Android App：  
**每 30 分钟（09:00–22:00）随机弹出一条安心提醒**，通知标题固定为「重要的事-可迁移」。  
同时内置 **物品管理** 模块：记录物品放在哪个房子的哪个位置、过期时间到期自动提醒，支持拍照与扫码识别。

## 功能
### 安心提醒
- WorkManager 周期任务，每 30 分钟触发一次；窗口外（22:00–次日 09:00）静默跳过。
- 消息库内置在 `Messages.kt`，共 40+ 条，随机推送。
- 主界面：消息总览列表、提醒开关、一键「测试一条」。
- 开机自启（BootReceiver）重新调度；开关状态持久化。

### 物品管理（`inventory` 包）
- **多房子多位置**：房子（自己家/父母家/公司…）→ 位置（厨房冰箱/主卧衣柜…）两级管理，物品挂到具体位置。
- **拍照**：系统相机拍照存入 App 私有目录，并用 ML Kit 中文 OCR（离线）自动从照片识别过期日期。
- **扫码**：ZXing 扫条形码/二维码，自动解析码内日期（支持 `2026-08-01`、`2026年8月1日`、`20260801`、GS1 AI(17) 等格式）。
- **过期时间**：识别失败可手动选日期；每件物品可单独设置「提前 N 天提醒」（默认 3 天）。
- **到期提醒**：WorkManager 每 12 小时检查（夜间静默），临期每天提醒一次、过期后持续每天提醒，通知点开直达物品列表。
- 数据存 Room 本地数据库（`inventory.db`），照片存 `filesDir/photos/`，无需后端。

## 技术栈
- Kotlin + AndroidX
- WorkManager（`work-runtime-ktx:2.9.0`）
- Room 2.6.1（物品/房子/位置本地数据库）
- ZXing `zxing-android-embedded:4.3.0`（扫条形码/二维码）
- ML Kit `text-recognition-chinese:16.0.0`（拍照 OCR 识别日期，纯离线）
- compileSdk / targetSdk 34，minSdk 24
- 无需任何后端，纯本地运行

## 构建与运行
> 需要 Android Studio / 已安装 Android SDK（Build-Tools 34）。

方式一：Android Studio
1. `File → Open` 选择本目录（`android_app/`）。
2. 等待 Gradle 同步（会自动生成 Gradle Wrapper）。
3. 连接设备或启动模拟器，`Run 'app'`。

方式二：命令行
```bash
# 若本机已装 Gradle，先生成 wrapper
gradle wrapper --gradle-version 8.6
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 自定义
- **改消息**：编辑 `app/src/main/java/com/example/migratable/Messages.kt` 的 `Messages.LIST`。
- **改频率/窗口**：`ReminderWorker.kt` 的 `30, TimeUnit.MINUTES` 与时间判断；调度在 `ReminderScheduler.kt`。
- **改包名/应用名**：`app/build.gradle` 的 `applicationId`、`AndroidManifest.xml` 的 `android:label`、`res/values/strings.xml`。

## 注意
- Android 13+ 首次运行需授权「发送通知」权限；低版本自动允许。
- WorkManager 最短周期为 15 分钟，30 分钟为系统允许的稳定间隔。
- 拍照 / 扫码首次使用需授权「相机」权限。
- 物品过期提醒依赖系统 WorkManager，个别厂商 ROM 深度省电模式下可能延迟，可在系统设置中将本 App 加入「不限制后台」。
