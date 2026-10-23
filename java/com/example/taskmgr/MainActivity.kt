package com.example.taskmgr

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.*
import android.os.*
import android.provider.Settings
import android.net.TrafficStats
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

data class Proc(val pkg: String, val name: String, val lastUsed: Long, val fgMs: Long)

fun hasUsage(c: Context): Boolean {
    val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
}

fun label(c: Context, pkg: String): String = try {
    c.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(pkg, 0)).toString() }
} catch (e: Exception) { pkg }

fun loadProcs(c: Context, hours: Int): List<Proc> {
    val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    return usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - hours * 3600_000L, now)
        .filter { it.lastTimeUsed > 0 && it.packageName != c.packageName }
        .groupBy { it.packageName }
        .map { (p, l) -> Proc(p, label(c, p), l.maxOf { it.lastTimeUsed }, l.sumOf { it.totalTimeInForeground }) }
}

@Composable
fun App() {
    val tabs = listOf("プロセス", "パフォーマンス", "サービス", "スタートアップ", "アプリの履歴")
    var tab by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var alive by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { alive = Sh.alive(); ready = Sh.ready(); delay(1000) } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (!ready) Row(Modifier.fillMaxWidth().background(Color(0xFF3A2A00)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (alive) "Shizukuの権限が未許可です" else "Shizuku未起動（簡易モード）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (alive) TextButton({ Sh.ask() }) { Text("許可") }
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) { 0 -> ProcessTab(ready); 1 -> PerfTab(); 2 -> ServicesTab(ready); 3 -> StartupTab(); else -> HistoryTab() }
    }
}

@Composable
fun ProcessTab(ready: Boolean) { if (ready) RealProcessTab() else BasicProcessTab() }

