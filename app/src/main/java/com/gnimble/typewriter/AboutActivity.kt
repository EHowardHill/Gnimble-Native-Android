// AboutActivity.kt
package com.gnimble.typewriter

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
// BUG FIX #11: Changed from android.app.Activity to AppCompatActivity
// Using plain Activity can crash when the app theme expects AppCompatActivity
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // 1. Load image from raw resource
        val imageView = findViewById<ImageView>(R.id.about_image)
        try {
            // Decode the raw stream into a Bitmap
            val inputStream = resources.openRawResource(R.raw.about_image)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            imageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            // Optional: Set a fallback image if loading fails
            // imageView.setImageResource(R.drawable.ic_launcher_foreground)
        }

        // 2. Close button logic
        findViewById<Button>(R.id.btn_close).setOnClickListener {
            finish()
        }
    }
}