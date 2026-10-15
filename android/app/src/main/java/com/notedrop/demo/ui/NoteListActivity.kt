package com.notedrop.demo.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notedrop.demo.R
import com.notedrop.demo.data.FavoriteStore
import com.notedrop.demo.data.InboxStore
import com.notedrop.demo.data.NoteStore
import com.notedrop.demo.data.NoteSticker
import com.notedrop.demo.sticker.StickerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 收件箱 / 收藏夹（共用一个页面，用 extra 区分）。
 *
 * 设计上刻意分成「列表 → 详情」两层，而不是在列表里直接放收藏按钮：
 * 小纸条是靠画面和手感传达情绪的，列表里那一行缩略摘要
 * 根本不足以让人判断"要不要收藏"。用户需要先**打开看**，
 * 看完再决定收藏或删除 —— 这也是用户反馈里明确提出的诉求。
 */
class NoteListActivity : AppCompatActivity() {

    enum class Mode { INBOX, FAVORITES }

    private lateinit var mode: Mode
    private lateinit var titleView: TextView
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView
    private lateinit var listView: android.widget.LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_list)

        findViewById<View>(R.id.root).applySystemBarInsets()

        mode = if (intent.getBooleanExtra(EXTRA_FAVORITES, false)) Mode.FAVORITES else Mode.INBOX

        titleView = findViewById(R.id.tv_title)
        countView = findViewById(R.id.tv_count)
        emptyView = findViewById(R.id.tv_empty)
        listView = findViewById(R.id.list)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        titleView.text = getString(
            if (mode == Mode.FAVORITES) R.string.favorites_title else R.string.inbox_title
        )
        emptyView.text = getString(
            if (mode == Mode.FAVORITES) R.string.favorites_empty else R.string.inbox_empty
        )
    }

    override fun onResume() {
        super.onResume()
        // 用户正在看着收件箱，就算"看过了"。
        // 放在 render 之前：渲染时读的就是刚更新过的状态。
        if (mode == Mode.INBOX) {
            InboxStore.markSeen(this)
        }
        render()
    }

    private fun render() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                // 缩略图边长在这里定：56dp 的格子 @3x 屏约 168px，
                // 读 112px 已经足够清晰，再大只是白占内存
                val thumbPx = (56 * resources.displayMetrics.density).toInt()
                val decodePx = (thumbPx * 2).coerceAtMost(256)

                // 贴纸库整个读一次，列表里每行都去读一遍 JSON 是没必要的开销
                val library = StickerStore.list(this@NoteListActivity)
                    .associateBy { it.path }

                fun thumbOf(doodlePath: String?, stickers: List<NoteSticker>): Pair<Bitmap?, Map<String, Bitmap>> {
                    val bmp = doodlePath?.let { NoteStore.decodeDoodle(it, decodePx) }
                    val bitmaps = mutableMapOf<String, Bitmap>()
                    stickers.forEach { s ->
                        val path = s.localPath ?: return@forEach
                        val sticker = library[path] ?: return@forEach
                        val sb = StickerStore.loadBitmap(sticker, decodePx) ?: return@forEach
                        bitmaps[s.id] = sb
                    }
                    return bmp to bitmaps
                }

                when (mode) {
                    Mode.INBOX -> InboxStore.list(this@NoteListActivity).map { item ->
                        val (bmp, bitmaps) = thumbOf(item.doodlePath, item.stickers)
                        // 收件箱条目要知道自己有没有被收藏过
                        Row(
                            id = item.id,
                            text = item.text,
                            senderName = item.senderName,
                            createdAt = item.createdAt,
                            hasDoodle = item.doodlePath != null,
                            thumbBase = bmp,
                            thumbPlacement = item.stickers,
                            thumbStickers = bitmaps,
                            favorited = FavoriteStore.contains(
                                this@NoteListActivity, item.text, item.createdAt
                            )
                        )
                    }

                    Mode.FAVORITES -> FavoriteStore.list(this@NoteListActivity).map { item ->
                        val (bmp, bitmaps) = thumbOf(item.doodlePath, item.stickers)
                        Row(
                            id = item.id,
                            text = item.text,
                            senderName = item.senderName,
                            createdAt = item.createdAt,
                            hasDoodle = item.doodlePath != null,
                            thumbBase = bmp,
                            thumbPlacement = item.stickers,
                            thumbStickers = bitmaps,
                            favorited = true
                        )
                    }
                }
            }

            listView.removeAllViews()
            emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            countView.text = if (rows.isEmpty()) "" else getString(R.string.list_count, rows.size)
            /*
             * 行之间不再画分隔线。
             *
             * 每一行左侧都有一个彩色圆头像，它本身就是天然的节奏点；
             * 再加发丝线是多余的，而且会让列表看起来像设置页。
             * 原型里也没有线，靠行间距分组。
             */
            rows.forEach { row -> listView.addView(buildRow(row)) }
        }
    }

    /** 极淡的分隔线。 */
    private fun buildHairline(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.divider))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            (0.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        )
    }

    private data class Row(
        val id: String,
        val text: String,
        val senderName: String,
        val createdAt: Long,
        val hasDoodle: Boolean,
        /**
         * 缩略图已经**解码好**的底图；为空时行首显示发送人首字。
         *
         * 刻意在 IO 线程里解码成位图再传进来，而不是把路径带到这一层 ——
         * 行构建跑在主线程上，在那里解码图片会直接把列表卡住。
         */
        val thumbBase: Bitmap?,
        /**
         * 贴纸的摆放信息（贴在哪、多大、转了多少度）。
         *
         * 和下面的位图表分开：摆放信息本来就在纸条存档里，
         * 但外层不持有 [InboxStore.Item]，只有渲染需要的那一份，
         * 所以显式传进来，避免为了取一个 id 再回查存档。
         */
        val thumbPlacement: List<NoteSticker> = emptyList(),
        /** 已解码的贴纸位图（贴纸 id → 位图）。缩略图要连着贴纸一起画。 */
        val thumbStickers: Map<String, Bitmap> = emptyMap(),
        val favorited: Boolean
    )

    /**
     * 一行 = 圆头像 + 名字 + 摘要，和首页同一套形态。
     *
     * ## 头像为什么是"缩略图或首字"
     *
     * 小纸条的价值大半在"手写"这件事上，所以有涂鸦时头像直接用**那张画**——
     * 圆形的裁剪让它在列表里既是一张缩略图，也是一个头像位。
     * 没有涂鸦才退回名字首字的色块。
     */
    private fun buildRow(row: Row): View {
        val d = resources.displayMetrics.density
        val faceSize = (44 * d).toInt()
        val gap = (14 * d).toInt()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(selectableItemBackgroundRes())
            setPadding(0, (9 * d).toInt(), 0, (9 * d).toInt())
            setOnClickListener { openDetail(row.id) }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ---- 左：圆头像 ----
        val face = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(faceSize, faceSize)
            // 圆形裁剪：里面的内容超出圆边就被切掉，看起来才是"头像"
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        val hasVisual = row.thumbBase != null || row.thumbStickers.isNotEmpty()
        if (hasVisual) {
            face.background = roundBackground(getColor(R.color.paper_white_fill))
            /*
             * 缩略图渲染。
             *
             * 关键点：**贴纸必须一起画出来**。之前这里只有 ImageView + 涂鸦，
             * 贴纸完全看不见，列表里那张光秃秃的纸条和点开后带贴纸的详情页
             * 对不上，用户会觉得"我明明贴了东西，怎么列表里没有"。
             */
            face.addView(NoteRenderView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                fit = NoteRenderView.Fit.CROP
                setContent(row.thumbBase, row.thumbPlacement, row.thumbStickers)
                // 头像里点贴纸没有意义（点整行是打开详情），
                // 明确置空，避免误触发"取下来"的菜单
                onStickerClick = null
                isClickable = false
                isFocusable = false
            })
        } else {
            face.background = faceBackground(row.senderName)
            face.addView(TextView(this).apply {
                text = row.senderName.trim().take(1).ifBlank { "纸" }
                textSize = 16f
                setTextColor(getColor(android.R.color.white))
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }
        container.addView(face)

        // ---- 右：名字 + 摘要 ----
        val info = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = gap }
        }

        val nameRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(this).apply {
            text = row.senderName
            textSize = 16f
            setTextColor(getColor(R.color.ink_on_paper))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })
        // 收藏标记：小小一颗星，跟在名字后面，不占一整行
        if (row.favorited) {
            nameRow.addView(TextView(this).apply {
                text = "★"
                textSize = 12f
                setTextColor(getColor(R.color.aurora))
                setPadding((6 * d).toInt(), 0, 0, 0)
            })
        }
        info.addView(nameRow)

        info.addView(TextView(this).apply {
            text = row.text.ifBlank {
                if (row.hasDoodle) getString(R.string.only_doodle_preview) else ""
            }
            textSize = 14f
            setTextColor(getColor(R.color.ink_secondary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (2 * d).toInt(), 0, 0)
        })

        // 时间是辅助信息，挂在名字行右边比单独占一行更省空间
        info.addView(TextView(this).apply {
            text = NoteStore.formatRelative(this@NoteListActivity, row.createdAt)
            textSize = 11f
            setTextColor(getColor(R.color.hint_on_paper))
            setPadding(0, (2 * d).toInt(), 0, 0)
        })

        container.addView(info)
        return container
    }

    /** 纯色圆。头像用。 */
    private fun roundBackground(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }

    /**
     * 按名字给头像挑一个颜色。
     *
     * 用名字哈希取模，保证**同一个人永远同一个颜色** ——
     * 随机的话每次进列表颜色都在跳，这个色块就没有识别意义了。
     * 色相全部落在极光色域里，和首页头图同源。
     */
    private fun faceBackground(name: String): android.graphics.drawable.GradientDrawable {
        val colors = intArrayOf(
            0xFF8E7BF0.toInt(), // 紫
            0xFFB06AE8.toInt(), // 紫品红
            0xFFE87BA8.toInt(), // 粉
            0xFF6E93F0.toInt(), // 蓝
            0xFF5BB8D9.toInt()  // 青
        )
        val index = ((name.hashCode() % colors.size) + colors.size) % colors.size
        return roundBackground(colors[index])
    }

    /** 行点击的水波纹，跟随主题。 */
    private fun selectableItemBackgroundRes(): Int {
        val out = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }

    private fun openDetail(noteId: String) {
        startActivity(
            Intent(this, NoteDetailActivity::class.java)
                .putExtra(NoteDetailActivity.EXTRA_NOTE_ID, noteId)
                .putExtra(NoteDetailActivity.EXTRA_FAVORITES_MODE, mode == Mode.FAVORITES)
        )
    }

    companion object {
        const val EXTRA_FAVORITES = "favorites_mode"
    }
}
