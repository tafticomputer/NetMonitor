package com.example.netmonitor.util

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import com.example.netmonitor.data.AppUsageInfo

object UsageStatsHelper {

    /** آیا کاربر دسترسی «Usage Access» را به این برنامه داده است؟ */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** باز کردن صفحه تنظیمات برای فعال‌کردن دسترسی Usage Access */
    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    /**
     * مصرف داده هر برنامه (موبایل + وای‌فای) بین startTime و endTime را برمی‌گرداند.
     * زمان‌ها بر حسب میلی‌ثانیه (System.currentTimeMillis) هستند.
     */
    fun getAppDataUsage(context: Context, startTime: Long, endTime: Long): List<AppUsageInfo> {
        if (!hasUsageAccess(context)) return emptyList()

        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val pm = context.packageManager

        // نگاشت uid -> (rx, tx) جمع‌شده روی همه نوع اتصال‌ها
        val usageByUid = HashMap<Int, LongArray>() // [0]=rx, [1]=tx

        fun accumulate(networkType: Int) {
            try {
                val bucket = NetworkStats.Bucket()
                val stats = nsm.querySummary(networkType, null, startTime, endTime)
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val arr = usageByUid.getOrPut(bucket.uid) { longArrayOf(0, 0) }
                    arr[0] += bucket.rxBytes
                    arr[1] += bucket.txBytes
                }
                stats.close()
            } catch (e: Exception) {
                // ممکن است روی برخی دستگاه‌ها/API-level ها یک نوع شبکه در دسترس نباشد
            }
        }

        accumulate(ConnectivityManager.TYPE_MOBILE)
        accumulate(ConnectivityManager.TYPE_WIFI)

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val uidToAppInfo = HashMap<Int, ApplicationInfo>()
        for (app in installedApps) {
            uidToAppInfo[app.uid] = app
        }

        val result = mutableListOf<AppUsageInfo>()
        for ((uid, bytes) in usageByUid) {
            if (bytes[0] + bytes[1] <= 0) continue
            val appInfo = uidToAppInfo[uid]
            val appName = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: "UID $uid"
            val packageName = appInfo?.packageName ?: "uid:$uid"
            val icon = try { appInfo?.loadIcon(pm) } catch (e: Exception) { null }

            result.add(
                AppUsageInfo(
                    packageName = packageName,
                    appName = appName,
                    icon = icon,
                    rxBytes = bytes[0],
                    txBytes = bytes[1],
                    uid = uid
                )
            )
        }

        return result.sortedByDescending { it.totalBytes }
    }
}
