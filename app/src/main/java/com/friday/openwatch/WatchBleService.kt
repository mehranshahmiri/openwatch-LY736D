package com.friday.openwatch

import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.ArrayDeque

class WatchBleService : Service() {

    companion object {
        private const val TAG = "WatchBleService"
        private const val CHANNEL_ID = "watch_ble_status"
        private const val NOTIF_ID = 1
        private const val BASE_RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val TIME_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val COMMAND_SPACING_MS = 120L

        const val ACTION_CONNECT = "com.friday.openwatch.action.CONNECT"
        const val ACTION_PUSH_NOTIFICATION = "com.friday.openwatch.action.PUSH_NOTIFICATION"
        const val ACTION_FIND_WATCH = "com.friday.openwatch.action.FIND_WATCH"
        const val ACTION_PUSH_WATCHFACE = "com.friday.openwatch.action.PUSH_WATCHFACE"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ICON = "icon"
        const val EXTRA_FRAME_PATH = "frame_path"

        const val ACTION_WATCHFACE_PROGRESS = "com.friday.openwatch.action.WATCHFACE_PROGRESS"
        const val ACTION_WATCHFACE_DONE = "com.friday.openwatch.action.WATCHFACE_DONE"
        const val EXTRA_SENT = "sent"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_SUCCESS = "success"

        const val ACTION_BATTERY_UPDATE = "com.friday.openwatch.action.BATTERY_UPDATE"
        const val EXTRA_BATTERY_LEVEL = "battery_level"

        fun pushNotification(ctx: Context, text: String, iconCode: Int) {
            val intent = Intent(ctx, WatchBleService::class.java).apply {
                action = ACTION_PUSH_NOTIFICATION
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_ICON, iconCode)
            }
            ctx.startForegroundService(intent)
        }

