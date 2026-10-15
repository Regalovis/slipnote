package com.notedrop.demo.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.notedrop.demo.R
import com.notedrop.demo.net.Api
import com.notedrop.demo.net.AppConfig
import com.notedrop.demo.net.NoteSocketService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 设置与配对页。
 *
 * 三件事：配置服务器地址、注册身份、用邀请码配对。
 * 配对支持两种方式：生成一个码发给对方，或者双方输入同一个自定义数字。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etServer: EditText
    private lateinit var etNickname: EditText
    private lateinit var etCode: EditText
    private lateinit var tvIdentity: TextView
    private lateinit var tvStatus: TextView
    private lateinit var peerContainer: LinearLayout
    private lateinit var etGroupName: EditText
    private lateinit var groupContainer: LinearLayout
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etNewNickname: EditText
    private lateinit var rowNickname: android.view.View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        AppConfig.load(this)

        findViewById<android.view.View>(R.id.root).applySystemBarInsets()

        etServer = findViewById(R.id.et_server)
        etNickname = findViewById(R.id.et_nickname)
        etCode = findViewById(R.id.etCode)
        tvIdentity = findViewById(R.id.tv_identity)
        tvStatus = findViewById(R.id.tv_conn_status)
        peerContainer = findViewById(R.id.peer_container)
        etGroupName = findViewById(R.id.etGroupName)
        groupContainer = findViewById(R.id.group_container)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etNewNickname = findViewById(R.id.etNewNickname)
        rowNickname = findViewById(R.id.row_nickname)

        etServer.setText(AppConfig.serverUrl)
        etNickname.setText(AppConfig.nickname)

        findViewById<MaterialButton>(R.id.btn_save_server).setOnClickListener { saveServer() }
        findViewById<MaterialButton>(R.id.btn_register).setOnClickListener { register() }
        findViewById<MaterialButton>(R.id.btn_login).setOnClickListener { login() }
        findViewById<MaterialButton>(R.id.btn_change_nickname).setOnClickListener { changeNickname() }
        findViewById<MaterialButton>(R.id.btn_create_code).setOnClickListener { createCode() }
        findViewById<MaterialButton>(R.id.btn_join_code).setOnClickListener { joinByCode() }
        findViewById<MaterialButton>(R.id.btn_create_group).setOnClickListener { createGroup() }
        findViewById<MaterialButton>(R.id.btn_join_group).setOnClickListener { joinGroup() }

        refreshIdentity()
        reloadAll()
    }

    // ---------- 群组 ----------

    /**
     * 创建群组。
     *
     * 群名留空时由服务端兜底成"纸条群" —— 建群不该被一个输入框拦住。
     * 创建成功后把邀请码显示在输入框里，方便直接复制发给朋友。
     */
    private fun createGroup() {
        if (!requireRegistered()) return
        val name = etGroupName.text?.toString().orEmpty().trim()

        runAsync(getString(R.string.working)) {
            val res = Api.createGroup(AppConfig.token!!, name)
            val code = res.getString("code")
            val groupName = res.getString("name")
            withContext(Dispatchers.Main) {
                etGroupName.setText("")
                // 把邀请码填进输入框，用户直接复制就能发给朋友
                etCode.setText(code)
                toast(getString(R.string.group_created, code))
                tvStatus.text = getString(R.string.group_code_ready, groupName, code)
            }
            // 建群后列表要更新，否则新群不显示
            reloadAll()
            null
        }
    }

    /** 用邀请码加入群组。复用配对码那个输入框，用户不用切来切去。 */
    private fun joinGroup() {
        if (!requireRegistered()) return
        val code = etCode.text?.toString().orEmpty().trim()
        if (code.isBlank()) {
            toast(getString(R.string.err_need_code))
            return
        }

        runAsync(getString(R.string.working)) {
            val res = Api.joinGroup(AppConfig.token!!, code)
            withContext(Dispatchers.Main) {
                etCode.setText("")
                toast(getString(R.string.group_joined, res.getString("name")))
            }
            reloadAll()
            null
        }
    }

    /** 拉取已加入的群组并渲染。 */
    private fun refreshGroups() {
        runAsync(null) {
            val res = runCatching { Api.groups(AppConfig.token!!) }.getOrNull()
                ?: return@runAsync null
            val arr = res.optJSONArray("groups") ?: return@runAsync null
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }

            withContext(Dispatchers.Main) {
                groupContainer.removeAllViews()
                if (list.isEmpty()) {
                    groupContainer.addView(hintText(getString(R.string.no_groups)))
                } else {
                    groupContainer.addView(hintText(getString(R.string.group_count, list.size)))
                    list.forEach { g -> groupContainer.addView(buildGroupRow(g)) }
                }
            }
            null
        }
    }

    /**
     * 一行 = 一个群。
     *
     * 显示群名 + 人数，并且**把邀请码也显示出来** ——
     * 建群的人往往需要反复把码发给新朋友，
     * 每次都要重新生成一遍很烦，展示出来更方便。
     */
    private fun buildGroupRow(group: JSONObject): android.view.View {
        val name = group.optString("name", "群组")
        val code = group.optString("code", "")
        val count = group.optInt("memberCount", 0)
        val d = resources.displayMetrics.density

        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(
                (12 * d).toInt(), (14 * d).toInt(),
                (12 * d).toInt(), (14 * d).toInt()
            )
        }

        row.addView(TextView(this).apply {
            text = getString(R.string.group_members, name, count)
            textSize = 15f
            setTextColor(getColor(R.color.paper_ink))
        })

        row.addView(TextView(this).apply {
            // 只显示邀请码，群名上面一行已经有了
            text = getString(R.string.group_code_only, code)
            textSize = 12f
            setTextColor(getColor(R.color.ink_secondary))
            setPadding(0, (4 * d).toInt(), 0, 0)
        })

        return row
    }

    private fun hintText(message: String) = TextView(this).apply {
        val d = resources.displayMetrics.density
        text = message
        textSize = 13f
        setTextColor(getColor(R.color.hint_on_paper))
        setPadding(
            (12 * d).toInt(), (12 * d).toInt(),
            (12 * d).toInt(), (12 * d).toInt()
        )
    }

    private fun saveServer() {
        val url = etServer.text?.toString().orEmpty().trim()
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            toast(getString(R.string.err_bad_server))
            return
        }
        AppConfig.setServer(this, url)
        toast(getString(R.string.server_saved))
    }

    /** 注册身份：拿一个 token 并存本地，后续所有请求用它认证。 */
    /**
     * 注册账号。
     *
     * 需要用户名 + 密码。用户名是稳定标识，之后改昵称不影响账号，
     * 所以配对关系和收到的纸条都能留住 —— 这是上一版最大的问题：
     * 那时候"注册"每次都新发一个 userId，换个名字等于换个人。
     */
    private fun register() {
        val username = etUsername.text?.toString().orEmpty().trim()
        val password = etPassword.text?.toString().orEmpty()
        val nick = etNickname.text?.toString().orEmpty().trim().ifBlank { username }

        if (username.isBlank() || password.isBlank()) {
            toast(getString(R.string.err_need_username))
            return
        }

        runAsync(getString(R.string.working)) {
            val res = Api.registerAccount(username, password, nick)
            AppConfig.saveIdentity(
                this,
                res.getString("userId"),
                res.getString("token"),
                res.getString("nickname"),
                res.optString("username")
            )
            NoteSocketService.start(this)
            reloadAll()
            withContext(Dispatchers.Main) {
                toast(getString(R.string.register_ok, username))
            }
            null
        }
    }

    /**
     * 登录已有账号。
     *
     * 登录后拿回原来的 userId，所以历史配对、群组、收件箱全都回来了。
     */
    private fun login() {
        val username = etUsername.text?.toString().orEmpty().trim()
        val password = etPassword.text?.toString().orEmpty()
        if (username.isBlank() || password.isBlank()) {
            toast(getString(R.string.err_need_username))
            return
        }

        runAsync(getString(R.string.working)) {
            val res = Api.login(username, password)
            AppConfig.saveIdentity(
                this,
                res.getString("userId"),
                res.getString("token"),
                res.getString("nickname"),
                res.optString("username")
            )
            NoteSocketService.start(this)
            reloadAll()
            withContext(Dispatchers.Main) {
                toast(getString(R.string.login_ok, res.getString("nickname")))
            }
            null
        }
    }

    /**
     * 改显示名。
     *
     * 只改 nickname，userId 不动 —— 配对、群组、纸条全部保留。
     */
    private fun changeNickname() {
        if (!requireRegistered()) return
        val nick = etNewNickname.text?.toString().orEmpty().trim()
        if (nick.isBlank()) return

        runAsync(getString(R.string.working)) {
            Api.changeNickname(AppConfig.token!!, nick)
            AppConfig.setNickname(this, nick)
            withContext(Dispatchers.Main) {
                etNewNickname.setText("")
                toast(getString(R.string.nickname_changed, nick))
            }
            null
        }
    }

    /** 生成邀请码，展示出来给对方输入。 */
    private fun createCode() {
        if (!requireRegistered()) return
        // 输入框里填了数字就用自定义码，实现"双方约定同一个数字"
        val custom = etCode.text?.toString().orEmpty().trim()

        runAsync(getString(R.string.working)) {
            val res = Api.createPairCode(AppConfig.token!!, custom.ifBlank { null })
            val code = res.getString("code")
            withContext(Dispatchers.Main) {
                etCode.setText(code)
                tvStatus.text = getString(R.string.code_ready, code)
                toast(getString(R.string.code_ready, code))
            }
            null
        }
    }

    /** 用邀请码加入配对。 */
    private fun joinByCode() {
        if (!requireRegistered()) return
        val code = etCode.text?.toString().orEmpty().trim()
        if (code.isBlank()) {
            toast(getString(R.string.err_need_code))
            return
        }

        runAsync(getString(R.string.working)) {
            val res = Api.joinPair(AppConfig.token!!, code)
            AppConfig.setLastPeer(this, res.getString("peerId"))
            withContext(Dispatchers.Main) {
                toast(getString(R.string.paired_ok, res.getString("peerNickname")))
            }
            reloadAll()
            null
        }
    }

    private fun requireRegistered(): Boolean {
        if (!AppConfig.isRegistered) {
            toast(getString(R.string.err_need_register))
            return false
        }
        return true
    }

    private fun refreshIdentity() {
        tvIdentity.text = if (AppConfig.isRegistered) {
            getString(
                R.string.identity_logged_in,
                AppConfig.nickname,
                AppConfig.username.ifBlank { "—" },
                AppConfig.userId
            )
        } else {
            getString(R.string.identity_none)
        }
        // 已登录才显示改昵称入口
        rowNickname.visibility = if (AppConfig.isRegistered) android.view.View.VISIBLE
        else android.view.View.GONE
    }

    /** 拉取配对列表并渲染成可点击的"选择发送对象"。 */
    private fun refreshPeers() {
        runAsync(null) {
            val res = runCatching { Api.peers(AppConfig.token!!) }.getOrNull() ?: return@runAsync null
            val arr = res.optJSONArray("peers") ?: return@runAsync null
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }

            withContext(Dispatchers.Main) {
                peerContainer.removeAllViews()
                if (list.isEmpty()) {
                    /*
                     * 提示文字放进容器内部，而不是写 tvStatus。
                     *
                     * tvStatus 是页面级的共享状态栏，同伴和群组两个区块
                     * 都会去写它，后执行的会把前面的覆盖掉 ——
                     * 用户就会看到"没有同伴"和"已加入 2 个群组"反复闪。
                     * 各自写在各自的容器里，两块互不干扰。
                     */
                    peerContainer.addView(hintText(getString(R.string.no_peers)))
                } else {
                    peerContainer.addView(hintText(getString(R.string.peer_count, list.size)))
                    list.forEach { peer -> peerContainer.addView(buildPeerRow(peer)) }
                }
            }
            null
        }
    }

    /** 一行 = 一个同伴，点击后设为默认发送对象。 */
    private fun buildPeerRow(peer: JSONObject): android.view.View {
        val peerId = peer.optString("userId")
        val nickname = peer.optString("nickname", "未命名")
        val selected = peerId == AppConfig.lastPeerId
        val d = resources.displayMetrics.density

        val row = TextView(this).apply {
            text = if (selected) "●  $nickname" else "○  $nickname"
            textSize = 15f
            setTextColor(getColor(R.color.paper_ink))
            isClickable = true
            isFocusable = true
            setPadding(
                (12 * d).toInt(), (14 * d).toInt(),
                (12 * d).toInt(), (14 * d).toInt()
            )
            // 选中项给一层强调色浅底，比加粗边框更轻
            if (selected) setBackgroundResource(R.drawable.pill_bg)
            setOnClickListener {
                AppConfig.setLastPeer(this@SettingsActivity, peerId)
                toast(getString(R.string.peer_selected, nickname))
                refreshPeers()
            }
        }
        return row
    }

    /**
     * 统一的异步任务包装：后台执行 + 回到主线程 + 错误提示。
     *
     * 这里刻意**不做任何列表刷新**。
     *
     * 刷新用的 refreshPeers / refreshGroups 自身也走 runAsync，
     * 如果在收尾处再调它们，就会形成
     * runAsync → refreshPeers → runAsync → … 的无限递归：
     * 界面卡死，而且会不停打服务器。
     *
     * 所以由调用方在 block 里明确说明要不要刷新，
     * 谁也不隐式触发谁。
     */
    private fun runAsync(busyText: String?, block: suspend () -> Unit?) {
        if (busyText != null) toast(busyText)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                refreshIdentity()
            } catch (e: Exception) {
                toast(e.message ?: getString(R.string.err_unknown))
            }
        }
    }

    /**
     * 重新加载同伴与群组两个列表。用于注册、配对、建群、加群之后。
     *
     * 内部走 lifecycleScope.launch（默认主线程调度器），
     * 所以从任意线程调用都安全，调用方不用自己切线程。
     */
    private fun reloadAll() {
        if (!AppConfig.isRegistered) return
        refreshPeers()
        refreshGroups()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
