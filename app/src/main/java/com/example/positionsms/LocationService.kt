package com.example.positionsms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocationService : Service() {

    companion object {
        @Volatile
        var running = false
        private const val CHANNEL_ID = "position_sms"
        private const val NOTIF_ID = 1
        private const val INTERVAL_MS = 5 * 60 * 1000L
    }

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { sendSms(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = getSharedPreferences("cfg", MODE_PRIVATE).getString("number", "") ?: ""
        if (number.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val notification = buildNotification(number)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        running = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(request, callback, mainLooper)
        } catch (e: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        running = false
        super.onDestroy()
    }

    private fun sendSms(loc: Location) {
        val number = getSharedPreferences("cfg", MODE_PRIVATE).getString("number", "") ?: return
        if (number.isBlank()) return
        val time = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        val text = "Position $time : https://maps.google.com/?q=" +
            String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude)
        try {
            val sms = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            sms.sendTextMessage(number, null, text, null, null)
        } catch (e: Exception) {
            // SMS non envoyé (permission, SIM absente...) : on réessaiera au prochain cycle
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Partage de position", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(number: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Partage de position actif")
            .setContentText("Envoi par SMS toutes les 5 min vers $number")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
