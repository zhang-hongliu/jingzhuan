package com.example.migratable.coloring

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContentUris
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 涂色画：浏览手机相册 → 批量生成线稿 → 导出 PDF 到下载目录。
 */
class ColoringActivity : AppCompatActivity() {

    private val photos = mutableListOf<PhotoItem>()
    private val results = LinkedHashMap<Long, Bitmap>()
    private lateinit var gridAdapter: PhotoGridAdapter
    private lateinit var progress: ProgressBar
    private lateinit var textStatus: TextView
    private lateinit var textSelection: TextView
    private lateinit var btnGenerate: Button
    private lateinit var btnExport: Button

    private var busy = false
    private var threshold = 50
    private var minArea = 60
    private var thicken = 1
    private var mode = ColoringProcessor.EdgeMode.XDOG

    companion object {
        private const val REQ_PERMISSION = 4101
        private const val MAX_PHOTOS = 400
        private const val MAX_WARN = 30
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coloring)

        progress = findViewById(R.id.progress)
        textStatus = findViewById(R.id.text_status)
        textSelection = findViewById(R.id.text_selection)
        btnGenerate = findViewById(R.id.btn_generate)
        btnExport = findViewById(R.id.btn_export)

        setupParams()
        setupGrid()
        setupActions()

        if (hasPermission()) loadPhotos() else requestPermission()
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    private fun setupParams() {
        val labelThreshold = findViewById<TextView>(R.id.text_threshold)
        val labelClean = findViewById<TextView>(R.id.text_clean)
        val labelThicken = findViewById<TextView>(R.id.text_thicken)

        val refresh = {
            labelThreshold.text = "线条量：$threshold（越小线越多）"
            labelClean.text = "清理杂线：$minArea（越大删得越狠）"
            labelThicken.text = "描边加粗：$thicken"
        }
        refresh()

        onSeek(findViewById(R.id.seek_threshold)) { threshold = it; refresh() }
        onSeek(findViewById(R.id.seek_clean)) { minArea = it; refresh() }
        onSeek(findViewById(R.id.seek_thicken)) { thicken = it; refresh() }

        val seekThreshold = findViewById<SeekBar>(R.id.seek_threshold)
        findViewById<RadioGroup>(R.id.radio_mode).setOnCheckedChangeListener { _, id ->
            mode = if (id == R.id.radio_sobel) ColoringProcessor.EdgeMode.SOBEL
            else ColoringProcessor.EdgeMode.XDOG
            // Sobel 梯度整体更强，给一个更高的默认阈值免得糊成一片
            val suggest = if (mode == ColoringProcessor.EdgeMode.SOBEL) 90 else 50
            seekThreshold.progress = suggest
            threshold = suggest
            refresh()
        }
    }

