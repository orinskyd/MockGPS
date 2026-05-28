package com.mockgps.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btnToggle: Button
    private lateinit var tvLocationName: TextView
    private lateinit var tvCoords: TextView
    private lateinit var tvStatus: TextView

    private var selectedLat: Double = 0.0
    private var selectedLng: Double = 0.0
    private var hasLocation = false
    private var isRunning = false

    private var mockService: MockLocationService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MockLocationService.LocalBinder
            mockService = b.getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            mockService = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        btnToggle = findViewById(R.id.btnToggle)
        tvLocationName = findViewById(R.id.tvLocationName)
        tvCoords = findViewById(R.id.tvCoords)
        tvStatus = findViewById(R.id.tvStatus)

        setupWebView()
        requestPermissions()

        btnToggle.setOnClickListener {
            if (!isRunning) startMocking() else stopMocking()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.loadUrl("file:///android_asset/map.html")
    }

    private fun requestPermissions() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun startMocking() {
        if (!hasLocation) {
            Toast.makeText(this, "请先在地图上选择位置", Toast.LENGTH_SHORT).show()
            return
        }
        // 检查WiFi状态，开着就提示用户关闭
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) {
            AlertDialog.Builder(this)
                .setTitle("需要关闭WiFi")
                .setMessage("WiFi开启时，系统会使用真实的WiFi定位，导致模拟失效。\n\n请关闭WiFi后再开启模拟，或点击「去设置关WiFi」手动关闭。")
                .setPositiveButton("去设置关WiFi") { _, _ ->
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                .setNegativeButton("继续（可能失效）") { _, _ ->
                    reallyStartMocking()
                }
                .setCancelable(false)
                .show()
        } else {
            reallyStartMocking()
        }
    }

    private fun reallyStartMocking() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            putExtra("lat", selectedLat)
            putExtra("lng", selectedLng)
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isRunning = true
        btnToggle.text = "停止GPS模拟"
        btnToggle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFFB71C1C.toInt())
        tvStatus.text = "模拟中"
        tvStatus.setTextColor(0xFF80FF80.toInt())
        Toast.makeText(this, "GPS模拟已开启！", Toast.LENGTH_SHORT).show()
    }

    private fun stopMocking() {
        val intent = Intent(this, MockLocationService::class.java)
        stopService(intent)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        isRunning = false
        btnToggle.text = "开启GPS模拟"
        btnToggle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
        tvStatus.text = "已关闭"
        tvStatus.setTextColor(0xFFFFCDD2.toInt())
        Toast.makeText(this, "GPS模拟已停止", Toast.LENGTH_SHORT).show()
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onLocationSelected(lat: Double, lng: Double) {
            runOnUiThread {
                selectedLat = lat
                selectedLng = lng
                hasLocation = true
                tvLocationName.text = "已选定位置"
                tvCoords.text = "纬度 %.6f   经度 %.6f".format(lat, lng)
                btnToggle.isEnabled = true
                Toast.makeText(applicationContext, "位置已选定", Toast.LENGTH_SHORT).show()
                // 如果已经在运行，自动更新坐标
                if (isRunning) {
                    mockService?.updateLocation(lat, lng)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) stopMocking()
    }
}
