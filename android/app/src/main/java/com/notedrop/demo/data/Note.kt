package com.notedrop.demo.data

/**
 * 一张小纸条。
 *
 * Demo 阶段的关键设计：文字和涂鸦分开存。
 * - text：用户敲的字，保持可编辑、可搜索、体积小。
 * - doodlePath：手写/涂鸦导出后的 PNG 文件路径，小部件里用 ImageView 直接显示。
 *
 * 后续接入服务端时，只需要把 doodlePath 换成可上传的本地文件 + 远端 URL 即可。
 */
data class Note(
    val text: String,
    val doodlePath: String?,
    val senderName: String,
    val createdAt: Long,
    /** 纸张样式。默认白色，符合"撕下来的小纸条"的直觉。 */
    val paperStyle: PaperStyle = PaperStyle.WHITE,
    /**
     * 纸张方向。true = 横版。
     *
     * 这个字段存在的理由：发送方和接收方的屏幕/小部件形状往往不同。
     * 竖着画的内容塞进横向小部件，或者反过来，都会出现大片留白，看着别扭。
     * 把方向随纸条一起传过去，接收方就能按正确的方式排版。
     */
    val landscape: Boolean = false,
    /**
     * 纸条上的贴纸。
     *
     * 贴纸**不烤进位图**，而是单独传一份数据 ——
     * 这样接收方能把贴纸单独取下来收藏，或者再贴到别的纸条上。
     * 如果烘焙进图片，收到就只是一张死图，贴纸的流转玩法就没了。
     *
     * 每项包含：贴纸内容（PNG 文件路径或远程数据）、在纸上的位置、
     * 大小、旋转角度。位置用相对值，保证在不同尺寸的屏幕上对得上。
     */
    val stickers: List<NoteSticker> = emptyList()
) {
    val hasText: Boolean get() = text.isNotBlank()
    val hasDoodle: Boolean get() = !doodlePath.isNullOrBlank()
    /** 纸条上有没有贴纸。只有贴纸、没有手写的纸条同样是有内容的。 */
    val hasStickers: Boolean get() = stickers.isNotEmpty()
    val isEmpty: Boolean get() = !hasText && !hasDoodle && !hasStickers
}
