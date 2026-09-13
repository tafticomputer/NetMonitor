package com.example.netmonitor.data

import android.graphics.drawable.Drawable

/**
 * اطلاعات مصرف داده برای یک برنامه در بازه زمانی انتخاب‌شده.
 */
data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val rxBytes: Long,   // حجم دریافتی
    val txBytes: Long,   // حجم ارسالی
    val uid: Int
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

/** تبدیل بایت به رشته خوانا مثل ۱۲.۳ مگابایت */
fun Long.toReadableSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = this.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return String.format("%.1f %s", value, units[unitIndex])
}
