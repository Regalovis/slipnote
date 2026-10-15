package com.notedrop.demo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.notedrop.demo.data.NoteSticker

/**
 * 纸条的只读渲染视图。
 *
 * 职责很单一：把一张纸条画出来 —— 手写底图 + 上面的贴纸。
 *
 * ## 为什么要单独做，不复用 DoodleView
 *
 * DoodleView 是**编辑器**：带画笔、橡皮、视口缩放、撤销快照……
 * 这些在查看场景下一个都用不上，反而带来一堆状态要维护。
 * 浏览页只需要"画出来 + 能点贴纸"两件事。
 *
 * ## 贴纸的可点击性
 *
 * 贴纸在纸条上是独立元素，用户点它应该能"取下来"。
 * 所以这里做命中检测，点中时回调出去 ——
 * 详情页收到回调后再决定是弹出菜单还是直接收藏。
 */
class NoteRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * 铺排方式。
     *
     * - [Fit.WHOLE]：纸条按自身比例完整展开（详情页用）。
     *   控件高度由 onMeasure 按底图比例算出，所以整张纸就是整个控件。
     * - [Fit.CROP]：把纸条裁切铺满一块固定形状的框（列表缩略图用）。
     *   缩略图必须是正方形格子，强制拉伸会让手写变形，只能居中裁。
     */
    enum class Fit { WHOLE, CROP }

    var fit: Fit = Fit.WHOLE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /**
     * 手写底图。
     *
     * 刻意**不回收**：详情页用的是刚解码出来的副本，列表缩略图用的是
     * 缓存在 Row 里的那份，所有权都在调用方。这里负责画，不负责释放。
     */
    private var base: Bitmap? = null

    /**
     * 贴纸及其已解码的位图。
     *
     * 位置用相对值（0~1），与编辑器、传输格式保持一致 ——
     * 这样纸条在编辑页和详情页的呈现位置完全对得上。
     */
    private data class Rendered(
        val sticker: NoteSticker,
        val bitmap: Bitmap
    )

    private val items = mutableListOf<Rendered>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 点击到贴纸时的回调。 */
    var onStickerClick: ((NoteSticker) -> Unit)? = null

    /** 底图绘制区域。WHOLE 下等于整个控件，CROP 下是按比例铺满后的裁切框。 */
    private val baseRect = RectF()

    /**
     * 设置内容。
     *
     * @param base 手写底图，可为 null（只有贴纸的纸条）
     * @param stickers 贴纸列表
     * @param bitmaps 贴纸 ID → 已解码位图
     */
    fun setContent(
        base: Bitmap?,
        stickers: List<NoteSticker>,
        bitmaps: Map<String, Bitmap>
    ) {
        this.base = base
        items.clear()
        stickers.forEach { s ->
            val bmp = bitmaps[s.id] ?: return@forEach
            items.add(Rendered(s, bmp))
        }
        requestLayout()
        invalidate()
    }

    /**
     * 根据底图比例算出控件应有的高度。
     *
     * 让 View 自己报高度，比在布局里写死或靠 adjustViewBounds 更可控 ——
     * 因为贴纸可能超出底图范围，高度要按两者的并集算。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (fit == Fit.CROP) {
            // 裁切模式：形状由父布局定，自己不按内容比例改高度
            // 极端情况下高度约束是 UNSPECIFIED，会拿到 0；
            // 退回正方形，总比画成一条看不见的线强
            val h = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: w
            setMeasuredDimension(w, h.coerceAtLeast(1))
            return
        }
        val b = base
        val h = if (b != null && b.width > 0) {
            (w.toFloat() * b.height / b.width).toInt()
        } else {
            // 没有底图（纯贴纸纸条）时给一个合理默认高度
            (w * 0.75f).toInt()
        }
        setMeasuredDimension(w, h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1) 手写底图。先算绘制区域，贴纸也按同一块区域摆，两者才不会错位。
        updateBaseRect()
        base?.let {
            if (!it.isRecycled) canvas.drawBitmap(it, null, baseRect, paint)
        }

        // 2) 贴纸，按相对坐标摆放
        items.forEach { r ->
            val rect = rectOf(r)
            canvas.save()
            canvas.rotate(r.sticker.rotation, rect.centerX(), rect.centerY())
            canvas.drawBitmap(r.bitmap, null, rect, paint)
            canvas.restore()
        }
    }

    /**
     * 算出底图的绘制区域。
     *
     * WHOLE：整张纸 = 整个控件。
     * CROP：按底图比例缩放至**铺满**控件，居中裁掉溢出部分 ——
     *       这样正方形格子里看到的是一块真实的手写局部，而不是压扁的纸。
     */
    private fun updateBaseRect() {
        val b = base
        if (fit == Fit.WHOLE || b == null || b.width <= 0 || b.height <= 0) {
            baseRect.set(0f, 0f, width.toFloat(), height.toFloat())
            return
        }
        val scale = maxOf(
            width.toFloat() / b.width,
            height.toFloat() / b.height
        )
        val w = b.width * scale
        val h = b.height * scale
        val cx = width / 2f
        val cy = height / 2f
        baseRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /** 贴纸在控件里的实际矩形。坐标相对 [baseRect]，和编辑页的相对坐标语义一致。 */
    private fun rectOf(r: Rendered): RectF {
        val w = baseRect.width() * r.sticker.widthRatio
        val h = if (r.bitmap.width > 0) {
            w * r.bitmap.height / r.bitmap.width.toFloat()
        } else {
            w
        }
        val cx = baseRect.left + baseRect.width() * r.sticker.cx
        val cy = baseRect.top + baseRect.height() * r.sticker.cy
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 没有点击回调（例如列表缩略图）时完全不拦截触摸，
        // 否则贴在缩略图上的手指会被这里吃掉，整行点不开
        val callback = onStickerClick ?: return false

        if (event.actionMasked != MotionEvent.ACTION_UP) {
            // 按下时也返回 true，才能收到后续的 UP
            return event.actionMasked == MotionEvent.ACTION_DOWN
        }

        // 从上往下找 —— 后贴的盖在前面，点击也该优先命中它
        for (i in items.indices.reversed()) {
            if (rectOf(items[i]).contains(event.x, event.y)) {
                callback(items[i].sticker)
                performClick()
                return true
            }
        }
        performClick()
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBaseRect()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 贴纸位图是借用贴纸库的副本，这里只清引用不回收 ——
        // 回收可能导致别处正在用的位图失效
        items.clear()
    }
}
