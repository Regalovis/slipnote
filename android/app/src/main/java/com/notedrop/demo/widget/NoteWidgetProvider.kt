package com.notedrop.demo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.notedrop.demo.R
import com.notedrop.demo.data.NoteComposer
import com.notedrop.demo.data.NoteStore
import com.notedrop.demo.ui.PaperBackground
import com.notedrop.demo.ui.ComposeActivity
import kotlin.math.max

/**
 * 小纸条的桌面小部件。
 *
 * 刷新机制（这是整个产品的技术核心）：
 * 1. 系统定时刷新：updatePeriodMillis=1800000，系统至少 30 分钟来叫一次，只做兜底。
 * 2. **主动刷新**：任何地方调用 refresh(context)，立刻推新内容到桌面。
 *    对方发来纸条后，就是靠这条链路做到"几秒内出现"。
 * 3. 曝光刷新：MIUI 上额外声明了 miuiWidgetRefresh=exposure。
 */
class NoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // 用户拖动改变尺寸后要重新排版（小尺寸只显示文字，大尺寸才铺开涂鸦）
        render(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 自定义的主动刷新广播，不依赖系统定时器
        if (intent.action == ACTION_REFRESH) {
            refresh(context)
        }
    }

    companion object {
        /** 主动刷新的广播 action，与 AndroidManifest 中声明的一致。 */
        const val ACTION_REFRESH = "com.notedrop.demo.action.WIDGET_UPDATE"

        /**
         * 主动刷新所有小纸条小部件。
         *
         * 这是"对方发来我桌面就变"的关键调用点：
         * 应用内任何位置（编辑页保存后、推送到达后）都可以直接调它。
         *
         * 之所以发广播而不是直接操作 AppWidgetManager，是因为小部件进程通常是独立的，
         * 走广播能让系统在正确的进程里完成 RemoteViews 组装。
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, NoteWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            ids.forEach { id -> render(context, manager, id) }
        }

        /** 组装并推送一张纸条的界面。 */
        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            // 整个渲染过程包在 try 里：
            // AppWidgetProvider 跑在系统发起的广播里，一旦抛异常就是进程崩溃，
            // 用户看到的就是“添加小部件时闪退”。宁可退化成空状态，也不能崩。
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_note)
                val note = NoteStore.read(context)

                // 小部件实际可用尺寸，用于决定布局档位和图片采样率
                val options = manager.getAppWidgetOptions(widgetId)
                val widthDp = max(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
                )
                val heightDp = max(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                )

            if (note == null) {
                renderEmpty(views)
            } else {
                renderNote(context, views, note, widthDp, heightDp)
            }

                // 整张纸条点击后进入编辑页（用户说“点击展开放大”，这里先落到编辑页）
                views.setOnClickPendingIntent(R.id.widget_root, composeIntent(context, widgetId))

                manager.updateAppWidget(widgetId, views)
            } catch (t: Throwable) {
                Log.e(TAG, "小部件渲染失败，退化为纯文字展示", t)
                runCatching {
                    val safe = RemoteViews(context.packageName, R.layout.widget_note)
                    val note = NoteStore.read(context)
                    safe.setViewVisibility(R.id.widget_doodle, View.GONE)
                    safe.setTextViewText(
                        R.id.widget_text,
                        note?.text?.takeIf { it.isNotBlank() } ?: "纸条内容暂时显示不出来"
                    )
                    safe.setTextViewText(R.id.widget_sender, note?.senderName.orEmpty())
                    safe.setTextViewText(
                        R.id.widget_time,
                        note?.let { NoteStore.relativeTime(context, it.createdAt) }.orEmpty()
                    )
                    safe.setOnClickPendingIntent(
                        R.id.widget_root,
                        composeIntent(context, widgetId)
                    )
                    manager.updateAppWidget(widgetId, safe)
                }
            }
        }

        private const val TAG = "NoteDrop"

        private fun renderEmpty(views: RemoteViews) {
            views.setTextViewText(R.id.widget_text, "还没有纸条\n点一下，写点什么")
            views.setTextViewText(R.id.widget_sender, "")
            views.setTextViewText(R.id.widget_time, "")
            views.setViewVisibility(R.id.widget_doodle, View.GONE)
        }

        private fun renderNote(
            context: Context,
            views: RemoteViews,
            note: com.notedrop.demo.data.Note,
            widthDp: Int,
            heightDp: Int
        ) {
            // 尺寸档位：< 180dp 视为小尺寸，只保留核心信息
            val isCompact = widthDp in 1..179
            val isLarge = widthDp >= 250 || heightDp >= 250

            // 纸张样式：白色 / 方格 / 米黄。
            // RemoteViews 只能用资源，所以走 setBackgroundResource。
            views.setInt(R.id.paper, "setBackgroundResource", PaperBackground.resId(note.paperStyle))

            if (note.hasText) {
                views.setViewVisibility(R.id.widget_text, View.VISIBLE)
                views.setTextViewText(R.id.widget_text, note.text)
                // 小部件没法直接按 dp 设字号，用 setTextViewTextSize 传 SP
                views.setTextViewTextSize(
                    R.id.widget_text,
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    if (isCompact) 13f else if (isLarge) 18f else 16f
                )
                // 有涂鸦时文字要收敛，把空间让给画面；纯文字则可以多显示几行
                views.setInt(
                    R.id.widget_text,
                    "setMaxLines",
                    if (note.hasDoodle || note.hasStickers) 2 else 5
                )
            } else {
                views.setViewVisibility(R.id.widget_text, View.GONE)
            }

            if (note.hasDoodle || note.hasStickers) {
                /*
                 * 涂鸦的显示策略取决于「纸条方向」和「格子形状」是否一致：
                 *
                 * - 一致或格子足够大：按格子尺寸出图，画面尽量铺满
                 * - 不一致（横画进竖格子）：按较**短**的一边出图，
                 *   因为 fitCenter 会把图缩到能放下的尺寸，
                 *   按长边出图会浪费大量像素并顶到位图预算上限
                 *
                 * 目标边长还要封顶：480dpi 屏幕上 250dp 格子就有 750px，
                 * 直接出图很容易撞上系统给单个小部件的位图内存上限。
                 */
                val density = context.resources.displayMetrics.density
                val slotW = widthDp.coerceIn(110, 360)
                val slotH = heightDp.coerceIn(110, 360)
                // 横画竖格 → 用较窄的一边做目标宽度，避免生成过大的图
                val mismatch = note.landscape != (slotW > slotH)
                val baseDp = if (mismatch) minOf(slotW, slotH) else maxOf(slotW, slotH)
                val targetPx = (baseDp * density).toInt().coerceAtMost(640)

                /*
                 * 贴纸必须**合成进这张图**才能显示出来。
                 *
                 * 小部件的 RemoteViews 里只有一个 ImageView，没法叠图层；
                 * 而贴纸在数据上又是独立的一份（为了"能取下来"）。
                 * 所以这里在给小部件出图之前先合一次 —— 只影响显示，
                 * 不影响存档里的贴纸数据，收到的人照样能单独取下来。
                 *
                 * targetPx 同时作为合成尺寸：合成后的图和小部件能显示的
                 * 尺寸一致，不额外占用位图预算。
                 */
                val doodle: Bitmap? = NoteStore.decodeDoodle(note.doodlePath, targetPx)
                val bitmap: Bitmap? = NoteComposer.compose(
                    context, doodle, note.stickers, targetPx
                )
                // 合成会产出一张新位图，中间那张底图就没人用了。
                // 小部件刷新很频繁，不回收的话很快就是几十 MB 的垃圾。
                if (bitmap !== doodle) doodle?.recycle()
                if (bitmap != null) {
                    views.setViewVisibility(R.id.widget_doodle, View.VISIBLE)
                    views.setImageViewBitmap(R.id.widget_doodle, bitmap)
                } else {
                    views.setViewVisibility(R.id.widget_doodle, View.GONE)
                }
            } else {
                views.setViewVisibility(R.id.widget_doodle, View.GONE)
            }

            // 小尺寸下不显示落款，避免挤占内容。
            // 整块 footer 一起隐藏，否则残留的 marginTop 也会占掉几像素。
            val showFooter = !isCompact
            views.setViewVisibility(
                R.id.widget_footer,
                if (showFooter) View.VISIBLE else View.GONE
            )
            views.setTextViewText(
                R.id.widget_sender,
                if (showFooter) note.senderName else ""
            )
            views.setTextViewText(
                R.id.widget_time,
                if (showFooter) NoteStore.relativeTime(context, note.createdAt) else ""
            )

            /*
             * “实体纸”的微倾斜。
             *
             * 但**横版纸条不倾斜**：横画本来就占满了宽度，
             * 再旋转会让四个角被父布局裁掉，反而露怯。
             * 竖版留白多，轻微倾斜能增加实体感。
             */
            views.setFloat(R.id.paper, "setRotation", if (note.landscape) 0f else -0.8f)
        }

        /**
         * 点击小纸条 → 进入编辑页。
         * 用 PendingIntent 直达 Activity，这是官方推荐、也是厂商规范里明确要求的方式
         * （不要用广播/Service 中转再拉 Activity）。
         */
        private fun composeIntent(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, ComposeActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            // requestCode 用 widgetId，避免多个小部件共用同一个 PendingIntent
            return PendingIntent.getActivity(
                context,
                widgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
