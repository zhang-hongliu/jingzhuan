package com.example.migratable.coloring

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.migratable.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 涂色画：打开系统相册挑图 → 批量生成线稿 → 导出 PDF 到下载目录。
 */
class ColoringActivity : AppCompatActivity() {

    private val photos = mutableListOf<PhotoItem>()
    /** 线稿结果只存掩膜，导出时才渲染成高清位图 */
    private val results = LinkedHashMap<Long, ColoringProcessor.LineArt>()
    private lateinit var gridAdapter: PhotoGridAdapter
    private lateinit var grid: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var textStatus: TextView
    private lateinit var textSelection: TextView
    private lateinit var textEmpty: TextView
    private lateinit var btnGenerate: Button
    private lateinit var btnExport: Button

    private var busy = false
    private var threshold = 50
    private var autoThreshold = true
    private var minArea = 60
    private var thicken = 1
    private var mode = ColoringProcessor.EdgeMode.XDOG
    /** 线稿输出分辨率，越大越清晰也越慢 */
    private var outSide = 1800

    /** Android 13+ 官方照片选择器：多选，不需要任何存储权限 */
    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)
    ) { uris -> onPicked(uris) }

    /** 旧系统的兜底：系统文件/相册应用多选图片 */
    private val legacyPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data ?: return@registerForActivityResult
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        uris.forEach { tryPersist(it) }
        onPicked(uris)
    }

    companion object {
        private const val REQ_PERMISSION = 4101
        private const val MAX_PHOTOS = 400
        private const val MAX_WARN = 30
        /** 单次从相册选择的张数上限 */
        private const val MAX_PICK = 50
        private const val THUMB = 512
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coloring)

        progress = findViewById(R.id.progress)
        textStatus = findViewById(R.id.text_status)
        textSelection = findViewById(R.id.text_selection)
        textEmpty = findViewById(R.id.text_empty)
        btnGenerate = findViewById(R.id.btn_generate)
        btnExport = findViewById(R.id.btn_export)

        setupParams()
        setupGrid()
        setupActions()

        updateSelectionText()
        textStatus.text = "打开相册选几张照片，然后点「生成线稿」"
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    private fun setupParams() {
        val labelThreshold = findViewById<TextView>(R.id.text_threshold)
        val labelClean = findViewById<TextView>(R.id.text_clean)
        val labelThicken = findViewById<TextView>(R.id.text_thicken)

        findViewById<android.widget.CheckBox>(R.id.check_auto)
            .setOnCheckedChangeListener { _, checked -> autoThreshold = checked }

        val refresh = {
            labelThreshold.text = if (autoThreshold) {
                "线条量：自动（约 6% 墨迹）"
            } else {
                "线条量：$threshold（手动，越小线越多）"
            }
            labelClean.text = "清理杂线：$minArea（越大删得越狠）"
            labelThicken.text = "描边加粗：$thicken"
        }
        refresh()

        onSeek(findViewById(R.id.seek_threshold)) { threshold = it; refresh() }
        onSeek(findViewById(R.id.seek_clean)) { minArea = it; refresh() }
        onSeek(findViewById(R.id.seek_thicken)) { thicken = it; refresh() }

        findViewById<RadioGroup>(R.id.radio_size)
            .setOnCheckedChangeListener { _, id ->
                outSide = when (id) {
                    R.id.radio_size_std -> 1200
                    R.id.radio_size_uhd -> 2200
                    else -> 1800
                }
            }

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
        maxSide = outSide,
        mode = mode,
        threshold = threshold,
        autoThreshold = autoThreshold,
        minArea = minArea,
        thicken = thicken
    )

    // ------------------------------------------------------------------
    // 图片网格
    // ------------------------------------------------------------------

    private fun setupGrid() {
        gridAdapter = PhotoGridAdapter(
            onToggle = { updateSelectionText() },
            onNeedThumb = { item -> loadThumbAsync(item) },
            onRemove = { item -> removePhoto(item) }
        )
        grid = findViewById(R.id.grid_photos)
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

    private fun readThumbnail(item: PhotoItem): Bitmap? {
        // 系统相册选择器返回的 uri 在部分机型上取不到缩略图，失败就退回自己解码
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                contentResolver
                    .loadThumbnail(item.uri, Size(THUMB, THUMB), null)
                    ?.let { return it }
            } catch (e: Exception) {
                // 继续走下面的兜底
            }
        }
        return try {
            ColoringProcessor.decodeSampled(contentResolver, item.uri, THUMB)
        } catch (e: Exception) {
            null
        }
    }

    private fun updateSelectionText() {
        val n = photos.count { it.selected }
        textSelection.text = "已选 $n / ${photos.size} 张"
        textEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun removePhoto(item: PhotoItem) {
        val idx = photos.indexOfFirst { it.id == item.id }
        if (idx < 0) return
        photos.removeAt(idx)
        results.remove(item.id)
        item.lineThumb?.recycle()
        gridAdapter.removeAt(idx)
        updateSelectionText()
    }

    // ------------------------------------------------------------------
    // 打开相册选图
    // ------------------------------------------------------------------

    private fun openPhotoPicker() {
        if (busy) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                return
            } catch (e: Exception) {
                // 少数机型没有照片选择器，走老的多选方案
            }
        }
        openLegacyPicker()
    }

    private fun openLegacyPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            legacyPicker.launch(Intent.createChooser(intent, "选择图片"))
        } catch (e: Exception) {
            toast("打不开相册，可试试「浏览全部照片」")
        }
    }

    private fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val seen = HashSet<String>()
        photos.forEach { seen.add(it.uri.toString()) }
        var added = 0
        uris.forEach { uri ->
            if (seen.add(uri.toString())) {
                photos.add(PhotoItem(uri = uri, name = displayNameOf(uri), selected = true))
                added++
            }
        }
        gridAdapter.submit(photos)
        updateSelectionText()
        textStatus.text = if (added > 0) {
            "已加入 $added 张（共 ${photos.size} 张），点「生成线稿」"
        } else {
            "这些图片已经在列表里了"
        }
        if (added > 0) grid.scrollToPosition(photos.size - 1)
    }

    private fun displayNameOf(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else ""
        } ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun tryPersist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // 部分相册不允许持久化授权，同一次会话内仍可读取
        }
    }

    /** 兜底入口：直接列出相册里最近的全部照片 */
    private fun loadPhotos() {
        textStatus.text = "正在读取相册…"
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { queryPhotos() }
            val seen = HashSet<String>()
            photos.forEach { seen.add(it.uri.toString()) }
            var added = 0
            list.forEach { item ->
                if (seen.add(item.uri.toString())) {
                    item.selected = false
                    photos.add(item)
                    added++
                }
            }
            gridAdapter.submit(photos)
            updateSelectionText()
            textStatus.text = when {
                added == 0 && photos.isEmpty() -> "相册里没找到图片"
                added == 0 -> "列表里已经是相册最近的全部照片"
                else -> "已加入最近 $added 张（共 ${photos.size} 张），勾选后点「生成线稿」"
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
                out.add(PhotoItem(uri = uri, name = c.getString(nameCol) ?: ""))
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private fun setupActions() {
        findViewById<Button>(R.id.btn_pick_photos).setOnClickListener { openPhotoPicker() }
        findViewById<Button>(R.id.btn_browse_all).setOnClickListener {
            if (hasPermission()) loadPhotos() else requestPermission()
        }

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
        findViewById<Button>(R.id.btn_remove_sel).setOnClickListener {
            val picked = photos.filter { it.selected }
            if (picked.isEmpty()) {
                toast("先勾选要移除的图片")
                return@setOnClickListener
            }
            picked.forEach { item ->
                photos.remove(item)
                results.remove(item.id)
                item.lineThumb?.recycle()
            }
            gridAdapter.submit(photos)
            updateSelectionText()
            textStatus.text = "已移除 ${picked.size} 张，剩 ${photos.size} 张"
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
        // 张数多时自动降一档，避免高清线稿把内存吃满
        val side = when {
            picked.size > 20 -> minOf(outSide, 1200)
            picked.size > 12 -> minOf(outSide, 1800)
            else -> outSide
        }
        val options = currentOptions().copy(maxSide = side)
        progress.max = picked.size
        progress.progress = 0
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            results.clear()
            picked.forEach { item ->
                item.failed = false
                // 不回收旧缩略图：它可能还在界面上显示着，交给 GC 即可
                item.lineThumb = null
            }
            gridAdapter.refresh()

            var done = 0
            var failed = 0
            for (item in picked) {
                val pair = withContext(Dispatchers.IO) {
                    // 多给 20% 采样余量，再做一次等比缩放，边缘更平滑不锯齿
                    val src = ColoringProcessor.decodeSampled(
                        contentResolver, item.uri, (side * 1.2f).toInt()
                    ) ?: return@withContext null
                    val art = ColoringProcessor.processMask(src, options)
                    art to ColoringProcessor.renderPreview(art, 320)
                }
                if (pair == null) {
                    item.failed = true
                    failed++
                } else {
                    results[item.id] = pair.first
                    item.lineThumb = pair.second
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
                "已生成 ${results.size} 张线稿（${side}px）${if (failed > 0) "（失败 $failed 张）" else ""}，可调参数重算或直接导出 PDF"
            }
            setBusy(false)
        }
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
    // 权限（只有「浏览全部照片」才需要）
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
            textStatus.text = "没给相册权限，用「打开相册选图片」一样可以选照片"
            AlertDialog.Builder(this)
                .setTitle("需要相册权限")
                .setMessage("这个入口需要读取全部照片；不给权限也没关系，用上面的「打开相册选图片」挑图就行。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    override fun onDestroy() {
        results.clear()
        photos.forEach { it.lineThumb?.recycle() }
        super.onDestroy()
    }
}
