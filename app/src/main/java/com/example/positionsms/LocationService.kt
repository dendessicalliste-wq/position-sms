package com.example.positionsms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.SmsManager
import android.util.Log
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
        private const val ACTION_SMS_SENT = "com.example.positionsms.SMS_SENT"
    }

    private var smsSentReceiver: BroadcastReceiver? = null

    private var intervalMs = 300_000L
    private var lastSentAt = 0L

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val now = SystemClock.elapsedRealtime()
            if (lastSentAt != 0L && now - lastSentAt < intervalMs * 8 / 10) return
            lastSentAt = now
            sendSms(loc)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val number = prefs.getString("number", "") ?: ""
        if (number.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val seconds = prefs.getLong("interval_s", 300L).coerceIn(1L, 86400L)
        intervalMs = seconds * 1000L
        lastSentAt = 0L

        createChannel()
        val notification = buildNotification(number, seconds)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        running = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
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
        smsSentReceiver?.let { unregisterReceiver(it) }
        smsSentReceiver = null
        running = false
        super.onDestroy()
    }

    private fun sendSms(loc: Location) {
        val number = getSharedPreferences("cfg", MODE_PRIVATE).getString("number", "") ?: return
        if (number.isBlank()) return
        val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
        val text = "Position $time : https://maps.google.com/?q=" +
            String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude)
        try {
            val sms = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            val sentIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_SMS_SENT).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            registerSentReceiver(number, text)
            sms.sendTextMessage(number, null, text, sentIntent, null)
        } catch (e: Exception) {
            // SMS non envoyé : on réessaiera au prochain cycle
        }
    }

    // Tente d'effacer le SMS du journal local une fois envoyé. Ne fonctionne
    // que si Android l'autorise pour une appli qui n'est pas la messagerie
    // par défaut ; sur certains téléphones, le SMS reste visible malgré tout.
    private fun registerSentReceiver(number: String, text: String) {
        smsSentReceiver?.let { unregisterReceiver(it) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    unregisterReceiver(this)
                } catch (e: Exception) { /* déjà désinscrit */ }
                smsSentReceiver = null
                Handler(Looper.getMainLooper()).postDelayed({
                    tryDeleteSentSms(number, text)
                }, 1500L)
            }
        }
        smsSentReceiver = receiver
        val filter = IntentFilter(ACTION_SMS_SENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    private fun tryDeleteSentSms(number: String, text: String) {
        try {
            val uri = Uri.parse("content://sms/sent")
            val cursor = contentResolver.query(
                uri, arrayOf("_id", "address", "body"), null, null, "date DESC"
            ) ?: return
            cursor.use {
                while (it.moveToNext()) {
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    if (body == text) {
                        val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                        val rows = contentResolver.delete(
                            Uri.parse("content://sms/$id"), null, null
                        )
                        if (rows <= 0) {
                            Log.i("PositionSMS", "Suppression du SMS refusée par le système")
                        }
                        return
                    }
                }
            }
        } catch (e: Exception) {
            // Pas de permission d'écriture sur le journal SMS : le message reste visible
        }
    }

    private fun label(s: Long): String = when {
        s % 3600L == 0L -> "${s / 3600L} h"
        s % 60L == 0L -> "${s / 60L} min"
        else -> "$s s"
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Partage de position", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(number: String, seconds: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Partage de position actif")
            .setContentText("Envoi par SMS toutes les ${label(seconds)} vers $number")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
