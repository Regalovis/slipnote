package com.notedrop.demo.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 系统栏避让。
 *
 * ## 为什么必须处理
 *
 * Android 15（API 35）起，只要 targetSdk >= 35，系统就**强制开启 edge-to-edge**：
 * 应用窗口会延伸到状态栏和导航栏底下，`android:navigationBarColor` 之类的设置会被忽略。
 *
 * 如果不主动避让，底部的按钮就会和手势导航条重叠 ——
 * 用户点下去落在系统手势区上，被系统拦截，表现上就像"点了没反应"。
 *
 * ## 做法
 *
 * 记录布局原有的 padding，再把系统栏高度加上去。
 * 注意必须叠加而不是覆盖：这些根布局本身有 16~24dp 的内边距，
 * 直接 setPadding 会把原有留白吃掉，内容贴边。
 */
fun View.applySystemBarInsets() {
    val padLeft = paddingLeft
    val padTop = paddingTop
    val padRight = paddingRight
    val padBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        // 键盘弹起时取两者较大值，这样内容既不会被导航栏压住，也不会被键盘遮住
        val bottom = maxOf(bars.bottom, ime.bottom)

        view.setPadding(
            padLeft + bars.left,
            padTop + bars.top,
            padRight + bars.right,
            padBottom + bottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * 只避让系统栏、**不**在键盘弹起时额外顶起内容。
 *
 * 用于首页这种"不需要打字"的页面：打开输入法相关的 inset 后，
 * 键盘动画期间整页会跟着抖一下，而这里根本没有输入框，纯属副作用。
 */
fun View.applySystemBarInsetsNoIme() {
    val padLeft = paddingLeft
    val padTop = paddingTop
    val padRight = paddingRight
    val padBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            padLeft + bars.left,
            padTop + bars.top,
            padRight + bars.right,
            padBottom + bars.bottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * 把系统栏高度作为**内容间距**交给回调，而不是写进 padding。
 *
 * 首页的头图需要延伸到状态栏底下（渐变一直铺到屏幕顶），
 * 但头图里的文字不能顶到状态栏。这时候全用 padding 就矛盾了 ——
 * 加了 padding，渐变也被推下来，上方露出一条底色。
 *
 * 所以这里只在回调里报告高度，调用方自己决定把它加在哪一层。
 */
fun View.onSystemBarInsets(callback: (left: Int, top: Int, right: Int, bottom: Int) -> Unit) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        callback(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
