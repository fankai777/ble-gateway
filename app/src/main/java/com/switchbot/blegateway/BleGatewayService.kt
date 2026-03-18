package com.switchbot.blegateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleGatewayService : Service() {

    companion object {
        const val TAG = "BleGateway"
        const val PORT = 9123
        const val CHANNEL_ID = "ble_gateway_channel"
    }

    private val binder = LocalBinder()
    private var httpServer: BleHttpServer? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner? = null
    
    // Connected GATT connections: address -> BluetoothGatt
    private val gattConnections = ConcurrentHashMap<String, BluetoothGatt>()
    
    // Scan results: address -> info
    private val scanResults = ConcurrentHashMap<String, JSONObject>()
    
    // Pending GATT operations callback results
    private val gattCallbacks = ConcurrentHashMap<String, MutableMap<String, String>>()

    inner class LocalBinder : Binder() {
        fun getService(): BleGatewayService = this@BleGatewayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        createNotificationChannel()
        startForeground(1, buildNotification("BLE Gateway running on port $PORT"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startHttpServer()
        return START_STICKY
    }

    override fun onDestroy() {
        stopHttpServer()
        gattConnections.values.forEach { it.disconnect(); it.close() }
        gattConnections.clear()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "BLE Gateway Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun startHttpServer() {
        if (httpServer == null) {
            httpServer = BleHttpServer()
            httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            Log.i(TAG, "HTTP server started on port $PORT")
        }
    }

    private fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
    }

    // ── HTTP Server ──────────────────────────────────────────────

    inner class BleHttpServer : NanoHTTPD(PORT) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method
            
            return try {
                when {
                    uri == "/status" && method == Method.GET -> jsonResponse(JSONObject().apply {
                        put("status", "ok")
                        put("bluetooth_enabled", bluetoothAdapter?.isEnabled == true)
                        put("address", bluetoothAdapter?.address ?: "unknown")
                        put("connections", gattConnections.size)
                        put("scan_results", scanResults.size)
                    })

                    uri == "/scan" && method == Method.POST -> handleScan()
                    uri == "/scan/stop" && method == Method.POST -> handleStopScan()
                    uri == "/scan/results" && method == Method.GET -> handleScanResults()
                    
                    uri == "/connect" && method == Method.POST -> handleConnect(session)
                    uri == "/disconnect" && method == Method.POST -> handleDisconnect(session)
                    uri == "/connections" && method == Method.GET -> handleConnections()
                    
                    uri.startsWith("/gatt/") -> handleGattOperation(uri, session)
                    
                    else -> jsonResponse(JSONObject().put("error", "Not found"), 404)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Request error", e)
                jsonResponse(JSONObject().put("error", e.message), 500)
            }
        }
    }

    // ── BLE Scan ─────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val info = JSONObject().apply {
                put("name", device.name ?: "Unknown")
                put("address", device.address)
                put("rssi", result.rssi)
                put("connectable", result.isConnectable)
            }
            // Include advertised services if available
            result.scanRecord?.serviceUuids?.let { uuids ->
                val services = JSONArray()
                uuids.forEach { services.put(it.uuid.toString()) }
                info.put("services", services)
            }
            scanResults[device.address] = info
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    private fun handleScan(): Response {
        if (!hasPermissions()) {
            return jsonResponse(JSONObject().put("error", "Missing Bluetooth permissions"), 403)
        }
        scanResults.clear()
        bluetoothLeScanner?.startScan(scanCallback)
        return jsonResponse(JSONObject().put("status", "scanning"))
    }

    private fun handleStopScan(): Response {
        bluetoothLeScanner?.stopScan(scanCallback)
        return jsonResponse(JSONObject().put("status", "scan_stopped"))
    }

    private fun handleScanResults(): Response {
        val arr = JSONArray()
        scanResults.values.forEach { arr.put(it) }
        return jsonResponse(JSONObject().put("devices", arr))
    }

    // ── BLE Connect/Disconnect ───────────────────────────────────

    private fun handleConnect(session: IHTTPSession): Response {
        val body = parseBody(session)
        val address = body.optString("address")
        if (address.isEmpty()) {
            return jsonResponse(JSONObject().put("error", "address required"), 400)
        }

        val device = bluetoothAdapter?.getRemoteDevice(address)
            ?: return jsonResponse(JSONObject().put("error", "Device not found"), 404)

        // Disconnect existing if any
        gattConnections[address]?.disconnect()
        gattConnections[address]?.close()

        var connectResult: String? = null
        val latch = Object()

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to $address")
                    gattConnections[address] = gatt
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from $address")
                    gattConnections.remove(address)
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.i(TAG, "Services discovered for $address, status=$status")
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                val key = "${gatt.device.address}:${characteristic.uuid}"
                val result = gattCallbacks.getOrPut(gatt.device.address) { mutableMapOf() }
                result[key] = bytesToHex(value)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val key = "${gatt.device.address}:${characteristic.uuid}:write"
                val result = gattCallbacks.getOrPut(gatt.device.address) { mutableMapOf() }
                result[key] = if (status == BluetoothGatt.GATT_SUCCESS) "ok" else "error:$status"
            }
        }

        device.connectGatt(this, false, callback)
        return jsonResponse(JSONObject().put("status", "connecting").put("address", address))
    }

    private fun handleDisconnect(session: IHTTPSession): Response {
        val body = parseBody(session)
        val address = body.optString("address")
        val gatt = gattConnections.remove(address)
        if (gatt != null) {
            gatt.disconnect()
            gatt.close()
            return jsonResponse(JSONObject().put("status", "disconnected").put("address", address))
        }
        return jsonResponse(JSONObject().put("error", "Not connected"), 404)
    }

    private fun handleConnections(): Response {
        val arr = JSONArray()
        gattConnections.keys.forEach { arr.put(it) }
        return jsonResponse(JSONObject().put("connections", arr))
    }

    // ── GATT Operations ──────────────────────────────────────────

    private fun handleGattOperation(uri: String, session: IHTTPSession): Response {
        val parts = uri.trimStart('/').split('/')
        // /gatt/{address}/services
        // /gatt/{address}/read/{service_uuid}/{char_uuid}
        // /gatt/{address}/write/{service_uuid}/{char_uuid}
        
        if (parts.size < 3) return jsonResponse(JSONObject().put("error", "Invalid path"), 400)
        
        val address = parts[1]
        val gatt = gattConnections[address]
            ?: return jsonResponse(JSONObject().put("error", "Not connected to $address"), 404)

        return when (parts[2]) {
            "services" -> handleListServices(gatt)
            "read" -> handleRead(gatt, parts, session)
            "write" -> handleWrite(gatt, parts, session)
            else -> jsonResponse(JSONObject().put("error", "Unknown operation"), 400)
        }
    }

    private fun handleListServices(gatt: BluetoothGatt): Response {
        val services = JSONArray()
        gatt.services.forEach { service ->
            val svc = JSONObject().apply {
                put("uuid", service.uuid.toString())
                val chars = JSONArray()
                service.characteristics.forEach { c ->
                    chars.put(JSONObject().apply {
                        put("uuid", c.uuid.toString())
                        put("properties", c.properties)
                    })
                }
                put("characteristics", chars)
            }
            services.put(svc)
        }
        return jsonResponse(JSONObject().put("services", services))
    }

    private fun handleRead(gatt: BluetoothGatt, parts: List<String>, session: IHTTPSession): Response {
        if (parts.size < 5) return jsonResponse(JSONObject().put("error", "Need service_uuid and char_uuid"), 400)
        val serviceUuid = UUID.fromString(parts[3])
        val charUuid = UUID.fromString(parts[4])
        
        val service = gatt.getService(serviceUuid)
            ?: return jsonResponse(JSONObject().put("error", "Service not found"), 404)
        val characteristic = service.getCharacteristic(charUuid)
            ?: return jsonResponse(JSONObject().put("error", "Characteristic not found"), 404)

        val key = "${gatt.device.address}:$charUuid"
        gattCallbacks[gatt.device.address]?.remove(key)
        
        if (!gatt.readCharacteristic(characteristic)) {
            return jsonResponse(JSONObject().put("error", "Read failed"), 500)
        }

        // Wait up to 3 seconds for result
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 3000) {
            val result = gattCallbacks[gatt.device.address]?.get(key)
            if (result != null) {
                return jsonResponse(JSONObject().apply {
                    put("value_hex", result)
                    put("value_ascii", hexToAscii(result))
                })
            }
            Thread.sleep(50)
        }
        return jsonResponse(JSONObject().put("error", "Read timeout"), 408)
    }

    private fun handleWrite(gatt: BluetoothGatt, parts: List<String>, session: IHTTPSession): Response {
        if (parts.size < 5) return jsonResponse(JSONObject().put("error", "Need service_uuid and char_uuid"), 400)
        val serviceUuid = UUID.fromString(parts[3])
        val charUuid = UUID.fromString(parts[4])
        
        val body = parseBody(session)
        val valueHex = body.optString("value_hex")
        if (valueHex.isEmpty()) {
            return jsonResponse(JSONObject().put("error", "value_hex required"), 400)
        }

        val service = gatt.getService(serviceUuid)
            ?: return jsonResponse(JSONObject().put("error", "Service not found"), 404)
        val characteristic = service.getCharacteristic(charUuid)
            ?: return jsonResponse(JSONObject().put("error", "Characteristic not found"), 404)

        characteristic.value = hexToBytes(valueHex)
        if (!gatt.writeCharacteristic(characteristic)) {
            return jsonResponse(JSONObject().put("error", "Write failed"), 500)
        }

        return jsonResponse(JSONObject().put("status", "write_sent"))
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun hasPermissions(): Boolean {
        return checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun parseBody(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: "{}"
        return JSONObject(body)
    }

    private fun jsonResponse(obj: JSONObject, code: Int = 200): Response {
        return newFixedLengthResponse(
            Response.Status.lookup(code),
            "application/json",
            obj.toString(2)
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun hexToAscii(hex: String): String {
        return hex.chunked(2)
            .map { it.toInt(16).toChar() }
            .joinToString("")
            .replace(Regex("[^\\x20-\\x7E]"), ".")
    }
}
