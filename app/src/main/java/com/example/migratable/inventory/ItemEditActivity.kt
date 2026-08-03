package com.example.migratable.inventory

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.migratable.R
import com.example.migratable.inventory.House
import com.example.migratable.inventory.InventoryDao
import com.example.migratable.inventory.StorageLocation
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/** 物品新增 / 编辑：拍照 + OCR 识别日期、扫条码/二维码、手动选日期、选择房子与位置 */
class ItemEditActivity : AppCompatActivity() {

    private var itemId: Long = 0
    private var existing: InvItem? = null

    private var photoPath: String? = null
    private var pendingPhotoFile: File? = null
    private var barcode: String? = null
    private var expiryAt: Long? = null

    private var houses: List<House> = emptyList()
    private var locations: List<LocationWithHouse> = emptyList()
    private var selectedLocationId: Long? = null
    private var selectedHouseId: Long? = null

    private lateinit var editCustom: EditText

    private lateinit var imgPhoto: ImageView
    private lateinit var textExpiry: TextView
    private lateinit var textBarcode: TextView
    private lateinit var spinnerHouse: Spinner
    private lateinit var spinnerLocation: Spinner

    // ---------- 拍照 ----------
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhotoFile ?: return@registerForActivityResult
        if (ok && file.exists()) {
            // 删除旧照片
            photoPath?.let { old -> if (old != file.absolutePath) File(old).delete() }
            photoPath = file.absolutePath
            showPhoto()
            recognizeDateFromPhoto(file)
        } else {
            file.delete()
        }
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
    }

    // ---------- 扫码 ----------
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents ?: return@registerForActivityResult
        barcode = content
        showBarcode()
        val parsed = DateParser.parse(content)
        if (parsed != null) {
            askApplyDate(parsed, "从码中识别到日期 ${DateParser.format(parsed)}，设为过期时间？")
        } else {
            Toast.makeText(this, "码内容已记录，未识别到日期，可手动选择", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item_edit)

        itemId = intent.getLongExtra("itemId", 0)

        imgPhoto = findViewById(R.id.img_photo)
        textExpiry = findViewById(R.id.text_expiry)
        textBarcode = findViewById(R.id.text_barcode)
        spinnerHouse = findViewById(R.id.spinner_house)
        spinnerLocation = findViewById(R.id.spinner_location)
        editCustom = findViewById(R.id.edit_custom_location)
        val editName = findViewById<EditText>(R.id.edit_name)
        val editRemind = findViewById<EditText>(R.id.edit_remind_days)
        val btnDelete = findViewById<Button>(R.id.btn_delete)

        findViewById<Button>(R.id.btn_photo).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) launchCamera()
            else requestCamera.launch(Manifest.permission.CAMERA)
        }

        findViewById<Button>(R.id.btn_scan).setOnClickListener {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                    .setPrompt("对准条形码 / 二维码")
                    .setBeepEnabled(true)
                    .setOrientationLocked(true)
            )
        }

        findViewById<Button>(R.id.btn_pick_date).setOnClickListener { pickDate() }
        findViewById<Button>(R.id.btn_clear_date).setOnClickListener {
            expiryAt = null; showExpiry()
        }
        findViewById<Button>(R.id.btn_manage_places).setOnClickListener {
            startActivity(Intent(this, PlacesActivity::class.java))
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val name = editName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请填写物品名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val remindDays = editRemind.text.toString().toIntOrNull() ?: 3
            saveItem(name, remindDays)
        }

        if (itemId > 0) {
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener { confirmDelete() }
            lifecycleScope.launch {
                val item = AppDatabase.get(this@ItemEditActivity).inventoryDao().getItem(itemId)
                    ?: return@launch
                existing = item
                editName.setText(item.name)
                editRemind.setText(item.remindDaysBefore.toString())
                photoPath = item.photoPath
                barcode = item.barcode
                expiryAt = item.expiryAt
                selectedLocationId = item.locationId
                showPhoto(); showBarcode(); showExpiry()
                loadPlaces()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadPlaces()
    }

    // ---------- 房子 / 位置选择 ----------
    private fun loadPlaces() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@ItemEditActivity).inventoryDao()
            houses = dao.listHouses()
            locations = dao.listLocations()
            setupHouseSpinner()
        }
    }

    private fun setupHouseSpinner() {
        val houseNames = listOf("（不指定）") + houses.map { it.name }
        spinnerHouse.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, houseNames
        )

        // 回显：根据已选位置定位房子
        val selLoc = locations.find { it.location.id == selectedLocationId }
        if (selLoc != null) {
            val idx = houses.indexOfFirst { it.id == selLoc.house.id }
            if (idx >= 0) spinnerHouse.setSelection(idx + 1)
        }

        spinnerHouse.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedHouseId = if (pos == 0) null else houses[pos - 1].id
                setupLocationSpinner(selectedHouseId)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        setupLocationSpinner(selLoc?.house?.id)
    }

    private fun setupLocationSpinner(houseId: Long?) {
        val locs = if (houseId == null) emptyList()
        else locations.filter { it.house.id == houseId }
        val names = listOf("（不指定）") + locs.map { it.location.name }
        spinnerLocation.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )

        val idx = locs.indexOfFirst { it.location.id == selectedLocationId }
        if (idx >= 0) spinnerLocation.setSelection(idx + 1)

        spinnerLocation.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedLocationId = if (pos == 0) null else locs[pos - 1].location.id
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    // ---------- 拍照 & OCR ----------
    private fun launchCamera() {
        val dir = File(filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "item_${System.currentTimeMillis()}.jpg")
        pendingPhotoFile = file
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        takePicture.launch(uri)
    }

    /** 用 ML Kit 中文 OCR 从照片中找过期日期 */
    private fun recognizeDateFromPhoto(file: File) {
        val image = try {
            InputImage.fromFilePath(this, Uri.fromFile(file))
        } catch (e: Exception) {
            Toast.makeText(this, "照片已保存（读取失败，无法识别文字）", Toast.LENGTH_SHORT).show()
            return
        }
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            .process(image)
            .addOnSuccessListener { visionText ->
                val parsed = DateParser.parse(visionText.text)
                if (parsed != null) {
                    askApplyDate(parsed, "从照片识别到日期 ${DateParser.format(parsed)}，设为过期时间？")
                } else {
                    Toast.makeText(this, "照片已保存，未识别到日期，可手动选择", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "照片已保存（文字识别失败）", Toast.LENGTH_SHORT).show()
            }
    }

    private fun askApplyDate(millis: Long, message: String) {
        AlertDialog.Builder(this)
            .setTitle("识别到日期")
            .setMessage(message)
            .setPositiveButton("使用") { _, _ ->
                expiryAt = millis; showExpiry()
            }
            .setNegativeButton("不用", null)
            .show()
    }

    // ---------- 日期选择 ----------
    private fun pickDate() {
        val c = Calendar.getInstance().apply {
            expiryAt?.let { timeInMillis = it }
        }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                expiryAt = DateParser.toMillis(y, m + 1, d)
                showExpiry()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ---------- 展示 ----------
    private fun showPhoto() {
        val p = photoPath
        if (p != null && File(p).exists()) {
            imgPhoto.setImageBitmap(BitmapFactory.decodeFile(p))
        }
    }

    private fun showBarcode() {
        val b = barcode
        if (b != null) {
            textBarcode.visibility = View.VISIBLE
            textBarcode.text = "码：$b"
        } else textBarcode.visibility = View.GONE
    }

    private fun showExpiry() {
        val e = expiryAt
        textExpiry.text = if (e == null) "未设置" else {
            val days = DateParser.daysLeft(e)
            DateParser.format(e) + when {
                days < 0 -> "（已过期 ${-days} 天）"
                days == 0 -> "（今天过期）"
                else -> "（还剩 $days 天）"
            }
        }
    }

    // ---------- 保存 / 删除 ----------
    private fun saveItem(name: String, remindDays: Int) {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@ItemEditActivity).inventoryDao()

            // 手动填写的位置优先：按所选房子查找或新建位置
            val custom = editCustom.text.toString().trim()
            if (custom.isNotEmpty()) {
                val houseId = selectedHouseId ?: ensureDefaultHouse(dao)
                val loc = dao.findLocation(houseId, custom)
                    ?: StorageLocation(houseId = houseId, name = custom)
                        .let { l -> l.copy(id = dao.insertLocation(l)) }
                selectedLocationId = loc.id
            }

            val base = existing
            if (base == null) {
                dao.insertItem(
                    InvItem(
                        name = name, locationId = selectedLocationId,
                        photoPath = photoPath, barcode = barcode,
                        expiryAt = expiryAt, remindDaysBefore = remindDays
                    )
                )
            } else {
                dao.updateItem(
                    base.copy(
                        name = name, locationId = selectedLocationId,
                        photoPath = photoPath, barcode = barcode,
                        expiryAt = expiryAt, remindDaysBefore = remindDays,
                        // 过期时间变化后重置提醒状态
                        lastNotifiedDay = if (base.expiryAt != expiryAt) 0 else base.lastNotifiedDay
                    )
                )
            }
            ExpiryScheduler.schedule(this@ItemEditActivity)
            Toast.makeText(this@ItemEditActivity, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** 未选择房子时，确保存在一个默认房子「我的家」并返回其 id */
    private suspend fun ensureDefaultHouse(dao: InventoryDao): Long {
        val existingHouse = dao.findHouse("我的家")
        return existingHouse?.id ?: dao.insertHouse(House(name = "我的家"))
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除物品")
            .setMessage("确定删除该物品吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    existing?.let {
                        AppDatabase.get(this@ItemEditActivity).inventoryDao().deleteItem(it)
                        it.photoPath?.let { p -> File(p).delete() }
                    }
                    finish()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
