# 小纸条 Android 端

安卓 App 和桌面小部件。写纸条、涂鸦、贴贴纸，发出去之后对方桌面就会更新。

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

也可以用 Android Studio 直接打开这个目录。

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**装之前先改服务器地址**：`app/src/main/java/com/notedrop/demo/net/AppConfig.kt`
里的 `DEFAULT_SERVER`。也可以装完在 App 的「连接设置」里填，填过的会存本地并优先生效。

## 代码结构

```
app/src/main/java/com/notedrop/demo/
├── data/        数据模型与本地存储
│   ├── Note.kt              纸条模型
│   ├── NoteStore.kt         桌面当前显示的那张
│   ├── InboxStore.kt        收件箱存档
│   ├── Favorite.kt          收藏夹
│   └── NoteComposer.kt      手写 + 贴纸合成一张图
├── net/         网络
│   ├── Api.kt               HTTP 接口
│   ├── AppConfig.kt         账号与服务器地址
│   └── NoteSocketService.kt WebSocket 长连接 + 兜底轮询
├── sticker/     贴纸
│   ├── MagicWand.kt         颜色键抠图
│   ├── CutoutView.kt        抠图画布（带棋盘格）
│   └── StickerStore.kt      贴纸库
├── ui/          界面
│   ├── MainActivity.kt      首页
│   ├── ComposeActivity.kt   写纸条页
│   ├── CutoutActivity.kt    抠图页
│   ├── NoteListActivity.kt  收件箱 / 收藏夹
│   ├── NoteDetailActivity.kt 纸条详情
│   ├── DoodleView.kt        手写画板
│   └── NoteRenderView.kt    只读渲染（能画贴纸、能点中）
└── widget/
    └── NoteWidgetProvider.kt  桌面小部件
```

## 三个关键实现

### 1. 桌面刷新不等系统定时器

`NoteWidgetProvider.refresh(context)` 是"立刻更新桌面"的唯一入口。
任何地方调用它，都会重新组装 RemoteViews 推给桌面：

```kotlin
NoteWidgetProvider.refresh(this)
```

收到消息后调这一句，就能做到「对方一发，我桌面就变」。
这条链路不受 `updatePeriodMillis` 那 30 分钟下限约束 —— 那个只是系统兜底唤醒。

### 2. 手写和贴纸要合成成一张图

RemoteViews 里只有一个 ImageView，没法叠图层；而贴纸在数据上又是独立的一份
（为了能让接收方单独取下来）。

所以给小部件出图前先合一次。只影响显示，不影响存档里的贴纸数据。

### 3. 画布尺寸是固定的

编辑页的工具栏是可收起的抽屉，展开时画布区会变矮。
如果画布跟着控件高度走，会出现两个问题：导出时按变矮后的高度出图，
下面的内容被裁掉；贴纸存的是相对坐标，高度一变位置就漂。

所以画布在第一次布局时定死尺寸，界面只是看它的窗口。

## 小部件适配

小部件是标准 AppWidget，**随 App 安装就能用，不需要任何厂商审核**。

已按小米规范预埋：

- `miuiWidget` / `miuiWidgetRefresh=exposure` / `miuiWidgetRefreshMinInterval`
- `miuiWidgetVersion=1`（应用内所有小部件共用，有变更时必须递增）

还没做的（不影响使用，只影响进小米小部件中心）：

- 独立进程 `:widgetProvider` 与 35M 内存约束
- 根布局 `@android:id/background` 的圆角标识
- 预览图目前是矢量占位，正式提交要按 440×440 / 948×440 / 948×1000 出图

## 已知边界

- 用户手动「强行停止」App 后，任何应用都无法拉起刷新，这是 Android 平台限制。
- 只在小米上实测过，华为 / OPPO / vivo / 鸿蒙没逐个验证。
- 多次发送时，桌面小部件只显示最新那张，历史在收件箱里。
