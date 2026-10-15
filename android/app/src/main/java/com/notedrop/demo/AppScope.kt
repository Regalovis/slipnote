package com.notedrop.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级的协程作用域，生命周期与进程一致。
 *
 * 用来跑"用户已经离开界面、但仍然必须完成"的任务，
 * 典型场景是**发送纸条**：
 *
 * 本地已经存好、桌面已经刷新之后，界面就该立刻关掉；
 * 但网络上传还得继续。如果用 Activity 的 lifecycleScope，
 * Activity 一 finish 协程就被取消，纸条就永远发不出去了。
 *
 * 用 SupervisorJob 是为了让单个任务失败不影响其他任务。
 */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)
