package com.example.migratable.coloring

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 照片 → 可涂色线稿 的处理管线（纯 Kotlin，无需 OpenCV / RenderScript）。
 *
 * 步骤：
 *   1. 解码并按 EXIF 摆正、限制最大边长
 *   2. 转灰度
 *   3. 高斯模糊去噪（可分离卷积，整数定点加速）
 *   4. 边缘检测提取轮廓：XDoG（默认，线条干净）或 Sobel
 *   5. 二值化 + 反色 → 黑线白底
 *   6. 连通域清理杂线（去孤立噪点、去糊成一片的大黑块）
 *   7. 形态学膨胀加粗描边
 */
object ColoringProcessor {

    enum class EdgeMode { XDOG, SOBEL }

    /** 线稿：只存二值掩膜，导出 PDF 时再渲染成位图，高清也不会撑爆内存 */
    data class LineArt(val mask: BooleanArray, val w: Int, val h: Int)

    /**
     * 参数基准分辨率：界面上的「清理杂线 / 描边加粗」都是按这个尺寸调的，
     * 换成更高分辨率时自动按比例换算，保证不同档位观感一致。
     */
    private const val BASE_SIDE = 1200

    data class Options(
        /** 处理分辨率上限，越大越精细但越慢 */
        val maxSide: Int = 1200,
        val mode: EdgeMode = EdgeMode.XDOG,
        /** 高斯模糊 sigma，越大越去噪、线条越少 */
        val blurSigma: Float = 1.4f,
        /** 二值化阈值 0..255，越小线条越多越碎（autoThreshold 关闭时生效） */
        val threshold: Int = 50,
        /** 自动按目标墨迹比例反推阈值，避免不同照片明暗差异导致线太多或太糊 */
        val autoThreshold: Boolean = true,
        /** 自动模式下的目标墨迹占比 */
        val inkRatio: Float = 0.06f,
        /** 小于该像素数的连通块当作杂线删除 */
        val minArea: Int = 60,
        /** 描边加粗半径 0..3 */
        val thicken: Int = 1,
        /** 先膨胀再腐蚀，把断掉的线接起来（线条更连贯） */
        val closeGaps: Boolean = true,
        /** 删除面积过大的黑色块（暗部糊成一片时很有用） */
        val dropLargeBlocks: Boolean = true
    )

    private const val XDOG_GAIN = 5.0f
    private const val SOBEL_GAIN = 2.4f

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 解码相册图片：按 EXIF 摆正 + 降采样到 maxSide */
    fun decodeSampled(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        } ?: return null

