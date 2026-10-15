package com.notedrop.demo.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.notedrop.demo.R
import com.notedrop.demo.sticker.CutoutView
import com.notedrop.demo.sticker.MagicWand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 抠图页：让用户自己指哪儿删哪儿。
 *
 * ## 为什么要重做
 *
 * 上一版是"选图 → AI 自动抠 → 出结果"，中间没有任何干预手段。
 * 用户实测"几乎无法使用"—— 这有两个原因：
 *
 * 1. **模型选错了**。`deeplab_v3` 是语义分割，只认 20 类常见物体，
 *    对商品图、截图这类"主体"它根本不认识，输出基本是随机的。
 * 2. **不可干预**。就算模型偶尔对，用户也没法修 —— 判错了只能重来。
 *
 * 现在换成颜色键（魔棒）：用户点哪儿，就去掉哪儿颜色相近的**连片**区域。
 * 不需要认识任何东西，白底图几乎百发百中，而且结果完全可预期。
 * 配合容差滑杆和撤销，用户可以自己修到满意。
 *
 * ## 撤销为什么存整张位图
 *
 * 也可以只存"每一步删了哪些像素"这个反向记录，省内存。
 * 但那样每步要额外维护一份索引表，代码复杂得多；
 * 而这里每步的位图是 1024 级别的，几十步也就几十 MB ——
 * 移动端短期操作完全撑得住。**先用简单可靠的做**。
 */
class CutoutActivity : AppCompatActivity() {

    private lateinit var cutoutView: CutoutView
    private lateinit var tvTolerance: TextView
    private lateinit var loading: View
    private lateinit var slider: Slider
    private lateinit var btnUndo: MaterialButton

    /** 原始图，用于"还原"。 */
    private var original: Bitmap? = null

    /** 撤销栈：每一步之前的状态。栈顶是最近一次操作前。 */
    private val undoStack = ArrayDeque<Bitmap>()

