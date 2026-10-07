package com.example.meowbot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            textSize = 16f
            text = "Setup (one time):\n\n" +
                "1. Tap the first button, open the ⋮ menu and choose \"Allow restricted settings\" " +
                "(Android 13+ only).\n\n" +
                "2. Tap the second button and turn on \"Meowdoku Bot\".\n\n" +
                "A 🐱 button then floats on your screen. Open Meowdoku, start a puzzle, " +
                "and tap the 🐱. You can drag it out of the way."
        })
        root.addView(Button(this).apply {
            text = "1. App info (allow restricted settings)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        })
        root.addView(Button(this).apply {
            text = "2. Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(root)
    }
}
