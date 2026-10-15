package com.notedrop.demo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 纸条的本地存储。
 *
 * Demo 阶段刻意做得极简：SharedPreferences 存文字 + 文件存涂鸦。
 * 没有数据库、没有网络。
 *
 * 为什么不用 Room：这一层以后一定会被"服务端 + 本地缓存"替换掉，
 * 现在引入 Room 只是徒增迁移成本。等接服务端时连这一层一起重写。
 */
object NoteStore {

    private const val PREF = "notedrop_store"
    private const val KEY_TEXT = "note_text"
    private const val KEY_SENDER = "note_sender"
    private const val KEY_TIME = "note_time"
    private const val KEY_DOODLE = "note_doodle_path"
    private const val KEY_PAPER = "note_paper_style"
    private const val KEY_LANDSCAPE = "note_landscape"
    private const val KEY_STICKERS = "note_stickers"
    private const val DOODLE_FILE = "doodle_latest.png"

    private const val TAG = "SlipNote"

    /**
     * 小部件单次更新的位图内存上限。
     *
     * 系统给单个小部件分配的 bitmap 预算是 min(屏幕尺寸 x 4, 1.5 x 屏幕内存类大小)，
     * 超了会直接抛 IllegalArgumentException 并且**让 receiver 崩溃**。
     * 这里按 ARGB_8888（4 字节/像素）反推一个安全的像素数上限，
     * 留足余量，别贴着系统的线走。
     */
    private const val MAX_WIDGET_BITMAP_BYTES = 6 * 1024 * 1024 // 6MB
    private const val BYTES_PER_PIXEL = 4

    /** 位图最长边的硬上限，防止极端长宽比把内存吃满。 */
    private const val MAX_BITMAP_WIDTH = 640

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 读取最近一张纸条；没有任何内容时返回 null。 */
    fun read(context: Context): Note? {
        val p = prefs(context)
        if (!p.contains(KEY_TIME)) return null
        val note = Note(
            text = p.getString(KEY_TEXT, "").orEmpty(),
            doodlePath = p.getString(KEY_DOODLE, null),
            senderName = p.getString(KEY_SENDER, "我").orEmpty(),
            createdAt = p.getLong(KEY_TIME, 0L),
            paperStyle = PaperStyle.fromOrdinal(p.getInt(KEY_PAPER, 0)),
            landscape = p.getBoolean(KEY_LANDSCAPE, false),
            stickers = readStickers(p.getString(KEY_STICKERS, null))
        )
        return if (note.isEmpty) null else note
    }

    /** 保存一张纸条（覆盖上一张）。 */
    fun write(context: Context, note: Note) {
        prefs(context).edit()
            .putString(KEY_TEXT, note.text)
            .putString(KEY_SENDER, note.senderName)
            .putLong(KEY_TIME, note.createdAt)
            .putString(KEY_DOODLE, note.doodlePath)
            .putInt(KEY_PAPER, note.paperStyle.ordinal)
            .putBoolean(KEY_LANDSCAPE, note.landscape)
            .putString(KEY_STICKERS, writeStickers(note.stickers))
            .apply()
    }

    /**
     * 贴纸列表的序列化。
     *
     * 只存"哪张、贴在哪"，图片内容留在贴纸库里 ——
     * 同一张贴纸可能被多张纸条引用，把 base64 抄进来的话
     * 一份内容要存好多次，还会让 SharedPreferences 迅速膨胀。
     */
    private fun writeStickers(stickers: List<NoteSticker>): String? {
        if (stickers.isEmpty()) return null
        return runCatching {
            val arr = org.json.JSONArray()
            stickers.forEach { s ->
                arr.put(
                    org.json.JSONObject().apply {
                        put("id", s.id)
                        put("cx", s.cx.toDouble())
                        put("cy", s.cy.toDouble())
                        put("widthRatio", s.widthRatio.toDouble())
                        put("rotation", s.rotation.toDouble())
                        put("localPath", s.localPath ?: "")
                        put("fromName", s.fromName ?: "")
                    }
                )
            }
            arr.toString()
        }.getOrNull()
    }

