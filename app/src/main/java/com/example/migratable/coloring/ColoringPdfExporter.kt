package com.example.migratable.coloring

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 线稿 → A4 PDF，并保存到系统「下载」目录（Android 10+ 走 MediaStore，无需存储权限）。
 */
object ColoringPdfExporter {

    private const val PAGE_W = 595          // A4 @72dpi
    private const val PAGE_H = 842
    private const val MARGIN = 40
    private const val SUB_DIR = "妥妥涂色画"

    /** 每页一张线稿，居中并等比缩放；逐页渲染 + 回收，高清也不占内存 */
    fun build(arts: List<ColoringProcessor.LineArt>): ByteArray {
        val doc = PdfDocument()
        arts.forEachIndexed { index, art ->
            val bmp = ColoringProcessor.renderLine(art)
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create()
            val page = doc.startPage(info)
            drawCentered(page.canvas, bmp)
            doc.finishPage(page)
            bmp.recycle()
        }
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private fun drawCentered(canvas: Canvas, bmp: Bitmap) {
        canvas.drawColor(Color.WHITE)
        val maxW = (PAGE_W - MARGIN * 2).toFloat()
        val maxH = (PAGE_H - MARGIN * 2).toFloat()
        val scale = minOf(maxW / bmp.width, maxH / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (PAGE_W - dw) / 2f
        val top = (PAGE_H - dh) / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), null)
    }

    /** 保存到 Downloads/妥妥涂色画/，返回 content Uri */
    fun save(context: Context, bytes: ByteArray, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, bytes, fileName)
        } else {
            saveViaFile(bytes, fileName)
        }
    }

    private fun saveViaMediaStore(context: Context, bytes: ByteArray, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveViaFile(bytes: ByteArray, fileName: String): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, fileName)
        return try {
            file.writeBytes(bytes)
            Uri.fromFile(file)
        } catch (e: Exception) {
            null
        }
    }
}
