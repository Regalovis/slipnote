package com.notedrop.demo.net

import android.app.Service
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.notedrop.demo.data.Note
import com.notedrop.demo.data.NoteStore
import com.notedrop.demo.data.PaperStyle
import com.notedrop.demo.data.InboxStore
import com.notedrop.demo.data.NoteSticker
import com.notedrop.demo.sticker.Sticker
import com.notedrop.demo.sticker.StickerStore
import com.notedrop.demo.ui.MainActivity
import com.notedrop.demo.widget.NoteWidgetProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * 与服务端保持 WebSocket 长连接。
 *
 * 这是"对方一发、我桌面就变"的客户端落点：
 * 收到 type=note 的消息 → 落盘 → 立刻刷新桌面小部件。
 *
 * 为什么用前台 Service：
 * 普通后台 Service 在 Android 8+ 很快会被系统冻结甚至杀掉，
 * 长连接随即断开。前台 Service 才能稳定存活到用户主动停止。
 */
class NoteSocketService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var pollJob: Job? = null
    private var socket: WebSocket? = null

    /** 是否已收到过 ready，用于给 UI 一个"在线"信号。 */
    @Volatile
    private var connected = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppConfig.load(this)
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppConfig.load(this)
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { runLoop() }
        }
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        /*
         * 每次被拉起都主动补一次离线纸条。
         *
         * 不能只依赖 WebSocket 连上后的补发：如果长连接因为网络原因
         * 迟迟建立不起来，用户就一直收不到东西，但界面上看不出异常。
         * HTTP 拉取是更可靠的兜底通道。
         */
        scope.launch { pullPending() }
        // 被系统杀掉后自动重启，保证长连接尽量存活
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        pollJob?.cancel()
        socket?.close(1000, "service destroyed")
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        runCatching {
            val notification = NotificationHelper.build(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NotificationHelper.ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationHelper.ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground 失败：${it.message}") }
    }

    /** 断线重连主循环：指数退避，避免网络差时疯狂重试耗电。 */
    private suspend fun runLoop() {
        var attempt = 0
        while (true) {
            val token = AppConfig.token
            if (token.isNullOrBlank()) {
                // 还没注册，等一会儿再试
                delay(5000)
                continue
            }

            try {
                connectOnce(token)
                // connectOnce 在连接结束时才返回，正常断开后重置退避
                attempt = 0
            } catch (e: Exception) {
                Log.w(TAG, "连接失败：${e.message}")
            }

            connected = false
            socket = null
            attempt++
            val backoff = minOf(30_000L, 1000L * (1 shl minOf(attempt, 5)))
            delay(backoff)
        }
    }

    /**
     * 兜底轮询。
     *
     * ## 为什么长连接之外还要轮询
     *
     * 用户报"对方发了三张，将近一个小时才收到"。长连接在国内手机上
     * 本来就容易被系统掐（省电策略、后台限制、切换网络），
     * 而掐掉的那一刻是**静默**的 —— 没有 FIN、没有回调，
     * 客户端以为自己还连着，服务端那边也还没超时。
     *
     * 这种半开连接最长可能拖到 TCP keepalive 才发现，就是几十分钟。
     * 只靠长连接必然出现这种"延迟很久"。
     *
     * 所以每 60 秒主动拉一次 pending。它走 HTTP，不依赖长连接状态；
     * 就算长连接是好的，这一趟也是空请求，开销可以忽略。
     * 这样最坏情况下的延迟从"几十分钟"压到"一分钟以内"。
     */
    private suspend fun pollLoop() {
        while (true) {
            delay(POLL_INTERVAL_MS)
            if (!AppConfig.isRegistered) continue
            runCatching { pullPending() }
        }
    }

    /** 建立一次连接并把收到的消息交给 handleMessage。连接关闭后返回。 */
    private suspend fun connectOnce(token: String) {
        val url = "${Api.wsUrl()}?token=$token"
        val request = Request.Builder().url(url).build()

        val client = Api.http.newBuilder()
            .pingInterval(25, TimeUnit.SECONDS) // 应用层心跳，穿透 NAT
            .build()

        val latch = java.util.concurrent.CountDownLatch(1)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已连接")
                socket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "处理消息失败", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket 断开：${t.message}")
                connected = false
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 关闭 code=$code reason=$reason")
                connected = false
                latch.countDown()
            }
        }

        client.newWebSocket(request, listener)

        // 阻塞等待连接结束；断线后由外层循环重连
        try {
            latch.await()
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun handleMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "ready" -> {
                connected = true
                Log.i(TAG, "服务端就绪 userId=${json.optString("userId")}")
                // 连上后立刻拉一次离线纸条，补齐断线期间错过的内容
                pullPending()
            }
            "note" -> {
                val note = json.optJSONObject("note") ?: return
                onNoteReceived(note)
                /*
                 * 实时推来的纸条也要回执。
                 *
                 * 服务端现在等 ACK 才标记已送达；实时通道同样需要，
                 * 否则这条会一直挂在待发队列里，每次重连都重发一遍
                 * （客户端有去重，不会存两条，但白白多跑一趟网络和磁盘）。
                 */
                note.optString("id").takeIf { it.isNotBlank() }?.let { sendAck(listOf(it)) }
            }
            "paired" -> {
                // 对方配对成功，记下作为默认发送对象
                val peer = json.optJSONObject("peer") ?: return
                val peerId = peer.optString("userId")
                if (peerId.isNotBlank()) AppConfig.setLastPeer(this, peerId)
            }
            "pong" -> Unit
        }
    }

    /** 收到对方发来的纸条：落盘 → 刷新桌面。 */
    private fun onNoteReceived(note: JSONObject) {
        // 服务端给每条纸条分配了稳定 id，用它做去重和存档主键。
        // 没有 id 的（理论上不该出现）用时间戳兜底，保证仍能存下来。
        val noteId = note.optString("id").takeIf { it.isNotBlank() }
            ?: "t${note.optLong("createdAt", System.currentTimeMillis())}"

        val text = note.optString("text", "")
        val fromNick = note.optString("fromNickname", "TA")
        val createdAt = note.optLong("createdAt", System.currentTimeMillis())
        // 发送方选的纸张样式与方向要一并落地，否则接收方没法正确排版
        val paperStyle = PaperStyle.fromWire(note.optString("paperStyle", "white"))
        val landscape = note.optBoolean("landscape", false)

        // 涂鸦是 base64 内联传输，落成本地 PNG 文件后交给小部件显示
        val doodleBase64 = note.optString("doodle", "")
        val doodlePath = if (doodleBase64.isNotBlank()) {
            saveDoodleFromBase64(doodleBase64)
        } else {
            null
        }

        /*
         * 贴纸落盘。
         *
         * 每张贴纸存两个地方：
         *   1. 贴纸库（StickerStore）—— 用户之后能重复使用
         *   2. 纸条自己的 NoteSticker 列表 —— 记录它在这张纸上的位置
         *
         * 分开存是因为同一张贴纸可能被贴到多张纸上，
         * 图片内容只存一份，位置各存各的。
         */
        val stickers = parseStickers(note.optJSONArray("stickers"), fromNick)

        val noteObj = Note(
            text = text,
            doodlePath = doodlePath,
            senderName = fromNick,
            createdAt = createdAt,
            paperStyle = paperStyle,
            landscape = landscape,
            stickers = stickers
        )

        /*
         * 三处各存一份，职责不同：
         * 1. InboxStore  —— 完整存档，用户之后能翻看（本次新增）
         * 2. NoteStore   —— 桌面小部件当前显示的那一张
         * 3. 收藏夹      —— 由用户在收件箱里手动决定，这里不动
         *
         * 顺序很重要：先存档再写小部件。
         * 存档里的涂鸦是复制出来的独立文件，
         * 而 NoteStore 用的 doodle_latest.png 会被下一条覆盖。
         */
        val archived = InboxStore.add(this, noteObj, noteId)

        NoteStore.write(this, noteObj)

        // 关键一步：立刻刷新小部件，用户几秒内就能在桌面看到
        NoteWidgetProvider.refresh(this)

        /*
         * 如果 App 不在前台，再补一条通知。
         *
         * 只刷小部件的话，用户可能一整天都不滑到那个桌面页，
         * 完全不知道有人给自己留了纸条。通知是必要的提醒手段。
         */
        if (!isAppInForeground()) {
            notifyNewNote(fromNick, text)
        }

        Log.i(
            TAG,
            "收到来自 $fromNick 的纸条（含 ${stickers.size} 张贴纸），已刷新桌面" +
                if (archived) "（已存档）" else "（重复，跳过存档）"
        )
    }

    /**
     * 解析收到的贴纸：把内联的 base64 图片落盘，存进贴纸库。
     *
     * 拿到的是"内容 + 位置"的组合，这里拆开：
     * 图片进贴纸库（可复用），位置留在纸条上（这张纸专属）。
     */
    private fun parseStickers(arr: org.json.JSONArray?, fromName: String): List<NoteSticker> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = mutableListOf<NoteSticker>()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val data = o.optString("data", "")
            if (data.isBlank()) continue

            /*
             * 贴纸必须**直接进贴纸库**，不能借道 NoteStore.saveDoodleBytes。
             *
             * 那条路径的落盘文件是固定的 doodle_latest.png ——
             * 它正是这张纸条手写底图要用的文件。贴纸走到那里的话，
             * 每解一张就把底图覆盖一次，最后这张纸条的手写部分
             * 会变成最后一张贴纸的图。这是个必须绕开的坑。
             *
             * 这里自己解、自己登记进贴纸库（sticker_xxx.png），
             * 和底图文件互不干涉。
             */
            val savedSticker = runCatching {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                // 走尺寸受限的解码，防止对方发超大图把本地位图预算撑爆
                val bmp = decodeBounded(bytes) ?: return@runCatching null
                try {
                    StickerStore.add(this, bmp, Sticker.Origin.RECEIVED, fromName)
                } finally {
                    bmp.recycle()
                }
            }.getOrNull()

            val localPath = savedSticker?.path

            out.add(
                NoteSticker(
                    id = o.optString("id").ifBlank { "s$i" },
                    cx = o.optDouble("cx", 0.5).toFloat(),
                    cy = o.optDouble("cy", 0.5).toFloat(),
                    widthRatio = o.optDouble("widthRatio", 0.3).toFloat(),
                    rotation = o.optDouble("rotation", 0.0).toFloat(),
                    localPath = localPath,
                    fromName = o.optString("fromName").takeIf { it.isNotBlank() } ?: fromName
                )
            )
        }
        return out
    }

    /**
     * 受限解码：最长边压到 [STICKER_MAX_PX] 以内。
     *
     * 接收方无法预判对方发多大的图，直接 decodeByteArray 有可能
     * 一次性申请几十 MB；先读尺寸再按 2 的幂降采样是标准做法。
     */
    private fun decodeBounded(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= STICKER_MAX_PX) sample *= 2

        return android.graphics.BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    /** 简单的可见性判断：有没有属于本应用的 Activity 处于前台。 */
    private fun isAppInForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val procs = am.runningAppProcesses ?: return false
        return procs.any {
            it.processName == packageName &&
                it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    /** 收到新纸条的通知，点击直接进首页（桌面小部件已经同步更新）。 */
    private fun notifyNewNote(fromNick: String, text: String) {
        runCatching {
            NotificationHelper.ensureChannel(this)
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val body = text.ifBlank { "发来了一张手写纸条" }.take(40)
            val notification = android.app.Notification.Builder(this, NotificationHelper.CHANNEL_NOTE)
                .setSmallIcon(com.notedrop.demo.R.drawable.ic_notify_paper)
                .setContentTitle("$fromNick 给你留了张纸条")
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_NOTE_ID, notification)
        }.onFailure { Log.w(TAG, "通知失败：${it.message}") }
    }

    private fun saveDoodleFromBase64(raw: String): String? {
        return runCatching {
            // 服务端存的是纯 base64，兼容带 data: 前缀的写法
            val pure = raw.substringAfter("base64,", raw)
            val bytes = Base64.decode(pure, Base64.DEFAULT)
            // 走带尺寸校验的落盘路径，防止对方发来超大图把本地小部件撑爆
            NoteStore.saveDoodleBytes(this, bytes)
        }.getOrNull()
    }

    /**
     * 拉取离线期间积压的纸条。
     *
     * ## 这里曾经有个严重的丢纸条 bug
     *
     * 原来只 `arr.getJSONObject(arr.length() - 1)` 取**最后一张**，
     * 而服务端在返回这批数据时会把它们**全部标记为已送达**。
     *
     * 所以对方连着发三张、自己离线的话：
     *   服务端返回 3 张 → 客户端只处理最后 1 张 → 另外 2 张永久丢失。
     * 用户报的"对方发了三张，好久以后只收到一张"就是这个。
     *
     * 现在全部处理，一张都不丢。
     */
    private fun pullPending() {
        val token = AppConfig.token ?: return
        runCatching {
            val res = Api.pending(token)
            val arr = res.optJSONArray("notes") ?: return
            if (arr.length() == 0) return

            val ids = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val note = arr.optJSONObject(i) ?: continue
                onNoteReceived(note)
                note.optString("id").takeIf { it.isNotBlank() }?.let { ids.add(it) }
            }

            /*
             * 处理完再回执。
             *
             * 服务端要收到 ACK 才把纸条标记为已送达 —— 这样万一
             * 处理到一半进程被杀，下次连上还会重发，而不是无声丢掉。
             * 客户端按 id 去重，重复投递不会产生重复记录。
             */
            if (ids.isNotEmpty()) sendAck(ids)
        }.onFailure { Log.w(TAG, "拉取离线纸条失败：${it.message}") }
    }

    /**
     * 回执：告诉服务端这几张已经安全落地了。
     *
     * 通过 WebSocket 发；发不出去也不重试 —— 那说明连接正断着，
     * 重连后自然会再拉一次 pending，那时再回执。
     */
    private fun sendAck(ids: List<String>) {
        val ws = socket ?: return
        runCatching {
            ws.send(org.json.JSONObject().apply {
                put("type", "ack")
                put("ids", org.json.JSONArray(ids))
            }.toString())
        }.onFailure { Log.w(TAG, "回执失败：${it.message}") }
    }

    companion object {
        private const val TAG = "NoteDrop"
        private const val NOTIFICATION_NOTE_ID = 2001

        /** 收到贴纸解码后的最长边上限。贴纸是点缀，不需要原图分辨率。 */
        private const val STICKER_MAX_PX = 512

        /**
         * 兜底轮询间隔。
         *
         * 60 秒是延迟和耗电之间的折中：再短就是无谓的唤醒，
         * 再长用户又会明显感觉到"对方发了半天没反应"。
         * 真正的主力仍然是长连接（通常亚秒级送达），这个只是保险。
         */
        private const val POLL_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, NoteSocketService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "启动长连接服务失败：${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, NoteSocketService::class.java)) }
        }
    }
}
