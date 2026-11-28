package ai.antigem.ui.netcap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.netcap.NetApps
import ai.antigem.data.netcap.NetStore
import ai.antigem.data.netcap.NetcapManager
import ai.antigem.data.netcap.NetcapVpnService
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import ai.antigem.ui.components.AppToastHelper
import kotlinx.coroutines.launch

/**
 * Choose the apps to capture: search, system apps, the chosen ones on top. While a capture runs, saving restarts it
 * with the new apps (an agent's capture too: both see the same list).
 */
@Composable
internal fun AppPickerScreen() {
    val host = LocalNetHost.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by NetStore.state.collectAsState()
    val mgr by NetcapManager.state.collectAsState()
    val capturing = mgr.capturing
    val current = if (capturing) st.capture.apps.ifEmpty { mgr.apps } else NetcapManager.Prefs.lastApps(context)
    val chosen = remember { mutableStateListOf<String>().apply { addAll(current) } }
    var apps by remember { mutableStateOf<List<NetApps.App>?>(null) }
    var q by remember { mutableStateOf("") }
    var system by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { apps = NetApps.all(context) }
    val shown = remember(apps, q, system, chosen.toList()) {
        val all = apps ?: return@remember emptyList()
        val t = q.trim()
        all.filter { a ->
            (system || !a.system || a.pkg in chosen) && (t.isEmpty() || a.label.contains(t, true) || a.pkg.contains(t, true))
        }.sortedWith(compareByDescending<NetApps.App> { it.pkg in chosen })
    }
    Column(Modifier.fillMaxSize()) {
        NetTopBar(L.netcap.screen.appsToCapture, subtitle = L.netcap.ui.appsChosen(chosen.size), onBack = host.back) {
            Button(
                enabled = !busy,
                onClick = {
                    val list = chosen.toList()
                    NetcapManager.Prefs.setLastApps(context, list)
                    if (!capturing || list.isEmpty()) { host.back(); return@Button }
                    busy = true
                    scope.launch {
                        val r = NetcapVpnService.startCapture(context, list)
                        busy = false
                        r.exceptionOrNull()?.let { AppToastHelper.showToast(it.message ?: it.toString()) }
                        host.back()
                    }
                },
                modifier = Modifier.padding(end = 6.dp)
            ) { Text(if (capturing) L.netcap.ui.applyRestart else L.common.done) }
        }
        NetSearchField(
            q, { q = it }, L.netcap.screen.searchApps, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            leading = { Icon(Icons.Outlined.Search, null, tint = AppColors.textSecondary, modifier = Modifier.size(18.dp)) }
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(system, { system = it })
            Text(L.netcap.screen.showSystemApps, color = AppColors.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (chosen.isNotEmpty()) TextButton(onClick = { chosen.clear() }) { Text(L.common.clear) }
        }
        if (chosen.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chosen.forEach { pkg ->
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(AppColors.primary.copy(alpha = 0.14f)).clickable { chosen.remove(pkg) }
                            .padding(start = 4.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(pkg, 20.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(NetApps.label(context, pkg), color = AppColors.onBackground, fontSize = 12.5.sp, maxLines = 1)
                        Icon(Icons.Filled.Close, L.common.remove, tint = AppColors.textSecondary, modifier = Modifier.padding(start = 3.dp).size(14.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, null, tint = AppColors.warning, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(L.netcap.screen.bankingWarning, color = AppColors.textSecondary, fontSize = 12.sp)
        }
        if (apps == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            items(shown, key = { it.pkg }) { a ->
                val on = a.pkg in chosen
                Row(
                    Modifier.fillMaxWidth().clickable { if (on) chosen.remove(a.pkg) else chosen.add(a.pkg) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(a.pkg, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, color = AppColors.onBackground, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.pkg, color = AppColors.textSecondary, fontSize = 11.5.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(on, { if (it) chosen.add(a.pkg) else chosen.remove(a.pkg) })
                }
            }
        }
    }
}
