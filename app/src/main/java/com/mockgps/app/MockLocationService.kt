package com.mockgps.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class MockLocationService : Service() {

    private val binder = LocalBinder()
    private var locationManager: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lat: Double = 0.0
    private var lng: Double = 0.0
    private val CHANNEL_ID = "mock_gps_channel"
    private val NOTIF_ID = 1001

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lat = intent?.getDoubleExtra("lat", 0.0) ?: 0.0
        lng = intent?.getDoubleExtra("lng", 0.0) ?: 0.0

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
        // 同时模拟GPS和NETWORK两个Provider，确保融合定位也能获取到模拟数据
        setupSingleProvider(LocationManager.GPS_PROVIDER)
        setupSingleProvider(LocationManager.NETWORK_PROVIDER)
    }

    private fun setupSingleProvider(provider: String) {
        try {
            locationManager?.removeTestProvider(provider)
        } catch (_: Exception) {}
        try {
            locationManager?.addTestProvider(
                provider,
                false, false, false, false, true, true, true,
                android.location.provider.ProviderProperties.POWER_USAGE_MEDIUM,
                android.location.provider.ProviderProperties.ACCURACY_FINE
            )
            locationManager?.setTestProviderEnabled(provider, true)
        } catch (e: Exception) {
            // 旧版API回退
            try {
                locationManager?.addTestProvider(
                    provider,
                    false, false, false, false, true, true, true,
                    2, 1
                )
                locationManager?.setTestProviderEnabled(provider, true)
            } catch (_: Exception) {}
        }
    }

    private val pushRunnable = object : Runnable {
        override fun run() {
            pushLocation()
            handler.postDelayed(this, 1000)
        }
    }

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
                    accuracy = 1.0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 1.0f
                    }
                }
                locationManager?.setTestProviderLocation(provider, loc)
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS模拟运行中")
            .setContentText("纬度 %.4f  经度 %.4f".format(lat, lng))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "GPS模拟服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持GPS模拟位置运行"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pushRunnable)
        try {
            locationManager?.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {}
        try {
            locationManager?.removeTestProvider(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