    /** 有没有真的抠掉过东西。决定"完成"要不要拦一下。 */
    private var hasCut = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cutout)
        findViewById<View>(R.id.root).applySystemBarInsets()

        cutoutView = findViewById(R.id.cutout)
        tvTolerance = findViewById(R.id.tvTolerance)
        loading = findViewById(R.id.loading)
        slider = findViewById(R.id.sliderTolerance)
        btnUndo = findViewById(R.id.btnUndo)

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { cancelAndFinish() }
        findViewById<MaterialButton>(R.id.btnDone).setOnClickListener { finishWithResult() }
        findViewById<MaterialButton>(R.id.btnAutoCorners).setOnClickListener { autoCorners() }
        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener { reset() }
        btnUndo.setOnClickListener { undo() }

        slider.addOnChangeListener { _, value, _ ->
            tvTolerance.text = value.toInt().toString()
        }
        tvTolerance.text = slider.value.toInt().toString()

        cutoutView.onPick = { x, y -> removeAt(x, y) }

        loadImage()
    }

    /** 读进来的图。 */
    private fun loadImage() {
        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                uri?.let { decodeSampled(it, MAX_SIZE) }
            }
            if (bmp == null) {
                toast(getString(R.string.cutout_failed))
                finish()
                return@launch
            }
            original = bmp
            cutoutView.setBitmap(bmp)
            refreshUndoButton()
        }
    }

    /**
     * 点哪儿删哪儿。
     *
     * 魔棒是逐像素的，一张 1024 的图在一百万像素量级 ——
     * 必须放后台，否则点一下界面会明显顿一下。
     */
    private fun removeAt(x: Int, y: Int) {
        val current = cutoutView.current() ?: return
        val tolerance = slider.value.toInt()

        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                MagicWand.removeConnected(current, x, y, tolerance)
            }
            setBusy(false)

            if (result.removed == 0) {
                // 没删掉任何像素，说明点到的是主体内部 —— 明确告诉用户，
                // 否则会以为"点了没反应"
                toast(getString(R.string.cutout_nothing))
                return@launch
            }

            // 推入撤销栈的是**操作前**的那张
            pushUndo(current)
            cutoutView.setBitmap(result.bitmap)
            hasCut = true
        }
    }

    /** 一键去掉四角连成一片的背景，白底图的快捷路径。 */
    private fun autoCorners() {
        val current = cutoutView.current() ?: return
        val tolerance = slider.value.toInt()

        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                MagicWand.autoFromCorners(current, tolerance)
            }
            setBusy(false)

            if (result.removed == 0) {
                toast(getString(R.string.cutout_nothing))
                return@launch
            }
            pushUndo(current)
            cutoutView.setBitmap(result.bitmap)
            hasCut = true
        }
    }

    private fun undo() {
        val previous = undoStack.removeLastOrNull()
        if (previous == null) {
            toast(getString(R.string.cutout_no_undo))
            return
        }
        // 撤掉的那张如果已经不在显示里了，回收掉，别留着占内存
        cutoutView.current()?.let { if (it !== previous && it !== original) it.recycle() }
        cutoutView.setBitmap(previous)
        hasCut = true
        refreshUndoButton()
    }

    /** 还原成刚读进来的样子。 */
    private fun reset() {
        val src = original ?: return
        cutoutView.current()?.let { if (it !== src) it.recycle() }
        undoStack.clear()
        cutoutView.setBitmap(src)
        hasCut = false
        refreshUndoButton()
    }

    private fun pushUndo(bitmap: Bitmap) {
        undoStack.addLast(bitmap)
        /*
         * 撤销栈封顶。
         *
         * 每张是 1024×1024 ARGB ≈ 4MB，不封顶的话用户连点几十下
         * 就是几百 MB，必然 OOM。封到 12 步 —— 手感上够用了，
         * 真删错了还可以「还原」回到最初。
         */
        while (undoStack.size > MAX_UNDO) {
            val dropped = undoStack.removeFirst()
            // 最老的这张已经不在显示里了，回收掉
            if (dropped !== original) runCatching { dropped.recycle() }
        }
        refreshUndoButton()
    }

    private fun refreshUndoButton() {
        btnUndo.isEnabled = undoStack.isNotEmpty()
        btnUndo.alpha = if (undoStack.isNotEmpty()) 1f else 0.4f
    }

    private fun setBusy(busy: Boolean) {
        loading.visibility = if (busy) View.VISIBLE else View.GONE
    }

    /**
     * 完成：把抠好的图交回编辑页。
     *
     * 用 setResult 把位图**放进内存**而不是写文件 —— 位图不大（1024 级），
     * 写一次文件反而多一轮 IO，还要管临时文件的清理。
     * 注意 Intent 里的 Bitmap 走的是进程内传递（同进程 Activity 之间），
     * 不会触发 TransactionTooLarge。
     */
    private fun finishWithResult() {
        val bmp = cutoutView.current()
        if (bmp == null || !hasCut) {
            toast(getString(R.string.cutout_empty))
            return
        }
        // 回传前裁掉四周空白，贴纸贴到纸上才不会是"一张大图里一个小东西"
        val trimmed = trim(bmp)
        /*
         * 结果走**进程内静态变量**，不走 Intent extra。
         *
         * Bitmap 实现了 Parcelable，塞进 Intent 也能用，但它是走 Binder 的 ——
         * 一张 1024×1024 的 ARGB 图就是 4MB，直接顶穿 Binder 的 1MB 事务上限，
         * 报 TransactionTooLargeException。
         *
         * 这两个 Activity 在同一个进程里，用一个静态字段交接最省事也最可靠。
         * 只传一个"成功了"的信号给 setResult。
         */
        pendingResult = trimmed
        setResult(Activity.RESULT_OK)
        finish()
    }

    /**
     * 裁掉四周全透明的边。
     *
     * 和魔棒分开放：用户可能先删一片、又后悔撤销，
     * 裁边只在最后交付时做一次，中途裁会让用户失去参照。
     */
    private fun trim(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 12) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return src

        val pad = (maxOf(maxX - minX, maxY - minY) * 0.02f).toInt().coerceAtLeast(2)
        val x0 = (minX - pad).coerceAtLeast(0)
        val y0 = (minY - pad).coerceAtLeast(0)
        val x1 = (maxX + pad + 1).coerceAtMost(w)
        val y1 = (maxY + pad + 1).coerceAtMost(h)
        if (x0 == 0 && y0 == 0 && x1 == w && y1 == h) return src

        return Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * 按目标尺寸读图。
     *
     * 相册里的图动辄 4000x3000，直接解码就是 48MB；
     * 而且魔棒是逐像素的，图越大越慢。压到 1024 以内，
     * 贴纸用途完全够，速度还快一个数量级。
     */
    private fun decodeSampled(uri: Uri, maxSize: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= maxSize) sample *= 2

        // 解码成 ARGB_8888 而不是默认的 565 —— 我们要保留 alpha 通道
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it, null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }
    }.getOrNull()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 取消退出。
     *
     * 必须显式清掉 pendingResult —— 它是静态的，上一次完成时留下的那张
     * 如果没被取走，这一次取消也会被当成"完成"贴出去。
     */
    private fun cancelAndFinish() {
        pendingResult = null
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 撤销栈和原图都可能是几百 KB 到几 MB，页面关掉就该放掉
        undoStack.forEach { runCatching { it.recycle() } }
        undoStack.clear()
        original?.let { runCatching { it.recycle() } }
        original = null
    }

    companion object {
        const val EXTRA_URI = "cutout_uri"

        /**
         * 抠好的位图在这里交接。
         *
         * 为什么不放 Intent：见 [finishWithResult] 的说明 ——
         * Binder 事务有 1MB 上限，位图必然超。
         *
         * 取走时**立刻置空**，避免这张图被长期持有（也就少一次泄漏机会）。
         */
        private var pendingResult: Bitmap? = null

        /** 由调用方（编辑页）在读结果时调用。取走即清空。 */
        fun takeResult(): Bitmap? = pendingResult.also { pendingResult = null }

        /** 处理尺寸上限。再大对贴纸没意义，只是慢。 */
        private const val MAX_SIZE = 1024

        /** 撤销栈深度上限。见 [pushUndo] 的说明。 */
        private const val MAX_UNDO = 12
    }
}