@Composable
fun RealProcessTab() {
    var cpu by remember { mutableFloatStateOf(0f) }
    var list by remember { mutableStateOf(listOf<SProc>()) }
    var msg by remember { mutableStateOf("") }
    var byMem by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { val r = withContext(Dispatchers.IO) { Sh.top() }; cpu = r.first; list = r.second } }
    val rows = if (byMem) list.sortedByDescending { it.resKb } else list.sortedByDescending { it.cpu }
    Column {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CPU ${cpu.toInt()}%  ${list.size}プロセス\n$msg", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton({ byMem = !byMem }) { Text(if (byMem) "メモリ順" else "CPU順") }
        }
        LazyColumn {
            items(rows, key = { it.pid }) { p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        Text("PID ${p.pid}   CPU ${p.cpu}%   ${p.resKb / 1024} MB", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    TextButton({ scope.launch { msg = withContext(Dispatchers.IO) { Sh.kill(p) } } }) { Text("終了") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun ServicesTab(ready: Boolean) {
    if (!ready) { Text("この機能にはShizukuが必要です", Modifier.padding(24.dp)); return }
    var list by remember { mutableStateOf(listOf<String>()) }
    var msg by remember { mutableStateOf("実行中のサービスを取得中…") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { Sh.services() }; msg = "実行中のサービス ${list.size}件" }
    Column {
        Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
        LazyColumn {
            items(list, key = { it }) { c ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ scope.launch { msg = withContext(Dispatchers.IO) { Sh.stopService(c) } } }) { Text("停止") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun NeedUsage(content: @Composable () -> Unit) {
    val c = LocalContext.current
    var ok by remember { mutableStateOf(hasUsage(c)) }
    LaunchedEffect(Unit) { while (true) { ok = hasUsage(c); delay(1000) } }
    if (ok) content() else Column(Modifier.padding(24.dp)) {
        Text("アプリの使用状況へのアクセスを許可してください（Androidの制限で必須です）")
        Spacer(Modifier.height(12.dp))
        Button({ c.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("設定を開く") }
    }
}

@Composable
fun BasicProcessTab() = NeedUsage {
    val c = LocalContext.current
    val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    var list by remember { mutableStateOf(loadProcs(c, 24).sortedByDescending { it.lastUsed }) }
    var msg by remember { mutableStateOf("") }
    Column {
        Text("直近24時間に使ったアプリ ${list.size}件  $msg", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
        LazyColumn {
            items(list, key = { it.pkg }) { p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        Text(p.pkg, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    TextButton({
                        am.killBackgroundProcesses(p.pkg)
                        msg = "${p.name} を終了しました"
                    }) { Text("タスクの終了") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun HistoryTab() = NeedUsage {
    val c = LocalContext.current
    val list = remember { loadProcs(c, 24 * 7).sortedByDescending { it.fgMs } }
    LazyColumn {
        item { Text("過去7日間の使用時間", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge) }
        items(list, key = { it.pkg }) { p ->
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(p.name, Modifier.weight(1f))
                Text("${p.fgMs / 60000} 分")
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun StartupTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    val list = remember {
        c.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED), 0)
            .map { Triple(it.activityInfo.packageName, label(c, it.activityInfo.packageName), it.activityInfo.name) }
            .sortedBy { it.second }
    }
    LazyColumn {
        item { Text("起動時に自動実行を登録しているアプリ ${list.size}件  $msg", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge) }
        items(list, key = { it.first + it.third }) { (pkg, name, cls) ->
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(name); Text(cls.substringAfterLast('.'), style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                TextButton({
                    if (Sh.ready()) scope.launch { msg = withContext(Dispatchers.IO) { Sh.disableComponent("$pkg/$cls") } }
                    else c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg")))
                }) { Text("無効化") }
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun Graph(data: List<Float>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(80.dp).background(Color(0xFF1B1B1B))) {
        if (data.size < 2) return@Canvas
        val p = Path()
        data.forEachIndexed { i, v ->
            val x = size.width * (i + 60 - data.size) / 59f
            val y = size.height * (1f - v.coerceIn(0f, 100f) / 100f)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(3f))
        for (k in 1..3) drawLine(Color(0x33FFFFFF), Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
    }
}

fun cpuPercent(): Float? = try {
    val cur = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }!!.mapNotNull {
        val c = File(it, "cpufreq/scaling_cur_freq").readText().trim().toFloat()
        val m = File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toFloat()
        c / m
    }
    if (cur.isEmpty()) null else cur.average().toFloat() * 100f
} catch (e: Exception) { null }

@Composable
fun PerfTab() {
    val c = LocalContext.current
    val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val cpu = remember { mutableStateListOf<Float>() }
    val mem = remember { mutableStateListOf<Float>() }
    val net = remember { mutableStateListOf<Float>() }
    var info by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        var lastRx = TrafficStats.getTotalRxBytes(); var lastTx = TrafficStats.getTotalTxBytes()
        while (true) {
            (if (Sh.ready()) withContext(Dispatchers.IO) { Sh.top().first } else cpuPercent())?.let { cpu.add(it) }
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val used = 100f * (mi.totalMem - mi.availMem) / mi.totalMem
            mem.add(used)
            val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
            val kbps = ((rx - lastRx) + (tx - lastTx)) / 1024f; lastRx = rx; lastTx = tx
            net.add((kbps / 10f).coerceAtMost(100f))
            val st = StatFs(Environment.getDataDirectory().path)
            val bat = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            info = "メモリ: ${(mi.totalMem - mi.availMem) shr 20} / ${mi.totalMem shr 20} MB (${used.toInt()}%)\n" +
                "ストレージ: ${(st.totalBytes - st.availableBytes) shr 30} / ${st.totalBytes shr 30} GB\n" +
                "ネットワーク: ${kbps.toInt()} KB/s\n" +
                "バッテリー: $lvl%  $temp°C\n" +
                "コア数: ${Runtime.getRuntime().availableProcessors()}  ${Build.HARDWARE}"
            while (cpu.size > 60) cpu.removeAt(0); while (mem.size > 60) mem.removeAt(0); while (net.size > 60) net.removeAt(0)
            delay(1000)
        }
    }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CPU"); Graph(cpu, Color(0xFF4FC3F7))
        Text("メモリ"); Graph(mem, Color(0xFFBA68C8))
        Text("ネットワーク（上限1MB/s）"); Graph(net, Color(0xFFFFB74D))
        Text(info)
    }
}