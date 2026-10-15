package com.notedrop.demo.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.notedrop.demo.R
import com.notedrop.demo.AppScope
import com.notedrop.demo.data.Note
import com.notedrop.demo.data.NoteStore
import com.notedrop.demo.data.PaperStyle
import com.notedrop.demo.net.Api
import com.notedrop.demo.net.AppConfig
import com.notedrop.demo.widget.NoteWidgetProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.notedrop.demo.data.Recipient
import com.notedrop.demo.sticker.Sticker
import com.notedrop.demo.sticker.StickerStore

/**
 * 写小纸条：选纸张 → 写字 / 涂鸦 → 发送。
 *
 * 画笔支持：粗细五档、颜色六色、橡皮擦。
 * 发送后本地留一份，自己的桌面立刻能看到。
 */
class ComposeActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var doodle: DoodleView
    private lateinit var paper: LinearLayout
    private lateinit var btnWidth: MaterialButton
    private lateinit var toolGroup: MaterialButtonToggleGroup
    private lateinit var btnRecipient: MaterialButton
    private lateinit var btnColor: MaterialButton
    private lateinit var btnTracing: MaterialButton
    private lateinit var btnZoom: MaterialButton
    private lateinit var btnSticker: MaterialButton
    private lateinit var btnStickerDelete: MaterialButton
    private lateinit var btnStickerLock: MaterialButton
    private lateinit var tvZoomHint: TextView

    /**
     * 工具抽屉。收起时只剩一根把手，展开时露出全部工具。
     *
     * **横屏没有抽屉** —— 横屏的工具栏在右侧竖排，本来就不占画布高度，
     * 不需要再藏起来（用户当初说的"横屏按钮别挤占上下空间"就是这么解决的）。
     * 所以这个字段可空，竖屏才有。
     */
    private var drawer: View? = null

    /** 当前选中的颜色。 */
    private var currentColor = 0

    /** 颜色名字，弹窗里显示用。 */
    private val paletteNames = listOf("墨黑", "靛蓝", "松绿", "琥珀", "朱红", "紫")

    private var paperStyle = PaperStyle.WHITE
    private var sending = false

    /**
     * 当前选中的收件人。
     *
     * 之前发送目标只能取 AppConfig.lastPeerId —— 用户没法在写的时候选人，
     * 这是最影响体验的一处缺陷。现在显式选择，并在发送前展示给用户确认。
     */
    private var recipient: Recipient? = null

    /** 可选的收件人列表，进入页面时异步加载。 */
    private var recipients: List<Recipient> = emptyList()

    private val palette = intArrayOf(
        0xFF2B2B2B.toInt(), // 墨黑
        0xFF2E5C8A.toInt(), // 靛蓝
        0xFF3E7A4E.toInt(), // 松绿
        0xFFC8892F.toInt(), // 琥珀
        0xFFB4443A.toInt(), // 朱红
        0xFF7A4E8A.toInt()  // 紫
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose)
        AppConfig.load(this)

        // 避开手势导航条，否则底部工具栏会被压住点不到
        findViewById<View>(R.id.root).applySystemBarInsets()

        input = findViewById(R.id.input_text)
        doodle = findViewById(R.id.doodle)
        paper = findViewById(R.id.paper)
        btnWidth = findViewById(R.id.btnWidth)
        toolGroup = findViewById(R.id.toolGroup)
        btnRecipient = findViewById(R.id.btnRecipient)
        btnTracing = findViewById(R.id.btnTracing)
        btnZoom = findViewById(R.id.btnZoom)
        btnSticker = findViewById(R.id.btnSticker)
        btnStickerDelete = findViewById(R.id.btnStickerDelete)
        btnStickerLock = findViewById(R.id.btnStickerLock)
        tvZoomHint = findViewById(R.id.tvZoomHint)
        drawer = findViewById(R.id.drawer)

        // 返回按钮竖屏才有；横屏靠系统返回键，没有这一颗
        findViewById<MaterialButton>(R.id.btnBack)?.setOnClickListener { finish() }

        /*
         * 恢复旋转前的纸张选择。
         *
         * 这里刻意**不声明** configChanges，所以旋转会重建 Activity ——
         * 这正是我们要的效果：只有重建才会加载 layout-land（横屏专用布局）。
         * 代价是界面状态要自己接住，否则用户转个手机纸张样式就重置了。
         *
         * 文字由 EditText 自动恢复；画作由 DoodleView 的
         * onSaveInstanceState 恢复；这里只处理纸张样式。
         */
        if (savedInstanceState != null) {
            paperStyle = PaperStyle.fromOrdinal(savedInstanceState.getInt(STATE_PAPER, 0))
        }

        setupPaperPicker()
        setupToolPicker()
        setupColorPicker()
        setupWidthButton()
        setupOrientation()
        setupRecipient()
        setupTracing()
        setupZoom()
        setupSticker()
        setupDrawer()

        findViewById<MaterialButton>(R.id.btnUndo).setOnClickListener { doodle.undo() }
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            doodle.clearAll()
            input.setText("")
        }
        findViewById<MaterialButton>(R.id.btnSend).setOnClickListener { send() }

        applyPaper()
        updateWidthLabel()
        applyRestickIntent()
    }

    /**
     * 从「再贴到新纸条上」入口进来时，把那张贴纸放到画布上。
     *
     * 这是贴纸流转玩法的第二步：收到贴纸 → 取下来 → 贴到新纸条上发给别人。
     *
     * ## 为什么做成显式入口，而不是"每次进来都恢复上一张的贴纸"
     *
     * 自动恢复看着省事，实际上很危险：用户只是想来写张新纸条，
     * 结果上一张的贴纸悄悄贴在了画布上，发出去就变成了"我没想贴的东西"。
     * 所以这里只在用户**明确点了**「再贴到新纸条上」时才摆放。
     */
    private fun applyRestickIntent() {
        val stickerId = intent.getStringExtra(EXTRA_RESTICK_ID) ?: return
        val cx = intent.getFloatExtra(EXTRA_RESTICK_CX, 0.5f)
        val cy = intent.getFloatExtra(EXTRA_RESTICK_CY, 0.5f)
        val widthRatio = intent.getFloatExtra(EXTRA_RESTICK_W, 0.3f)
        val rotation = intent.getFloatExtra(EXTRA_RESTICK_ROT, 0f)

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val sticker = StickerStore.get(this@ComposeActivity, stickerId)
                    ?: return@withContext null
                val bmp = StickerStore.loadBitmap(sticker, 512) ?: return@withContext null
                com.notedrop.demo.sticker.PlacedSticker(
                    stickerId = sticker.id,
                    cx = cx,
                    cy = cy,
                    widthRatio = widthRatio,
                    rotation = rotation
                ) to bmp
            }
            val (placed, bmp) = loaded ?: return@launch

            // 切到贴纸模式，用户可以立刻拖动调整位置（这时触摸是操作贴纸而不是画笔）
            doodle.setStickerMode(true)
            doodle.restorePlacedStickers(listOf(placed), mapOf(placed.stickerId to bmp))
        }
    }

    // ---------- 收件人 ----------

    // ---------- 工具抽屉 ----------

    /**
     * 可拖拽的工具抽屉。
     *
     * 三个交互，和原型里那套 HTML 完全一致：
     *  1. 点把手 → 展开 / 收起
     *  2. 上下拖 → 跟手改变高度，松手吸附到最近的一端
     *  3. 手指落到画布上 → 自动收起（用户一开写，工具就让位）
     *
     * ## 为什么不用 BottomSheetDialog
     *
     * Material 的 BottomSheet 是一个**独立的浮层**，弹出来会盖住画布，
     * 而且自带拖拽把手和一整套动效，改起来比自己写还麻烦。
     * 这里要的是"嵌在布局里的可伸缩区域"，画布跟着它一起变高变矮 ——
     * 那就得是这个 Layout 的高度在变，而不是叠一个新窗口上去。
     *
     * ## 为什么拖拽绑在把手上而不是整个抽屉
     *
     * 抽屉里全是按钮。如果在整块区域上监听拖拽，滑动会先被按钮吃掉，
     * 拖动时也容易误触发点击。把手是干净的空白区，专门用来拖。
     */
    private fun setupDrawer() {
        // 横屏没有抽屉，直接返回
        val target = drawer ?: return
        val grabber = findViewById<View>(R.id.grabber)
        val closedPx = dp(DRAWER_CLOSED_DP)
        val openPx = dp(DRAWER_OPEN_DP)

        var startY = 0f
        var startHeight = 0

        /** 把抽屉高度夹在 [收起, 展开] 之间。 */
        fun applyHeight(h: Int) {
            val clamped = h.coerceIn(closedPx, openPx)
            target.layoutParams = target.layoutParams.apply { height = clamped }
            target.requestLayout()
        }

        /** 松手后吸附。过了一半就算展开，否则收起 —— 这个阈值符合直觉。 */
        fun settle() {
            val goal = if (target.height > (closedPx + openPx) / 2) openPx else closedPx
            android.animation.ValueAnimator.ofInt(target.height, goal).apply {
                duration = 180
                addUpdateListener { applyHeight(it.animatedValue as Int) }
                start()
            }
        }

        grabber.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startHeight = target.height
                    true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    // 手指往上 → 抽屉变高，所以是 startY - rawY
                    applyHeight(startHeight + (startY - event.rawY).toInt())
                    true
                }

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    /*
                     * 位移很小 —— 当成"点了一下"，直接切到相反状态。
                     *
                     * 不这么做的话，点把手会先被算成一次零位移的拖拽，
                     * 结果吸附回原位，看起来就是"点了没反应"。
                     */
                    val moved = kotlin.math.abs(event.rawY - startY)
                    if (moved < dp(6)) {
                        val goal = if (isDrawerOpen()) closedPx else openPx
                        android.animation.ValueAnimator.ofInt(target.height, goal).apply {
                            duration = 180
                            addUpdateListener { applyHeight(it.animatedValue as Int) }
                            start()
                        }
                    } else {
                        settle()
                    }
                    v.performClick()
                    true
                }

                else -> false
            }
        }

        // 一落笔就收起 —— 写字的时候工具不该占着地方
        doodle.onTouchStart = {
            if (isDrawerOpen()) {
                android.animation.ValueAnimator.ofInt(target.height, closedPx).apply {
                    duration = 160
                    addUpdateListener { applyHeight(it.animatedValue as Int) }
                    start()
                }
            }
        }
    }

    private fun isDrawerOpen(): Boolean {
        val d = drawer ?: return false
        return d.height > dp((DRAWER_CLOSED_DP + DRAWER_OPEN_DP) / 2)
    }

    /** dp → px。布局里的尺寸要和 XML 保持一致，所以常量都写成 dp。 */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setupRecipient() {
        btnRecipient.setOnClickListener { showRecipientPicker() }
        loadRecipients()
    }

    /**
     * 拉取可选的收件人：配对好友 + 我加入的群组。
     *
     * 群组会出现两次（接力 / 随机匿名），因为这是两种不同的玩法，
     * 让用户在选人这一步就决定，比发完再问"用哪种方式"更自然。
     */
    private fun loadRecipients() {
        if (!AppConfig.isRegistered) {
            updateRecipientLabel()
            return
        }
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val out = mutableListOf<Recipient>()
                val token = AppConfig.token
                if (token != null) {
                    runCatching {
                        val peers = Api.peers(token).optJSONArray("peers")
                        if (peers != null) {
                            for (i in 0 until peers.length()) {
                                val p = peers.getJSONObject(i)
                                out.add(
                                    Recipient.Person(
                                        p.optString("userId"),
                                        p.optString("nickname", "好友")
                                    )
                                )
                            }
                        }
                    }
                    runCatching {
                        val groups = Api.groups(token).optJSONArray("groups")
                        if (groups != null) {
                            for (i in 0 until groups.length()) {
                                val g = groups.getJSONObject(i)
                                val gid = g.optString("groupId")
                                val gname = g.optString("name", "群组")
                                // 人不够的群也列出来，但选中时给出提示，
                                // 而不是让用户困惑"为什么这里没有我的群"
                                out.add(Recipient.Group(gid, gname, Recipient.Group.Mode.CHAIN))
                                out.add(Recipient.Group(gid, gname, Recipient.Group.Mode.RANDOM))
                            }
                        }
                    }
                }
                out
            }
            recipients = list
            // 默认选中：优先沿用上次的发送对象
            if (recipient == null && list.isNotEmpty()) {
                val last = AppConfig.lastPeerId
                recipient = list.firstOrNull { it is Recipient.Person && it.userId == last }
                    ?: list.first()
            }
            updateRecipientLabel()
        }
    }

    private fun updateRecipientLabel() {
        val r = recipient
        btnRecipient.text = when {
            r == null -> getString(R.string.recipient_pick)
            r is Recipient.Person -> getString(R.string.recipient_to, r.nickname)
            r is Recipient.Group && r.mode == Recipient.Group.Mode.CHAIN ->
                getString(R.string.recipient_group_chain, r.name)
            else -> {
                val g = r as Recipient.Group
                getString(R.string.recipient_group_random, g.name)
            }
        }
    }

    private fun showRecipientPicker() {
        if (recipients.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.recipient_title)
                .setMessage(R.string.recipient_no_peer)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val labels = recipients.map { r ->
            when (r) {
                is Recipient.Person -> r.nickname
                is Recipient.Group ->
                    if (r.mode == Recipient.Group.Mode.CHAIN) {
                        getString(R.string.recipient_group_chain, r.name)
                    } else {
                        getString(R.string.recipient_group_random, r.name)
                    }
            }
        }.toTypedArray()

        val checked = recipients.indexOfFirst { sameRecipient(it, recipient) }

        AlertDialog.Builder(this)
            .setTitle(R.string.recipient_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                recipient = recipients[which]
                updateRecipientLabel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 判断两个收件人是否等价（群组要比 id + 玩法都相同）。 */
    private fun sameRecipient(a: Recipient?, b: Recipient?): Boolean {
        if (a == null || b == null) return false
        return when {
            a is Recipient.Person && b is Recipient.Person -> a.userId == b.userId
            a is Recipient.Group && b is Recipient.Group ->
                a.groupId == b.groupId && a.mode == b.mode
            else -> false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAPER, paperStyle.ordinal)
    }

    // ---------- 纸张 ----------

    private fun setupPaperPicker() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.paperGroup)
        // 按当前样式勾选，而不是硬编码白纸 ——
        // 旋转重建后 paperStyle 可能是恢复出来的其他值
        group.check(
            when (paperStyle) {
                PaperStyle.WHITE -> R.id.paperWhite
                PaperStyle.GRID -> R.id.paperGrid
                PaperStyle.CREAM -> R.id.paperCream
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            paperStyle = when (checkedId) {
                R.id.paperGrid -> PaperStyle.GRID
                R.id.paperCream -> PaperStyle.CREAM
                else -> PaperStyle.WHITE
            }
            applyPaper()
        }
    }

    private fun applyPaper() {
        paper.setBackgroundResource(PaperBackground.resId(paperStyle))
    }

    // ---------- 纸张方向 ----------

    private fun setupOrientation() {
        findViewById<MaterialButton>(R.id.btnOrientation).text =
            getString(if (isLandscape()) R.string.orient_landscape else R.string.orient_portrait)

        findViewById<MaterialButton>(R.id.btnOrientation).setOnClickListener {
            /*
             * 切换方向 = 真的把屏幕转过去。
             *
             * 只设置 requestedOrientation，剩下的交给系统：
             * 旋转会重建 Activity，重建时自动加载 layout-land（横屏布局），
             * 用户自然把手机转过来，画布铺满整个横屏空间。
             *
             * 布局本身的差异（工具栏是"底部两行"还是"顶部一行"）
             * 完全由布局文件决定，代码不需要再去改 LayoutParams ——
             * 之前那样手改约束既多余，又容易因为 id 对不上而崩。
             */
            val target = if (isLandscape()) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            requestedOrientation = target
        }
    }

    // ---------- 画笔 / 橡皮 ----------

    private fun setupToolPicker() {
        toolGroup.check(R.id.toolPen)
        toolGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            doodle.setTool(
                if (checkedId == R.id.toolEraser) DoodleView.Tool.ERASER
                else DoodleView.Tool.PEN
            )
        }
    }

    // ---------- 颜色 ----------

    private fun setupColorPicker() {
        /*
         * 颜色改成弹窗选择。
         *
         * 原来把 6 个色点平铺在工具栏上，竖屏要占掉一整行、
         * 横屏还得排成两列 —— 工具比画布还抢眼。
         * 收进弹窗后工具栏只剩一行图标，画布能多出不少高度。
         *
         * 按钮上用一个圆点显示当前颜色，不用打开弹窗也能看出用的哪支笔。
         */
        btnColor = findViewById(R.id.btnColor)
        btnColor.setOnClickListener { showColorPicker() }
        applyColorToButton(palette[0])
        doodle.setColor(palette[0])
    }

    /** 把当前颜色画到"颜色"按钮上（左侧一个小圆点）。 */
    private fun applyColorToButton(color: Int) {
        val d = resources.displayMetrics.density
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1.5f * d).toInt(), 0x33000000)
            setSize((16 * d).toInt(), (16 * d).toInt())
        }
        btnColor.icon = dot
        btnColor.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        btnColor.iconPadding = (6 * d).toInt()
    }

    private fun showColorPicker() {
        val labels = paletteNames.toTypedArray()
        val current = palette.indexOfFirst { it == currentColor }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.tool_color)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                currentColor = palette[which]
                doodle.setColor(currentColor)
                // 选颜色时自动切回画笔 —— 正在擦除时选颜色几乎一定是想继续画
                doodle.setTool(DoodleView.Tool.PEN)
                toolGroup.check(R.id.toolPen)
                applyColorToButton(currentColor)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 粗细 ----------

    private fun setupWidthButton() {
        btnWidth.setOnClickListener {
            showBrushPanel()
        }
    }

    /**
     * 笔刷设置面板：笔宽档位 + 笔锋强度。
     *
     * 两件事放一起是因为它们都属于"这支笔的形态"，
     * 分开做两个入口反而让人多点一次。
     */
    private fun showBrushPanel() {
        val total = doodle.getWidthCount()
        val widthNames = (0 until total).map { i ->
            when {
                i == 0 -> getString(R.string.width_thin)
                i == total - 1 -> getString(R.string.width_thick)
                i == total / 2 -> getString(R.string.width_mid)
                else -> "${i + 1}/$total"
            }
        }.toTypedArray()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, (8 * p / 20), p, 0)
        }

        container.addView(TextView(this).apply {
            text = getString(R.string.width_label, "")
            textSize = 13f
            setTextColor(getColor(R.color.ink_secondary))
        })

        container.addView(android.widget.SeekBar(this).apply {
            max = total - 1
            progress = doodle.getWidthIndex()
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    doodle.stepWidth(value - doodle.getWidthIndex())
                    updateWidthLabel()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })

        container.addView(TextView(this).apply {
            text = getString(R.string.taper_title)
            textSize = 13f
            setTextColor(getColor(R.color.ink_secondary))
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        })

        container.addView(android.widget.SeekBar(this).apply {
            max = 100
            progress = (doodle.getTaperStrength() * 100).toInt()
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    doodle.setTaperStrength(value / 100f)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })

        container.addView(TextView(this).apply {
            text = getString(R.string.taper_hint)
            textSize = 11f
            setTextColor(getColor(R.color.hint_on_paper))
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.brush_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updateWidthLabel() {
        val total = doodle.getWidthCount()
        val idx = doodle.getWidthIndex()
        val name = when {
            idx <= 0 -> getString(R.string.width_thin)
            idx >= total - 1 -> getString(R.string.width_thick)
            idx == total / 2 -> getString(R.string.width_mid)
            else -> "${idx + 1}/$total"
        }
        btnWidth.text = getString(R.string.width_label, name)
    }

    // ---------- 描画 ----------

    /**
     * 描画功能：选一张参考图垫在画布下面，照着它描。
     *
     * 参考图**不会随纸条发送** —— 它是书写辅助，不是内容。
     * 这点在提示文案里也写明了，避免用户误以为会被对方看到。
     */
    private fun setupTracing() {
        btnTracing.setOnClickListener {
            if (doodle.hasTracing()) {
                showTracingMenu()
            } else {
                pickTracingImage()
            }
        }
    }

    /** 用系统图片选择器挑一张图。 */
    private fun pickTracingImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        tracingPicker.launch(intent)
    }

    private val tracingPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    // 参考图只用于描画，按屏幕尺寸下采样就够，
                    // 直接解原图可能几十 MB，白白占内存
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, opts)
                    }
                    val maxDim = maxOf(opts.outWidth, opts.outHeight)
                    var sample = 1
                    while (maxDim / (sample * 2) >= 1280) sample *= 2

                    contentResolver.openInputStream(uri)?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(
                            stream, null,
                            android.graphics.BitmapFactory.Options().apply {
                                inSampleSize = sample
                            }
                        )
                    }
                }.getOrNull()
            }

            if (bmp == null) {
                toast(getString(R.string.tracing_load_failed))
                return@launch
            }
            doodle.setTracingBitmap(bmp, uri.toString())
            btnTracing.text = getString(R.string.tool_tracing) + " · " +
                getString(R.string.tracing_unlocked)
            // 选完立刻把操作菜单弹出来，不用用户再点一次
            showTracingMenu()
        }
    }

    /** 描画层的操作菜单：锁定 / 透明度 / 移除。 */
    private fun showTracingMenu() {
        val locked = doodle.isTracingLocked()
        val items = arrayOf(
            getString(
                if (locked) R.string.tracing_action_unlock
                else R.string.tracing_action_lock
            ),
            getString(R.string.tracing_action_alpha),
            getString(R.string.tracing_action_remove)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.tracing_menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleTracingLock()
                    1 -> showTracingAlphaDialog()
                    2 -> removeTracing()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleTracingLock() {
        if (doodle.isTracingLocked()) {
            doodle.unlockTracing()
            btnTracing.text = getString(R.string.tool_tracing) + " · " +
                getString(R.string.tracing_unlocked)
            toast(getString(R.string.tracing_unlocked))
        } else {
            doodle.lockTracing()
            btnTracing.text = getString(R.string.tool_tracing) + " · " +
                getString(R.string.tracing_locked)
            toast(getString(R.string.tracing_locked))
        }
    }

    private fun showTracingAlphaDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, (8 * p / 20), p, 0)
        }
        container.addView(android.widget.SeekBar(this).apply {
            max = 235
            progress = (doodle.getTracingAlpha() - 20).coerceIn(0, 235)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    doodle.setTracingAlpha(value + 20)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.tracing_hint)
            textSize = 11f
            setTextColor(getColor(R.color.hint_on_paper))
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.tracing_action_alpha)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun removeTracing() {
        doodle.removeTracing()
        btnTracing.text = getString(R.string.tool_tracing)
    }

    // ---------- 缩放 ----------

    /**
     * 缩放。
     *
     * 双指手势直接作用在画布上，这个按钮负责"回到原始大小"
     * 以及告诉用户当前放大倍数 —— 放大了却不知道、
     * 想缩回去又找不到入口是很常见的困惑。
     */
    private fun setupZoom() {
        // 手指缩放时实时更新提示，用户才知道自己放大了
        doodle.onViewportChanged = { updateZoomHint() }

        btnZoom.setOnClickListener {
            if (doodle.isZoomed()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.zoom_title)
                    .setMessage(
                        getString(R.string.zoom_current, doodle.getViewScale()) +
                            "\n\n" + getString(R.string.zoom_hint)
                    )
                    .setPositiveButton(R.string.zoom_reset) { _, _ ->
                        doodle.resetViewport()
                        updateZoomHint()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.zoom_title)
                    .setMessage(getString(R.string.zoom_hint))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** 放大时显示一个小提示，告诉用户当前处于放大状态。 */
    private fun updateZoomHint() {
        tvZoomHint.visibility =
            if (doodle.isZoomed()) View.VISIBLE else View.GONE
    }

    // ---------- 贴纸 ----------

    private fun setupSticker() {
        btnSticker.setOnClickListener {
            // 进入贴纸模式，让触摸优先作用于贴纸
            doodle.setStickerMode(true)
            StickerPanel.show(
                this,
                onPickFromGallery = { pickImageForCutout() },
                onPickSticker = { placeSticker(it) }
            )
        }

        btnStickerDelete.setOnClickListener {
            doodle.removeSelectedSticker()
        }

        /*
         * 锁定贴纸。
         *
         * 用户的原话："再进行绘制会点到这个贴图，我建议增加一下锁定位置的小按钮。"
         * 贴完之后点一下锁住，这张贴纸就不再拦触摸，可以放心在上面写字。
         */
        btnStickerLock.setOnClickListener {
            val locked = doodle.toggleSelectedStickerLock()
            when (locked) {
                true -> toast(getString(R.string.sticker_locked))
                false -> toast(getString(R.string.sticker_unlocked))
                null -> Unit
            }
        }

        // 选中贴纸时显示删除按钮，取消选中时隐藏
        doodle.onStickerSelected = { has ->
            btnStickerDelete.visibility = if (has) View.VISIBLE else View.GONE
            btnStickerLock.visibility = if (has) View.VISIBLE else View.GONE
            if (has) refreshLockButtonLabel()
        }
    }

    /** 锁定按钮的文案跟着当前状态走：未锁显示「锁定」，已锁显示「解锁」。 */
    private fun refreshLockButtonLabel() {
        btnStickerLock.text = when (doodle.isSelectedStickerLocked()) {
            true -> getString(R.string.sticker_unlock)
            else -> getString(R.string.sticker_lock)
        }
    }

    /** 从相册选图，用于抠贴纸。 */
    private fun pickImageForCutout() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        pickImagePicker.launch(intent)
    }

    /**
     * 相册选择。
     *
     * 和 [cutoutPicker] 分两层：这一层只负责拿到 URI，
     * 拿到之后立刻转交给抠图页。分开是因为抠图页要单独启动、
     * 也要能独立接收结果（用户在抠图页里可能反复改）。
     */
    private val pickImagePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        cutoutPicker.launch(
            Intent(this, CutoutActivity::class.java)
                .putExtra(CutoutActivity.EXTRA_URI, uri.toString())
        )
    }

    /**
     * 相册选图 → 交给抠图页 → 拿回抠好的位图。
     *
     * 分两步而不是"选完直接自动抠"：用户必须能**自己指哪儿删哪儿**。
     * 上一版是选完就出结果、中间没法干预，实测"几乎无法使用"。
     */
    private val cutoutPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val cut = CutoutActivity.takeResult()
            ?: return@registerForActivityResult
        saveAndPlace(cut)
    }

    /**
     * 把抠好的位图存进贴纸库，并立刻贴到画布上。
     *
     * 存库是为了以后能重复用、也能再贴到别的纸条上；
     * 立刻贴上去则省掉用户"再去贴纸库里找一遍"这一步。
     */
    private fun saveAndPlace(cut: Bitmap) {
        lifecycleScope.launch {
            val sticker = withContext(Dispatchers.IO) {
                StickerStore.add(this@ComposeActivity, cut, Sticker.Origin.OWN)
            }
            cut.recycle()

            if (sticker == null) {
                toast(getString(R.string.sticker_cut_failed))
                return@launch
            }

            val bmp = withContext(Dispatchers.IO) { StickerStore.loadBitmap(sticker, 512) }
            if (bmp != null) {
                doodle.setStickerMode(true)
                doodle.addSticker(sticker.id, bmp)
                toast(getString(R.string.sticker_cut_done))
            }
        }
    }

    /** 把贴纸库里的一张贴到画布上。 */
    private fun placeSticker(sticker: Sticker) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { StickerStore.loadBitmap(sticker, 512) }
            if (bmp == null) {
                toast(getString(R.string.sticker_cut_failed))
                return@launch
            }
            doodle.setStickerMode(true)
            doodle.addSticker(sticker.id, bmp)
            toast(getString(R.string.sticker_added))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    /**
     * 把画布上的贴纸编码成发送用的 JSON。
     *
     * 每张贴纸包含：位置/大小/旋转（相对值）+ 图片 base64。
     * 图片**单独编码**而不是和手写笔迹合成 ——
     * 接收方拿到后能把贴纸单独取下来。
     *
     * 返回 null 表示没有贴纸（不往请求里塞空数组，省点带宽）。
     */
    private fun encodeStickersForSend(): org.json.JSONArray? {
        val placed = doodle.exportPlacedStickers()
        if (placed.isEmpty()) return null

        val arr = org.json.JSONArray()
        placed.forEach { p ->
            val sticker = StickerStore.get(this, p.stickerId) ?: return@forEach
            val bmp = StickerStore.loadBitmap(sticker, 512) ?: return@forEach

            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            bmp.recycle()

            arr.put(
                org.json.JSONObject()
                    .put("id", p.stickerId)
                    .put("cx", p.cx.toDouble())
                    .put("cy", p.cy.toDouble())
                    .put("widthRatio", p.widthRatio.toDouble())
                    .put("rotation", p.rotation.toDouble())
                    .put("data", b64)
                    .put("fromName", sticker.fromName ?: AppConfig.nickname)
            )
        }
        return if (arr.length() == 0) null else arr
    }

    // ---------- 发送 ----------

    private fun send() {
        if (sending) return

        val text = input.text?.toString().orEmpty().trim()

        sending = true
        findViewById<MaterialButton>(R.id.btnSend).isEnabled = false

        // 用用户显式选中的收件人，而不是隐式的"最后一个配对的人"
        val target = recipient

        lifecycleScope.launch {
            // exportBitmap 里有逐像素扫描（找笔迹范围用于裁边），
            // 在 1064x1884 的画布上是一百多万次操作。
            // 之前它跑在主线程，每次点发送都会卡一下 —— 必须挪到后台。
            val doodleBitmap = withContext(Dispatchers.Default) {
                doodle.exportBitmap()
            }

            // 只贴了贴纸、没写字也没画线的纸条同样是有内容的，不能拦下来
            val stickerCount = doodle.placedStickerCount()
            if (text.isEmpty() && doodleBitmap == null && stickerCount == 0) {
                sending = false
                findViewById<MaterialButton>(R.id.btnSend).isEnabled = true
                toast(getString(R.string.toast_empty))
                return@launch
            }

            // 先编码再落盘：saveDoodle 会回收位图，顺序反了会拿到已回收的对象
            val doodleBase64 = withContext(Dispatchers.Default) {
                doodleBitmap?.let { encodePng(it) }
            }

            /*
             * 贴纸单独编码。
             *
             * 注意：贴纸**不烤进 doodleBase64**，而是独立一份数据。
             * 这样接收方能把贴纸单独取下来收藏、或再贴到别的纸条上。
             * 如果烘焙进图片，收到就只是一张死图，贴纸的流转玩法就没了。
             */
            val stickersJson = withContext(Dispatchers.Default) {
                encodeStickersForSend()
            }

            val localPath = withContext(Dispatchers.IO) {
                doodleBitmap?.let { NoteStore.saveDoodle(this@ComposeActivity, it) }
            }

            // 本地先落地：自己的桌面立刻就能看到写出来的内容
            NoteStore.write(
                this@ComposeActivity,
                Note(
                    text = text,
                    doodlePath = localPath,
                    senderName = AppConfig.nickname.ifBlank { getString(R.string.default_sender) },
                    createdAt = System.currentTimeMillis(),
                    paperStyle = paperStyle,
                    // 方向直接取设备当前实际姿态，不缓存到成员变量 ——
                    // 缓存的话旋转后就变成旧值，"横着画的纸条被标成竖版"
                    landscape = isLandscape(),
                    /*
                     * 本地这份也要带上贴纸。
                     *
                     * 桌面小部件读的就是这张纸条，缺了贴纸的话
                     * 自己桌面上看到的是"没贴贴纸的版本"，
                     * 而对方收到的却有 —— 两边不一致，看起来像 bug。
                     */
                    stickers = localStickers()
                )
            )
            NoteWidgetProvider.refresh(this@ComposeActivity)

            /*
             * 关键改动：网络上传不再阻塞界面。
             *
             * 以前要等 POST 返回才 finish()，网络一慢用户就得干等，
             * 看起来就像"点了发送没反应"。
             *
             * 现在本地一存好就立刻关页面，上传交给 AppScope 继续跑；
             * 上传结果通过 Toast 事后告知。
             */
            /*
             * 判断写在一起，而不是先算出 canSendRemote 再判两次。
             *
             * 拆开的话 Kotlin 没法把 `target != null` 的结论带进下面，
             * when 分支就得按可空类型写，凭空多出一堆兜底代码。
             */
            if (AppConfig.isRegistered && target != null) {
                val token = AppConfig.token
                if (token != null) {
                    AppScope.launch {
                        val result = runCatching {
                            when (target) {
                                is Recipient.Person -> Api.sendNote(
                                    token, target.userId, text, doodleBase64,
                                    paperStyle, isLandscape(), stickersJson
                                )

                                is Recipient.Group -> Api.sendGroupNote(
                                    token, target.groupId,
                                    if (target.mode == Recipient.Group.Mode.CHAIN) "chain" else "random",
                                    text, doodleBase64, paperStyle, isLandscape(), stickersJson
                                )
                            }
                        }
                        val msg = if (result.isSuccess) {
                            when (target) {
                                is Recipient.Person -> getString(R.string.sent_remote)
                                is Recipient.Group ->
                                    if (target.mode == Recipient.Group.Mode.CHAIN) {
                                        getString(R.string.sent_group_chain)
                                    } else {
                                        getString(R.string.sent_group_random)
                                    }
                            }
                        } else {
                            getString(
                                R.string.sent_failed_kept,
                                result.exceptionOrNull()?.message.orEmpty()
                            )
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                // 没有选收件人（或没注册）：只贴在自己桌面，明确告知用户
                toast(getString(R.string.sent_local))
            }

            finish()
        }
    }

    /** 涂鸦转 PNG 再转 base64。不回收位图——随后还要落盘，回收交给 saveDoodle。 */
    private fun encodePng(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 本地存档用的贴纸列表。
     *
     * 和发送用的那份（[encodeStickersForSend]）区别在于：
     * 这里**不带 base64 图片数据**，只留贴纸库里的路径引用 ——
     * 自己这台设备上图片本来就在库里，再抄一份 base64 纯属浪费存储。
     */
    private fun localStickers(): List<com.notedrop.demo.data.NoteSticker> =
        doodle.exportPlacedStickers().mapNotNull { p ->
            val sticker = StickerStore.get(this, p.stickerId) ?: return@mapNotNull null
            com.notedrop.demo.data.NoteSticker(
                id = p.stickerId,
                cx = p.cx,
                cy = p.cy,
                widthRatio = p.widthRatio,
                rotation = p.rotation,
                localPath = sticker.path,
                fromName = sticker.fromName ?: AppConfig.nickname
            )
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 当前是否处于横屏。 */
    private fun isLandscape(): Boolean =
        resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    companion object {
        private const val STATE_PAPER = "compose_paper_style"

        /** 横屏工具栏是窄竖条，颜色点排两列。 */
        private const val COLUMNS_LANDSCAPE = 2

        /**
         * 抽屉收起时的高度。
         *
         * 30dp = 把手本身的高度。留着它是因为**用户需要知道这里能拉开** ——
         * 完全不显示的话，没人会想到屏幕底下还藏着一排工具。
         */
        private const val DRAWER_CLOSED_DP = 30

        /**
         * 抽屉展开时的高度。
         *
         * 展开后是四行：
         *   工具（画笔/橡皮/颜色/粗细）  44dp
         *   贴纸 / 描画 / 缩放           44dp
         *   纸张 / 撤销                  44dp
         *   清空 / 发送                  46dp（含上方 6dp 间距）
         * 加上内容层上下各 2dp / 10dp 的留白，一共约 196dp。
         *
         * 取 205dp 留一点余量，让**发送键完整露出来** ——
         * 如果展开后还要再滑一下才能点发送，那这个抽屉就是失败的。
         * 内容层外面套了 ScrollView，极端情况下（大字体）仍可滚动兜底。
         *
         * 再高就吃画布了 —— 这个页面的第一优先级永远是画布。
         */
        private const val DRAWER_OPEN_DP = 205

        /*
         * 「再贴到新纸条上」的入参。
         *
         * 从收件箱藏起来一张贴纸后，带着它的 id 和当初在纸上的位置
         * 进编辑页 —— 位置一起带过来，用户看到的是"这张贴纸刚贴好的样子"，
         * 而不是堆在正中间等他重新摆。
         */
        const val EXTRA_RESTICK_ID = "restick_sticker_id"
        const val EXTRA_RESTICK_CX = "restick_cx"
        const val EXTRA_RESTICK_CY = "restick_cy"
        const val EXTRA_RESTICK_W = "restick_width_ratio"
        const val EXTRA_RESTICK_ROT = "restick_rotation"
    }
}
