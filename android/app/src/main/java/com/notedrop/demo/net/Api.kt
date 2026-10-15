package com.notedrop.demo.net

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 与服务端通信的极简客户端。
 *
 * 没有引入 Retrofit：接口一共就 7 个，手写 OkHttp 反而更少依赖、更好排查。
 * WebSocket 也复用同一个 OkHttpClient，连接池和线程都统一。
 */
object Api {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 共享的连接器。WebSocket 也用这个，避免两个线程池。 */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // 长连接靠心跳保活，读超时给足
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    /** 由 AppConfig 提供，形如 http://192.168.1.10:8080 */
    fun baseUrl(): String = AppConfig.serverUrl

    fun wsUrl(): String = baseUrl()
        .replaceFirst("http://", "ws://")
        .replaceFirst("https://", "wss://")
        .trimEnd('/') + "/ws"

    class ApiException(message: String, val code: Int = 0) : IOException(message)

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null
    ): JSONObject {
        val builder = Request.Builder().url(baseUrl().trimEnd('/') + path)

        if (token != null) builder.header("Authorization", "Bearer $token")

        when (method) {
            "GET" -> builder.get()
            "POST" -> {
                val payload = (body ?: JSONObject()).toString()
                builder.post(payload.toRequestBody(JSON))
            }
            else -> throw IllegalArgumentException("unsupported method $method")
        }

        try {
            http.newCall(builder.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                if (!res.isSuccessful) {
                    throw ApiException(json.optString("error", "HTTP ${res.code}"), res.code)
                }
                return json
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("连接服务器失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- 具体接口 ----------

    /** 注册设备，返回 userId / token / nickname。 */
    fun register(nickname: String): JSONObject =
        request("POST", "/api/register", JSONObject().put("nickname", nickname))

    /** 生成邀请码。传入 customCode 可自定义 4~6 位数字。 */
    fun createPairCode(token: String, customCode: String? = null): JSONObject {
        val body = JSONObject()
        if (!customCode.isNullOrBlank()) body.put("code", customCode)
        return request("POST", "/api/pair/code", body, token)
    }

    /** 用邀请码配对。 */
    fun joinPair(token: String, code: String): JSONObject =
        request("POST", "/api/pair/join", JSONObject().put("code", code), token)

    /** 我的配对列表。 */
    fun peers(token: String): JSONObject = request("GET", "/api/peers", null, token)

    /**
     * 发送纸条。doodleBase64 为空表示只发文字。
     *
     * paperStyle 和 landscape 一起带上：接收方的小部件需要知道
     * 这张纸条是什么纸、什么方向，才能按正确的方式排版。
     */
    fun sendNote(
        token: String,
        toUserId: String,
        text: String,
        doodleBase64: String?,
        paperStyle: com.notedrop.demo.data.PaperStyle,
        landscape: Boolean,
        stickers: org.json.JSONArray? = null
    ): JSONObject {
        val body = JSONObject()
            .put("toUserId", toUserId)
            .put("text", text)
            .put("paperStyle", paperStyle.name.lowercase())
            .put("landscape", landscape)
        if (!doodleBase64.isNullOrBlank()) body.put("doodle", doodleBase64)
        stickers?.let { if (it.length() > 0) body.put("stickers", it) }
        return request("POST", "/api/notes", body, token)
    }

    /** 拉取未读纸条（离线补偿）。 */
    fun pending(token: String): JSONObject = request("GET", "/api/notes/pending", null, token)

    /** 健康检查，用于"测试连接"按钮。 */
    fun health(): JSONObject = request("GET", "/api/health")

    // ---------- 账号 ----------

    /** 注册账号。username 是稳定标识，换昵称不影响账号。 */
    fun registerAccount(username: String, password: String, nickname: String): JSONObject =
        request(
            "POST", "/api/auth/register",
            JSONObject()
                .put("username", username)
                .put("password", password)
                .put("nickname", nickname),
            token = null
        )

    /** 登录。返回 userId / token / nickname。 */
    fun login(username: String, password: String): JSONObject =
        request(
            "POST", "/api/auth/login",
            JSONObject().put("username", username).put("password", password),
            token = null
        )

    /** 改显示名。账号与配对关系都不受影响。 */
    fun changeNickname(token: String, nickname: String): JSONObject =
        request(
            "POST", "/api/auth/nickname",
            JSONObject().put("nickname", nickname),
            token
        )

    /** 当前账号信息。 */
    fun me(token: String): JSONObject = request("GET", "/api/auth/me", null, token)

    // ---------- 群组 ----------

    /** 创建群组，返回 groupId / name / code。 */
    fun createGroup(token: String, name: String): JSONObject =
        request("POST", "/api/groups", JSONObject().put("name", name), token)

    /** 用邀请码加入群组。 */
    fun joinGroup(token: String, code: String): JSONObject =
        request("POST", "/api/groups/join", JSONObject().put("code", code), token)

    /** 我加入的群组。 */
    fun groups(token: String): JSONObject = request("GET", "/api/groups", null, token)

    /**
     * 群内发纸条。
     *
     * mode = "chain"  ：按顺序传给下一位（A→B→C→D）
     * mode = "random" ：随机挑一位群友，双方匿名
     */
    fun sendGroupNote(
        token: String,
        groupId: String,
        mode: String,
        text: String,
        doodleBase64: String?,
        paperStyle: com.notedrop.demo.data.PaperStyle,
        landscape: Boolean,
        stickers: org.json.JSONArray? = null
    ): JSONObject {
        val body = JSONObject()
            .put("mode", mode)
            .put("text", text)
            .put("paperStyle", paperStyle.name.lowercase())
            .put("landscape", landscape)
        if (!doodleBase64.isNullOrBlank()) body.put("doodle", doodleBase64)
        stickers?.let { if (it.length() > 0) body.put("stickers", it) }
        return request("POST", "/api/groups/$groupId/notes", body, token)
    }
}
