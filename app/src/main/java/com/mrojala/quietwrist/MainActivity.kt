package com.mrojala.quietwrist

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.service.notification.NotificationListenerService
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
        root.addView(
            button("Reconnect the listener") {
                // One UI sometimes leaves the listener unbound after an update.
                NotificationListenerService.requestRebind(
                    ComponentName(this, RelayService::class.java)
                )
                Toast.makeText(this, "Rebind requested", Toast.LENGTH_SHORT).show()
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
        root.addView(
            switch("Relay everything (debug)", Prefs.relayEverything(this)) { on ->
                Prefs.setRelayEverything(this, on)
            }
        )
        root.addView(
            hint(
                "Ignores the filter and relays every WhatsApp message. Use it to tell " +
                    "\"the filter rejected it\" apart from \"the relay never reached the watch\"."
            )
        )
        root.addView(
            switch("Log every app (debug)", Prefs.logAllApps(this)) { on ->
                Prefs.setLogAllApps(this, on)
            }
        )
        root.addView(
            hint(
                "Logs notifications from all apps without relaying them, so you can " +
                    "confirm the listener is receiving anything at all."
            )
        )

        root.addView(spacer(16))
        root.addView(heading("Recent decisions"))

        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextColor(Color.DKGRAY)
        }

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
        root.addView(logView)

        // One scrolling page rather than a scrolling log inside a fixed frame: the
        // controls alone are taller than a phone screen.
        setContentView(
            ScrollView(this).apply {
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        )
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

        val lines = Prefs.readLog(this)
        val connected = lines.any { it.contains("listener connected") }

        status.text = buildString {
            appendLine(mark(listenerOn) + " Notification access")
            appendLine(mark(connected) + " Listener has connected")
            appendLine(mark(canPost) + " Permission to post notifications")
            append(mark(whatsAppInstalled) + " WhatsApp installed")
            when {
                !listenerOn -> append("\n\nGrant notification access to start relaying.")
                !connected -> append("\n\nAccess is granted but the service never bound. " +
                    "Tap “Reconnect the listener”.")
            }
        }

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
