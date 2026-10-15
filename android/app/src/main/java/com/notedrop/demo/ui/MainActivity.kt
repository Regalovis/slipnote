package com.notedrop.demo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.notedrop.demo.R
import com.notedrop.demo.data.FavoriteStore
import com.notedrop.demo.data.InboxStore
import com.notedrop.demo.data.Note
import com.notedrop.demo.data.NoteComposer
import com.notedrop.demo.data.NoteStore
import com.notedrop.demo.net.AppConfig
import com.notedrop.demo.net.NoteSocketService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页。
 *
 * 版面照用户给的参考图：**渐变头图 + 漂浮纸条 + 白色圆角上盖 + 紧凑图标排**。
 *
 * ## 这一版推翻的东西
 *
 * 上一版是"暖灰桌面 + 深蓝图标块 + 大标题 + 白卡 + 三行带图标的导航行"。
 * 那是早期系统应用的排版思路 —— 一屏全是文字，没有任何色域和形状，
 * 用户的原话是"这是一个水平的 UI 设计吗"。
 *
 * 现在头图占了上半屏，是一整块会流动的极光；纸条从卡片里"浮"出来；
 * 三行导航压成一行四个图标。
 *
 * ## 头图上那两张纸条分别是什么
 *
 * - **漂浮的大纸条**：最新收到（或自己写的）那张，手写内容直接画上去。
 *   它是整屏的视觉主角，占据参考图里"角色"的位置。
 * - **下面的列表**：更早的纸条，按时间倒序。
 *
 * 同一个来源不会出现两次：列表里会跳过已经在头图上展示的那张。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvHeroFrom: TextView
    private lateinit var tvHeroTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvConnection: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var ivDoodle: ImageView
    private lateinit var btnService: TextView
    private lateinit var listRecent: LinearLayout
    private lateinit var dotInbox: View

    /** 头图里的内容层，需要按状态栏高度下移。 */
    private lateinit var heroContent: LinearLayout

    /** 底部卡片，需要按导航栏高度增加下边距。 */
    private lateinit var sheet: LinearLayout

    /** Android 13+ 需要运行时授权通知，否则收到纸条不会有提醒。 */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 拒绝也能用，只是收不到提醒通知
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppConfig.load(this)

        /*
         * 首页的 inset 处理和别的页面不一样。
         *
         * 头图要**一直铺到屏幕顶部**（状态栏底下也是渐变色），
         * 所以不能给根布局加 padding —— 那会把整块渐变推下来，
         * 顶上露出一条底色。
         *
         * 正确做法是：渐变色块照常铺满，只把**里面的文字**下移一个状态栏高度，
         * 底部卡片的 padding 加上导航栏高度。分头处理，各管各的。
         */
        heroContent = findViewById(R.id.hero_content)
        sheet = findViewById(R.id.sheet)

        /*
         * 头图高度取屏幕的 52%，和原型的 `height: 52dvh` 对齐。
         *
         * 原型里同时有 `min-height: 400px` 兜底 ——
         * 小屏手机上 52% 会太矮，纸条就没地方漂了。
         * 这里用同样的双重限制：先按比例算，再夹到 [400dp, 520dp]。
         *
         * 上限 520dp 是防止平板上头图吃掉大半个屏幕 ——
         * 越大的屏，绝对留白本来就更充裕，不需要按比例一直放大。
         */
        findViewById<View>(R.id.hero).minimumHeight =
            (resources.displayMetrics.heightPixels * HERO_HEIGHT_RATIO)
                .toInt()
                .coerceIn(dp(400), dp(520))

        /*
         * 布局里的原始内边距要在这里读出来。
         *
         * 注意单位：XML 里写的是 dp，读出来已经是 px；
         * 而下面回调里的 inset 也是 px。两者直接相加才对。
         * 如果在这里写 "16" 这种字面量，加进去的就是 16 像素而不是 16dp，
         * 在高密度屏上会明显偏窄。
         */
        val heroPadTop = heroContent.paddingTop
        val sheetPadBottom = sheet.paddingBottom
        findViewById<View>(R.id.root).onSystemBarInsets { _, top, _, bottom ->
            heroContent.setPadding(
                heroContent.paddingLeft,
                heroPadTop + top,
                heroContent.paddingRight,
                heroContent.paddingBottom
            )
            sheet.setPadding(
                sheet.paddingLeft,
                sheet.paddingTop,
                sheet.paddingRight,
                sheetPadBottom + bottom
            )
        }

        /*
         * 状态栏图标改成浅色。
         *
         * 主题里默认是 windowLightStatusBar=true（深色图标），
         * 那是为白底页面设的。但首页顶上是深紫的极光渐变 ——
         * 深色图标压在上面几乎看不见，时间和电量会糊成一团。
         *
         * 只在这一个页面改：别的页面仍然是白底 + 深色图标。
         */
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false

        tvHeroFrom = findViewById(R.id.tv_hero_from)
        tvHeroTime = findViewById(R.id.tv_hero_time)
        tvStatus = findViewById(R.id.tv_status)
        tvConnection = findViewById(R.id.tv_connection)
        tvEmpty = findViewById(R.id.tv_empty)
        ivDoodle = findViewById(R.id.iv_current_doodle)
        btnService = findViewById(R.id.btn_service)
        listRecent = findViewById(R.id.list_recent)
        dotInbox = findViewById(R.id.dot_inbox)

        findViewById<View>(R.id.q_compose).setOnClickListener {
            startActivity(Intent(this, ComposeActivity::class.java))
        }
        findViewById<View>(R.id.q_inbox).setOnClickListener {
            startActivity(Intent(this, NoteListActivity::class.java))
        }
        findViewById<View>(R.id.q_fav).setOnClickListener {
            startActivity(
                Intent(this, NoteListActivity::class.java)
                    .putExtra(NoteListActivity.EXTRA_FAVORITES, true)
            )
        }
        findViewById<View>(R.id.q_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnService.setOnClickListener { reconnect() }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        AppConfig.load(this)
        ensureServiceRunning()
        render()
    }

    /**
     * 只要已经注册，就保证长连接在跑。
     *
     * 接收纸条是核心功能，不该依赖用户手动开启开关 ——
     * 之前做成按钮的结果就是纸条发得出去、收不进来，
     * 而且界面上完全看不出问题。
     */
    private fun ensureServiceRunning() {
        if (AppConfig.isRegistered) NoteSocketService.start(this)
    }

    private fun render() {
        val current = NoteStore.read(this)
        renderHero(current)

        tvConnection.text = when {
            !AppConfig.isRegistered -> getString(R.string.conn_unregistered)
            AppConfig.lastPeerId.isNullOrBlank() -> getString(R.string.conn_no_peer)
            else -> getString(R.string.conn_ready, AppConfig.nickname)
        }
        btnService.text = getString(R.string.action_start_service)

        /*
         * 列表要读两个存储 + 解码缩略图，全放后台。
         *
         * 之前这部分在主线程做，收件箱一多首页会明显卡一下才出内容。
         */
        lifecycleScope.launch {
            // 未读数也要读 JSON 文件，和列表一起放后台，
            // 别在主线程上碰磁盘
            val (rows, unread) = withContext(Dispatchers.IO) {
                loadRecent(current) to InboxStore.unreadCount(this@MainActivity)
            }
            renderRecent(rows, unread)
        }
    }

    // ---------- 头图 ----------

    /**
     * 头图上的主角：最新那张纸条。
     *
     * 有手写就把手写画在漂浮的纸上（这才是"纸条"的价值），
     * 没有手写就显示文字。两者都没有时纸上是一句提示，
     * 落款整段隐藏 —— 不出现"来自 暂无"这种占位文案。
     */
    private fun renderHero(note: Note?) {
        if (note == null) {
            tvStatus.text = getString(R.string.status_empty)
            tvStatus.visibility = View.VISIBLE
            ivDoodle.visibility = View.GONE
            tvHeroFrom.visibility = View.GONE
            tvHeroTime.visibility = View.GONE
            return
        }

        /*
         * 贴纸要和手写合成一张再显示。
         *
         * 这个位置只有一个 ImageView，而且它展示的是"别人看到的那一面"，
         * 不合成的话首页会显示一张没贴纸的纸条，和对方收到的对不上。
         */
        val doodle: Bitmap? = NoteStore.decodeDoodle(note.doodlePath, 640)
        val bmp: Bitmap? = NoteComposer.compose(this, doodle, note.stickers, 640)
        if (bmp !== doodle) doodle?.recycle()

        if (bmp != null) {
            ivDoodle.setImageBitmap(bmp)
            ivDoodle.visibility = View.VISIBLE
            // 有手写时文字让位：纸上已经写着东西了，再叠一行字会打架
            tvStatus.visibility = View.GONE
        } else {
            ivDoodle.visibility = View.GONE
            tvStatus.text = note.text.ifBlank { getString(R.string.status_empty) }
            tvStatus.visibility = View.VISIBLE
        }

        tvHeroFrom.text = getString(R.string.chip_from, note.senderName)
        tvHeroFrom.visibility = View.VISIBLE
        tvHeroTime.text = NoteStore.relativeTime(this, note.createdAt)
        tvHeroTime.visibility = View.VISIBLE
    }

    // ---------- 列表 ----------

    /** 列表里的一行。已经带上要显示的全部内容，构建 View 时不再碰磁盘。 */
    private data class RecentRow(
        val id: String,
        val senderName: String,
        val text: String,
        val hasDoodle: Boolean,
        val createdAt: Long,
        /** 这条是不是收藏夹里的。决定点击后进哪个详情页。 */
        val fromFavorites: Boolean
    )

    /**
     * 收集要显示的纸条。
     *
     * 三个来源合并：
     * 1. 收件箱 —— 收到的
     * 2. 收藏夹 —— 用户主动存下的
     * 3. 当前纸条 —— 自己刚写的那张（它不在收件箱里）
     *
     * 去重按「发送人 + 内容 + 时间」：同一张纸条可能既在收件箱里
     * 又被收藏过，那应该只出现一次。
     *
     * @param current 头图上已经展示的那张，列表里要跳过，避免一屏出现两次
     */
    private fun loadRecent(current: Note?): List<RecentRow> {
        val seen = mutableSetOf<String>()

        // 头图上那张先占位，后面的同名条目会被它挡掉
        if (current != null) {
            seen.add(dedupeKey(current.senderName, current.text, current.createdAt))
        }

        val out = mutableListOf<RecentRow>()

        InboxStore.list(this).forEach { item ->
            val key = dedupeKey(item.senderName, item.text, item.createdAt)
            if (!seen.add(key)) return@forEach
            out.add(
                RecentRow(
                    id = item.id,
                    senderName = item.senderName,
                    text = item.text,
                    hasDoodle = item.doodlePath != null,
                    createdAt = item.createdAt,
                    fromFavorites = false
                )
            )
        }

        FavoriteStore.list(this).forEach { item ->
            val key = dedupeKey(item.senderName, item.text, item.createdAt)
            if (!seen.add(key)) return@forEach
            out.add(
                RecentRow(
                    id = item.id,
                    senderName = item.senderName,
                    text = item.text,
                    hasDoodle = item.doodlePath != null,
                    createdAt = item.createdAt,
                    fromFavorites = true
                )
            )
        }

        return out.sortedByDescending { it.createdAt }.take(MAX_RECENT)
    }

    private fun dedupeKey(sender: String, text: String, createdAt: Long): String =
        "$sender|$createdAt|$text"

    private fun renderRecent(rows: List<RecentRow>, unread: Int) {
        listRecent.removeAllViews()
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        rows.forEach { row ->
            val view = inflater.inflate(R.layout.row_home_note, listRecent, false)
            view.setOnClickListener {
                startActivity(
                    Intent(this, NoteDetailActivity::class.java)
                        .putExtra(NoteDetailActivity.EXTRA_NOTE_ID, row.id)
                        .putExtra(NoteDetailActivity.EXTRA_FAVORITES_MODE, row.fromFavorites)
                )
            }

            val face = view.findViewById<TextView>(R.id.face)
            face.text = row.senderName.trim().take(1).ifBlank { "纸" }
            face.background = faceBackground(row.senderName)

            view.findViewById<TextView>(R.id.name).text = row.senderName
            view.findViewById<TextView>(R.id.msg).text = row.text.ifBlank {
                if (row.hasDoodle) getString(R.string.only_doodle_preview) else ""
            }
            listRecent.addView(view)
        }

        /*
         * 收件箱图标上的小圆点 = 有未读。
         *
         * 一开始我写成"列表非空就亮"，那等于永远亮着 ——
         * 一个永远亮着的提示点和不做提示是一样的。
         * 现在用"比上次打开收件箱更新的纸条数量"来判断。
         */
        dotInbox.visibility = if (unread > 0) View.VISIBLE else View.GONE
    }

    /**
     * 按名字给头像挑一个颜色。
     *
     * 用名字的哈希取模，保证**同一个人永远是同一个颜色** ——
     * 随机的话每次进首页颜色都在跳，那这个色块就没有识别意义了。
     *
     * 色相全部落在极光的色域里（紫→品红→蓝），
     * 和头图的渐变同源，所以整屏看起来是一套东西。
     */
    private fun faceBackground(name: String): GradientDrawable {
        val colors = intArrayOf(
            0xFF8E7BF0.toInt(), // 紫
            0xFFB06AE8.toInt(), // 紫品红
            0xFFE87BA8.toInt(), // 粉
            0xFF6E93F0.toInt(), // 蓝
            0xFF5BB8D9.toInt()  // 青
        )
        val index = ((name.hashCode() % colors.size) + colors.size) % colors.size
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colors[index])
        }
    }

    // ---------- 其他 ----------

    private fun reconnect() {
        if (!AppConfig.isRegistered) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        NoteSocketService.stop(this)
        NoteSocketService.start(this)
        tvConnection.text = getString(R.string.conn_reconnecting)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** dp → px。 */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /**
         * 首页列表最多显示几条。
         *
         * 首页是"扫一眼"的地方，不是档案库 —— 想看全部去收件箱。
         * 条数多了会把它压成第二个列表页，头图也就被挤出屏幕了。
         */
        const val MAX_RECENT = 6

        /** 头图占屏幕高度的比例，对应原型里的 `52dvh`。 */
        const val HERO_HEIGHT_RATIO = 0.52f

    }
}
