package com.example.netmonitor

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.netmonitor.data.AppDomainsGroup
import com.example.netmonitor.data.AppUsageInfo
import com.example.netmonitor.data.toReadableSize
import com.example.netmonitor.util.UsageStatsHelper
import com.example.netmonitor.vpn.LocalVpnService
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startForegroundService(Intent(this, LocalVpnService::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        viewModel = viewModel,
                        onRequestUsageAccess = { UsageStatsHelper.openUsageAccessSettings(this) },
                        onStartVpnMonitor = { startVpnMonitor() },
                        onStopVpnMonitor = { stopVpnMonitor() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // هر بار که کاربر از تنظیمات برمی‌گردد، مصرف داده را دوباره می‌خوانیم
        viewModel.refreshUsage()
    }

    private fun startVpnMonitor() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startForegroundService(Intent(this, LocalVpnService::class.java))
        }
    }

    private fun stopVpnMonitor() {
        val intent = Intent(this, LocalVpnService::class.java).apply {
            action = LocalVpnService.ACTION_STOP
        }
        startService(intent)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    viewModel: MainViewModel,
    onRequestUsageAccess: () -> Unit,
    onStartVpnMonitor: () -> Unit,
    onStopVpnMonitor: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val hasUsageAccess = UsageStatsHelper.hasUsageAccess(androidx.compose.ui.platform.LocalContext.current)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("مانیتور شبکه") })

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                text = { Text("مصرف داده") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                text = { Text("دامنه‌ها") })
        }

        when (selectedTab) {
            0 -> UsageTab(viewModel, hasUsageAccess, onRequestUsageAccess)
            1 -> DomainsTab(viewModel, onStartVpnMonitor, onStopVpnMonitor)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun UsageTab(
    viewModel: MainViewModel,
    hasUsageAccess: Boolean,
    onRequestUsageAccess: () -> Unit
) {
    val usageList by viewModel.usageList.collectAsState()
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()

    LaunchedEffect(hasUsageAccess) {
        if (hasUsageAccess) viewModel.refreshUsage()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (!hasUsageAccess) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "برای دیدن حجم مصرف اینترنت هر برنامه، باید دسترسی «Usage Access» را از تنظیمات فعال کنید.",
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onRequestUsageAccess) {
                        Text("رفتن به تنظیمات دسترسی")
                    }
                }
            }
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UsagePeriod.values().forEach { period ->
                FilterChip(
                    selected = selectedPeriod == period,
                    onClick = { viewModel.setPeriod(period) },
                    label = { Text(period.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (usageList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("داده‌ای برای این بازه یافت نشد", color = Color.Gray)
            }
        } else {
            LazyColumn {
                items(usageList) { item ->
                    UsageRow(item)
                    Divider()
                }
            }
        }
    }
}

@Composable
fun UsageRow(item: AppUsageInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DrawableIcon(item.icon)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.appName, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(item.packageName, fontSize = 11.sp, color = Color.Gray)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(item.totalBytes.toReadableSize(), fontWeight = FontWeight.Bold)
            Text(
                "↓${item.rxBytes.toReadableSize()}  ↑${item.txBytes.toReadableSize()}",
                fontSize = 11.sp, color = Color.Gray
            )
        }
    }
}

@Composable
fun DrawableIcon(drawable: Drawable?) {
    if (drawable == null) {
        Box(
            modifier = Modifier.size(40.dp),
        )
        return
    }
    val bitmap = remember(drawable) {
        try { drawable.toBitmap(120, 120).asImageBitmap() } catch (e: Exception) { null }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
    } else {
        Box(modifier = Modifier.size(40.dp))
    }
}

@Composable
fun DomainsTab(
    viewModel: MainViewModel,
    onStartVpnMonitor: () -> Unit,
    onStopVpnMonitor: () -> Unit
) {
    val groups by viewModel.domainGroups.collectAsState()
    var monitorOn by remember { mutableStateOf(LocalVpnService.isRunning) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "برای دیدن دامنه‌هایی که هر برنامه به آن‌ها وصل می‌شود، باید یک VPN محلی روی گوشی فعال شود " +
                        "(ترافیک شما به بیرون از دستگاه ارسال نمی‌شود؛ فقط پرسش‌های DNS محلی تحلیل می‌شوند).",
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row {
                    Button(onClick = {
                        if (monitorOn) onStopVpnMonitor() else onStartVpnMonitor()
                        monitorOn = !monitorOn
                    }) {
                        Text(if (monitorOn) "توقف رصد" else "شروع رصد دامنه‌ها")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = { viewModel.clearDomainHistory() }) {
                        Text("پاک‌کردن تاریخچه")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("هنوز دامنه‌ای ثبت نشده. رصد را روشن کنید و کمی در برنامه‌های دیگر بگردید.", color = Color.Gray)
            }
        } else {
            val byApp = groups.groupBy { it.appName }
            LazyColumn {
                byApp.forEach { (appName, domains) ->
                    item {
                        Text(
                            appName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(domains) { d -> DomainRow(d) }
                }
            }
        }
    }
}

@Composable
fun DomainRow(group: AppDomainsGroup) {
    val sdf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(group.domain, fontSize = 14.sp)
        Row {
            Text("${group.count}×", fontSize = 12.sp, color = Color.Gray)
            Spacer(modifier = Modifier.width(8.dp))
            Text(sdf.format(Date(group.lastSeen)), fontSize = 12.sp, color = Color.Gray)
        }
    }
}
