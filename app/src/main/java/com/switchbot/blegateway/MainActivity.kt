package com.switchbot.blegateway

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var scroll: ScrollView

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "🔵 BLE Gateway v1.0"
            textSize = 24f
        }
        layout.addView(title)

        statusText = TextView(this).apply {
            text = "Status: Initializing..."
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        scroll = ScrollView(this).apply { addView(statusText) }
        layout.addView(scroll)

        val startBtn = Button(this).apply {
            text = "Start Gateway"
            setOnClickListener { startGateway() }
        }
        layout.addView(startBtn)

        val stopBtn = Button(this).apply {
            text = "Stop Gateway"
            setOnClickListener { stopGateway() }
        }
        layout.addView(stopBtn)

        val testBtn = Button(this).apply {
            text = "Test API (localhost:9123/status)"
            setOnClickListener { testApi() }
        }
        layout.addView(testBtn)

        setContentView(layout)
        checkPermissions()
    }

    private fun checkPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            appendStatus("✅ All permissions granted")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first }
            if (denied.isEmpty()) {
                appendStatus("✅ All permissions granted")
            } else {
                appendStatus("❌ Denied: ${denied.joinToString()}")
            }
        }
    }

    private fun startGateway() {
        val intent = Intent(this, BleGatewayService::class.java)
        startForegroundService(intent)
        appendStatus("🚀 Gateway starting on http://localhost:9123")
        appendStatus("   Docker containers can access via http://localhost:9123")
    }

    private fun stopGateway() {
        val intent = Intent(this, BleGatewayService::class.java)
        stopService(intent)
        appendStatus("🛑 Gateway stopped")
    }

    private fun testApi() {
        thread {
            try {
                val conn = URL("http://localhost:9123/status").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                val response = conn.inputStream.bufferedReader().readText()
                runOnUiThread { appendStatus("📡 API Response:\n$response") }
            } catch (e: Exception) {
                runOnUiThread { appendStatus("❌ API Error: ${e.message}") }
            }
        }
    }

    private fun appendStatus(msg: String) {
        statusText.text = "${statusText.text}\n$msg"
        scroll.fullScroll(ScrollView.FOCUS_DOWN)
    }
}
