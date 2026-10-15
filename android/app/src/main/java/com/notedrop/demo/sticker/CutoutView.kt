package com.notedrop.demo.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * 抠图编辑画布。
 *
 * 显示的是一张待抠的图 + 一层**棋盘格**表示透明区。
 * 用户点哪儿，就去掉哪儿颜色相近的连通区域。
 *
 * ## 为什么要棋盘格
 *
 * 透明背景如果直接铺白底，用户根本分不清"这里抠掉了"和"这里本来就是白的"。
 * 棋盘格是图像编辑器的通用约定，一眼就知道那块是空的。
 *
 * ## 坐标换算
 *
 * 图片按 fitCenter 摆在控件里，两侧可能有留白。
 * 用户点的是控件坐标，必须换算成**图片坐标**才能喂给魔棒 ——
 * 这个换算错了，表现为"点哪儿抠掉的都是旁边一块"。
 */
class CutoutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * 点击回调。
     *
     * 参数是**图片内的像素坐标**，不是控件坐标 —— 换算在这里做完了，
     * 外面拿到就能直接喂给 [MagicWand]。
     */
    var onPick: ((x: Int, y: Int) -> Unit)? = null

    private var bitmap: Bitmap? = null

    /** 图片在控件里的绘制区域（fitCenter 之后）。 */
    private val dst = RectF()

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 棋盘格的两种灰。 */
    private val checkerLight = 0xFFFFFFFF.toInt()
    private val checkerDark = 0xFFE8E8EC.toInt()
    private val checkerPaint = Paint()

    /** 棋盘格边长。8dp 是各家图像编辑器的常见粒度，够看又不闹。 */
    private val checkerSize = 8f * resources.displayMetrics.density

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        requestLayout()
        invalidate()
    }

    fun current(): Bitmap? = bitmap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)

        /*
         * 高度怎么定，分两种情况。
         *
         * 父容器给了确定高度（EXACTLY，比如"填满剩余的 weight=1 区域"）时
         * **必须听父的**。曾经这里一律按图片比例自己算高度，
         * 结果是控件比容器高，图片被裁掉一截 —— 而且因为图片是按
         * fitCenter 居中的，看起来像"图跑到上面去了"，很难往高度上想。
         *
         * 父容器没给确定高度时才按图片比例报一个，供 wrap_content 用。
         */
        val h = if (hMode == MeasureSpec.EXACTLY) {
            hSize
        } else {
            val b = bitmap
            val wanted = if (b != null && b.width > 0) {
                (w.toFloat() * b.height / b.width).toInt()
            } else {
                (w * 0.75f).toInt()
            }
            if (hMode == MeasureSpec.AT_MOST) min(wanted, hSize) else wanted
        }

        setMeasuredDimension(w, h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        if (b.isRecycled || b.width <= 0) return

        updateDst()

        // 1) 棋盘格打底，只在图片区域内画
        canvas.save()
        canvas.clipRect(dst)
        drawChecker(canvas)
        canvas.restore()

        // 2) 图片本身叠上去。透明处会露出下面的棋盘格
        canvas.drawBitmap(b, null, dst, paint)

        // 3) 细边框，让图片边界清楚
        canvas.drawRect(dst, Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * resources.displayMetrics.density
            color = 0x22000000
        })
    }

    /** 按 fitCenter 算出图片的绘制矩形。 */
    private fun updateDst() {
        val b = bitmap ?: return
        val scale = min(
            width.toFloat() / b.width,
            height.toFloat() / b.height
        )
        val w = b.width * scale
        val h = b.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        dst.set(left, top, left + w, top + h)
    }

    private fun drawChecker(canvas: Canvas) {
        val x0 = dst.left
        val y0 = dst.top
        val cols = ((dst.width() / checkerSize).toInt()) + 1
        val rows = ((dst.height() / checkerSize).toInt()) + 1

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                checkerPaint.color = if ((r + c) % 2 == 0) checkerLight else checkerDark
                val left = x0 + c * checkerSize
                val top = y0 + r * checkerSize
                canvas.drawRect(
                    left,
                    top,
                    left + checkerSize,
                    top + checkerSize,
                    checkerPaint
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) {
            return event.actionMasked == MotionEvent.ACTION_DOWN
        }

        val b = bitmap ?: return false
        if (!dst.contains(event.x, event.y)) return false

        // 控件坐标 → 图片像素坐标
        val scaleX = b.width / dst.width()
        val scaleY = b.height / dst.height()
        val px = ((event.x - dst.left) * scaleX).toInt().coerceIn(0, b.width - 1)
        val py = ((event.y - dst.top) * scaleY).toInt().coerceIn(0, b.height - 1)

        performClick()
        onPick?.invoke(px, py)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
