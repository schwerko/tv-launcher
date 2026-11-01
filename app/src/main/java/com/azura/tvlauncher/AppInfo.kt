package com.azura.tvlauncher

import android.content.Intent
import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val banner: Drawable?,
    val launchIntent: Intent,
)
