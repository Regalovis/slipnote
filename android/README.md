# 小纸条 Demo（NoteDrop）

一个可编译、可安装的 Android 小部件 Demo，验证「写纸条 → 桌面立刻出现」这条核心链路。

## 现在能做什么

1. 打开 App，点「写一张小纸条」
2. 可以打字，也可以直接在下方用手写/涂鸦
3. 点「发送」
4. 返回桌面，长按空白处 → 添加小部件 → 找到「小纸条」
5. 桌面上就会出现这张纸条，可拖动调整大小
6. 再点桌面上的纸条，会重新进入编辑页

**关键验证点**：从点「发送」到桌面小部件刷新，走的是**主动刷新**通道，
不等系统那 30 分钟的定时器。

## 环境

| 项目 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 36 |
| minSdk | 26（Android 8.0） |

## 构建

```bash
cd android
./gradlew assembleDebug
```

第一次跑需要本机有 Gradle 8.9+；也可以直接用 Android Studio 打开这个目录。

产物：`app/build/outputs/apk/debug/app-debug.apk`

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 代码结构

```
app/src/main/java/com/notedrop/demo/
├── data/
│   ├── Note.kt              纸条数据模型
│   └── NoteStore.kt         本地存储（SharedPreferences + 涂鸦文件）
├── ui/
│   ├── MainActivity.kt      首页，显示当前纸条状态
│   ├── ComposeActivity.kt   写纸条页：打字 + 涂鸦 + 发送
│   └── DoodleView.kt        手写画板（中点贝塞尔平滑）
└── widget/
    └── NoteWidgetProvider.kt  桌面小部件 + 主动刷新入口
```

## 两个关键实现点

### 1. 主动刷新（产品的技术核心）

`NoteWidgetProvider.refresh(context)` 是所有"立刻更新桌面"的唯一入口。
任何地方调用它，都会重新组装 RemoteViews 并推送给桌面：

```kotlin
// 在发送后调用
NoteWidgetProvider.refresh(this)
```

接推送时，只要在收到消息的回调里调这一句，就能做到"对方一发，我桌面就变"。
这条链路**不受 `updatePeriodMillis` 30 分钟下限的约束**，
那个 30 分钟只是系统兜底唤醒的间隔。

### 2. 涂鸦为什么要存成图片

手写内容没有对应的 RemoteViews 控件，所以 `DoodleView` 导出成 PNG 存本地，
小部件里用 `ImageView` 显示。读取时按小部件实际宽度做采样
（`NoteStore.decodeDoodle`），避免大图进 RemoteViews 造成 transaction 超限
——这是小部件里最容易踩的坑之一。

## 尚未包含（Demo 的边界）

这个 Demo 只解决"从本机写、到本机桌面显示"，以下都还没做：

* 账号、配对、多用户
* 服务端、网络请求
* 推送（FCM / 小米 / 华为 / OPPO / vivo）
* 纸条历史列表
* 点击小部件"展开放大"的动效（目前是跳编辑页）
* 手写字体已打包进 `assets/LongCang-Regular.ttf`（龙藏体，OFL 授权），但还没接到 UI 上

## 各厂商适配现状

小部件本身是标准 Android AppWidget，**随 App 安装即可使用，不需要任何厂商审核**。

已按小米规范预埋的项：

* `miuiWidget` / `miuiWidgetRefresh=exposure` / `miuiWidgetRefreshMinInterval` 元数据
* `miuiWidgetVersion=1`（应用内所有小部件共用，有变更时必须递增）

未做的项（不影响使用，但影响进小米小部件中心）：

* 独立进程 `:widgetProvider` 与 35M 内存约束
* 根布局 `@android:id/background` 的圆角标识
* 预览图目前是矢量占位图，正式提交需要按 440×440 / 948×440 / 948×1000 出图

## 已知边界

* 用户手动"强行停止"App 后，任何应用都无法拉起刷新，这是 Android 平台限制。
* 多个小部件共用同一份数据；"每个配对各自一张纸条"需要等接入多用户后再做。
* 桌面上的纸条整块都是一个点击热区，目前没有区分"查看"和"写"两个动作。
