package com.example.positionsms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var phoneInput: EditText
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Position SMS"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "Envoie la position de ce téléphone par SMS toutes les 5 minutes au numéro ci-dessous."
            setPadding(0, pad / 2, 0, pad)
        })

        phoneInput = EditText(this).apply {
            hint = "Numéro destinataire (ex. +2250700000000)"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(prefs().getString("number", ""))
        }
        root.addView(phoneInput)

        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        root.addView(toggle)

        root.addView(Button(this).apply {
            text = "Désactiver l'optimisation de batterie"
            setOnClickListener { requestBatteryExemption() }
        })

        status = TextView(this).apply { setPadding(0, pad, 0, 0) }
        root.addView(status)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun prefs() = getSharedPreferences("cfg", MODE_PRIVATE)

    private fun refreshUi() {
        val on = LocationService.running
        toggle.text = if (on) "Arrêter le partage" else "Démarrer le partage"
        status.text = if (on) "Partage actif" else "Partage arrêté"
    }

    private fun onToggle() {
        if (LocationService.running) {
            stopService(Intent(this, LocationService::class.java))
            LocationService.running = false
            refreshUi()
            return
        }
        val number = phoneInput.text.toString().trim()
        if (number.length < 6) {
            Toast.makeText(this, "Saisis un numéro valide", Toast.LENGTH_SHORT).show()
            return
        }
        prefs().edit().putString("number", number).apply()
        startSharing()
    }

    private fun missingPermissions(): List<String> {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        return needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun startSharing() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        startForegroundService(Intent(this, LocationService::class.java))
        LocationService.running = true
        refreshUi()

        // Localisation en arrière-plan : demandée séparément (obligatoire depuis Android 10)
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1) {
            if (missingPermissions().isEmpty()) startSharing()
            else Toast.makeText(this, "Permissions refusées : le partage ne peut pas démarrer", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Déjà désactivée", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }
}