    private fun readStickers(raw: String?): List<NoteSticker> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = o.optString("localPath").takeIf { it.isNotBlank() }
                // 文件可能已被清理，读的时候校验一次，
                // 免得小部件去合成一张已经不存在的贴纸
                if (path != null && !File(path).exists()) return@mapNotNull null
                NoteSticker(
                    id = o.optString("id"),
                    cx = o.optDouble("cx", 0.5).toFloat(),
                    cy = o.optDouble("cy", 0.5).toFloat(),
                    widthRatio = o.optDouble("widthRatio", 0.3).toFloat(),
                    rotation = o.optDouble("rotation", 0.0).toFloat(),
                    localPath = path,
                    fromName = o.optString("fromName").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 把涂鸦 Bitmap 落盘，返回文件路径。
     *
     * 之所以导出成 PNG 而不是直接把 Path 数据塞进小部件：
     * RemoteViews 只能显示系统支持的控件，画布没法直接复用，
     * 转成图片是最稳的做法（也是 MIUI / vivo 规范推荐的方式）。
     */
    fun saveDoodle(context: Context, bitmap: Bitmap): String? = runCatching {
        val file = File(context.filesDir, DOODLE_FILE)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        // PNG 自身带压缩，文件可能很小，但解码后是按像素算内存的，
        // 所以这里要记录的是像素尺寸，不能只看文件大小。
        Log.i(TAG, "doodle saved: ${file.length()} bytes, ${bitmap.width}x${bitmap.height}")
        bitmap.recycle()
        file.absolutePath
    }.getOrNull()

    /**
     * 把收到的涂鸦字节落盘。
     *
     * 收到网络传来的图片后必须**先做尺寸校验**再落盘，
     * 否则对方发来一张超大图，本地渲染小部件时又会撞上系统的位图预算。
     */
    fun saveDoodleBytes(context: Context, bytes: ByteArray): String? = runCatching {
        // 先只读尺寸，判断是否需要降采样
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = if (maxDim > MAX_BITMAP_WIDTH * 2) {
                // 按 2 的幂次粗降，细调交给后面的精确缩放
                var s = 1
                while (maxDim / (s * 2) >= MAX_BITMAP_WIDTH) s *= 2
                s
            } else {
                1
            }
        }

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        // 精确压到上限内，和读取路径保持一致的保护强度
        val longest = maxOf(decoded.width, decoded.height)
        val finalBitmap = if (longest > MAX_BITMAP_WIDTH) {
            val ratio = MAX_BITMAP_WIDTH.toFloat() / longest.toFloat()
            val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
            val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
            if (scaled !== decoded) decoded.recycle()
            scaled
        } else {
            decoded
        }

        saveDoodle(context, finalBitmap)
    }.getOrNull()

    /** 清空纸条内容（同时删除涂鸦文件）。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        runCatching { File(context.filesDir, DOODLE_FILE).delete() }
    }

    /**
     * 读涂鸦，保证返回的位图**一定不会撑爆小部件的位图预算**。
     *
     * 这里做了两层保护，缺一不可：
     * 1. inSampleSize 快速降采样：先按目标宽度取 2 的幂次采样率。
     * 2. inSampleSize 是 2 的幂次，粒度很粗，可能仍然超标；
     *    所以再按实际像素数算出精确的缩放比，用 createScaledBitmap 二次缩到预算内。
     *
     * 之前的实现只做了第 1 步，在 480dpi 的屏幕上依然生成了 3150 万字节的位图，
     * 直接触发 "RemoteViews ... exceeds maximum bitmap memory usage" 并把进程打崩。
     */
    fun decodeDoodle(path: String?, maxWidthPx: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val target = maxWidthPx.coerceIn(64, MAX_BITMAP_WIDTH)

        // 第一层：2 的幂次采样
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        // 第二层：精确缩放，把像素数压到预算内
        val maxPixels = MAX_WIDGET_BITMAP_BYTES / BYTES_PER_PIXEL
        val pixels = decoded.width.toLong() * decoded.height.toLong()
        if (pixels <= maxPixels && decoded.width <= MAX_BITMAP_WIDTH) {
            return decoded
        }

        val ratio = minOf(
            kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble()),
            MAX_BITMAP_WIDTH.toDouble() / decoded.width.toDouble()
        ).coerceAtMost(1.0)

        val outW = (decoded.width * ratio).toInt().coerceAtLeast(1)
        val outH = (decoded.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, outW, outH, true)
        if (scaled !== decoded) decoded.recycle()

        Log.i(TAG, "doodle decoded: ${decoded.width}x${decoded.height} -> ${outW}x${outH}")
        return scaled
    }

    /** 把时间戳格式化成小纸条落款的相对时间。 */
    fun relativeTime(context: Context, timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val diff = System.currentTimeMillis() - timestamp
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff < minute -> "刚刚"
            diff < hour -> "${diff / minute} 分钟前"
            diff < day -> "${diff / hour} 小时前"
            diff < 2 * day -> "昨天"
            else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(timestamp))
        }
    }

    /** 列表里用的简短相对时间：刚刚 / N分钟前 / N小时前 / 昨天 / 月日。 */
    fun formatRelative(context: Context, timestamp: Long): String =
        relativeTime(context, timestamp)
}
