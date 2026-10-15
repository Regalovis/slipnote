package com.notedrop.demo.data

/**
 * 贴在纸条上的一张贴纸（随纸条一起传输的那份数据）。
 *
 * ## 为什么内容和位置分开
 *
 * 位置上：同一张贴纸可以被贴到多张纸条上，每次位置都不同，
 * 所以位置属于"这一次的摆放"，不属于贴纸本身。
 *
 * 内容上：[localPath] 和 [remoteBase64] 二选一 ——
 * - 本机编辑/接收到的纸条：贴纸已经落盘，用 [localPath]
 * - 正在发送或刚从网络拿到的：图片以 base64 内联，用 [remoteBase64]
 *
 * 这样同一条纸条在"本地已存"和"传输中"两种状态下都能表达，
 * 不用拆成两个模型。
 */
data class NoteSticker(
    /** 贴纸 ID。发送方生成，接收方取下来时用它去重。 */
    val id: String,

    /** 中心点相对坐标（0~1），相对纸条可绘制区域。 */
    val cx: Float,
    val cy: Float,

    /** 宽度占纸条宽度的比例。高度按图片原始比例自动算。 */
    val widthRatio: Float,

    /** 旋转角度（度）。 */
    val rotation: Float,

    /** 本机文件路径。本地已落盘的贴纸用这个。 */
    val localPath: String? = null,

    /** base64 编码的 PNG。网络传输时用这个。 */
    val remoteBase64: String? = null,

    /** 原始作者昵称，用于展示"这张贴纸来自谁"。 */
    val fromName: String? = null
)
