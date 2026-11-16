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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var phoneInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var toggle: Button

    private val units = arrayOf("secondes", "minutes", "heures")
    private val multipliers = longArrayOf(1L, 60L, 3600L)

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
            text = "Envoie la position de ce téléphone par SMS au numéro ci-dessous."
            setPadding(0, pad / 2, 0, pad)
        })

        phoneInput = EditText(this).apply {
            hint = "Numéro destinataire (ex. +2250700000000)"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(prefs().getString("number", ""))
        }
        root.addView(phoneInput)

        root.addView(TextView(this).apply {
            text = "Envoyer la position toutes les :"
            setPadding(0, pad / 2, 0, 0)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        intervalInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        unitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, units)
        }
        row.addView(intervalInput)
        row.addView(unitSpinner)
        root.addView(row)

        val saved = prefs().getLong("interval_s", 300L)
        val (value, unitIndex) = when {
            saved % 3600L == 0L -> Pair(saved / 3600L, 2)
            saved % 60L == 0L -> Pair(saved / 60L, 1)
            else -> Pair(saved, 0)
        }
        intervalInput.setText(value.toString())
        unitSpinner.setSelection(unitIndex)

        root.addView(TextView(this).apply {
            text = "De 1 seconde à 24 heures. Un intervalle très court envoie beaucoup de SMS."
            textSize = 12f
        })

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
        phoneInput.isEnabled = !on
        intervalInput.isEnabled = !on
        unitSpinner.isEnabled = !on
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
        val value = intervalInput.text.toString().trim().toLongOrNull()
        if (value == null || value < 1L || value > 86400L) {
            Toast.makeText(this, "Choisis un intervalle valide", Toast.LENGTH_SHORT).show()
            return
        }
        val seconds = value * multipliers[unitSpinner.selectedItemPosition]
        if (seconds < 1L || seconds > 86400L) {
            Toast.makeText(this, "L'intervalle doit être entre 1 seconde et 24 heures", Toast.LENGTH_LONG).show()
            return
        }
        if (seconds < 60L) {
            Toast.makeText(
                this,
                "Intervalle très court : Android peut demander de confirmer l'envoi de nombreux SMS",
                Toast.LENGTH_LONG
            ).show()
        }
        prefs().edit().putString("number", number).putLong("interval_s", seconds).apply()
        startSharing()
    }

    private fun missingPermissions(): List<String> {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
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
        saveCurrentSimAsKnown()
        refreshUi()

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

    private fun saveCurrentSimAsKnown() {
        try {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED
            ) return
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            @Suppress("DEPRECATION")
            val simId = tm.simSerialNumber ?: tm.subscriberId ?: tm.line1Number ?: "unknown"
            prefs().edit().putString("known_sim", simId).apply()
        } catch (e: Exception) {
            // Pas grave : la détection de changement de SIM sera juste moins précise
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
