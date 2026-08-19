package com.friday.openwatch

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var widthInput: EditText
    private lateinit var heightInput: EditText
    private lateinit var previewImage: ImageView
    private lateinit var pushWatchfaceBtn: MaterialButton
    private lateinit var setupCard: LinearLayout
    private lateinit var setupToggle: TextView
    private var selectedBitmap: Bitmap? = null
    private var originalBitmap: Bitmap? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results handled by re-checking before each action */ }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) loadPreview(uri)
    }

    private val watchfaceProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WatchBleService.ACTION_WATCHFACE_PROGRESS -> {
                    val sent = intent.getIntExtra(WatchBleService.EXTRA_SENT, 0)
                    val total = intent.getIntExtra(WatchBleService.EXTRA_TOTAL, 1)
                    val pct = if (total > 0) (sent * 100 / total) else 0
                    pushWatchfaceBtn.text = "Pushing… $pct% ($sent/$total)"
                }
                WatchBleService.ACTION_WATCHFACE_DONE -> {
                    val success = intent.getBooleanExtra(WatchBleService.EXTRA_SUCCESS, false)
                    pushWatchfaceBtn.isEnabled = true
                    pushWatchfaceBtn.alpha = 1f
                    pushWatchfaceBtn.text = "Push to watch"
                    statusText.text = if (success) "Watchface push complete." else "Watchface push failed."
                }
            }
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
        setPadding(20.dp(), 18.dp(), 20.dp(), 18.dp())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16.dp() }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.text_secondary))
        letterSpacing = 0.05f
        setPadding(0, 0, 0, 10.dp())
    }

    private fun bodyText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(color(R.color.text_secondary))
        setLineSpacing(6f, 1f)
    }

    private fun primaryButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            cornerRadius = 14.dp()
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10.dp() }
            setOnClickListener { onClick() }
        }

    private fun secondaryButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            cornerRadius = 14.dp()
            strokeColor = android.content.res.ColorStateList.valueOf(color(R.color.divider))
            setTextColor(color(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { topMargin = 10.dp() }
            setOnClickListener { onClick() }
        }

    private fun rowSpacer(width: Int = 10) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(width.dp(), 1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(color(R.color.background))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 28.dp(), 20.dp(), 32.dp())
        }

        // Header
        root.addView(TextView(this).apply {
            text = "OpenWatch"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text_primary))
        })
        root.addView(TextView(this).apply {
            text = "Local, no-cloud companion for your watch"
            textSize = 13.5f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, 4.dp(), 0, 24.dp())
        })

        // Status card
        val statusCard = card()
        statusCard.addView(sectionTitle("STATUS"))
        statusText = bodyText(statusSummary())
        statusCard.addView(statusText)
        val statusBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusBtnRow.addView(secondaryButton("Refresh") { statusText.text = statusSummary() })
        statusBtnRow.addView(rowSpacer())
        statusBtnRow.addView(secondaryButton("Scan & connect") { scanAndConnect() })
        statusCard.addView(statusBtnRow)
        statusCard.addView(secondaryButtonFullWidth("Reset pairing (after watch factory reset)") {
            Prefs.clearBoundOnce(this@MainActivity)
            statusText.text = "Pairing state cleared - next connect will re-bind.\n\n" + statusSummary()
        })
        root.addView(statusCard)

        // Setup card - only shown until first-time setup is fully done
        setupCard = card()
        setupCard.addView(sectionTitle("FIRST-TIME SETUP"))
        setupCard.addView(bodyText("Bluetooth, notification access, and battery exemption - required once."))
        setupCard.addView(primaryButton("Grant Bluetooth + notification permissions") { requestRuntimePermissions() })
        setupCard.addView(secondaryButtonFullWidth("Open notification access settings") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        setupCard.addView(secondaryButtonFullWidth("Exempt from battery optimization") {
            requestIgnoreBatteryOptimizations()
        })
        root.addView(setupCard)

        setupToggle = TextView(this).apply {
            textSize = 12.5f
            setTextColor(color(R.color.accent))
            setPadding(0, 0, 0, 16.dp())
            setOnClickListener {
                setupCard.visibility = if (setupCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                updateSetupToggleText()
            }
        }
        root.addView(setupToggle)

        // Features card
        val featuresCard = card()
        featuresCard.addView(sectionTitle("FEATURES"))
        featuresCard.addView(primaryButton("Find my watch (buzz for 5s)") {
            WatchBleService.findWatch(this@MainActivity)
        })
        root.addView(featuresCard)

        // Watchface card
        val watchfaceCard = card()
        watchfaceCard.addView(sectionTitle("CUSTOM WATCH FACE"))
        watchfaceCard.addView(bodyText("Protocol confirmed from a real device capture (RGB565 BMP, chunked transfer). Default size matches a confirmed real dial; adjust if yours differs."))
        val dimsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10.dp() }
        }
        widthInput = EditText(this).apply {
            hint = "width"
            setText("240")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heightInput = EditText(this).apply {
            hint = "height"
            setText("296")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dimsRow.addView(widthInput)
        dimsRow.addView(rowSpacer())
        dimsRow.addView(heightInput)
        watchfaceCard.addView(dimsRow)

        val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pickRow.addView(secondaryButton("Pick image") { pickImageLauncher.launch("image/*") })
        pickRow.addView(rowSpacer())
        pickRow.addView(secondaryButton("Recrop") { applyCropAndPreview() })
        watchfaceCard.addView(pickRow)

        previewImage = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 220.dp()
            ).apply { topMargin = 10.dp() }
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_soft)
        }
        watchfaceCard.addView(previewImage)

        pushWatchfaceBtn = primaryButton("Push to watch") { pushSelectedWatchface() }.apply {
            isEnabled = false
            alpha = 0.5f
        }
        watchfaceCard.addView(pushWatchfaceBtn)
        root.addView(watchfaceCard)

        // Log card
        val logCard = card()
        logCard.addView(sectionTitle("ERROR / CONNECTION LOG"))
        val logRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logRow.addView(secondaryButton("Refresh log") { logText.text = AppLog.readAll(this@MainActivity) })
        logRow.addView(rowSpacer())
        logRow.addView(secondaryButton("Clear log") {
            AppLog.clear(this@MainActivity)
            logText.text = AppLog.readAll(this@MainActivity)
        })
        logCard.addView(logRow)

        logText = TextView(this).apply {
            text = AppLog.readAll(this@MainActivity)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#D1FADF"))
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
        }
        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 240.dp()
            ).apply { topMargin = 10.dp() }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_log)
            addView(logText)
        }
        logCard.addView(logScroll)
        root.addView(logCard)

        val outerScroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.background))
            addView(root)
        }
        setContentView(outerScroll)

        setupCard.visibility = if (isSetupComplete()) View.GONE else View.VISIBLE
        updateSetupToggleText()
    }

    private fun isSetupComplete(): Boolean {
        val hasBt = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
        return hasBt && isNotificationAccessGranted() && isIgnoringBatteryOptimizations()
    }

    private fun updateSetupToggleText() {
        setupToggle.text = if (setupCard.visibility == View.VISIBLE) "Hide setup" else "Show first-time setup"
    }

    private fun secondaryButtonFullWidth(text: String, onClick: () -> Unit): MaterialButton =
        secondaryButton(text, onClick).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10.dp() }
        }

    override fun onResume() {
        super.onResume()
        statusText.text = statusSummary()
        if (::logText.isInitialized) logText.text = AppLog.readAll(this)
        if (::setupCard.isInitialized && isSetupComplete() && setupCard.visibility == View.VISIBLE) {
            setupCard.visibility = View.GONE
            updateSetupToggleText()
        }
        val filter = IntentFilter().apply {
            addAction(WatchBleService.ACTION_WATCHFACE_PROGRESS)
            addAction(WatchBleService.ACTION_WATCHFACE_DONE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(watchfaceProgressReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(watchfaceProgressReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(watchfaceProgressReceiver) } catch (_: Exception) {}
    }

    private fun statusSummary(): String {
        val addr = Prefs.deviceAddress(this) ?: "not paired"
        val bound = Prefs.isBoundOnce(this)
        val notifAccess = isNotificationAccessGranted()
        val battery = isIgnoringBatteryOptimizations()
        return """
            Device: $addr
            Bound (first-time setup done): $bound
            Notification access granted: $notifAccess
            Battery optimization exempt: $battery
        """.trimIndent()
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun hasPermission(perm: String) =
        ActivityCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun scanAndConnect() {
        val needsScan = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        val needsConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        if (!needsScan || !needsConnect) {
            requestRuntimePermissions()
            return
        }

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Bluetooth adapter not available/enabled"
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            statusText.text = "bluetoothLeScanner is null (adapter not ready) - toggle Bluetooth off/on and retry"
            return
        }
        statusText.text = "Scanning for 10s..."

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name == "Watch") {
                    scanner.stopScan(this)
                    val address = result.device.address
                    Prefs.setDeviceAddress(this@MainActivity, address)
                    WatchBleService.connectTo(this@MainActivity, address)
                    runOnUiThread { statusText.text = "Found watch: $address\nConnecting..." }
                }
            }
        }
        scanner.startScan(callback)
        android.os.Handler(mainLooper).postDelayed({
            scanner.stopScan(callback)
        }, 10_000)
    }

    private fun loadPreview(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(input)
            input.close()
            if (bitmap == null) {
                statusText.text = "Could not decode picked image"
                return
            }
            originalBitmap = bitmap
            applyCropAndPreview()
            pushWatchfaceBtn.isEnabled = true
            pushWatchfaceBtn.alpha = 1f
        } catch (e: Exception) {
            AppLog.log(this, "MainActivity", "image preview failed: ${e.message}")
            statusText.text = "Could not load image: ${e.message}"
        }
    }

    /** Center-crops (no distortion) to exactly match the target width:height aspect ratio. */
    private fun centerCropToAspect(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val srcRatio = src.width.toFloat() / src.height.toFloat()
        val cropWidth: Int
        val cropHeight: Int
        if (srcRatio > targetRatio) {
            cropHeight = src.height
            cropWidth = (cropHeight * targetRatio).toInt().coerceAtMost(src.width)
        } else {
            cropWidth = src.width
            cropHeight = (cropWidth / targetRatio).toInt().coerceAtMost(src.height)
        }
        val x = (src.width - cropWidth) / 2
        val y = (src.height - cropHeight) / 2
        return Bitmap.createBitmap(src, x, y, cropWidth, cropHeight)
    }

    private fun applyCropAndPreview() {
        val original = originalBitmap ?: return
        val width = widthInput.text.toString().toIntOrNull() ?: 240
        val height = heightInput.text.toString().toIntOrNull() ?: 296
        val cropped = centerCropToAspect(original, width, height)
        selectedBitmap = cropped
        previewImage.setImageBitmap(cropped)
        previewImage.visibility = View.VISIBLE
    }

    private fun pushSelectedWatchface() {
        val bitmap = selectedBitmap ?: return
        if (!pushWatchfaceBtn.isEnabled) return // guard against double-tap queuing overlapping transfers
        try {
            val width = widthInput.text.toString().toIntOrNull() ?: 240
            val height = heightInput.text.toString().toIntOrNull() ?: 296
            val frame = WatchfaceConverter.buildDialFile(this, bitmap, width, height)
            val file = File(cacheDir, "watchface_frame.bin")
            file.writeBytes(frame)
            AppLog.log(this, "MainActivity", "watchface: ${width}x$height, ${frame.size} bytes -> ${file.path}")
            WatchBleService.pushWatchface(this, file.path)
            statusText.text = "Pushing watchface (${width}x$height, ${frame.size} bytes)…"

            pushWatchfaceBtn.isEnabled = false
            pushWatchfaceBtn.alpha = 0.5f
            pushWatchfaceBtn.text = "Pushing… 0%"
        } catch (e: Exception) {
            AppLog.log(this, "MainActivity", "watchface push failed: ${e.message}")
            statusText.text = "Watchface push failed: ${e.message}"
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @Suppress("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}