        val upright = rotateByExif(resolver, uri, raw)
        return fit(upright, maxSide)
    }

    /** 完整管线：原图 → 线稿掩膜（推荐，省内存，导出前再渲染） */
    fun processMask(source: Bitmap, opt: Options = Options()): LineArt {
        val work = fit(source, opt.maxSide)
        val w = work.width
        val h = work.height
        if (work !== source) source.recycle()

        val gray = toGray(work, w, h)
        work.recycle()

        val ink = when (opt.mode) {
            EdgeMode.XDOG -> xdog(gray, w, h, opt.blurSigma)
            EdgeMode.SOBEL -> sobel(gray, w, h, opt.blurSigma)
        }

        val threshold =
            if (opt.autoThreshold) autoThreshold(ink, opt.inkRatio) else opt.threshold
        val mask = BooleanArray(w * h)
        for (i in ink.indices) mask[i] = ink[i] >= threshold

        // 分辨率变化时换算参数：噪点面积按平方增长，线宽按线性增长
        val scale = max(w, h).toFloat() / BASE_SIDE
        val minArea = (opt.minArea * scale * scale).roundToInt().coerceAtLeast(1)
        val thicken =
            if (opt.thicken > 0) (opt.thicken * scale).roundToInt().coerceAtLeast(1) else 0

        cleanLines(mask, w, h, minArea, opt.dropLargeBlocks)

        // 闭运算：把断成一段一段的线接起来
        val closed = if (opt.closeGaps) close(mask, w, h, scale.roundToInt().coerceAtLeast(1)) else mask

        val finalMask = if (thicken > 0) dilate(closed, w, h, thicken) else closed
        return LineArt(finalMask, w, h)
    }

    /** 完整管线：原图 → 黑线白底线稿位图 */
    fun process(source: Bitmap, opt: Options = Options()): Bitmap =
        renderLine(processMask(source, opt))

    /** 按原分辨率渲染成黑线白底位图（用于导出 / 打印） */
    fun renderLine(art: LineArt): Bitmap {
        val out = Bitmap.createBitmap(art.w, art.h, Bitmap.Config.ARGB_8888)
        val px = IntArray(art.w * art.h)
        for (i in px.indices) px[i] = if (art.mask[i]) Color.BLACK else Color.WHITE
        out.setPixels(px, 0, art.w, 0, 0, art.w, art.h)
        return out
    }

    /**
     * 网格里的小预览图：按墨迹覆盖率做降采样，
     * 比直接缩放位图清楚得多，不会糊成灰团。
     */
    fun renderPreview(art: LineArt, maxSide: Int = 320): Bitmap {
        val scale = min(1f, maxSide.toFloat() / max(art.w, art.h))
        val w = max(1, (art.w * scale).roundToInt())
        val h = max(1, (art.h * scale).roundToInt())
        val step = 1f / scale
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val sy0 = (y * step).toInt()
            val sy1 = min(art.h, ((y + 1) * step).toInt().coerceAtLeast(sy0 + 1))
            for (x in 0 until w) {
                val sx0 = (x * step).toInt()
                val sx1 = min(art.w, ((x + 1) * step).toInt().coerceAtLeast(sx0 + 1))
                var ink = 0
                var total = 0
                for (sy in sy0 until sy1) {
                    val row = sy * art.w
                    for (sx in sx0 until sx1) {
                        if (art.mask[row + sx]) ink++
                        total++
                    }
                }
                val dark = if (total == 0) 0f else ink.toFloat() / total
                val v = (255 * (1f - dark)).roundToInt().coerceIn(0, 255)
                px[y * w + x] = Color.rgb(v, v, v)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    // ------------------------------------------------------------------
    // 1. 尺寸 / 方向
    // ------------------------------------------------------------------

    private fun rotateByExif(resolver: ContentResolver, uri: Uri, src: Bitmap): Bitmap {
        val angle = try {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        if (angle == 0f) return src
        val matrix = Matrix().apply { postRotate(angle) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun fit(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        val nw = (src.width * scale).toInt().coerceAtLeast(1)
        val nh = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(src, nw, nh, true)
        if (out !== src) src.recycle()
        return out
    }

    // ------------------------------------------------------------------
    // 2. 灰度
    // ------------------------------------------------------------------

    private fun toGray(src: Bitmap, w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return gray
    }

    // ------------------------------------------------------------------
    // 3. 高斯模糊（可分离，整数核，权重和 = 1024）
    // ------------------------------------------------------------------

    private fun gaussianKernel(sigma: Float): IntArray {
        val s = sigma.coerceAtLeast(0.3f)
        val radius = max(1, ceil(s * 3.0).toInt())
        val size = radius * 2 + 1
        val raw = DoubleArray(size)
        var sum = 0.0
        for (i in 0 until size) {
            val x = (i - radius).toDouble()
            raw[i] = exp(-(x * x) / (2.0 * s * s))
            sum += raw[i]
        }
        val k = IntArray(size)
        var acc = 0
        for (i in 0 until size) {
            k[i] = (raw[i] / sum * 1024.0).roundToInt().coerceAtLeast(1)
            acc += k[i]
        }
        k[radius] += 1024 - acc // 修正到精确 1024
        return k
    }

    private fun blur(src: IntArray, w: Int, h: Int, sigma: Float): IntArray {
        val k = gaussianKernel(sigma)
        val r = k.size / 2
        val tmp = IntArray(w * h)

        // 横向
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0
                var ki = 0
                var xx = x - r
                while (xx <= x + r) {
                    val sx = if (xx < 0) 0 else if (xx >= w) w - 1 else xx
                    acc += src[row + sx] * k[ki]
                    ki++
                    xx++
                }
                tmp[row + x] = acc shr 10
            }
        }

        // 纵向
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0
                var ki = 0
                var yy = y - r
                while (yy <= y + r) {
                    val sy = if (yy < 0) 0 else if (yy >= h) h - 1 else yy
                    acc += tmp[sy * w + x] * k[ki]
                    ki++
                    yy++
                }
                out[row + x] = acc shr 10
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // 4. 边缘检测
    // ------------------------------------------------------------------

    /** XDoG：两次不同 sigma 的高斯模糊相减，对轮廓响应干净、抗噪好 */
    private fun xdog(gray: IntArray, w: Int, h: Int, sigma: Float): IntArray {
        val b1 = blur(gray, w, h, sigma)
        val b2 = blur(gray, w, h, sigma * 1.6f)
        val out = IntArray(w * h)
        for (i in out.indices) {
            val d = abs(b1[i] - b2[i])
            out[i] = (d * XDOG_GAIN).toInt().coerceAtMost(255)
        }
        return out
    }

    /** Sobel：经典梯度算子，细节更锐利 */
    private fun sobel(gray: IntArray, w: Int, h: Int, sigma: Float): IntArray {
        val g = blur(gray, w, h, sigma)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val rowUp = (if (y > 0) y - 1 else 0) * w
            val rowMid = y * w
            val rowDown = (if (y < h - 1) y + 1 else h - 1) * w
            for (x in 0 until w) {
                val xl = if (x > 0) x - 1 else 0
                val xr = if (x < w - 1) x + 1 else w - 1

                val a = g[rowUp + xl]
                val b = g[rowUp + x]
                val c = g[rowUp + xr]
                val d = g[rowMid + xl]
                val f = g[rowMid + xr]
                val p = g[rowDown + xl]
                val q = g[rowDown + x]
                val s = g[rowDown + xr]

                val gx = (c + 2 * f + s) - (a + 2 * d + p)
                val gy = (p + 2 * q + s) - (a + 2 * b + c)
                val mag = sqrt((gx * gx + gy * gy).toDouble()).toFloat() / 4f
                out[rowMid + x] = (mag * SOBEL_GAIN).toInt().coerceAtMost(255)
            }
        }
        return out
    }

    /**
     * 自适应阈值：统计墨迹强度直方图，从强到弱累积到目标占比处取阈值，
     * 这样不同明暗 / 不同细节密度的照片都能得到密度接近的线稿。
     */
    private fun autoThreshold(ink: IntArray, targetRatio: Float): Int {
        val hist = IntArray(256)
        for (v in ink) hist[v]++
        val target = (ink.size * targetRatio).toInt().coerceAtLeast(1)
        var acc = 0
        for (t in 255 downTo 1) {
            acc += hist[t]
            if (acc >= target) return t
        }
        return 1
    }

    // ------------------------------------------------------------------
    // 5. 清理杂线：连通域分析
    // ------------------------------------------------------------------

    private fun cleanLines(
        mask: BooleanArray,
        w: Int,
        h: Int,
        minArea: Int,
        dropLargeBlocks: Boolean
    ) {
        if (minArea <= 0 && !dropLargeBlocks) return
        val total = w * h
        val maxArea = (total * 0.35f).toInt()
        val visited = BooleanArray(total)
        val stack = IntArray(total)

        for (start in 0 until total) {
            if (!mask[start] || visited[start]) continue

            // 第一遍：统计连通块面积
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var area = 0
            while (sp > 0) {
                val p = stack[--sp]
                area++
                val x = p % w
                val y = p / w
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    val nrow = ny * w
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val np = nrow + nx
                        if (mask[np] && !visited[np]) {
                            visited[np] = true
                            stack[sp++] = np
                        }
                    }
                }
            }

            val drop = area < minArea || (dropLargeBlocks && area > maxArea)
            if (!drop) continue

            // 第二遍：擦除该连通块
            sp = 0
            stack[sp++] = start
            mask[start] = false
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    val nrow = ny * w
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val np = nrow + nx
                        if (mask[np]) {
                            mask[np] = false
                            stack[sp++] = np
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 6. 加粗描边：可分离最大值滤波（等价方形结构元膨胀）
    // ------------------------------------------------------------------

    private fun dilate(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var v = false
                var xx = x - r
                val end = x + r
                while (!v && xx <= end) {
                    if (xx >= 0 && xx < w && mask[row + xx]) v = true
                    xx++
                }
                tmp[row + x] = v
            }
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = false
                var yy = y - r
                val end = y + r
                while (!v && yy <= end) {
                    if (yy >= 0 && yy < h && tmp[yy * w + x]) v = true
                    yy++
                }
                out[y * w + x] = v
            }
        }
        return out
    }

    /** 腐蚀：可分离最小值滤波 */
    private fun erode(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var v = true
                var xx = x - r
                val end = x + r
                while (v && xx <= end) {
                    if (xx >= 0 && xx < w && !mask[row + xx]) v = false
                    xx++
                }
                tmp[row + x] = v
            }
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = true
                var yy = y - r
                val end = y + r
                while (v && yy <= end) {
                    if (yy >= 0 && yy < h && !tmp[yy * w + x]) v = false
                    yy++
                }
                out[y * w + x] = v
            }
        }
        return out
    }

    /** 闭运算 = 膨胀后腐蚀：连接断线、填掉线上的小孔，不改变线宽 */
    private fun close(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray =
        erode(dilate(mask, w, h, r), w, h, r)

    // ------------------------------------------------------------------
    // 7. 出图：见上面的 renderLine / renderPreview
    // ------------------------------------------------------------------
}
