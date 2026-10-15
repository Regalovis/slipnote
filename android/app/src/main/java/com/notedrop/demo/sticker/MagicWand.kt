package com.notedrop.demo.sticker

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 魔棒 / 颜色键抠图。
 *
 * ## 为什么换掉 AI 抠图
 *
 * 之前用的是 MediaPipe `deeplab_v3`。那是**语义分割**模型 ——
 * 它只认识 PASCAL VOC 那 20 类（人、车、猫、狗、瓶子…），
 * 输出的是"这块像素属于哪一类"，而不是"哪块是主体"。
 *
 * 所以拿一张普通照片或截图给它，结果基本是随机的：
 * 用户测下来"几乎全部失败"，不是调参能解决的，是方案选错了。
 *
 * 颜色键是另一条路：**用户指哪儿，就去掉哪儿颜色相近的连通区域**。
 * 它不需要认识任何东西，只比较颜色，所以：
 *   - 白底商品图、截图、logo、手写字 —— 几乎百发百中
 *   - 结果是**确定的**：同样一点，永远同样的结果，用户可以预期
 *   - 用户可以控制：点错了一下撤销就好
 *
 * 这也是 Photoshop 魔棒、以及各种成熟抠图 App 的默认做法。
 *
 * ## 实现的算法
 *
 * 从用户点的那一点出发做**洪水填充**（flood fill），
 * 把颜色在容差范围内、且**四连通**的像素标记为背景。
 *
 * "四连通"（只走上下左右，不走斜角）是关键：
 * 八连通会让颜色从斜缝里"漏"过去，把不该去掉的地方吃掉。
 *
 * 用**迭代 + 显式栈**而不是递归 —— 一张 1000x1000 的图
 * 递归深度可能到百万级，必然栈溢出。
 */
object MagicWand {

    /**
     * 一次魔棒操作的结果。
     *
     * @param bitmap    处理后的位图（**新的**，原图不动）
     * @param removed   这次去掉的像素数。0 表示没删掉任何东西，
     *                  调用方可以据此提示"这里没有可以去掉的连续区域"
     */
    data class Result(val bitmap: Bitmap, val removed: Int)

    /**
     * 从 ([x], [y]) 出发，去掉与那一点颜色相近的连通区域。
     *
     * @param source    原图（不被修改，返回的是新位图）
     * @param x         点的横坐标（原图像素坐标）
     * @param y         点的纵坐标
     * @param tolerance 容差 0~255。0 = 只去掉颜色完全相同的像素；
     *                  越大越"激进"。白底图一般 30 左右就够。
     * @param edgeSoften 是否把边缘的 alpha 做一次羽化，让抠出来的边不生硬
     */
    fun removeConnected(
        source: Bitmap,
        x: Int,
        y: Int,
        tolerance: Int,
        edgeSoften: Boolean = true
    ): Result {
        val w = source.width
        val h = source.height
        if (x !in 0 until w || y !in 0 until h) return Result(source, 0)

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val target = pixels[y * w + x]
        // 目标本身就基本透明 —— 没有东西可去
        if (Color.alpha(target) < 8) return Result(source, 0)

        val removed = floodRemove(pixels, w, h, x, y, target, tolerance)
        if (removed == 0) return Result(source, 0)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)

