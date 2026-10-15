package com.notedrop.demo.net

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 本机身份与服务器地址。
 *
 * Demo 阶段用匿名身份：首次打开自动注册一个 userId + token 存本地。
 * 正式版应换成账号体系（手机号/微信登录），但存储结构可以不变。
 */
object AppConfig {

    private const val PREF = "notedrop_config"
    private const val KEY_SERVER = "server_url"
    private const val KEY_USER = "user_id"
    private const val KEY_TOKEN = "token"
    private const val KEY_NICK = "nickname"
    private const val KEY_USERNAME = "username"
    private const val KEY_PEER = "last_peer_id"

    /**
     * 默认服务器地址。
     *
     * 走 nginx 反代并复用已有的 TLS 证书，所以是 https，
     * Android 不需要额外放开明文流量。
     *
     * ## 部署前请改成你自己的地址
     *
     * 这里刻意留成示例域名，不是真实部署地址 ——
     * 开源仓库里写死生产域名，别人拿到会直接把流量打到我的服务器上，
     * 而且我改地址就得动代码。改成自己的域名（或 IP）即可。
     *
     * 也可以不改代码：App 的「连接设置」页里能手动填服务器地址，
     * 填过的值会存进本地配置并优先生效。
     */
    const val DEFAULT_SERVER = "https://example.com/notedrop"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    var serverUrl: String = DEFAULT_SERVER
        private set

    var userId: String? = null
        private set

    var token: String? = null
        private set

    var nickname: String = ""
        private set

    /**
     * 登录用户名。
     *
     * 和 nickname 分开存：username 是账号标识（不可改），
     * nickname 只是显示名（随便改）。之前混在一起，
     * 导致改个名字就等于换了个人。
     */
    var username: String = ""
        private set

    /**
     * 最近一次发送对象。
     * 目前 UI 只选了"最后一个配对的人"，等做多对多聊天列表时把它换成会话选择。
     */
    var lastPeerId: String? = null
        private set

    /** App 启动时调用，把持久化的配置读进内存。 */
    fun load(context: Context) {
        val p = prefs(context)
        serverUrl = p.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        userId = p.getString(KEY_USER, null)
        token = p.getString(KEY_TOKEN, null)
        nickname = p.getString(KEY_NICK, "").orEmpty()
        username = p.getString(KEY_USERNAME, "").orEmpty()
        lastPeerId = p.getString(KEY_PEER, null)
    }

    fun setServer(context: Context, url: String) {
        serverUrl = url.trim().trimEnd('/')
        prefs(context).edit().putString(KEY_SERVER, serverUrl).apply()
    }

    fun saveIdentity(
        context: Context,
        id: String,
        tk: String,
        nick: String,
        user: String = ""
    ) {
        userId = id
        token = tk
        nickname = nick
        if (user.isNotBlank()) username = user
        prefs(context).edit()
            .putString(KEY_USER, id)
            .putString(KEY_TOKEN, tk)
            .putString(KEY_NICK, nick)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    /** 退出登录：清掉身份，但保留服务器地址。 */
    fun signOut(context: Context) {
        userId = null
        token = null
        nickname = ""
        username = ""
        lastPeerId = null
        prefs(context).edit()
            .remove(KEY_USER)
            .remove(KEY_TOKEN)
            .remove(KEY_NICK)
            .remove(KEY_USERNAME)
            .remove(KEY_PEER)
            .apply()
    }

    fun setNickname(context: Context, nick: String) {
        nickname = nick
        prefs(context).edit().putString(KEY_NICK, nick).apply()
    }

    fun setLastPeer(context: Context, peerId: String?) {
        lastPeerId = peerId
        prefs(context).edit().putString(KEY_PEER, peerId).apply()
    }

    val isRegistered: Boolean get() = !userId.isNullOrBlank() && !token.isNullOrBlank()

    /** 没有配置昵称时给一个默认值，避免服务端显示空名。 */
    fun suggestedNickname(): String = "纸条用户${UUID.randomUUID().toString().take(4)}"
}
