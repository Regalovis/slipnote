package com.notedrop.demo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.notedrop.demo.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 手写画板。
 *
 * ## 功能
 *
 * 1. **笔锋**：笔画宽度随书写速度变化 —— 慢则粗、快则细，
 *    模仿真实笔触。实现方式是每个采样点记录一个宽度，
 *    逐段用不同线宽绘制（圆头连接，接缝看不出来）。
 *
 * 2. **自由缩放**：画布总尺寸不变，但可以双指放大后做精细书写。
 *    缩放只影响视口，不影响笔迹坐标，导出时永远是完整画布。
 *
 * 3. **描画层**：可以放一张参考图垫在下面（可调透明度/位置/大小），
 *    锁定后照着描。这一层**不会被导出**，纯粹是书写辅助。
 *
 * 4. **不裁切导出**：按画布原始比例整体导出，
 *    不做"只留笔迹范围"的裁剪 —— 用户画在哪里、留白多少，
 *    对方看到的就是什么样。
 *
 * ## 性能
 *
 * 已完成的笔迹烘焙进 cache 位图，每帧只做「贴 cache + 画当前笔画」，
 * 与笔画数量无关。缩放只是改变贴图时的矩阵，不重建 cache。
 */
class DoodleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Tool { PEN, ERASER }

    /** 交互模式。描画层未锁定时用它来区分"拖图"和"写字"。 */
    enum class Mode { DRAW, MOVE_TRACING }

    /**
     * 一条笔画。
     *
     * 点坐标与宽度分开存：xs/ys 是采样位置，widths 是每个位置对应的笔宽。
     * 分开存比存 Point 对象数组省内存，也方便归一化导出。
     */
    private class Stroke(val color: Int, val tool: Tool) {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val widths = ArrayList<Float>()

        fun add(x: Float, y: Float, w: Float) {
            xs.add(x); ys.add(y); widths.add(w)
        }

        val size: Int get() = xs.size
    }

    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastWidth = 0f
    private var lastTime = 0L

    /** 已定型笔迹的合成位图（画布坐标系）。 */
    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null

    // ==================== 固定逻辑画布 ====================

    /*
     * ## 为什么要有"画布尺寸"这个概念，而不是直接用控件尺寸
     *
     * 原来所有坐标都是**控件像素**：笔迹、贴纸、导出，全按 View 的 width/height 算。
     * 这带来三个连锁问题，用户都碰到了：
     *
     * 1. **画布被裁**。工具抽屉一展开，画布区变矮 → View 变矮 →
     *    导出时按变矮后的高度出图，画在下面的内容直接没了。
     * 2. **贴纸位置漂移**。贴纸位置是相对值（0~1），乘的是**当前的** height。
     *    抽屉一动、高度一变，同一张 0.8 就被算到不同的地方 ——
     *    用户看到的就是"贴纸被顶起来了"。
     * 3. 旋转屏幕后同样错位。
     *
     * 根子是：**画布不该跟着界面布局变**。界面只是一个观察窗口，
     * 窗口可大可小，但纸始终是那张纸。
     *
     * 所以现在固定一块逻辑画布，所有笔迹/贴纸坐标都存它上面；
     * 控件尺寸只影响"怎么把这块画布显示出来"（[baseScale] 等三个量）。
     */

    /** 逻辑画布宽度（像素）。 */
    private var canvasW = 0

    /** 逻辑画布高度（像素）。 */
    private var canvasH = 0

    /** 画布 → 控件的等比缩放。 */
    private var baseScale = 1f

    /** 画布在控件里的偏移（居中留白）。 */
    private var baseDx = 0f
    private var baseDy = 0f

    /** 画布是否已经定好尺寸。第一非零布局时确定，之后不再变。 */
    private val canvasReady: Boolean get() = canvasW > 0 && canvasH > 0

    /** 撤回快照（橡皮会改像素，必须靠快照回滚）。 */
    private val snapshots = ArrayDeque<Bitmap>()

    // ---------- 可调参数 ----------

    private var tool = Tool.PEN
    private var mode = Mode.DRAW
    private var brushColor = Color.BLACK
    private var brushWidthRatio = 0.012f

    /** 笔锋强度：0 = 等宽，1 = 速度对宽度影响最大。 */
    private var taperStrength = 0.55f

    private val widthSteps = floatArrayOf(0.004f, 0.008f, 0.014f, 0.024f, 0.040f)
    private var widthIndex = 2

    // ---------- 视口（缩放/平移） ----------

    private var viewScale = 1f
    private var viewTx = 0f
    private var viewTy = 0f

    private val minScale = 1f
    private val maxScale = 6f

    // ---------- 描画层 ----------

    private var tracing: Bitmap? = null
    private var tracingLocked = false
    private var tracingAlpha = 140
    private val tracingMatrix = Matrix()
    private val tracingRect = RectF()

    /** 描画层源文件路径，供旋转屏幕后恢复。 */
    var tracingSourcePath: String? = null
        private set

    private val cachePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 视口变化回调（缩放/平移后触发），用于刷新界面上的倍数提示。 */
    var onViewportChanged: (() -> Unit)? = null

    // ==================== 贴纸层 ====================

    /**
     * 贴在纸上的贴纸。
     *
     * 位置/大小用**相对值**（0~1），不是像素：
     * 编辑时纸是一个尺寸，发到对方手机上显示是另一个尺寸，
     * 相对值才能保证位置在任何尺寸下都对得上。
     */
    private data class Placed(
        val stickerId: String,
        val bitmap: Bitmap,
        /** 中心点相对坐标 */
        var cx: Float,
        var cy: Float,
        /** 宽度占画布宽度的比例 */
        var wRatio: Float,
        /** 旋转角度（度） */
        var rotation: Float,
        /**
         * 位置锁定。
         *
         * 锁定后这张贴纸**完全不拦截触摸**，手指可以照常从它上面画过去。
         * 这是用户明确提的需求："再进行绘制会点到这个贴图，
         * 增加一下锁定位置的小按钮" —— 贴完固定住，然后安心在上面写字。
         */
        var locked: Boolean = false
    )

    private val placed = mutableListOf<Placed>()

    /** 贴纸模式：开启后触摸操作的是贴纸而不是画笔。 */
    private var stickerMode = false

    /** 当前选中的贴纸下标，-1 表示没选中。 */
    private var selectedSticker = -1

    /** 选中贴纸时显示的操作句柄（删除 / 缩放手柄）回调。 */
    var onStickerSelected: ((hasSelection: Boolean) -> Unit)? = null

    /**
     * 手指刚落到画布上时的回调。
     *
     * 编辑页用它收起工具抽屉 —— 用户开始写了，工具就该让位。
     * 做成回调而不是让 DoodleView 直接操作抽屉：
     * 这个控件不该知道外面长什么样。
     */
    var onTouchStart: (() -> Unit)? = null

    fun setStickerMode(on: Boolean) {
        stickerMode = on
        if (!on) {
            selectedSticker = -1
            onStickerSelected?.invoke(false)
        }
        invalidate()
    }

    fun isStickerMode(): Boolean = stickerMode

    /** 往画布中央放一张贴纸。 */
    fun addSticker(id: String, bitmap: Bitmap) {
        placed.add(
            Placed(
                stickerId = id,
                bitmap = bitmap,
                cx = 0.5f,
                cy = 0.5f,
                // 初始宽度占画布 32% —— 够显眼，又不会一上来就盖住半个画布
                wRatio = 0.32f,
                rotation = 0f
            )
        )
        // 放进来就选中，用户可以立刻拖到想要的位置
        selectedSticker = placed.lastIndex
        onStickerSelected?.invoke(true)
        invalidate()
    }

    /** 删除当前选中的贴纸。 */
    fun removeSelectedSticker() {
        if (selectedSticker !in placed.indices) return
        placed.removeAt(selectedSticker).bitmap.recycle()
        selectedSticker = -1
        onStickerSelected?.invoke(false)
        invalidate()
    }

    /**
     * 锁定 / 解锁当前选中的贴纸。
     *
     * 锁定后这张贴纸不再拦截触摸，用户可以从它上面直接画过去。
     * 这是"贴完固定住，然后安心在上面写字"那个流程的开关。
     *
     * @return 锁定后的状态；没有选中贴纸时返回 null
     */
    fun toggleSelectedStickerLock(): Boolean? {
        if (selectedSticker !in placed.indices) return null
        val p = placed[selectedSticker]
        p.locked = !p.locked

        /*
         * 锁定后主动取消选中。
         *
         * 否则会出现这种别扭情况：贴纸已经锁了、触摸穿透过去开始画画，
         * 但虚线框还挂在上面、删除按钮还亮着，看起来像"还能操作"。
         * 锁定的语义就是"别管我了"，那就把选中态一并交出去。
         */
        if (p.locked) {
            selectedSticker = -1
            onStickerSelected?.invoke(false)
        }
        invalidate()
        return p.locked
    }

    /** 当前选中的贴纸是否已锁定。没选中时返回 null。 */
    fun isSelectedStickerLocked(): Boolean? =
        if (selectedSticker in placed.indices) placed[selectedSticker].locked else null

    /** 有几张贴纸是锁定的（用于调试/提示）。 */
    fun lockedStickerCount(): Int = placed.count { it.locked }

    /** 取消选中（点空白处时调用）。 */
    fun clearStickerSelection() {
        if (selectedSticker < 0) return
        selectedSticker = -1
        onStickerSelected?.invoke(false)
        invalidate()
    }

    val stickerCount: Int get() = placed.size

    /**
     * 导出画布上的贴纸位置（相对值），用于序列化发送。
     */
    fun exportPlacedStickers(): List<com.notedrop.demo.sticker.PlacedSticker> =
        placed.map {
            com.notedrop.demo.sticker.PlacedSticker(
                stickerId = it.stickerId,
                cx = it.cx,
                cy = it.cy,
                widthRatio = it.wRatio,
                rotation = it.rotation
            )
        }

    /**
     * 画布上当前贴了几张贴纸。
     *
     * 发送前校验用：只有贴纸、没写字也没画线的纸条同样是有内容的，
     * 不能因为"没有笔画"就判成空纸条拦下来。
     */
    fun placedStickerCount(): Int = placed.size

    /**
     * 恢复贴纸到画布（重新编辑已有纸条时用）。
     * 位图由调用方负责加载，这里只管摆放。
     */
    fun restorePlacedStickers(
        items: List<com.notedrop.demo.sticker.PlacedSticker>,
        bitmaps: Map<String, Bitmap>
    ) {
        placed.forEach { it.bitmap.recycle() }
        placed.clear()
        items.forEach { p ->
            val bmp = bitmaps[p.stickerId] ?: return@forEach
            placed.add(
                Placed(
                    stickerId = p.stickerId,
                    bitmap = bmp,
                    cx = p.cx,
                    cy = p.cy,
                    wRatio = p.widthRatio,
                    rotation = p.rotation
                )
            )
        }
        selectedSticker = -1
        onStickerSelected?.invoke(false)
        invalidate()
    }

    /** 贴纸在画布坐标系里的外接矩形（考虑旋转）。 */
    private fun stickerRect(p: Placed): RectF {
        val w = canvasW * p.wRatio
        val h = w * p.bitmap.height / p.bitmap.width.toFloat()
        val cx = canvasW * p.cx
        val cy = canvasH * p.cy
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /** 点击命中的贴纸下标（从上层往下找），没命中返回 -1。 */
    private fun hitTest(x: Float, y: Float): Int {
        for (i in placed.indices.reversed()) {
            // 锁定的贴纸不参与命中 —— 手指从它上面划过时应该是画笔的事
            if (placed[i].locked) continue
            val r = stickerRect(placed[i])
            if (r.contains(x, y)) return i
        }
        return -1
    }

    // ==================== 公共 API ====================

    fun setTool(t: Tool) { tool = t }
    fun getTool(): Tool = tool

    fun setMode(m: Mode) { mode = m }
    fun getMode(): Mode = mode

    fun setColor(color: Int) { brushColor = color }

    /** 笔宽档位，返回当前档位下标。 */
    fun stepWidth(delta: Int): Int {
        widthIndex = (widthIndex + delta).coerceIn(0, widthSteps.lastIndex)
        brushWidthRatio = widthSteps[widthIndex]
        return widthIndex
    }

    fun getWidthIndex(): Int = widthIndex
    fun getWidthCount(): Int = widthSteps.size

    /** 笔锋强度 0~1。 */
    fun setTaperStrength(v: Float) {
        taperStrength = v.coerceIn(0f, 1f)
    }

    fun getTaperStrength(): Float = taperStrength

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun undo() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.lastIndex)
        val snap = snapshots.removeLastOrNull()
        if (snap != null) restoreFrom(snap) else rebuildCache()
        invalidate()
    }

    fun clearAll() {
        strokes.clear()
        current = null
        snapshots.forEach { it.recycle() }
        snapshots.clear()
        rebuildCache()
        invalidate()
    }

    // ---------- 视口 ----------

    fun resetViewport() {
        viewScale = 1f
        viewTx = 0f
        viewTy = 0f
        invalidate()
    }

    fun getViewScale(): Float = viewScale

    /** 是否处于放大状态（用于提示用户"正在局部放大"）。 */
    fun isZoomed(): Boolean = viewScale > 1.01f

    // ---------- 描画层 ----------

    /**
     * 设置描画参考图。
     *
     * 初始按"等比放进画布"摆放并居中，用户可再拖动缩放。
     * 设置后自动切到 MOVE_TRACING 模式，方便立刻调整位置。
     */
    fun setTracingBitmap(bmp: Bitmap, sourcePath: String? = null) {
        tracing?.recycle()
        tracing = bmp
        tracingSourcePath = sourcePath
        tracingLocked = false
        mode = Mode.MOVE_TRACING

        // 参考图按**画布**尺寸摆放（tracingMatrix 是画布坐标系里的变换），
        // 这样它和笔迹一样不受控件大小变化影响
        val vw = canvasW.coerceAtLeast(1).toFloat()
        val vh = canvasH.coerceAtLeast(1).toFloat()
        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        val fit = min(vw / iw, vh / ih) * 0.85f

        tracingMatrix.reset()
        tracingMatrix.postScale(fit, fit)
        tracingMatrix.postTranslate((vw - iw * fit) / 2f, (vh - ih * fit) / 2f)
        syncTracingRect()
        invalidate()
    }

    fun hasTracing(): Boolean = tracing != null

    fun isTracingLocked(): Boolean = tracingLocked

    /** 锁定描画层：从此只作为背景，不再响应拖动。 */
    fun lockTracing() {
        tracingLocked = true
        mode = Mode.DRAW
        invalidate()
    }

    fun unlockTracing() {
        if (tracing == null) return
        tracingLocked = false
        mode = Mode.MOVE_TRACING
        invalidate()
    }

    /** 调整描画层不透明度，0~255。 */
    fun setTracingAlpha(a: Int) {
        tracingAlpha = a.coerceIn(20, 255)
        invalidate()
    }

    fun getTracingAlpha(): Int = tracingAlpha

    fun removeTracing() {
        tracing?.recycle()
        tracing = null
        tracingSourcePath = null
        tracingLocked = false
        mode = Mode.DRAW
        invalidate()
    }

    private fun syncTracingRect() {
        val t = tracing ?: return
        tracingRect.set(0f, 0f, t.width.toFloat(), t.height.toFloat())
        tracingMatrix.mapRect(tracingRect)
    }

    // ==================== 尺寸与缓存 ====================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        /*
         * 第一次拿到非零尺寸时，把逻辑画布定下来。
         *
         * 之后就**不再改** —— 抽屉展开、键盘弹起、旋转导致的控件变化，
         * 都只改变"怎么显示"，不改变画布本身。这是修掉"画布被裁"
         * 和"贴纸被顶起来"的关键。
         */
        if (!canvasReady) {
            canvasW = w
            canvasH = h
        }
        updateBaseTransform()

        /*
         * cache 的尺寸跟着**画布**走，不跟控件走。
         *
         * ensureCache 用 canvasReady 判断：控件尺寸变了但画布没变时，
         * 这里什么都不做，已画好的内容原样保留。
         */
        ensureCache(canvasW, canvasH)
        if (oldw <= 0 || oldh <= 0) rebuildCache()
    }

    /**
     * 算出"画布 → 控件"的等比缩放和居中偏移。
     *
     * 取两个方向里较小的那个比例（fitCenter），保证整张纸都看得见。
     * 用较短边而不是拉伸铺满：拉扁的纸会让手写变形。
     */
    private fun updateBaseTransform() {
        if (!canvasReady || width <= 0 || height <= 0) return
        baseScale = min(
            width.toFloat() / canvasW,
            height.toFloat() / canvasH
        )
        baseDx = (width - canvasW * baseScale) / 2f
        baseDy = (height - canvasH * baseScale) / 2f
    }

    private fun ensureCache(w: Int, h: Int) {
        val existing = cache
        if (existing != null && existing.width == w && existing.height == h && !existing.isRecycled) return
        /*
         * 换缓存位图时必须**先把旧内容搬过去**。
         *
         * 画布尺寸是不会变的（上面已经定死），所以这条路径正常走不到；
         * 但万一走到了（比如从初始化异常恢复），
         * 直接丢掉旧 cache 等于把用户画的东西全抹了。
         */
        val old = existing?.takeIf { !it.isRecycled }
        existing?.recycle()
        cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cacheCanvas = Canvas(cache!!)
        if (old != null) {
            cacheCanvas!!.drawBitmap(old, 0f, 0f, null)
            old.recycle()
        }
    }

    private fun rebuildCache() {
        val c = cacheCanvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        strokes.forEach { drawStroke(c, it) }
    }

    private fun takeSnapshot() {
        val src = cache ?: return
        if (src.isRecycled) return
        val copy = src.copy(Bitmap.Config.ARGB_8888, false) ?: return
        snapshots.addLast(copy)
        while (snapshots.size > MAX_SNAPSHOTS) snapshots.removeFirstOrNull()?.recycle()
    }

    private fun restoreFrom(snap: Bitmap) {
        val c = cacheCanvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (!snap.isRecycled) c.drawBitmap(snap, 0f, 0f, null)
        snap.recycle()
    }

    // ==================== 绘制 ====================

    private fun baseWidthPx(): Float {
        // 笔宽按**画布**尺寸算，这样换屏幕、开合抽屉都不会让笔迹粗细变化
        val base = max(canvasW, canvasH).toFloat()
        val w = if (base <= 0f) 12f else base * brushWidthRatio
        return w.coerceIn(2f, 90f)
    }

    private fun newPaint(color: Int, width: Float, tool: Tool): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.isDither = true
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = width
        if (tool == Tool.ERASER) {
            p.color = Color.TRANSPARENT
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else {
            p.color = color
        }
        return p
    }

    /**
     * 把一条笔画画到目标画布。
     *
     * 逐段用各自的线宽绘制，形成笔锋。线段之间靠圆头连接，
     * 相邻宽度差不大时完全看不出接缝。
     */
    private fun drawStroke(canvas: Canvas, s: Stroke) {
        val n = s.size
        if (n == 0) return

        if (n == 1) {
            // 单点：画个圆点
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            if (s.tool == Tool.ERASER) {
                p.color = Color.TRANSPARENT
                p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            } else {
                p.color = s.color
            }
            canvas.drawCircle(s.xs[0], s.ys[0], s.widths[0] / 2f, p)
            return
        }

        val paint = newPaint(s.color, s.widths[0], s.tool)
        for (i in 0 until n - 1) {
            val w = (s.widths[i] + s.widths[i + 1]) / 2f
            paint.strokeWidth = w
            canvas.drawLine(s.xs[i], s.ys[i], s.xs[i + 1], s.ys[i + 1], paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        /*
         * 三层变换，顺序从外到内：
         *   1. 视口（用户双指缩放/平移）
         *   2. 画布居中 + 等比放进控件
         *   3. 之后所有绘制都直接在**画布坐标**里，不用再管控件多大
         */
        canvas.translate(viewTx, viewTy)
        canvas.scale(viewScale, viewScale)
        canvas.translate(baseDx, baseDy)
        canvas.scale(baseScale, baseScale)

        // 1) 描画层垫在最下面
        tracing?.let { t ->
            if (!t.isRecycled) {
                val p = Paint(Paint.FILTER_BITMAP_FLAG)
                p.alpha = tracingAlpha
                canvas.drawBitmap(t, tracingMatrix, p)
            }
        }

        // 2) 已定型笔迹
        cache?.let { if (!it.isRecycled) canvas.drawBitmap(it, 0f, 0f, cachePaint) }

        // 3) 当前笔画
        current?.let { drawStroke(canvas, it) }

        // 4) 贴纸层 —— 画在最上面，盖住笔迹
        drawStickers(canvas)

        canvas.restore()
    }

    /**
     * 绘制所有贴纸。
     *
     * 贴纸画在笔迹之上：用户贴上去的东西应该盖住下面的字，
     * 这是"贴"这个动作的直觉。
     */
    private fun drawStickers(canvas: Canvas) {
        placed.forEachIndexed { index, p ->
            val r = stickerRect(p)
            canvas.save()
            // 旋转围绕贴纸中心，而不是画布原点
            canvas.rotate(p.rotation, r.centerX(), r.centerY())

            /*
             * 锁定的贴纸画得**略淡**。
             *
             * 它已经不可操作了，视觉上就该和"还在手上"的贴纸区分开，
             * 否则用户会反复去点它、纳闷怎么没反应。
             * 用 alpha 而不是灰掉：灰掉会让用户以为它被禁用了/坏了。
             */
            if (p.locked) {
                val p2 = Paint(Paint.FILTER_BITMAP_FLAG)
                p2.alpha = 160
                canvas.drawBitmap(p.bitmap, null, r, p2)
            } else {
                canvas.drawBitmap(p.bitmap, null, r, cachePaint)
            }

            // 选中的贴纸加一圈虚线框，让用户知道正在操作哪一个
            if (index == selectedSticker) {
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * resources.displayMetrics.density
                    color = ContextCompat.getColor(context, R.color.brand)
                    pathEffect = android.graphics.DashPathEffect(
                        floatArrayOf(8f, 6f), 0f
                    )
                }
                val grow = 6f * resources.displayMetrics.density / viewScale
                val box = RectF(r).apply { inset(-grow, -grow) }
                canvas.drawRoundRect(box, grow, grow, border)
            }
            canvas.restore()
        }
    }

    // ==================== 触摸 ====================

    /**
     * 控件坐标 → 画布坐标。
     *
     * 两步换算，顺序不能反：
     *   1. 先反掉视口（用户双指缩放/平移）
     *   2. 再反掉"画布居中放进控件"的基准变换
     */
    private fun toCanvas(x: Float, y: Float): PointF {
        val vx = (x - viewTx) / viewScale
        val vy = (y - viewTy) / viewScale
        return PointF(
            (vx - baseDx) / baseScale,
            (vy - baseDy) / baseScale
        )
    }

    /** 画布坐标 → 控件坐标。贴纸命中测试用（它在屏幕坐标系下按手指算）。 */
    private fun toView(x: Float, y: Float): PointF {
        val vx = x * baseScale + baseDx
        val vy = y * baseScale + baseDy
        return PointF(
            vx * viewScale + viewTx,
            vy * viewScale + viewTy
        )
    }

    // 视口手势
    private var gestureActive = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f

    // 描画层拖动
    private var draggingTracing = false
    private var dragLastX = 0f
    private var dragLastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 手指落下的瞬间先通知外部：编辑页要借此把工具抽屉收起来，
        // 把画布让给正在写的人。放在最前面，任何模式都会触发。
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onTouchStart?.invoke()
        }

        /*
         * 贴纸模式下，触摸优先作用于贴纸。
         *
         * 只有"点在贴纸上"才拦截；点在空白处则取消选中并放行给画笔 ——
         * 这样用户不用手动切模式也能一边贴一边画。
         */
        if (stickerMode && placed.isNotEmpty()) {
            if (handleStickerTouch(event)) return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (mode == Mode.MOVE_TRACING && tracing != null && !tracingLocked) {
                    draggingTracing = true
                    dragLastX = event.x
                    dragLastY = event.y
                } else {
                    beginStroke(event)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下 → 转为视口手势，放弃正在画的这一笔
                cancelCurrentStroke()
                gestureActive = true
                lastFocusX = focusX(event)
                lastFocusY = focusY(event)
                lastSpan = span(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureActive && event.pointerCount >= 2) {
                    handleViewportGesture(event)
                } else if (draggingTracing) {
                    val dx = event.x - dragLastX
                    val dy = event.y - dragLastY
                    tracingMatrix.postTranslate(dx, dy)
                    dragLastX = event.x
                    dragLastY = event.y
                    syncTracingRect()
                    invalidate()
                } else {
                    extendStroke(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起一根手指后重新基准，避免跳变
                lastFocusX = focusX(event)
                lastFocusY = focusY(event)
                lastSpan = span(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!gestureActive && !draggingTracing) endStroke()
                gestureActive = false
                draggingTracing = false
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    private fun focusX(e: MotionEvent): Float {
        if (e.pointerCount < 2) return e.x
        return (e.getX(0) + e.getX(1)) / 2f
    }

    private fun focusY(e: MotionEvent): Float {
        if (e.pointerCount < 2) return e.y
        return (e.getY(0) + e.getY(1)) / 2f
    }

    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    /**
     * 双指缩放 + 平移。
     *
     * 缩放围绕两指中点进行，这样手指下的内容不会跑掉。
     */
    private fun handleViewportGesture(e: MotionEvent) {
        val fx = focusX(e)
        val fy = focusY(e)
        val sp = span(e)

        if (lastSpan > 0f && sp > 0f) {
            val factor = (sp / lastSpan).coerceIn(0.5f, 2f)
            val target = (viewScale * factor).coerceIn(minScale, maxScale)
            val actual = target / viewScale

            // 围绕焦点缩放：先平移到焦点，缩放，再平移回去
            viewTx = fx - (fx - viewTx) * actual
            viewTy = fy - (fy - viewTy) * actual
            viewScale = target
        }

        viewTx += fx - lastFocusX
        viewTy += fy - lastFocusY
        clampViewport()

        lastFocusX = fx
        lastFocusY = fy
        lastSpan = sp
        onViewportChanged?.invoke()
        invalidate()
    }

    /** 限制平移范围，避免把画布拖出屏幕外找不回来。 */
    private fun clampViewport() {
        if (!canvasReady) return
        /*
         * 能拖的范围要按**画布显示出来的大小**算。
         *
         * 画布是用 fitCenter 放进控件的，可能比控件矮（上下有留白）。
         * 那点留白不该被当"可以拖过去的区域" —— 否则 1x 时也能把纸拖走，
         * 用户会以为画布跑丢了。
         */
        val dw = canvasW * baseScale
        val dh = canvasH * baseScale
        val minTx = min(0f, width - dw * viewScale)
        val maxTx = 0f
        val minTy = min(0f, height - dh * viewScale)
        val maxTy = 0f
        viewTx = viewTx.coerceIn(minTx, maxTx)
        viewTy = viewTy.coerceIn(minTy, maxTy)
    }

    private fun beginStroke(event: MotionEvent) {
        takeSnapshot()
        val p = toCanvas(event.x, event.y)
        val s = Stroke(brushColor, tool)
        val w = baseWidthPx()
        s.add(p.x, p.y, w)
        strokes.add(s)
        current = s
        lastX = p.x
        lastY = p.y
        lastWidth = w
        lastTime = System.currentTimeMillis()
    }

    /**
     * 继续一笔，并按速度计算笔锋。
     *
     * 速度越快 → 笔画越细。用固定时间窗平滑，避免抖动导致宽度突变。
     */
    private fun extendStroke(event: MotionEvent) {
        val s = current ?: return
        val p = toCanvas(event.x, event.y)

        val now = System.currentTimeMillis()
        val dt = (now - lastTime).coerceAtLeast(1L)
        val dist = hypot(p.x - lastX, p.y - lastY)
        val speed = dist / dt // px per ms

        val base = baseWidthPx()
        // 速度归一化：约 1.5 px/ms 视为"很快"
        val speedNorm = (speed / 1.5f).coerceIn(0f, 1f)
        val target = base * (1f - taperStrength * speedNorm * 0.7f)

        // 与上一宽度做平滑，避免锯齿
        val w = lastWidth + (target - lastWidth) * 0.35f

        s.add(p.x, p.y, w)
        lastX = p.x
        lastY = p.y
        lastWidth = w
        lastTime = now
        invalidate()
    }

    private fun endStroke() {
        val s = current ?: return
        // 收笔自然变细
        if (s.size >= 2) {
            s.widths[s.size - 1] = s.widths[s.size - 1] * 0.55f
        }
        current = null
        // 定型后烘进 cache
        cacheCanvas?.let { drawStroke(it, s) }
        invalidate()
    }

    private fun cancelCurrentStroke() {
        val s = current ?: return
        strokes.remove(s)
        current = null
        invalidate()
    }

    // ==================== 贴纸手势 ====================

    /** 贴纸拖拽 / 缩放的临时状态。 */
    private var stickerDragIndex = -1
    private var stickerLastX = 0f
    private var stickerLastY = 0f
    private var stickerStartDist = 0f
    private var stickerStartRatio = 0f
    private var stickerStartAngle = 0f
    private var stickerStartRotation = 0f

    /**
     * 处理贴纸上的触摸。
     *
     * 交互设计：
     *   单指拖动 → 移动位置
     *   双指捏合 → 缩放
     *   双指旋转 → 旋转（和缩放在同一手势里，更自然）
     *
     * @return true 表示这次事件被贴纸消费掉了，不该传给画笔
     */
    private fun handleStickerTouch(event: MotionEvent): Boolean {
        val cp = toCanvas(event.x, event.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(cp.x, cp.y)
                if (hit < 0) {
                    // 点在空白处：取消选中，让画笔接管
                    clearStickerSelection()
                    return false
                }
                selectedSticker = hit
                stickerDragIndex = hit
                stickerLastX = cp.x
                stickerLastY = cp.y
                onStickerSelected?.invoke(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下 → 进入缩放/旋转
                if (stickerDragIndex >= 0 && event.pointerCount >= 2) {
                    val p = placed[stickerDragIndex]
                    val r = stickerRect(p)
                    stickerStartDist = hypot(
                        event.getX(0) - event.getX(1),
                        event.getY(0) - event.getY(1)
                    )
                    stickerStartRatio = p.wRatio
                    stickerStartAngle = angleOf(event)
                    stickerStartRotation = p.rotation
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (stickerDragIndex !in placed.indices) return false
                val p = placed[stickerDragIndex]

                if (event.pointerCount >= 2 && stickerStartDist > 0f) {
                    // 缩放
                    val dist = hypot(
                        event.getX(0) - event.getX(1),
                        event.getY(0) - event.getY(1)
                    )
                    p.wRatio = (stickerStartRatio * (dist / stickerStartDist))
                        .coerceIn(MIN_STICKER_RATIO, MAX_STICKER_RATIO)

                    // 旋转：只取角度差，不做累加，避免抖动累积
                    val delta = angleOf(event) - stickerStartAngle
                    p.rotation = stickerStartRotation + delta
                } else {
                    // 拖动：位移换算成相对值。除的是**画布**尺寸，不是控件尺寸
                    val dx = (cp.x - stickerLastX) / canvasW
                    val dy = (cp.y - stickerLastY) / canvasH
                    p.cx = (p.cx + dx).coerceIn(0f, 1f)
                    p.cy = (p.cy + dy).coerceIn(0f, 1f)
                    stickerLastX = cp.x
                    stickerLastY = cp.y
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起一根手指后重新基准，避免跳变
                if (event.pointerCount >= 2) {
                    stickerStartDist = 0f
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                /*
                 * 只有**真的在拖贴纸**时才吞掉这个 UP。
                 *
                 * 这里原来无条件 return true，导致一个很隐蔽的严重 bug：
                 * 手指落在空白处（没点中贴纸）时 DOWN 已经返回 false 让画笔接管了，
                 * 但 UP 仍被这里吃掉 —— onTouchEvent 提前 return，
                 * 于是 endStroke() 永远不执行。
                 *
                 * 后果：
                 *   1. 这一笔永远不烘进 cache，一直挂在 current 上
                 *   2. 下一笔的 DOWN 直接把 current 换成新的笔画，
                 *      上一笔就**从画面上消失了**
                 *   3. 于是"贴了贴纸之后再画画，只看得见正在画的那一笔"
                 *
                 * 用户报的正是这个现象。判定条件加上 stickerDragIndex，
                 * 没在拖贴纸就把 UP 放行给画笔。
                 */
                val wasDragging = stickerDragIndex >= 0
                stickerDragIndex = -1
                stickerStartDist = 0f
                return wasDragging
            }
        }
        return false
    }

    /** 两指连线的角度（度）。 */
    private fun angleOf(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(1) - e.getX(0)
        val dy = e.getY(1) - e.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ==================== 导出 ====================

    /**
     * 导出整张画布。
     *
     * **不裁切**：保持画布原始比例，用户留白多少就发多少。
     * 上一版会裁掉空白只留笔迹范围，导致接收方看到的构图
     * 和发送方当时写的位置完全对不上。
     *
     * 尺寸按 [maxDimension] 等比限制 —— 位图内存 = 宽×高×4 字节，
     * 480dpi 屏幕上整屏画布接近 2000px 高，不限制会顶爆小部件的位图预算。
     * 描画层不参与导出。
     */
    fun exportBitmap(maxDimension: Int = 1280): Bitmap? {
        // 只有贴纸没有笔画时也要能导出 —— 用户可能只贴了一张贴纸
        if ((strokes.isEmpty() && placed.isEmpty()) || !canvasReady) return null

        val src = cache
        if (src == null || src.isRecycled) return null

        /*
         * 导出的是**整块逻辑画布**，不是控件当前显示的那一块。
         *
         * 这是"不裁切"的技术落点：抽屉开着还是关着、屏幕转向哪边，
         * 导出的尺寸和内容都不变 —— 用户当时画的是什么，发出去就是什么。
         */
        val longest = max(canvasW, canvasH).toFloat()
        val scale = min(1f, maxDimension / longest)
        val outW = (canvasW * scale).toInt().coerceAtLeast(1)
        val outH = (canvasH * scale).toInt().coerceAtLeast(1)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.TRANSPARENT)
        canvas.drawBitmap(
            src,
            android.graphics.Rect(0, 0, canvasW, canvasH),
            RectF(0f, 0f, outW.toFloat(), outH.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        /*
         * 贴纸要单独画到导出图上。
         *
         * 不能直接复用 onDraw 里的绘制 —— 那是在**屏幕坐标**下画的，
         * 而导出图尺寸和屏幕尺寸不同（按 maxDimension 缩过）。
         * 所以这里按导出尺寸重新算一遍位置。
         */
        placed.forEach { p ->
            val w = outW * p.wRatio
            val h = w * p.bitmap.height / p.bitmap.width.toFloat()
            val cx = outW * p.cx
            val cy = outH * p.cy
            val r = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            canvas.save()
            canvas.rotate(p.rotation, r.centerX(), r.centerY())
            canvas.drawBitmap(p.bitmap, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }

        return out
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cache?.recycle()
        cache = null
        cacheCanvas = null
        snapshots.forEach { it.recycle() }
        snapshots.clear()
        tracing?.recycle()
        tracing = null
    }

    private companion object {
        const val MAX_SNAPSHOTS = 12

        /** 贴纸最小宽度占画布比例（太小就点不到了）。 */
        const val MIN_STICKER_RATIO = 0.08f
        /** 贴纸最大宽度占画布比例（防止一张贴纸盖满整张纸）。 */
        const val MAX_STICKER_RATIO = 1.2f
    }
}
