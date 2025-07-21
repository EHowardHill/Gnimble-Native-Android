package com.gnimble.typewriter

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

data class FontItem(
    val name: String,
    val resourceId: Int,
    val typeface: Typeface?
)

class FontAdapter(
    context: Context,
    private val fonts: List<FontItem>
) : ArrayAdapter<FontItem>(context, android.R.layout.simple_spinner_item, fonts) {

    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_spinner_item, parent, false)

        val textView = view.findViewById<TextView>(android.R.id.text1)
        val fontItem = fonts[position]

        textView.text = fontItem.name
        fontItem.typeface?.let {
            textView.typeface = it
        }

        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_spinner_dropdown_item, parent, false)

        val textView = view.findViewById<TextView>(android.R.id.text1)
        val fontItem = fonts[position]

        textView.text = fontItem.name
        fontItem.typeface?.let {
            textView.typeface = it
        }

        return view
    }
}