        return Result(if (edgeSoften) featherEdges(out) else out, removed)
    }

    /**
     * 洪水填充，把连通区域置为全透明。返回去掉的像素数。
     *
     * 用 IntArray 当栈（存像素下标），比 ArrayDeque<Integer> 快得多，
     * 也完全不产生装箱对象。
     */
    private fun floodRemove(
        pixels: IntArray,
        w: Int,
        h: Int,
        startX: Int,
        startY: Int,
        target: Int,
        tolerance: Int
    ): Int {
        // 预先把阈值平方出来，循环里就不用开方
        val threshold = tolerance * tolerance * 3

        val stack = IntArray(w * h)
        var top = 0
        val startIndex = startY * w + startX

        // 已经处理过的像素。不去重的话同一格会被反复压栈，栈会爆
        val visited = java.util.BitSet(w * h)
        visited.set(startIndex)
        stack[top++] = startIndex

        var count = 0

        while (top > 0) {
            val index = stack[--top]
            val p = pixels[index]

            // 颜色超出容差 → 边界，停在这里（保留这个像素）
            if (!within(p, target, threshold)) continue

            // 去掉它：只清 alpha，保留 RGB（羽化边缘时还要用到颜色）
            pixels[index] = p and 0x00FFFFFF
            count++

            val px = index % w
            val py = index / w

            // 四连通：上下左右。不走斜角，否则颜色会从斜缝漏过去
            if (px > 0) {
                val n = index - 1
                if (!visited.get(n)) { visited.set(n); stack[top++] = n }
            }
            if (px < w - 1) {
                val n = index + 1
                if (!visited.get(n)) { visited.set(n); stack[top++] = n }
            }
            if (py > 0) {
                val n = index - w
                if (!visited.get(n)) { visited.set(n); stack[top++] = n }
            }
            if (py < h - 1) {
                val n = index + w
                if (!visited.get(n)) { visited.set(n); stack[top++] = n }
            }
        }

        return count
    }

    /**
     * 两个像素颜色是否在容差内。
     *
     * 用 RGB 欧氏距离的平方和比较 —— 比逐通道比更符合"看起来像不像"，
     * 也比转 HSL 便宜得多。除 3 是为了和 [tolerance] 的直观含义对齐
     * （0~255 的每通道平均差）。
     */
    private fun within(a: Int, b: Int, thresholdSquaredTimes3: Int): Boolean {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db <= thresholdSquaredTimes3
    }

    /**
     * 边缘羽化。
     *
     * 洪水填充留下的是硬边（像素非 0 即 255），放大看是一圈锯齿。
     * 这里把"紧挨着透明区的半透明像素"找出来，给它们一个中间 alpha，
     * 让边界柔和一点。
     *
     * 只处理紧邻透明区的那一圈，不动内部 —— 内部被改会让实心区域变灰。
     */
    private fun featherEdges(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val out = px.copyOf()

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                // 只看不透明的像素；已经是透明的跳过
                if ((px[i] ushr 24) != 0xFF) continue

                // 四邻里有没有透明的
                val hasTransparentNeighbor =
                    (px[i - 1] ushr 24) < 8 ||
                        (px[i + 1] ushr 24) < 8 ||
                        (px[i - w] ushr 24) < 8 ||
                        (px[i + w] ushr 24) < 8

                if (hasTransparentNeighbor) {
                    // 压到 ~70% 不透明，边界就柔了
                    out[i] = (178 shl 24) or (px[i] and 0x00FFFFFF)
                }
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        src.recycle()
        return result
    }

    /**
     * 自动去掉**四角**连续的背景。
     *
     * 这是"一键抠白底"的快捷路径：从四个角各做一次魔棒，
     * 把它们连成一片的背景清掉。四角都点一遍是因为
     * 背景可能被主体分割开（比如主体是一根竖线，左右两侧不连通）。
     *
     * @return 处理后的位图，以及一共去掉多少像素
     */
    fun autoFromCorners(source: Bitmap, tolerance: Int = 32): Result {
        var current = source
        var total = 0

        val corners = arrayOf(
            0 to 0,
            (source.width - 1) to 0,
            0 to (source.height - 1),
            (source.width - 1) to (source.height - 1)
        )

        corners.forEach { (x, y) ->
            // 每个角落之前先重新读一遍当前状态 —— 上一次的改动要接上
            val r = removeConnected(current, x, y, tolerance, edgeSoften = false)
            if (r.removed > 0) {
                if (current !== source) current.recycle()
                current = r.bitmap
                total += r.removed
            }
        }

        if (total == 0) return Result(source, 0)
        // featherEdges 内部会回收传进去的那张，所以这里不能再回收一次
        return Result(featherEdges(current), total)
    }
}
