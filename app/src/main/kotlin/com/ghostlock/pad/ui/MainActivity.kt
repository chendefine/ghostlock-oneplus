package com.ghostlock.pad.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostlock.pad.BuildConfig
import com.ghostlock.pad.EngineContract
import com.ghostlock.pad.R
import com.ghostlock.pad.data.ManagerScanner
import com.ghostlock.pad.engine.KernelGate
import com.ghostlock.pad.shizuku.ShizukuEngine
import com.ghostlock.pad.shizuku.ShizukuStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GhostLockPadApp() }
    }
}

private val PHASES = listOf(
    EngineContract.PHASE_STAGE,
    EngineContract.PHASE_EXPLOIT,
    EngineContract.PHASE_VERIFY,
    EngineContract.PHASE_KSU,
    EngineContract.PHASE_DONE,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GhostLockPadApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engine = remember { ShizukuEngine(context) }
    var shizukuStatus by remember { mutableStateOf(ShizukuStatus.NOT_RUNNING) }
    var kernel by remember { mutableStateOf(System.getProperty("os.version", "")) }
    var probeText by remember { mutableStateOf<String?>(null) }
    var managers by remember { mutableStateOf<List<ManagerScanner.Candidate>>(emptyList()) }
    var selectedManager by remember {
        mutableStateOf(
            context.getSharedPreferences("ghostlock", Context.MODE_PRIVATE)
                .getString("manager_package", null),
        )
    }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }

    val kernelSupported = remember(kernel) { KernelGate.isSupported(kernel) }
    val rootLive = remember(probeText) { probeText?.lines()?.any { it.startsWith("root=1") } == true }
    val chosenCandidate = remember(managers, selectedManager) {
        managers.firstOrNull { it.packageName == selectedManager }
    }
    var scanInFlight by remember { mutableStateOf(false) }
    var lastFullScan by remember { mutableStateOf(0L) }

    /**
     * Never runs package scanning on the calling thread: Shizuku binder
     * listeners fire this from binder threads and the composition calls it
     * from the main thread. A full [ManagerScanner.scan] with the OEM
     * "read app list" grant returns hundreds of packages, and probing them
     * on the main thread froze the app at startup (looked like a crash) —
     * it is debounced and dispatched to IO here instead.
     */
    fun refreshStatus() {
        runCatching {
            shizukuStatus = engine.status()
            kernel = System.getProperty("os.version", "")
            if (shizukuStatus == ShizukuStatus.READY) {
                scope.launch {
                    probeText = runCatching { engine.probe() }.getOrNull()
                }
            } else {
                probeText = null
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (!scanInFlight && now - lastFullScan > RESCAN_INTERVAL_MS) {
                scanInFlight = true
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val found = runCatching {
                        ManagerScanner.scan(context.packageManager, context.packageName)
                    }.getOrDefault(emptyList())
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        // Keep the previous good result if this scan came back empty.
                        if (found.isNotEmpty() || managers.isEmpty()) {
                            managers = found
                        }
                        if (selectedManager == null) {
                            selectedManager = managers.firstOrNull { it.hasKsud }?.packageName
                        }
                        scanInFlight = false
                        lastFullScan = android.os.SystemClock.elapsedRealtime()
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        engine.setStatusListener { refreshStatus() }
        refreshStatus()
    }

    fun appendLog(line: String) {
        logs = (logs + line).takeLast(MAX_LOG_LINES)
    }

    fun doRun() {
        if (running) return
        running = true
        phase = null
        summary = null
        logs = emptyList()
        appendLog("<b> GhostLock Pad ${BuildConfig.VERSION_NAME} starting")
        appendLog("<b> target kernel: ${EngineContract.EXPECTED_KERNEL}")
        scope.launch {
            engine.run(
                managerPackage = selectedManager,
                onLog = { line -> appendLog(line) },
                onPhase = { p -> phase = p },
                onComplete = { code, s ->
                    summary = "exit=$code $s"
                    running = false
                    refreshStatus()
                },
            )
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    stringResource(R.string.app_name),
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusCard(
                        kernel = kernel,
                        supported = kernelSupported,
                        rootLive = rootLive,
                    )
                    ShizukuCard(
                        status = shizukuStatus,
                        onGrant = { engine.requestPermission() },
                    )
                    ManagerCard(
                        managers = managers,
                        selected = chosenCandidate,
                        onSelect = { pkg ->
                            selectedManager = pkg
                            context.getSharedPreferences("ghostlock", Context.MODE_PRIVATE)
                                .edit().putString("manager_package", pkg).apply()
                        },
                    )
                    RunCard(
                        enabled = kernelSupported &&
                            shizukuStatus == ShizukuStatus.READY &&
                            !running,
                        running = running,
                        phase = phase,
                        summary = summary,
                        onRun = { doRun() },
                    )
                    LogCard(logs = logs, onCopy = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText("ghostlock-log", logs.joinToString("\n")),
                        )
                    })
                    Text(
                        stringResource(R.string.disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(kernel: String, supported: Boolean, rootLive: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.kernel_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (rootLive) Badge(stringResource(R.string.root_live), Color(0xFF16A34A))
            }
            Text(kernel, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            if (supported) {
                Badge(stringResource(R.string.kernel_supported), Color(0xFF2563EB))
            } else {
                Badge(stringResource(R.string.kernel_unsupported), Color(0xFFDC2626))
                Text(
                    stringResource(R.string.kernel_unsupported_hint, EngineContract.EXPECTED_KERNEL),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShizukuCard(status: ShizukuStatus, onGrant: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.shizuku_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                when (status) {
                    ShizukuStatus.READY -> Badge(stringResource(R.string.shizuku_ready), Color(0xFF16A34A))
                    ShizukuStatus.PERMISSION_REQUIRED ->
                        Badge(stringResource(R.string.shizuku_permission), Color(0xFFD97706))
                    ShizukuStatus.NOT_RUNNING ->
                        Badge(stringResource(R.string.shizuku_offline), Color(0xFFDC2626))
                }
            }
            when (status) {
                ShizukuStatus.NOT_RUNNING -> Text(
                    stringResource(R.string.shizuku_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ShizukuStatus.PERMISSION_REQUIRED -> Button(onClick = onGrant) {
                    Text(stringResource(R.string.shizuku_grant))
                }
                ShizukuStatus.READY -> {}
            }
        }
    }
}

@Composable
private fun ManagerCard(
    managers: List<ManagerScanner.Candidate>,
    selected: ManagerScanner.Candidate?,
    onSelect: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.manager_title), style = MaterialTheme.typography.titleMedium)
            if (managers.isEmpty()) {
                Text(
                    stringResource(R.string.manager_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val ksudCarriers = managers.filter { it.hasKsud }
                if (ksudCarriers.isEmpty()) {
                    Text(
                        stringResource(R.string.manager_no_ksud),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD97706),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    managers.take(8).forEach { candidate ->
                        FilterChip(
                            selected = candidate == selected,
                            onClick = { onSelect(candidate.packageName) },
                            label = {
                                Text(
                                    if (candidate.hasKsud) "${candidate.label} ✓" else candidate.label,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunCard(
    enabled: Boolean,
    running: Boolean,
    phase: String?,
    summary: String?,
    onRun: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.run_title), style = MaterialTheme.typography.titleMedium)
            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(phase ?: EngineContract.PHASE_STAGE, fontFamily = FontFamily.Monospace)
                }
            }
            Button(
                onClick = onRun,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (running) stringResource(R.string.run_busy) else stringResource(R.string.run_action),
                )
            }
            summary?.let {
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LogCard(logs: List<String>, onCopy: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.log_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onCopy) { Text(stringResource(R.string.log_copy)) }
            }
            Spacer(Modifier.height(8.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Color(0xFF0B1220), RoundedCornerShape(8.dp)),
            ) {
                if (logs.isEmpty()) {
                    Text(
                        stringResource(R.string.log_empty),
                        Modifier.padding(12.dp),
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    ) {
                        items(logs) { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    line.startsWith("[+]") -> Color(0xFF4ADE80)
                                    line.startsWith("[!]") -> Color(0xFFFBBF24)
                                    line.startsWith("[-]") || line.contains("error") -> Color(0xFFF87171)
                                    line.startsWith("<b>") -> Color(0xFF93C5FD)
                                    else -> Color(0xFFCBD5E1)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Row(
        Modifier
            .background(color.copy(alpha = 0.15f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

private const val MAX_LOG_LINES = 2000

/** Minimum spacing between full manager scans (debounce for listener storms). */
private const val RESCAN_INTERVAL_MS = 5_000L
