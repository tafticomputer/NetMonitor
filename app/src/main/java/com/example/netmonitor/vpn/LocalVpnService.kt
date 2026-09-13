package com.example.netmonitor.vpn

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.example.netmonitor.MainActivity
import com.example.netmonitor.data.AppDatabase
import com.example.netmonitor.data.DomainConnectionEntity
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * یک VpnService سبک که فقط ترافیک DNS (پورت 53) را از رابط TUN عبور می‌دهد،
 * دامنه هر پرسش را استخراج و به برنامه صاحب آن (uid) نسبت می‌دهد،
 * سپس پرسش را به یک سرور DNS واقعی (protect شده، خارج از تونل) ارسال کرده
 * و پاسخ را به برنامه اصلی بازمی‌گرداند.
 *
 * چون فقط مسیر آدرس DNS مجازی به تونل هدایت می‌شود (نه 0.0.0.0/0)،
 * بقیه ترافیک اینترنت برنامه‌ها دست‌نخورده و مستقیم باقی می‌ماند.
 */
class LocalVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.example.netmonitor.STOP_VPN"
        const val NOTIFICATION_CHANNEL_ID = "netmonitor_vpn"
        const val NOTIFICATION_ID = 1001

        const val FAKE_DNS_IP = "10.111.222.2"
        const val TUN_LOCAL_IP = "10.111.222.1"
        const val UPSTREAM_DNS = "8.8.8.8" // سرور DNS واقعی برای رله کردن پرسش‌ها

        @Volatile var isRunning: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mainJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        startVpn()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "مانیتور شبکه",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("مانیتور شبکه فعال است")
            .setContentText("در حال شناسایی دامنه‌های متصل‌شده توسط برنامه‌ها")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startVpn() {
        if (vpnInterface != null) return // در حال اجراست

        val builder = Builder()
            .setSession("NetMonitor DNS Watch")
            .addAddress(TUN_LOCAL_IP, 24)
            .addDnsServer(FAKE_DNS_IP)
            // فقط مسیر آدرس فرضی DNS به تونل هدایت می‌شود، نه کل اینترنت:
            .addRoute(FAKE_DNS_IP, 32)
            .setMtu(1500)

        vpnInterface = builder.establish()
        isRunning = true

        mainJob = serviceScope.launch {
            runReadLoop()
        }
    }

    private fun stopVpn() {
        mainJob?.cancel()
        try { vpnInterface?.close() } catch (e: Exception) { }
        vpnInterface = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runReadLoop() {
        val tunFd = vpnInterface ?: return
        val input = FileInputStream(tunFd.fileDescriptor)
        val output = FileOutputStream(tunFd.fileDescriptor)
        val buffer = ByteArray(32767)
        val dao = AppDatabase.getInstance(applicationContext).domainConnectionDao()

        while (currentCoroutineContext().isActive) {
            val length = try { input.read(buffer) } catch (e: Exception) { break }
            if (length <= 0) continue

            val packetCopy = buffer.copyOf(length)
            val query = DnsPacketUtils.tryParseDnsQuery(packetCopy, length) ?: continue

            // پردازش هر پرسش DNS به‌صورت موازی، بدون بلاک‌کردن حلقه اصلی خواندن
            launch(Dispatchers.IO) {
                handleDnsQuery(query, output, dao)
            }
        }
    }

    private fun handleDnsQuery(
        query: ParsedDnsQuery,
        output: FileOutputStream,
        dao: com.example.netmonitor.data.DomainConnectionDao
    ) {
        // ۱) شناسایی برنامه صاحب این پرسش
        val (uid, appName, packageName) = resolveOwner(query)

        // ۲) ثبت در دیتابیس محلی برای نمایش در UI
        serviceScope.launch {
            try {
                dao.insert(
                    DomainConnectionEntity(
                        uid = uid,
                        appName = appName,
                        packageName = packageName,
                        domain = query.domain,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) { }
        }

        // ۳) ارسال واقعی پرسش به یک DNS واقعی (خارج از تونل) و بازگرداندن پاسخ
        try {
            val socket = DatagramSocket()
            protect(socket) // این سوکت را از ورود دوباره به تونل معاف می‌کند
            socket.soTimeout = 5000

            val upstream = InetSocketAddress(InetAddress.getByName(UPSTREAM_DNS), 53)
            val outPacket = DatagramPacket(query.rawDnsPayload, query.rawDnsPayload.size, upstream)
            socket.send(outPacket)

            val responseBuffer = ByteArray(4096)
            val inPacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(inPacket)
            socket.close()

            val dnsResponse = inPacket.data.copyOfRange(0, inPacket.length)
            val responsePacket = DnsPacketUtils.buildResponsePacket(query, dnsResponse)

            synchronized(output) {
                output.write(responsePacket)
            }
        } catch (e: Exception) {
            // پرسش‌هایی که Timeout یا خطا بخورند نادیده گرفته می‌شوند
        }
    }

    /** تلاش برای یافتن uid/نام برنامه صاحب این اتصال UDP (نیازمند Android 10+) */
    private fun resolveOwner(query: ParsedDnsQuery): Triple<Int, String, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Triple(-1, "نامشخص (Android < 10)", "unknown")
        }
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val local = InetSocketAddress(query.srcIp, query.srcPort)
            val remote = InetSocketAddress(query.dstIp, query.dstPort)
            val uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remote)

            if (uid <= 0) {
                Triple(-1, "نامشخص", "unknown")
            } else {
                val pm = packageManager
                val packages = pm.getPackagesForUid(uid)
                val pkg = packages?.firstOrNull() ?: "uid:$uid"
                val label = try {
                    val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) { pkg }
                Triple(uid, label, pkg)
            }
        } catch (e: Exception) {
            Triple(-1, "نامشخص", "unknown")
        }
    }
}
