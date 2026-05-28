package com.mockgps.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.lang.reflect.Field

class MockLocationService : Service() {

    private val binder = LocalBinder()
    private var locationManager: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lat: Double = 0.0
    private var lng: Double = 0.0
    private val CHANNEL_ID = "mock_gps_channel"
    private val NOTIF_ID = 1001
    private var pushCount = 0
    private var field_mIsFromMockProvider: Field? = null

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
        initReflection()
    }

    /**
     * 预加载反射字段 —— 用来隐藏 isFromMockProvider 标志
     * 紫米能骗过钉钉的核心可能就是这个
     */
    private fun initReflection() {
        try {
            field_mIsFromMockProvider = Location::class.java.getDeclaredField("mIsFromMockProvider")
            field_mIsFromMockProvider?.isAccessible = true
        } catch (_: Exception) {
            field_mIsFromMockProvider = null
        }
    }

    private fun hideMockFlag(loc: Location) {
        try {
            field_mIsFromMockProvider?.setBoolean(loc, false)
        } catch (_: Exception) {}
    }

    /**
     * 自检：读回当前定位状态，用于诊断
     */
    fun checkMockStatus(): String {
        val sb = StringBuilder()
        val providers = arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (p in providers) {
            try {
                val loc = locationManager?.getLastKnownLocation(p)
                if (loc != null) {
                    sb.append("$p=${loc.latitude},${loc.longitude} mock=${loc.isFromMockProvider}\n")
                } else {
                    sb.append("$p=无数据\n")
                }
            } catch (_: Exception) {
                sb.append("$p=读取出错\n")
            }
        }
        return sb.toString()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lat = intent?.getDoubleExtra("lat", 0.0) ?: 0.0
        lng = intent?.getDoubleExtra("lng", 0.0) ?: 0.0
        pushCount = 0
        startForeground(NOTIF_ID, buildNotification())
        setupMockProvider()
        startPushing()
        return START_STICKY
    }

    fun updateLocation(newLat: Double, newLng: Double) {
        lat = newLat
        lng = newLng
    }

    private fun setupMockProvider() {
        setupSingleProvider(LocationManager.GPS_PROVIDER)
        setupSingleProvider(LocationManager.NETWORK_PROVIDER)
    }

    private fun setupSingleProvider(provider: String) {
        try { locationManager?.removeTestProvider(provider) } catch (_: Exception) {}
        try {
            locationManager?.addTestProvider(
                provider,
                false, false, false, false, true, true, true,
                android.location.provider.ProviderProperties.POWER_USAGE_MEDIUM,
                android.location.provider.ProviderProperties.ACCURACY_FINE
            )
            locationManager?.setTestProviderEnabled(provider, true)
        } catch (e: Exception) {
            try {
                locationManager?.addTestProvider(
                    provider, false, false, false, false, true, true, true, 2, 1
                )
                locationManager?.setTestProviderEnabled(provider, true)
            } catch (_: Exception) {}
        }
    }

    private val pushRunnable = object : Runnable {
        override fun run() {
            pushLocation()
            pushCount++
            val now = System.currentTimeMillis()
            if (now - lastNotifUpdate > 10000) {
                lastNotifUpdate = now
                try {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification())
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, 500)
        }
    }
    private var lastNotifUpdate = 0L

    private fun startPushing() {
        handler.post(pushRunnable)
    }

    private fun pushLocation() {
        val providers = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            try {
                val loc = Location(provider).apply {
                    latitude = lat
                    longitude = lng
                    altitude = 10.0
                    accuracy = 3.0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    bearing = (pushCount % 360).toFloat()
                    speed = 0.5f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 5.0f
                        bearingAccuracyDegrees = 10.0f
                        speedAccuracyMetersPerSecond = 0.3f
                    }
                }
                hideMockFlag(loc)
                locationManager?.setTestProviderLocation(provider, loc)
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
        val status = buildString {
            append("%.4f,%.4f".format(lat, lng))
            append(" | 推送${pushCount}次")
            if (wifiManager.isWifiEnabled) append(" | ⚠️请关WiFi") else append(" | WiFi已关✓")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS模拟运行中")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "纬度: %.4f  经度: %.4f\n推送: $pushCount 次\nWiFi: %s".format(
                    lat, lng,
                    if (wifiManager.isWifiEnabled) "开启(⚠️请关闭!!)" else "已关闭(OK)"
                )
            ))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GPS模拟服务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "保持GPS模拟位置运行"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pushRunnable)
        try { locationManager?.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        try { locationManager?.removeTestProvider(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) {}
        super.onDestroy()
    }
}
