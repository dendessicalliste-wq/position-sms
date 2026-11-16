package com.example.positionsms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager

// Reçu au démarrage du téléphone et à chaque changement d'état de la carte
// SIM. Si la SIM actuelle diffère de celle enregistrée lors du dernier
// partage, ou si aucune n'était enregistrée, le partage redémarre tout seul
// (en utilisant le dernier numéro et intervalle choisis dans l'appli).
class SimWatcher : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val number = prefs.getString("number", "") ?: ""
        if (number.isBlank()) return // le partage n'a jamais été configuré

        val currentSim = currentSimId(context)
        val knownSim = prefs.getString("known_sim", null)

        if (knownSim == null) {
            // Première fois qu'on observe une SIM : on l'enregistre sans
            // déclencher d'alerte.
            prefs.edit().putString("known_sim", currentSim).apply()
            return
        }

        if (currentSim != knownSim) {
            prefs.edit().putString("known_sim", currentSim).apply()
            startSharing(context)
            return
        }

        // Même SIM : s'assurer que le partage tourne après un redémarrage.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && !LocationService.running) {
            startSharing(context)
        }
    }

    private fun currentSimId(context: Context): String {
        return try {
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED
            ) return "unknown"
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            tm.simSerialNumber ?: tm.subscriberId ?: tm.line1Number ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun startSharing(context: Context) {
        val serviceIntent = Intent(context, LocationService::class.java)
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            // Le système peut refuser le démarrage dans de rares cas (par
            // ex. restrictions fabricant) ; l'utilisateur pourra toujours
            // démarrer le partage manuellement depuis l'appli.
        }
    }
}
