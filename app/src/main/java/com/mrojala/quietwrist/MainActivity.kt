package com.mrojala.quietwrist

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Single setup-and-diagnostics screen. Built in code rather than XML: it is a
 * handful of controls, and there is no layout worth previewing.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Relay.createChannels(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.3f)
        }
        root.addView(status)
        root.addView(spacer(16))

        root.addView(
            button("Grant notification access") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )
        root.addView(
            button("Exempt from battery optimisation") {
                // One UI puts unused apps to sleep, which silently kills the listener.
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        )
        root.addView(
            button("Send test notification") {
                Relay.post(this, "QuietWrist test", "If your watch buzzed, the relay path works.")
                Toast.makeText(this, "Test notification posted", Toast.LENGTH_SHORT).show()
            }
        )

        root.addView(spacer(8))
        root.addView(
            switch("Require actual sound/vibration", Prefs.requireAudible(this)) { on ->
                Prefs.setRequireAudible(this, on)
            }
        )
        root.addView(
            hint(
                "Stricter, but relays nothing while the ringer is silenced or Do Not " +
                    "Disturb is on."
            )
        )
        root.addView(
            switch("Vibrate the phone too", Prefs.vibratePhone(this)) { on ->
                Prefs.setVibratePhone(this, on)
            }
        )
        root.addView(hint("Off means only the watch buzzes."))

        root.addView(spacer(16))
        root.addView(heading("Recent decisions"))

        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextColor(Color.DKGRAY)
        }
        root.addView(
            ScrollView(this).apply {
                addView(logView)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                ).apply { weight = 1f }
            }
        )

        val logButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logButtons.addView(button("Refresh") { refresh() }, rowParams())
        logButtons.addView(
            button("Clear") {
                Prefs.clearLog(this)
                refresh()
            },
            rowParams(),
        )
        root.addView(logButtons)

        setContentView(root)
        requestPostNotifications()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val listenerOn = isListenerEnabled()
        val whatsAppInstalled = isPackageInstalled(RelayService.WHATSAPP_PACKAGE)
        val canPost = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        status.text = buildString {
            appendLine(mark(listenerOn) + " Notification access")
            appendLine(mark(canPost) + " Permission to post notifications")
            append(mark(whatsAppInstalled) + " WhatsApp installed")
            if (!listenerOn) {
                append("\n\nGrant notification access to start relaying.")
            }
        }

        val lines = Prefs.readLog(this)
        logView.text = if (lines.isEmpty()) {
            "Nothing yet. Send yourself a WhatsApp message, then tap Refresh."
        } else {
            lines.joinToString("\n")
        }
    }

    private fun requestPostNotifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun isListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.split(":").any { entry ->
            entry.isNotBlank() && entry.substringBefore('/') == packageName
        }
    }

    private fun isPackageInstalled(name: String): Boolean =
        try {
            packageManager.getPackageInfo(name, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    // --- tiny view helpers ---------------------------------------------------

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun spacer(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(height),
        )
    }

    private fun heading(label: String) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun hint(label: String) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(Color.GRAY)
        setPadding(0, 0, 0, dp(8))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setAllCaps(false)
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { onClick() }
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            text = label
            isChecked = checked
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun rowParams() = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { weight = 1f }
}
