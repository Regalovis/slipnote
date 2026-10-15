package com.notedrop.demo.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收件箱：把自己收到的纸条全部存档。
 *
 * ## 为什么需要它
 *
 * 原来的 [NoteStore] 只能存**一张**纸条（固定的 key + 固定的图片文件名
 * `doodle_latest.png`），新纸条一到就把旧的覆盖掉。
 * 两个人同时发过来的话，先到的那张会无声消失 —— 用户甚至不知道它来过。
 *
 * 现在把职责拆开：
 * - [InboxStore] 负责**存档**：每条纸条一个独立图片文件，索引存在 JSON 里
 * - [NoteStore]  只负责**桌面当前显示哪张**（小部件读它）
 * - [FavoriteStore] 负责**用户主动收藏**的部分，独立存储、不受清理影响
 *
 * ## 存储结构
 *
 * ```
 * files/
 *   inbox.json              索引（倒序，最新在前）
 *   inbox_<id>.png          每条纸条自己的涂鸦文件
 *   favorites.json          收藏索引
 *   fav_<id>.png            收藏的涂鸦副本
 * ```
 */
object InboxStore {

    private const val FILE_NAME = "inbox.json"

    /**
     * "上次看收件箱"的时间戳存在哪儿。
     *
     * 单独一个文件而不是塞进 inbox.json：索引文件是整块读写的，
     * 而"什么时候看的"和"有哪些纸条"是两件事，没必要互相牵连。
     * 它只是一个时间戳，用 SharedPreferences 最省事。
     */
    private const val PREF = "notedrop_inbox"
    private const val KEY_SEEN_AT = "seen_at"

    /** 超过这个条数就清理最旧的，避免无限占用存储。 */
    private const val MAX_ITEMS = 300

