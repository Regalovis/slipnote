package com.notedrop.demo.data

/**
 * 纸张样式。
 *
 * 用序号持久化（而不是名字），这样以后增删样式时，
 * 老数据不会因为找不到名字而失效 —— 只需要保持已有序号不变。
 */
enum class PaperStyle(val label: String) {
    /** 纯白，干净利落 */
    WHITE("白纸"),

    /** 方格本，像从作业本上撕下来的 */
    GRID("方格"),

    /** 米黄，原来的默认色，偏暖 */
    CREAM("米黄");

    /** 传给服务端的稳定标识，与网页端保持一致。 */
    val wireName: String get() = name.lowercase()

    companion object {
        fun fromOrdinal(value: Int): PaperStyle =
            entries.getOrElse(value) { WHITE }

        /** 从服务端下发的标识反查样式。 */
        fun fromWire(name: String?): PaperStyle =
            entries.firstOrNull { it.wireName == name?.lowercase() } ?: WHITE
    }
}
