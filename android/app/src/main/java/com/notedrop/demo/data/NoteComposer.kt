package com.notedrop.demo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.notedrop.demo.sticker.StickerStore

/**
 * 把「手写底图 + 贴纸」合成一张位图。
 *
 * ## 为什么需要这个
 *
 * 贴纸在数据上是**独立的一份**（[NoteSticker] 只管位置，图片内容在贴纸库里），
 * 因为只有这样才能做到"收到后把贴纸单独取下来收藏"。
 *
 * 但桌面小部件只能显示一张静态图（RemoteViews 里就一个 ImageView），
 * 没法把几个图层叠出来。所以给小部件准备的那份图必须**先合成好**。
 *
 * 换句话说：
 * - **传输/存储**：贴纸保持独立 —— 玩法需要
 * - **小部件显示**：合成成一张 —— 系统限制
 *
 * 两者不矛盾，只是同一个纸条的两种用途。
 */
object NoteComposer {

    /** 贴纸位图解码的最大边长。合成目标图本身也就几百像素，读原图没意义。 */
    private const val STICKER_DECODE_MAX = 512

    /**
     * 合成一张纸条的完整画面。
     *
     * @param base 手写底图，可为 null（只贴了贴纸、没写字）
     * @param stickers 贴纸摆放信息
     * @param maxSize 合成结果的像素上限，和调用方的位图预算保持一致
     * @return 合成后的位图；没有贴纸时**直接返回原底图**（不做无谓拷贝）
     */
    fun compose(
        context: Context,
        base: Bitmap?,
        stickers: List<NoteSticker>,
        maxSize: Int
    ): Bitmap? {
        if (stickers.isEmpty()) return base

        // 贴纸位图先解出来，一张都拿不到就说明贴纸文件已被清理，
        // 这时画空白反而更奇怪，直接退回底图
        val library = StickerStore.list(context).associateBy { it.path }
        val decoded = mutableMapOf<String, Bitmap>()
        stickers.forEach { s ->
            val path = s.localPath ?: return@forEach
            val sticker = library[path] ?: return@forEach
            val bmp = StickerStore.loadBitmap(sticker, STICKER_DECODE_MAX) ?: return@forEach
            decoded[s.id] = bmp
        }
        if (decoded.isEmpty()) return base

        /*
         * 输出尺寸。
         *
         * 有底图就跟底图同尺寸、同比例 —— 贴纸坐标是相对值，
         * 只要画布比例和底图一致，位置就自动对得上。
         * 没有底图（纯贴纸纸条）时按竖版纸条的常见比例给个画布。
         */
        val (outW, outH) = if (base != null && base.width > 0 && base.height > 0) {
            scaleToFit(base.width, base.height, maxSize)
        } else {
            val w = maxSize.coerceAtLeast(64)
            w to (w * 4 / 3)
        }

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // 1) 手写底图铺满整块画布
        if (base != null && !base.isRecycled) {
            canvas.drawBitmap(base, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), paint)
        }

        // 2) 贴纸逐张叠上去。顺序和编辑页一致：先放的在下，后放的在上。
        stickers.forEach { s ->
            val bmp = decoded[s.id] ?: return@forEach
            val w = outW * s.widthRatio
            val h = if (bmp.width > 0) w * bmp.height / bmp.width.toFloat() else w
            val cx = outW * s.cx
            val cy = outH * s.cy
            val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            canvas.save()
            canvas.rotate(s.rotation, cx, cy)
            canvas.drawBitmap(bmp, null, rect, paint)
            canvas.restore()
        }

        return out
    }

    /** 等比缩到最长边不超过 [maxSize]。 */
    private fun scaleToFit(w: Int, h: Int, maxSize: Int): Pair<Int, Int> {
        val longest = maxOf(w, h)
        if (longest <= maxSize) return w to h
        val ratio = maxSize.toFloat() / longest
        return (w * ratio).toInt().coerceAtLeast(1) to
            (h * ratio).toInt().coerceAtLeast(1)
    }
}
