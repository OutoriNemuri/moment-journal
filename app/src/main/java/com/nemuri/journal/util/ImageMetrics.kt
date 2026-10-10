package com.nemuri.journal.util

import android.graphics.BitmapFactory
import java.io.File

/**
 * 图片尺寸计算。
 *
 * ## 为什么单独抽出来
 * 屏幕渲染与长图导出都需要「按目标宽度算出等比高度」。
 * 此前两处各写了一遍，结果一处正确（导出按原始比例算）、一处错误
 * （屏幕渲染写死了 180dp），导致插入的图片在界面里被裁成一条。
 * 同一个问题只应有一处实现。
 *
 * ## 只读尺寸，不解码像素
 * 用 `inJustDecodeBounds` 读取宽高，不把位图读进内存 ——
 * 在排版阶段只需要知道尺寸，此时解码整张图是浪费，长图导出时更是 OOM 的来源。
 */
object ImageMetrics {

    data class Size(val width: Int, val height: Int) {
        val ratio: Float get() = if (width <= 0) 1f else height.toFloat() / width.toFloat()
    }

    /** 读取图片的像素尺寸。失败或非图片时返回 null。 */
    fun read(file: File): Size? {
        if (!file.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return Size(opts.outWidth, opts.outHeight)
    }

    /**
     * 按目标宽度计算等比高度。
     *
     * @param maxHeightRatio 高度上限，相对目标宽度。默认 1.6：
     *   极高的竖图若按原比例铺开会占满好几屏，滚动起来失去上下文。
     *   超过上限时**不裁切**，而是返回被限制的高度，由调用方按 `Fit` 缩放居中 ——
     *   裁切会让用户看不到图片的一部分，缩小至少完整。
     */
    fun heightForWidth(
        file: File,
        targetWidth: Float,
        maxHeightRatio: Float = 1.6f,
    ): Float? {
        val size = read(file) ?: return null
        val natural = targetWidth * size.ratio
        val cap = targetWidth * maxHeightRatio
        return if (natural > cap) cap else natural
    }

    /** 是否因为过高而被限制（调用方据此决定用 Fit 还是 FillWidth）。 */
    fun isHeightCapped(
        file: File,
        targetWidth: Float,
        maxHeightRatio: Float = 1.6f,
    ): Boolean {
        val size = read(file) ?: return false
        return targetWidth * size.ratio > targetWidth * maxHeightRatio
    }

    /**
     * 计算降采样倍数，使解码后的宽度不小于 [targetWidth]。
     * 供长图导出使用：导出会一次性绘制整页，原图直接解码会占用过多内存。
     */
    fun sampleSizeFor(file: File, targetWidth: Int): Int {
        val size = read(file) ?: return 1
        var sample = 1
        while (size.width / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }
}
