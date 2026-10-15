package com.notedrop.demo.sticker

/**
 * 一张贴纸。
 *
 * 贴纸的生命周期比纸条长：纸条发出去内容就固定了，
 * 贴纸可以在朋友之间流转 —— 从纸条上取下来、存进贴纸库、再贴到新纸条上。
 *
 * 所以贴纸必须**独立存储**，不能只存在某张纸条里。
 */
data class Sticker(
    /** 唯一 ID。同一张图被多人取下来时各自独立，互不影响。 */
    val id: String,
    /** 抠好的透明 PNG 文件路径。 */
    val path: String,
    /** 来源：自己从相册抠的，还是从别人纸条上取下来的。 */
    val origin: Origin,
    /** 取下来时记录是谁贴的，用于展示来源；自己抠的为空。 */
    val fromName: String? = null,
    /** 加入贴纸库的时间，用于排序。 */
    val createdAt: Long
) {
    enum class Origin {
        /** 自己从相册抠的 */
        OWN,

        /** 从收到的纸条上取下来的 */
        RECEIVED
    }
}

/**
 * 贴在纸条上的一个实例。
 *
 * 同一张贴纸可以贴到多张纸条上，每次的位置和大小都不同，
 * 所以"贴纸本体"和"贴在纸上的实例"要分开 ——
 * 这是把素材和排版解耦的常规做法。
 *
 * 坐标全部用**相对值**（0~1），不是绝对像素：
 * 编辑时纸是一个尺寸，发到对方手机上显示是另一个尺寸，
 * 用相对值才能保证位置在任何尺寸下都对得上。
 */
data class PlacedSticker(
    val stickerId: String,
    /** 中心点相对坐标（0~1），相对纸条可绘制区域。 */
    val cx: Float,
    val cy: Float,
    /** 宽度占纸条宽度的比例。高度按贴纸原始比例自动算。 */
    val widthRatio: Float,
    /** 旋转角度（度）。 */
    val rotation: Float
)
