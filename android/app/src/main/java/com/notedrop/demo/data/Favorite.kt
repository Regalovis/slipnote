package com.notedrop.demo.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 收藏的纸条。
 *
 * 收藏是**只增不改**的列表，所以用一个 JSON 文件整体读写就够了，
 * 不需要数据库。等收藏多到几百条再考虑换 SQLite。
 *
 * 涂鸦单独存成文件（fav_<id>.png），而不是塞进 JSON ——
 * base64 塞进 JSON 会让文件膨胀 33%，而且每次读写都要全量解析。
 */
object FavoriteStore {

    private const val FILE_NAME = "favorites.json"
    private const val MAX_ITEMS = 500

    data class Item(
        val id: String,
        val text: String,
        val doodlePath: String?,
        val senderName: String,
        val createdAt: Long,
        val savedAt: Long,
        val paperStyle: PaperStyle,
        /**
         * 收藏时纸条上的贴纸。
         *
         * 收藏是一个"定格"动作 —— 把当时那张纸的样子存下来。
         * 所以贴纸也要一起存，否则用户收藏时看到的和以后翻出来看到的不一样。
         */
        val stickers: List<NoteSticker> = emptyList()
    )

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun list(context: Context): List<Item> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(
                id = o.optString("id"),
                text = o.optString("text"),
                doodlePath = o.optString("doodlePath").takeIf { it.isNotBlank() },
                senderName = o.optString("senderName", "TA"),
                createdAt = o.optLong("createdAt"),
                savedAt = o.optLong("savedAt"),
                paperStyle = PaperStyle.fromOrdinal(o.optInt("paperStyle", 0)),
                stickers = parseStickers(o.optJSONArray("stickers"))
            )
        }.sortedByDescending { it.savedAt }
    }.getOrDefault(emptyList())

    private fun parseStickers(arr: JSONArray?): List<NoteSticker> {
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
                localPath = path?.takeIf { File(it).exists() },
                fromName = o.optString("fromName").takeIf { it.isNotBlank() }
            )
        }
    }

    /** 收藏一张纸条。返回是否新增（已存在则返回 false）。 */
    fun add(context: Context, note: Note): Boolean = runCatching {
        val items = list(context).toMutableList()
        // 用 内容+时间 做去重键，避免同一张纸条被收藏两次
        val key = dedupeKey(note.text, note.createdAt)
        if (items.any { dedupeKey(it.text, it.createdAt) == key }) return false

        val id = "f" + System.currentTimeMillis().toString(36)

        // 涂鸦复制一份独立文件，这样原纸条被替换/删除也不影响收藏
        val copied = note.doodlePath?.let { src ->
            runCatching {
                val from = File(src)
                if (!from.exists()) return@runCatching null
                val to = File(context.filesDir, "fav_$id.png")
                from.copyTo(to, overwrite = true)
                to.absolutePath
            }.getOrNull()
        }

        items.add(
            0,
            Item(
                id = id,
                text = note.text,
                doodlePath = copied,
                senderName = note.senderName,
                createdAt = note.createdAt,
                savedAt = System.currentTimeMillis(),
                paperStyle = note.paperStyle,
                stickers = note.stickers
            )
        )

        // 超出上限时丢掉最旧的，并清理它的图片文件
        while (items.size > MAX_ITEMS) {
            val dropped = items.removeAt(items.lastIndex)
            dropped.doodlePath?.let { runCatching { File(it).delete() } }
        }

        write(context, items)
        true
    }.getOrDefault(false)

    fun remove(context: Context, id: String) {
        runCatching {
            val items = list(context).toMutableList()
            val idx = items.indexOfFirst { it.id == id }
            if (idx < 0) return
            val removed = items.removeAt(idx)
            removed.doodlePath?.let { runCatching { File(it).delete() } }
            write(context, items)
        }
    }

    fun isFavorited(context: Context, note: Note): Boolean {
        val key = dedupeKey(note.text, note.createdAt)
        return list(context).any { dedupeKey(it.text, it.createdAt) == key }
    }

    /** 按内容判断是否已收藏。
     *
     *  收件箱和收藏夹是两套独立存储，同一条纸条在两边 id 不同，
     *  所以跨列表判断"这张收藏过没有"只能用内容做键。 */
    fun contains(context: Context, text: String, createdAt: Long): Boolean {
        val key = dedupeKey(text, createdAt)
        return list(context).any { dedupeKey(it.text, it.createdAt) == key }
    }

    /** 按内容取消收藏，用于从收件箱进来的详情页。 */
    fun removeByContent(context: Context, text: String, createdAt: Long) {
        runCatching {
            val key = dedupeKey(text, createdAt)
            val items = list(context).toMutableList()
            val idx = items.indexOfFirst { dedupeKey(it.text, it.createdAt) == key }
            if (idx < 0) return
            val removed = items.removeAt(idx)
            removed.doodlePath?.let { runCatching { File(it).delete() } }
            write(context, items)
        }
    }

    private fun dedupeKey(text: String, createdAt: Long) = "$createdAt|${text.hashCode()}"

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
                    put("savedAt", it.savedAt)
                    put("paperStyle", it.paperStyle.ordinal)
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

    fun formatTime(ts: Long): String =
        SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(ts))

    /** 贴纸列表序列化。只存位置信息，图片本身在贴纸库里。 */
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
