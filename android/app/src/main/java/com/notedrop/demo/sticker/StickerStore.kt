package com.notedrop.demo.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 贴纸库。
 *
 * 和纸条分开存储，因为贴纸的生命周期更长：
 * 纸条删了，贴纸还在贴纸库里，可以再贴到别的纸条上。
 *
 * 存储结构：
 *   files/stickers.json        索引
 *   files/sticker_<id>.png     每张贴纸一个文件
 */
object StickerStore {

    private const val FILE_NAME = "stickers.json"
    private const val MAX_ITEMS = 200

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun list(context: Context): List<Sticker> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("path")
            // 文件可能被系统清理掉，读的时候校验一次，
            // 避免贴纸库里出现点开是空白的条目
            if (path.isBlank() || !File(path).exists()) return@mapNotNull null
            Sticker(
                id = o.optString("id"),
                path = path,
                origin = runCatching {
                    Sticker.Origin.valueOf(o.optString("origin", "OWN"))
                }.getOrDefault(Sticker.Origin.OWN),
                fromName = o.optString("fromName").takeIf { it.isNotBlank() },
                createdAt = o.optLong("createdAt")
            )
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    /** 把一张位图存成贴纸。origin 区分"自己抠的"和"从别人纸条取的"。 */
    fun add(
        context: Context,
        bitmap: Bitmap,
        origin: Sticker.Origin,
        fromName: String? = null
    ): Sticker? = runCatching {
        val id = "s" + UUID.randomUUID().toString().take(8)
        val target = File(context.filesDir, "sticker_$id.png")
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val stickers = list(context).toMutableList()
        val sticker = Sticker(
            id = id,
            path = target.absolutePath,
            origin = origin,
            fromName = fromName,
            createdAt = System.currentTimeMillis()
        )
        stickers.add(0, sticker)

        while (stickers.size > MAX_ITEMS) {
            val dropped = stickers.removeAt(stickers.lastIndex)
            runCatching { File(dropped.path).delete() }
        }

        write(context, stickers)
        sticker
    }.getOrNull()

    fun get(context: Context, id: String): Sticker? =
        list(context).firstOrNull { it.id == id }

    fun remove(context: Context, id: String) {
        runCatching {
            val stickers = list(context).toMutableList()
            val idx = stickers.indexOfFirst { it.id == id }
            if (idx < 0) return
            val removed = stickers.removeAt(idx)
            runCatching { File(removed.path).delete() }
            write(context, stickers)
        }
    }

    /**
     * 按最大边长读贴纸位图。
     *
     * 贴纸最终要贴到纸条上，纸条本身也就一两千像素，
     * 读原图没意义还占内存，所以按需降采样。
     */
    fun loadBitmap(sticker: Sticker, maxSize: Int = 512): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sticker.path, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= maxSize) sample *= 2

        BitmapFactory.decodeFile(
            sticker.path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()

    private fun write(context: Context, stickers: List<Sticker>) {
        val arr = JSONArray()
        stickers.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("path", s.path)
                    put("origin", s.origin.name)
                    put("fromName", s.fromName ?: "")
                    put("createdAt", s.createdAt)
                }
            )
        }
        val target = file(context)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(arr.toString())
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }
}
