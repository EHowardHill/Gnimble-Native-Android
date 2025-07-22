// FontAdapter.kt
package com.gnimble.typewriter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.gnimble.typewriter.data.FontItem

class FontAdapter(
    context: Context,
    private val fonts: List<FontItem>
) : ArrayAdapter<FontItem>(context, android.R.layout.simple_spinner_item, fonts) {

    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent) as TextView
        val fontItem = fonts[position]
        view.text = fontItem.name
        fontItem.typeface?.let { view.typeface = it }
        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getDropDownView(position, convertView, parent) as TextView
        val fontItem = fonts[position]
        view.text = fontItem.name
        fontItem.typeface?.let { view.typeface = it }
        return view
    }
}