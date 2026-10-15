package com.notedrop.demo.ui

import com.notedrop.demo.R
import com.notedrop.demo.data.PaperStyle

/**
 * 纸张样式 → 资源 id。
 *
 * 刻意返回**资源 id 而不是 Drawable 对象**：
 * 小部件（RemoteViews）只能通过 `setInt(viewId, "setBackgroundResource", id)`
 * 使用资源，不能接受运行时构造的 Drawable。
 * 统一走资源 id，App 内和小部件就能共用同一套纸张样式。
 */
object PaperBackground {

    fun resId(style: PaperStyle): Int = when (style) {
        PaperStyle.WHITE -> R.drawable.paper_white
        PaperStyle.GRID -> R.drawable.paper_grid
        PaperStyle.CREAM -> R.drawable.paper_cream
    }
}