    data class Item(
        val id: String,
        val text: String,
        val doodlePath: String?,
        val senderName: String,
        val createdAt: Long,
        val paperStyle: PaperStyle,
        val landscape: Boolean,
        /**
         * 贴在这张纸条上的贴纸。
         *
         * 贴纸内容存在贴纸库里（StickerStore），这里只记"哪几张、贴在哪"。
         * 所以纸条存档不会因为贴纸而变大，同一张贴纸也能被多张纸条引用。
         */
        val stickers: List<NoteSticker> = emptyList()
    ) {
        fun toNote(): Note = Note(
            text = text,
            doodlePath = doodlePath,
            senderName = senderName,
            createdAt = createdAt,
            paperStyle = paperStyle,
            landscape = landscape,
            stickers = stickers
        )
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 读取全部存档，最新在前。 */
    fun list(context: Context): List<Item> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("doodlePath").takeIf { it.isNotBlank() }
            Item(
                id = o.optString("id"),
                text = o.optString("text"),
                // 图片文件可能被系统清理或用户手动删掉，这里做一次存在性校验，
                // 避免列表里出现一条点开是空白的记录
                doodlePath = path?.takeIf { File(it).exists() },
                senderName = o.optString("senderName", "TA"),
                createdAt = o.optLong("createdAt"),
                paperStyle = PaperStyle.fromOrdinal(o.optInt("paperStyle", 0)),
                landscape = o.optBoolean("landscape", false),
                stickers = parseStickerArray(o.optJSONArray("stickers"))
            )
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    /** 从存档里读贴纸列表。 */
    private fun parseStickerArray(arr: JSONArray?): List<NoteSticker> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("localPath").takeIf { it.isNotBlank() }
            NoteSticker(
                id = o.optString("id"),
                cx = o.optDouble("cx", 0.5).toFloat(),
                cy = o.optDouble("cy", 0.5).toFloat(),
                widthRatio = o.optDouble("widthRatio", 0.3).toFloat(),
                rotation = o.optDouble("rotation", 0.0).toFloat(),
                // 文件可能已被清理，读的时候校验一次
                localPath = path?.takeIf { File(it).exists() },
                fromName = o.optString("fromName").takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * 存档一条收到的纸条。
     *
     * 涂鸦会被**复制**成独立文件：调用方传进来的路径通常是小部件用的
     * `doodle_latest.png`，那个文件下次收纸条就会被覆盖，
     * 不复制的话历史记录里的图片会全部变成同一张。
     */
    fun add(context: Context, note: Note, noteId: String): Boolean = runCatching {
        val items = list(context).toMutableList()

        // 同一条消息重复投递（比如重连后补发）不应产生重复记录
        if (items.any { it.id == noteId }) return false

        val archived = note.doodlePath?.let { src ->
            runCatching {
                val from = File(src)
                if (!from.exists()) return@runCatching null
                val to = File(context.filesDir, "inbox_${noteId}.png")
                from.copyTo(to, overwrite = true)
                to.absolutePath
            }.getOrNull()
        }

        items.add(
            0,
            Item(
                id = noteId,
                text = note.text,
                doodlePath = archived,
                senderName = note.senderName,
                createdAt = note.createdAt,
                paperStyle = note.paperStyle,
                landscape = note.landscape,
                stickers = note.stickers
            )
        )

        // 超出上限就丢掉最旧的，并删除它的图片文件
        while (items.size > MAX_ITEMS) {
            val dropped = items.removeAt(items.lastIndex)
            deleteDoodle(context, dropped)
        }

        write(context, items)
        true
    }.getOrDefault(false)

    fun remove(context: Context, id: String) {
        runCatching {
            val items = list(context).toMutableList()
            val idx = items.indexOfFirst { it.id == id }
            if (idx < 0) return
            deleteDoodle(context, items.removeAt(idx))
            write(context, items)
        }
    }

    fun clear(context: Context) {
        runCatching {
            list(context).forEach { deleteDoodle(context, it) }
            file(context).delete()
        }
    }

    fun count(context: Context): Int = list(context).size

    // ---------- 未读 ----------

    /**
     * 上次打开收件箱的时间。
     *
     * 之前首页那个小圆点是"收件箱非空就亮"，那等于永远亮着，
     * 起不到任何提示作用。改成用它判断"有没有比上次看的时候更新的纸条"。
     */
    private fun seenAt(context: Context): Long =
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getLong(KEY_SEEN_AT, 0L)

    /** 用户打开收件箱时调用，把"看到哪儿了"推到最新。 */
    fun markSeen(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_SEEN_AT, System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * 有几条是上次看收件箱之后才到的。
     *
     * 用**纸条的收到时间**和 seenAt 比 —— 而不是数量差：
     * 用户看了之后再删掉两条，数量会变负，但"没有新纸条"这个结论要一样成立。
     */
    fun unreadCount(context: Context): Int {
        val seen = seenAt(context)
        if (seen <= 0L) {
            // 从来没看过：全算未读。第一次打开时提示一下是合理的。
            return list(context).size
        }
        return list(context).count { it.createdAt > seen }
    }

    private fun deleteDoodle(context: Context, item: Item) {
        item.doodlePath?.let { runCatching { File(it).delete() } }
    }

    private fun write(context: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { it ->
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("text", it.text)
                    put("doodlePath", it.doodlePath ?: "")
                    put("senderName", it.senderName)
                    put("createdAt", it.createdAt)
                    put("paperStyle", it.paperStyle.ordinal)
                    put("landscape", it.landscape)
                    put("stickers", stickerArray(it.stickers))
                }
            )
        }
        // 先写临时文件再改名，避免写一半被中断导致 JSON 损坏
        val target = file(context)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(arr.toString())
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    /** 贴纸列表序列化。只存"哪张贴纸 + 贴在哪"，图片本身在贴纸库里。 */
    private fun stickerArray(stickers: List<NoteSticker>): JSONArray {
        val arr = JSONArray()
        stickers.forEach { s ->
            arr.put(
                JSONObject().apply {
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
        return arr
    }
}
