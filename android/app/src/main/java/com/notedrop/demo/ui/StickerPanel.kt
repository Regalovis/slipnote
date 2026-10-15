package com.notedrop.demo.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.notedrop.demo.R
import com.notedrop.demo.sticker.Sticker
import com.notedrop.demo.sticker.StickerStore

/**
 * 贴纸面板。
 *
 * 两个入口：
 *   1. 从相册选图 → 本地抠图 → 得到一张新贴纸
 *   2. 从贴纸库挑一张已有的（包括从别人纸条上取下来的）
 *
 * 面板内容用代码构建而不是 XML：
 * 贴纸库里有几张是不固定的，动态构建比写一堆 visibility 切换的静态布局清爽。
 */
object StickerPanel {

    /** 保存当前对话框的关闭方法，供点击项时调用。 */
    private var dismissRef: (() -> Unit)? = null

    /**
     * 弹出贴纸面板。
     *
     * @param onPickFromGallery 用户点了"从相册抠一张"
     * @param onPickSticker     用户从贴纸库里挑了一张
     */
    fun show(
        activity: Activity,
        onPickFromGallery: () -> Unit,
        onPickSticker: (Sticker) -> Unit
    ) {
        val ctx: Context = activity
        val d = ctx.resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }

        // ---- 入口 1：抠新贴纸 ----
        root.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.sticker_new)
                textSize = 15f
                setTextColor(ctx.getColor(R.color.brand))
                gravity = Gravity.CENTER
                setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
                setBackgroundResource(R.drawable.pill_bg)
                isClickable = true
                setOnClickListener {
                    dismissRef?.invoke()
                    onPickFromGallery()
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // ---- 入口 2：贴纸库 ----
        val stickers = StickerStore.list(ctx)
        root.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.sticker_library)
                textSize = 13f
                setTextColor(ctx.getColor(R.color.ink_secondary))
                setPadding(0, (20 * d).toInt(), 0, (8 * d).toInt())
            }
        )

        if (stickers.isEmpty()) {
            root.addView(
                TextView(ctx).apply {
                    text = ctx.getString(R.string.sticker_empty)
                    textSize = 13f
                    setTextColor(ctx.getColor(R.color.hint_on_paper))
                    setLineSpacing(0f, 1.4f)
                }
            )
        } else {
            // 网格，一行 4 个。手写网格而不是上 RecyclerView：
            // 面板尺寸小、数量有限，不值得引入整套 adapter
            val size = (64 * d).toInt()
            var row: LinearLayout? = null
            stickers.forEachIndexed { index, s ->
                if (index % 4 == 0) {
                    row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (10 * d).toInt() }
                    }
                    root.addView(row)
                }
                row?.addView(buildCell(ctx, s, size, d) {
                    dismissRef?.invoke()
                    onPickSticker(s)
                })
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.sticker_panel_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dismissRef = { dialog.dismiss() }
        dialog.show()
    }

    /** 贴纸库里一个小格子。 */
    private fun buildCell(
        ctx: Context,
        sticker: Sticker,
        size: Int,
        d: Float,
        onClick: () -> Unit
    ): View {
        val box = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (10 * d).toInt()
            }
            setBackgroundResource(R.drawable.input_bg)
            isClickable = true
            setOnClickListener { onClick() }
        }

        val bmp = StickerStore.loadBitmap(sticker, size * 2)
        if (bmp != null) {
            box.addView(
                ImageView(ctx).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val pad = (6 * d).toInt()
                    setPadding(pad, pad, pad, pad)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
            )
        }
        return box
    }

    /*
     * 读图和抠图都搬走了。
     *
     * - 读图 + 尺寸校验 → CutoutActivity（它有图片区，需要自己控制解码尺寸）
     * - 抠图           → MagicWand（颜色键，不再需要模型）
     *
     * 这个面板现在只负责"挑一张贴纸"，职责更单一了。
     */
}