    private fun onSeek(bar: SeekBar, onChange: (Int) -> Unit) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                onChange(value)
            }

            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
    }

    private fun currentOptions() = ColoringProcessor.Options(
        mode = mode,
        threshold = threshold,
        minArea = minArea,
        thicken = thicken
    )

    // ------------------------------------------------------------------
    // 相册网格
    // ------------------------------------------------------------------

    private fun setupGrid() {
        gridAdapter = PhotoGridAdapter(
            onToggle = { updateSelectionText() },
            onNeedThumb = { item -> loadThumbAsync(item) }
        )
        val grid = findViewById<RecyclerView>(R.id.grid_photos)
        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = gridAdapter
        grid.setHasFixedSize(true)
    }

    private fun loadThumbAsync(item: PhotoItem) {
        if (item.thumb != null || item.isLoading) return
        item.isLoading = true
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { readThumbnail(item) }
            item.isLoading = false
            if (bmp != null) {
                item.thumb = bmp
                val idx = gridAdapter.indexOf(item)
                if (idx >= 0) gridAdapter.notifyItemChanged(idx)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readThumbnail(item: PhotoItem): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(
                contentResolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun loadPhotos() {
        textStatus.text = "正在读取相册…"
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { queryPhotos() }
            photos.clear()
            photos.addAll(list)
            gridAdapter.submit(photos)
            updateSelectionText()
            textStatus.text = if (photos.isEmpty()) {
                "相册里没找到图片"
            } else {
                "共 ${photos.size} 张，勾选后点「生成线稿」"
            }
        }
    }

    private fun queryPhotos(): List<PhotoItem> {
        val out = mutableListOf<PhotoItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sort
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext() && out.size < MAX_PHOTOS) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                out.add(PhotoItem(id, uri, c.getString(nameCol) ?: ""))
            }
        }
        return out
    }

    private fun updateSelectionText() {
        val n = photos.count { it.selected }
        textSelection.text = "已选 $n 张"
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private fun setupActions() {
        findViewById<Button>(R.id.btn_select_all).setOnClickListener {
            photos.forEach { it.selected = true }
            gridAdapter.refresh()
            updateSelectionText()
        }
        findViewById<Button>(R.id.btn_clear_sel).setOnClickListener {
            photos.forEach { it.selected = false }
            gridAdapter.refresh()
            updateSelectionText()
        }

        btnGenerate.setOnClickListener { generate() }
        btnExport.setOnClickListener { exportPdf() }
    }

    private fun generate() {
        if (busy) return
        val picked = photos.filter { it.selected }
        if (picked.isEmpty()) {
            toast("先勾选至少一张照片")
            return
        }
        if (picked.size > MAX_WARN) {
            AlertDialog.Builder(this)
                .setTitle("图片较多")
                .setMessage("一次处理 ${picked.size} 张会较慢且占用内存，建议分批。是否继续？")
                .setPositiveButton("继续") { _, _ -> runGenerate(picked) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        runGenerate(picked)
    }

    private fun runGenerate(picked: List<PhotoItem>) {
        setBusy(true)
        val options = currentOptions()
        progress.max = picked.size
        progress.progress = 0
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            results.values.forEach { it.recycle() }
            results.clear()
            picked.forEach { it.failed = false; it.lineThumb = null }

            var done = 0
            var failed = 0
            for (item in picked) {
                val line = withContext(Dispatchers.IO) {
                    val src = ColoringProcessor.decodeSampled(contentResolver, item.uri, 1400)
                        ?: return@withContext null
                    ColoringProcessor.process(src, options)
                }
                if (line == null) {
                    item.failed = true
                    failed++
                } else {
                    results[item.id] = line
                    item.lineThumb = previewOf(line)
                }
                done++
                progress.progress = done
                textStatus.text = "处理中 $done/${picked.size}"
                gridAdapter.refresh()
            }

            progress.visibility = View.GONE
            textStatus.text = if (results.isEmpty()) {
                "全部处理失败，换几张照片试试"
            } else {
                "已生成 ${results.size} 张线稿${if (failed > 0) "（失败 $failed 张）" else ""}，可调参数重算或直接导出 PDF"
            }
            setBusy(false)
        }
    }

    private fun previewOf(line: Bitmap): Bitmap {
        val w = 320
        val h = (w.toFloat() * line.height / line.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(line, w, h, true)
    }

    private fun exportPdf() {
        if (busy) return
        if (results.isEmpty()) {
            toast("先点「生成线稿」")
            return
        }
        setBusy(true)
        textStatus.text = "正在生成 PDF…"

        lifecycleScope.launch {
            val images = results.values.toList()
            val bytes = withContext(Dispatchers.IO) {
                ColoringPdfExporter.build(images)
            }
            val name = "涂色画_${
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            }.pdf"
            val uri = withContext(Dispatchers.IO) {
                ColoringPdfExporter.save(this@ColoringActivity, bytes, name)
            }
            setBusy(false)

            if (uri == null) {
                textStatus.text = "保存失败，请检查存储空间或权限"
                toast("PDF 保存失败")
            } else {
                textStatus.text = "已保存 $name（下载 / 妥妥涂色画）"
                AlertDialog.Builder(this@ColoringActivity)
                    .setTitle("PDF 已生成")
                    .setMessage("共 ${images.size} 页，保存在 下载 / 妥妥涂色画 / $name")
                    .setPositiveButton("打开") { _, _ -> openPdf(uri) }
                    .setNegativeButton("完成", null)
                    .show()
            }
        }
    }

    private fun openPdf(uri: Uri) {
        val target = if (uri.scheme == "file") {
            try {
                FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", File(uri.path ?: return)
                )
            } catch (e: Exception) {
                uri
            }
        } else {
            uri
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "打开 PDF"))
        } catch (e: Exception) {
            toast("没有找到能打开 PDF 的应用")
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        btnGenerate.isEnabled = !value
        btnExport.isEnabled = !value
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private fun hasPermission(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() =
        requestPermissions(requiredPermissions(), REQ_PERMISSION)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSION) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadPhotos()
        } else {
            textStatus.text = "需要相册权限才能读取照片"
            AlertDialog.Builder(this)
                .setTitle("需要相册权限")
                .setMessage("请在系统设置里允许本应用读取照片，才能把照片转成涂色画。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    override fun onDestroy() {
        results.values.forEach { it.recycle() }
        results.clear()
        super.onDestroy()
    }
}
