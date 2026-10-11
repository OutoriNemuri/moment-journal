package com.nemuri.journal.export

/**
 * 导出图的切分策略。
 *
 * ## 为什么要有这个类型
 * 此前只有一个 `forceSlice: Boolean`，表达力不足 —— 它只能表达"切还是不切"，
 * 而实际需求有四种切法。用一个布尔值硬撑，结果就是「切分的几张短图」
 * 与「一张长图」在多数情况下产出完全相同的结果（见下）。
 *
 * 现在把切法本身建模成类型，四种算法各写一处，共用同一套按块切分的循环。
 *
 * ## 四种切法的数值对照（以宽 1080 为例）
 *
 * | 策略 | 每张高度 | 张数 |
 * |---|---|---|
 * | [None] | ≤ 4,000（上限） | 1 或不切 |
 * | [Count] | 总高 / N | 由用户指定 |
 * | [Screen] | 屏幕物理高度 | 由内容长度决定 |
 * | [Ratio] | 宽 × 比例 | 由内容长度决定 |
 */
sealed interface SliceStrategy {

    /**
     * 不切，输出一张长图。
     *
     * 仍受 [MAX_SINGLE_HEIGHT] 限制：超过则该图长到不可读
     * （旧实现的上限是 24,000px，宽高比 22:1，实际无法查看）。
     * 超限时由调用方提示用户改用切分，而不是默默产出。
     */
    data object None : SliceStrategy

    /**
     * 平均分成 [n] 张。
     *
     * 切点会对齐到块边界，因此各张高度略有出入 —— 这是可接受的，
     * 但**不能产出空图**：若块数少于 [n]，实际张数会少于 [n]，
     * 调用方需把这一点告知用户。
     */
    data class Count(val n: Int) : SliceStrategy

    /**
     * 每张高度等于屏幕物理高度。
     *
     * 用物理高度而非可用高度：这样每张图与屏幕等宽等高，
     * 「逐屏查看」时图片与屏幕一一对应。
     * 若用可用高度（去掉状态栏与导航栏），图会比屏幕矮一截，
     * 滚动查看时内容对不齐。
     */
    data class Screen(val heightPx: Int) : SliceStrategy

    /**
     * 每张高度 = 宽度 × [heightOverWidth]。
     *
     * 比例读作**高:宽**。例如「4:3」传入 `4f/3f`，得到 810×1080 的竖图。
     * 这与日记的纵向内容一致 —— 若按宽:高理解会得到横向的图，与内容方向矛盾。
     */
    data class Ratio(val heightOverWidth: Float) : SliceStrategy

    companion object {
        /**
         * 单张长图的高度上限。
         *
         * 定这个值的依据是**可读性**，不是内存。
         * 旧值 24,000px 是按 `1080 × 24000 × 4 ≈ 99MB` 反推的，
         * 完全没考虑这么长的图还能不能看 —— 真机上约 3,550px
         * 就需要滚动数屏，11:1 以上基本失去可读性。
         *
         * 4,000px 约等于 3.7:1，略高于一屏，是"长图"与"没法看"之间的折中。
         */
        const val MAX_SINGLE_HEIGHT = 4_000

        /** 单张位图的硬上限，防止切分参数异常时分配超大位图。 */
        const val HARD_MAX_HEIGHT = 24_000

        /** 常用的比例选项。 */
        val RATIO_4_3 = Ratio(4f / 3f)
        val RATIO_5_3 = Ratio(5f / 3f)

        /** 分成 N 张的候选项。 */
        val COUNT_OPTIONS = listOf(2, 3, 4, 6)
    }
}

/**
 * 单张切片的尺寸计算。
 *
 * 与切分循环分开：循环只负责"按块累积到不超过目标高度"，
 * 目标高度由本函数给出。四条路径的差别全部集中在这里。
 */
internal object SliceSizing {

    /**
     * 计算每张的目标高度。
     *
     * @param totalHeight 内容总高（不含上下内边距）
     * @param contentWidth 内容宽度（像素）
     * @param blockCount 块的数量，用于 [SliceStrategy.Count] 的空图防护
     * @return 每张的目标高度；返回 null 表示不切分
     */
    fun targetHeight(
        strategy: SliceStrategy,
        totalHeight: Int,
        contentWidth: Int,
        blockCount: Int,
    ): Int? = when (strategy) {
        // 不切。上限判断由调用方处理 —— 它需要向用户提示，而不只是切掉。
        SliceStrategy.None -> null

        is SliceStrategy.Count -> {
            // 张数多于块数时按块数算，避免产出空图。
            // 实际张数会少于用户要求，这一点由调用方告知。
            val effective = strategy.n.coerceAtLeast(1).coerceAtMost(blockCount.coerceAtLeast(1))
            // 用 ceil 而非整除：整除会让余数落到多出来的一张上，使实际张数比
            // effective 多 1（实测「要 6 张、3 个块」会切出 4 张）。
            // ceil 保证每张目标高度足够容纳 totalHeight / effective。
            ceilDiv(totalHeight, effective).coerceAtLeast(1)
        }

        is SliceStrategy.Screen ->
            strategy.heightPx.coerceIn(1, SliceStrategy.HARD_MAX_HEIGHT)

        is SliceStrategy.Ratio -> {
            val h = (contentWidth * strategy.heightOverWidth).toInt()
            h.coerceIn(1, SliceStrategy.HARD_MAX_HEIGHT)
        }
    }

    /**
     * 「一张长图」是否因为内容过长而失去可读性。
     *
     * [SliceStrategy.None] 不切分，但仍要把超过 [SliceStrategy.MAX_SINGLE_HEIGHT]
     * 的情况**报出来** —— 让调用方提示用户改用切分，
     * 而不是默默产出一张 22:1、实际没法看的长条。
     */
    fun exceedsReadableLimit(totalHeight: Int): Boolean =
        totalHeight > SliceStrategy.MAX_SINGLE_HEIGHT

    /**
     * 按策略实际会产出几张。
     *
     * 注意这只是**估算上限**：实际张数还取决于块边界，
     * 可能少于这个数（块太大会独占一张）。
     */
    fun plannedCount(
        strategy: SliceStrategy,
        totalHeight: Int,
        contentWidth: Int,
        blockCount: Int,
    ): Int? {
        val per = targetHeight(strategy, totalHeight, contentWidth, blockCount) ?: return null
        if (per <= 0) return null
        return ceilDiv(totalHeight, per).coerceAtLeast(1)
    }

    /** 向上取整的除法。用于把余数并入最后一张，而不是多切出一张。 */
    internal fun ceilDiv(a: Int, b: Int): Int =
        if (b <= 0) a else (a + b - 1) / b
}