        fun connectTo(ctx: Context, address: String) {
            val intent = Intent(ctx, WatchBleService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_ADDRESS, address)
            }
            ctx.startForegroundService(intent)
        }

        fun findWatch(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, WatchBleService::class.java).apply {
                action = ACTION_FIND_WATCH
            })
        }

        /** framePath: a file containing the pre-built frame buffer (see WatchfaceConverter). */
        fun pushWatchface(ctx: Context, framePath: String) {
            ctx.startForegroundService(Intent(ctx, WatchBleService::class.java).apply {
                action = ACTION_PUSH_WATCHFACE
                putExtra(EXTRA_FRAME_PATH, framePath)
            })
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var connected = false
    private var deviceAddress: String? = null
    private var reconnectDelay = BASE_RECONNECT_DELAY_MS

    private val outbox = ArrayDeque<ByteArray>()
    private var draining = false

    private var watchfaceActive = false
    private var watchfaceSent = 0
    private var watchfaceTotal = 0

    private fun log(msg: String) = AppLog.log(this, TAG, msg)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildStatusNotification("Starting…"))
        deviceAddress = Prefs.deviceAddress(this)
        log("service created, saved address=$deviceAddress")
        if (deviceAddress != null) connect(deviceAddress!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS)
                if (addr != null) {
                    Prefs.setDeviceAddress(this, addr)
                    deviceAddress = addr
                    reconnectDelay = BASE_RECONNECT_DELAY_MS
                    connect(addr)
                }
            }
            ACTION_PUSH_NOTIFICATION -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                val icon = intent.getIntExtra(EXTRA_ICON, Protocol.Icon.SMS)
                log("push request: '$text' icon=$icon connected=$connected writeChar=${writeChar != null}")
                enqueue(Protocol.pushNotification(text, icon))
            }
            ACTION_FIND_WATCH -> {
                log("find-watch requested")
                enqueue(Protocol.findWatch(true))
                handler.postDelayed({ enqueue(Protocol.findWatch(false)) }, 5000)
            }
            ACTION_PUSH_WATCHFACE -> {
                val path = intent.getStringExtra(EXTRA_FRAME_PATH)
                if (path != null) pushWatchfaceFile(path)
            }
        }
        return START_STICKY
    }

    private fun pushWatchfaceFile(path: String) {
        try {
            val frame = File(path).readBytes()
            log("watchface push: ${frame.size} bytes total (font + algorithm-2 pic)")
            val chunks = WatchfaceConverter.chunk(frame)
            watchfaceActive = true
            watchfaceSent = 0
            watchfaceTotal = chunks.size + 2 // + start + finish
            broadcastWatchfaceProgress()
            enqueue(Protocol.Dial.updateStart(Protocol.Dial.SOURCE_TYPE_CUSTOM_PHOTO))
            handler.postDelayed({
                for ((seq, chunk) in chunks) {
                    enqueue(Protocol.Dial.updateChunk(seq, chunk))
                }
                enqueue(Protocol.Dial.updateFinish(frame.size, WatchfaceConverter.totalChecksum(frame)))
                log("watchface push: all chunks enqueued")
            }, 500)
        } catch (e: Exception) {
            log("watchface push failed: ${e.message}")
            watchfaceActive = false
            broadcastWatchfaceDone(false)
        }
    }

    private fun broadcastWatchfaceProgress() {
        sendBroadcast(Intent(ACTION_WATCHFACE_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_SENT, watchfaceSent)
            putExtra(EXTRA_TOTAL, watchfaceTotal)
        })
    }

    private fun broadcastWatchfaceDone(success: Boolean) {
        sendBroadcast(Intent(ACTION_WATCHFACE_DONE).apply {
            setPackage(packageName)
            putExtra(EXTRA_SUCCESS, success)
        })
    }

    @Suppress("MissingPermission")
    private fun connect(address: String) {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            log("connect() aborted: bluetooth adapter not available/enabled")
            updateStatus("Bluetooth not available")
            handler.postDelayed({ connect(address) }, BASE_RECONNECT_DELAY_MS)
            return
        }
        val device = adapter.getRemoteDevice(address)
        updateStatus("Connecting…")
        log("connectGatt($address, autoConnect=false, TRANSPORT_LE)")
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @Suppress("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                reconnectDelay = BASE_RECONNECT_DELAY_MS
                updateStatus("Connected, negotiating MTU…")
                g.requestMtu(200)
            } else {
                connected = false
                writeChar = null
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("connection failed with GATT status $status, backing off ${reconnectDelay}ms")
                }
                updateStatus("Disconnected (status=$status), retrying in ${reconnectDelay / 1000}s…")
                g.close()
                val addr = deviceAddress
                handler.postDelayed({ addr?.let { connect(it) } }, reconnectDelay)
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("onMtuChanged mtu=$mtu status=$status")
            updateStatus("Connected, discovering services…")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("onServicesDiscovered status=$status")
            val service = g.getService(Protocol.UART_SERVICE) ?: run {
                log("UART service not found among ${g.services.size} services")
                updateStatus("UART service not found")
                return
            }
            writeChar = service.getCharacteristic(Protocol.UART_WRITE)
            val notifyChar = service.getCharacteristic(Protocol.UART_NOTIFY)
            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(Protocol.CCCD)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }

            // Standard BLE Battery Service - separate from the proprietary UART service.
            val batteryService = g.getService(Protocol.BATTERY_SERVICE)
            val batteryChar = batteryService?.getCharacteristic(Protocol.BATTERY_LEVEL_CHAR)
            if (batteryChar != null) {
                g.setCharacteristicNotification(batteryChar, true)
                batteryChar.getDescriptor(Protocol.CCCD)?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(it)
                }
                handler.postDelayed({ g.readCharacteristic(batteryChar) }, 800)
            } else {
                log("battery service/characteristic not found")
            }

            handler.postDelayed({ runHandshake() }, 300)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                outbox.poll() // only remove on confirmed success; otherwise retry same packet
                if (watchfaceActive) {
                    watchfaceSent++
                    broadcastWatchfaceProgress()
                    if (watchfaceSent >= watchfaceTotal) {
                        watchfaceActive = false
                        log("watchface push complete: $watchfaceSent/$watchfaceTotal packets confirmed")
                        broadcastWatchfaceDone(true)
                    }
                }
            } else {
                log("write failed status=$status, will retry same packet")
            }
            draining = false
            drainOutbox()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.uuid == Protocol.BATTERY_LEVEL_CHAR && status == BluetoothGatt.GATT_SUCCESS) {
                handleBatteryValue(c.value)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == Protocol.BATTERY_LEVEL_CHAR) {
                handleBatteryValue(c.value)
            } else {
                log("notify: ${c.value?.joinToString("") { "%02x".format(it) }}")
            }
        }
    }

    private fun handleBatteryValue(value: ByteArray?) {
        val level = value?.getOrNull(0)?.toInt()?.and(0xFF) ?: return
        log("battery level: $level%")
        Prefs.setBatteryLevel(this, level)
        sendBroadcast(Intent(ACTION_BATTERY_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_BATTERY_LEVEL, level)
        })
    }

    private fun runHandshake() {
        log("running handshake, boundOnce=${Prefs.isBoundOnce(this)}")
        enqueue(Protocol.pair())
        if (!Prefs.isBoundOnce(this)) {
            enqueue(Protocol.bind())
            Prefs.setBoundOnce(this)
        }
        enqueue(Protocol.enableAllPushCategories())
        enqueue(Protocol.setTime())
        updateStatus("Connected")
        scheduleTimeSync()
    }

    private fun scheduleTimeSync() {
        handler.postDelayed({
            if (connected) {
                enqueue(Protocol.setTime())
                scheduleTimeSync()
            }
        }, TIME_SYNC_INTERVAL_MS)
    }

    @Synchronized
    private fun enqueue(packet: ByteArray) {
        outbox.add(packet)
        drainOutbox()
    }

    @Suppress("MissingPermission")
    @Synchronized
    private fun drainOutbox() {
        if (draining) return
        val char = writeChar ?: return
        val g = gatt ?: return
        val packet = outbox.peek() ?: return // NOT removed until onCharacteristicWrite confirms success
        draining = true
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        char.value = packet
        val ok = g.writeCharacteristic(char)
        if (!ok) {
            // stack busy (e.g. previous write still in flight); back off briefly and retry the SAME packet
            draining = false
            handler.postDelayed({ drainOutbox() }, 40)
            return
        }
        val hex = packet.joinToString("") { "%02x".format(it) }
        log("write ${if (hex.length > 80) hex.take(80) + "…(${packet.size}B)" else hex} queued=$ok")
        handler.postDelayed({ if (draining) { draining = false; drainOutbox() } }, COMMAND_SPACING_MS * 4)
    }

    private fun updateStatus(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildStatusNotification(text))
    }

    private fun buildStatusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenWatch")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Watch connection status", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        log("service destroyed")
        @Suppress("MissingPermission")
        gatt?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
