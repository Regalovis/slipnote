package com.notedrop.demo.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.notedrop.demo.R
import com.notedrop.demo.data.FavoriteStore
import com.notedrop.demo.data.InboxStore
import com.notedrop.demo.data.Note
import com.notedrop.demo.data.NoteStore
import com.notedrop.demo.data.NoteSticker
import com.notedrop.demo.sticker.Sticker
import com.notedrop.demo.sticker.StickerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 纸条详情页：完整查看一张收到的纸条，并决定收藏或删除。
 *
 * 这是「打开 → 看 → 决定」流程里的"看"和"决定"两步。
 * 界面刻意做得像一张摊开的纸：纸张样式、方向都还原发送方的选择，
 * 让收纸条的人感受到对方当时是怎么写的。
 */
class NoteDetailActivity : AppCompatActivity() {

    private var noteId: String? = null
    private var favoritesMode = false

    /** 当前展示的纸条，收藏和删除都要用到。 */
    private var current: Note? = null

    private lateinit var paper: LinearLayout
    private lateinit var doodleView: NoteRenderView
    private lateinit var tvStickerHint: TextView
    private lateinit var textView: TextView
    private lateinit var metaView: TextView
    private lateinit var btnFav: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_detail)

        findViewById<View>(R.id.root).applySystemBarInsets()

        noteId = intent.getStringExtra(EXTRA_NOTE_ID)
        favoritesMode = intent.getBooleanExtra(EXTRA_FAVORITES_MODE, false)

        paper = findViewById(R.id.paper)
        doodleView = findViewById(R.id.doodle)
        tvStickerHint = findViewById(R.id.tvStickerHint)
        textView = findViewById(R.id.text)
        metaView = findViewById(R.id.meta)
        btnFav = findViewById(R.id.btn_fav)

        findViewById<MaterialButton>(R.id.btn_delete).setOnClickListener { confirmDelete() }
        btnFav.setOnClickListener { toggleFavorite() }

        // 点贴纸 → 弹出"取下来"的菜单
        doodleView.onStickerClick = { sticker -> showStickerMenu(sticker) }

        load()
    }

    /**
     * 载入纸条内容。
     *
     * 收藏夹里的条目要从 FavoriteStore 取，收件箱的从 InboxStore 取 ——
     * 两边是独立存储，同一张纸条在两个列表里的 id 并不相同。
     */
    private fun load() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadNote() }
            if (loaded == null) {
                Toast.makeText(this@NoteDetailActivity, R.string.note_gone, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            current = loaded
            bind(loaded)
        }
    }

    private fun loadNote(): Note? {
        val id = noteId
        return if (favoritesMode) {
            val item = FavoriteStore.list(this).firstOrNull { it.id == id } ?: return null
            Note(
                text = item.text,
                doodlePath = item.doodlePath,
                senderName = item.senderName,
                createdAt = item.createdAt,
                paperStyle = item.paperStyle,
                stickers = item.stickers
            )
        } else {
            val item = InboxStore.list(this).firstOrNull { it.id == id } ?: return null
            item.toNote()
        }
    }

    private fun bind(note: Note) {
        // 还原发送方选的纸张样式
        paper.setBackgroundResource(PaperBackground.resId(note.paperStyle))

        textView.visibility = if (note.hasText) View.VISIBLE else View.GONE
        textView.text = note.text

        val bmp: Bitmap? = NoteStore.decodeDoodle(note.doodlePath, 900)

        /*
         * 贴纸位图按 ID 加载。
         *
         * 贴纸内容存在贴纸库里（同一张贴纸可能被多张纸条引用），
         * 纸条上只记"哪几张、贴在哪"。所以这里要先查库拿图。
         */
        val stickerBitmaps = mutableMapOf<String, Bitmap>()
        note.stickers.forEach { s ->
            val sticker = s.localPath?.let { path ->
                StickerStore.list(this).firstOrNull { it.path == path }
            }
            val sb = sticker?.let { StickerStore.loadBitmap(it, 512) }
            if (sb != null) stickerBitmaps[s.id] = sb
        }

        if (bmp != null || stickerBitmaps.isNotEmpty()) {
            doodleView.visibility = View.VISIBLE
            doodleView.setContent(bmp, note.stickers, stickerBitmaps)
        } else {
            doodleView.visibility = View.GONE
        }

        // 有贴纸才提示"可以点它取下来"
        tvStickerHint.visibility =
            if (stickerBitmaps.isNotEmpty()) View.VISIBLE else View.GONE

        metaView.text = getString(
            R.string.note_meta,
            note.senderName,
            FavoriteStore.formatTime(note.createdAt)
        )
        refreshFavButton()
    }

    /**
     * 点中贴纸后的菜单：取下来收进贴纸库。
     *
     * 这是贴纸玩法的核心一步 —— 收到的贴纸可以变成自己的，
     * 再贴到写给别人的纸条上。
     */
    private fun showStickerMenu(sticker: NoteSticker) {
        AlertDialog.Builder(this)
            .setTitle(R.string.sticker_take_title)
            .setItems(
                arrayOf(
                    getString(R.string.sticker_take),
                    getString(R.string.sticker_restick)
                )
            ) { _, which ->
                if (which == 0) takeSticker(sticker) else restickToNewNote(sticker)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 把这张贴纸放进一张新纸条。
     *
     * ## 为什么要先"取下来"再开新页面
     *
     * 纸条上记的贴纸 id 是**发送方**的编号；在本地，这张贴纸是被复制成
     * 新文件、重新登记进贴纸库的，本地 id 是另一个。直接把它带到编辑页
     * 会导致发送时按 id 查不到、贴纸被静默丢掉。
     *
     * 所以这里走一遍和「收进贴纸库」完全相同的那套：
     * 按内容哈希去重后拿到**本地那条记录的 id**，再用它开编辑页。
     * 顺带也满足了用户的心理模型 —— 贴纸得先属于我，才能用。
     */
    private fun restickToNewNote(sticker: NoteSticker) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                takeStickerInternal(sticker)
            }
            val stickerId = when (result) {
                is TakeResult.Ok -> result.id
                TakeResult.Failed -> {
                    Toast.makeText(
                        this@NoteDetailActivity, R.string.sticker_take_failed, Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
            }
            startActivity(
                Intent(this@NoteDetailActivity, ComposeActivity::class.java)
                    .putExtra(ComposeActivity.EXTRA_RESTICK_ID, stickerId)
                    .putExtra(ComposeActivity.EXTRA_RESTICK_CX, sticker.cx)
                    .putExtra(ComposeActivity.EXTRA_RESTICK_CY, sticker.cy)
                    .putExtra(ComposeActivity.EXTRA_RESTICK_W, sticker.widthRatio)
                    .putExtra(ComposeActivity.EXTRA_RESTICK_ROT, sticker.rotation)
            )
            Toast.makeText(
                this@NoteDetailActivity, R.string.sticker_restick_copied, Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 把收到的贴纸收进自己的贴纸库。
     *
     * 「取下来」= 在贴纸库里新建一条**属于自己**的记录
     * （来源标记为 RECEIVED，带上原作者昵称）。
     *
     * 为什么要复制而不是直接复用原条目：
     * 原来的贴纸是跟着那张纸条来的，如果用户删了那张纸条，
     * 贴纸也会被一起清掉 —— 那就白"取下来"了。
     * 复制一份，收藏关系才独立、可靠。
     */
    private fun takeSticker(sticker: NoteSticker) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                takeStickerInternal(sticker)
            }

            val msg = when (outcome) {
                is TakeResult.Ok ->
                    if (outcome.createdNew) R.string.sticker_take_ok
                    else R.string.sticker_take_exists
                TakeResult.Failed -> R.string.sticker_take_failed
            }
            Toast.makeText(this@NoteDetailActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** 「收进贴纸库」的结果。[Ok.createdNew] 区分新收进来和早就收过了。 */
    private sealed interface TakeResult {
        data class Ok(val id: String, val createdNew: Boolean) : TakeResult
        data object Failed : TakeResult
    }

    /**
     * 把贴纸收进贴纸库，返回**本地贴纸库里那条记录的 id**。
     *
     * 已经收过就直接返回已有的那条 id（不重复复制文件）。
     * 失败返回 null。
     *
     * ## 为什么用内容哈希去重
     *
     * 不能用贴纸 id 比 —— 那是发送方的编号，不同人发来的可能撞号；
     * 也不能只看文件名 —— 收下来时会复制成新文件。
     * 用**图片内容的 md5** 最可靠：同样的图就是同一张，
     * 不管它来自哪张纸条、被复制过几次。
     */
    private fun takeStickerInternal(sticker: NoteSticker): TakeResult {
        val src = sticker.localPath ?: return TakeResult.Failed
        val sourceHash = md5Of(src) ?: return TakeResult.Failed

        val existing = StickerStore.list(this).firstOrNull { s ->
            s.origin == Sticker.Origin.RECEIVED && md5Of(s.path) == sourceHash
        }
        if (existing != null) return TakeResult.Ok(existing.id, createdNew = false)

        val bmp = android.graphics.BitmapFactory.decodeFile(src) ?: return TakeResult.Failed
        val saved = StickerStore.add(this, bmp, Sticker.Origin.RECEIVED, sticker.fromName)
        bmp.recycle()
        return saved?.let { TakeResult.Ok(it.id, createdNew = true) } ?: TakeResult.Failed
    }

    /**
     * 算文件的 md5。
     *
     * 用于判断"这张贴纸我是不是已经收藏过了" ——
     * 比文件名、比 id 都可靠，因为同样的图必然是同一个哈希。
     * 贴纸文件通常几十 KB，算哈希的开销可以忽略。
     */
    private fun md5Of(path: String): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("MD5")
        java.io.File(path).inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun refreshFavButton() {
        val note = current ?: return
        val saved = FavoriteStore.contains(this, note.text, note.createdAt)
        btnFav.text = getString(
            if (saved) R.string.action_favorited else R.string.action_favorite
        )
        // 已收藏的可以取消收藏，所以不 disable，只改文案
    }

    private fun toggleFavorite() {
        val note = current ?: return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                if (FavoriteStore.contains(this@NoteDetailActivity, note.text, note.createdAt)) {
                    FavoriteStore.removeByContent(this@NoteDetailActivity, note.text, note.createdAt)
                    false
                } else {
                    FavoriteStore.add(this@NoteDetailActivity, note)
                    true
                }
            }
            Toast.makeText(
                this@NoteDetailActivity,
                if (saved) R.string.favorited_toast else R.string.unfavorited_toast,
                Toast.LENGTH_SHORT
            ).show()
            refreshFavButton()
            // 从收藏夹进来时取消收藏，列表就少一条，直接退出更符合直觉
            if (favoritesMode && !saved) finish()
        }
    }

    private fun confirmDelete() {
        val label = if (favoritesMode) R.string.delete_fav_confirm else R.string.delete_confirm
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage(label)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> doDelete() }
            .show()
    }

    private fun doDelete() {
        val id = noteId ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (favoritesMode) {
                    FavoriteStore.remove(this@NoteDetailActivity, id)
                } else {
                    InboxStore.remove(this@NoteDetailActivity, id)
                }
            }
            Toast.makeText(this@NoteDetailActivity, R.string.deleted_toast, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_FAVORITES_MODE = "from_favorites"
    }
}
