// FontItem.kt
package com.gnimble.typewriter.data

import android.graphics.Typeface

data class FontItem(
    val name: String,
    val resourceId: Int,
    val typeface: Typeface?
)