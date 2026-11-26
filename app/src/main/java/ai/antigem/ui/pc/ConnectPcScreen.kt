package ai.antigem.ui.pc

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.pc.PcConnect
import ai.antigem.data.pc.PcConnect.PairedPc
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ai.antigem.i18n.L
import ai.antigem.ui.components.mergedIntoRow
import ai.antigem.ui.components.toggleRowSemantics

/** The pairing port VS Code uses when the user types the address by hand (it is shown next to the code there). */
private const val DEFAULT_PAIR_PORT = 1250

private val PcAccent: Color get() = ai.antigem.theme.AppColors.accentTeal

/** Live "can the phone reach it" for each paired PC (TCP to its SSH port), refreshed while shown. */
@Composable
private fun rememberReachability(pcs: List<PairedPc>): Map<String, Boolean?> {
    var state by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    LaunchedEffect(pcs.map { it.id to it.orderedAddresses() }) {
        while (true) {
            val next = mutableMapOf<String, Boolean?>()
            for (pc in pcs) next[pc.id] = PcConnect.reachableAddress(pc, 1_200) != null
            state = next
            delay(10_000)
        }
    }
    return state
}

private fun lastUsedText(pc: PairedPc): String {
    if (pc.lastUsedAt <= 0) return L.pc.connectPc.neverUsed
    val mins = (System.currentTimeMillis() - pc.lastUsedAt) / 60_000
    return when {
        mins < 1 -> L.pc.connectPc.usedJustNow
        mins < 60 -> L.pc.connectPc.usedMinsMAgo(mins)
        mins < 60 * 24 -> L.pc.connectPc.usedValueHAgo(mins / 60)
        else -> L.pc.connectPc.usedValueDAgo(mins / (60 * 24))
    }
}

private fun osIcon(os: String) = when (os) {
    "darwin" -> Icons.Outlined.LaptopMac
    "win32" -> Icons.Outlined.DesktopWindows
    else -> Icons.Outlined.Computer
}

/** Asks before switching (the app reopens) and switches. */
@Composable
private fun SwitchConfirm(target: PairedPc?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val back = PcConnect.ownModeLabel()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(if (target != null) osIcon(target.os) else Icons.Outlined.PhoneAndroid, null, tint = PcAccent) },
        title = { Text(if (target != null) "Use ${target.name}?" else "$back?", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Text(
                if (target != null) L.pc.connectPc.antigemReopensAndConnectsToName(target.name)
                else L.pc.connectPc.antigemReopensWithYourOwnSettings,
                fontSize = 13.5.sp, lineHeight = 19.sp
            )
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch { PcConnect.switchTo(context, target?.id) }
            }) { Text(if (busy) L.pc.connectPc.reopening else L.pc.connectPc.reopenNow, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

// ------------------------------------------------------------------------------------------------ settings page

@Composable
fun ConnectPcScreen(cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PcConnect.init(context) }
    val enabled by PcConnect.enabled.collectAsState()
    val pcs by PcConnect.pcs.collectAsState()
    val activeId by PcConnect.activeId.collectAsState()
    val active = pcs.firstOrNull { it.id == activeId }?.takeIf { enabled }
    val reach = rememberReachability(if (enabled) pcs else emptyList())

    var showAdd by remember { mutableStateOf(false) }
    var switchTarget by remember { mutableStateOf<PairedPc?>(null) }
    var switchBack by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PairedPc?>(null) }
    var removing by remember { mutableStateOf<PairedPc?>(null) }

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // the feature switch: on only makes it available, it does not connect
        Card(cardBg, cardBorder) {
            val onToggleEnabled: (Boolean) -> Unit = { on ->
                if (!on && active != null) confirmOff = true else PcConnect.setEnabled(on)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.toggleRowSemantics(enabled, onToggleEnabled)) {
                Box(Modifier.size(38.dp).background(PcAccent.copy(alpha = 0.14f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Computer, null, tint = PcAccent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(L.pc.connectPc.connectToPc, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        L.pc.connectPc.useTheAgentsRunningOnYour,
                        fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = enabled, onCheckedChange = onToggleEnabled, modifier = Modifier.mergedIntoRow())
            }
            if (!enabled) Hint(L.pc.connectPc.turningThisOnAddsAPc)
        }

        if (enabled) {
            // what the app uses now
            Card(cardBg, cardBorder) {
                Text("NOW USING", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(if (active != null) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        active?.name ?: if (ai.antigem.data.preferences.AuthPreferences.storedRemoteMode) L.pc.connectPc.yourServerSettingsServers else L.common.thisPhone,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f)
                    )
                    if (active != null) OutlinedButton(onClick = { switchBack = true }) { Text(PcConnect.ownModeLabel()) }
                }
            }

            // paired PCs
            Card(cardBg, cardBorder) {
                Text("PAIRED PCS", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                if (pcs.isEmpty()) Hint(L.pc.connectPc.noPcYetTapAddA)
                pcs.forEach { pc ->
                    PcRow(
                        pc = pc,
                        inUse = pc.id == active?.id,
                        reachable = reach[pc.id],
                        onConnect = { switchTarget = pc },
                        onRename = { renaming = pc },
                        onRemove = { removing = pc }
                    )
                }
                Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.pc.connectPc.addAPc)
                }
            }

            // how to get the PC ready
            Card(cardBg, cardBorder) {
                Text("ON THE PC (ONCE)", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Step(1, L.pc.connectPc.installAntigemInVsCodeAnd)
                Step(2, L.pc.connectPc.turnOnThePcsSshServer)
                Step(3, L.pc.connectPc.inAntigemOnThePcOpen)
                Step(4, L.pc.connectPc.hereAddAPcPickIt)
                Hint(L.pc.connectPc.thePhoneGetsItsOwnKey)
            }
        }
    }

    if (showAdd) AddPcDialog(onDismiss = { showAdd = false }, onConnect = { pc -> showAdd = false; switchTarget = pc })
    switchTarget?.let { SwitchConfirm(it) { switchTarget = null } }
    if (switchBack) SwitchConfirm(null) { switchBack = false }
    if (confirmOff) {
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(L.pc.connectPc.turnOffConnectToPc, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Text(L.pc.connectPc.youAreUsingNameAntigemReopens(active?.name), fontSize = 13.5.sp) },
            confirmButton = {
                Button(onClick = {
                    PcConnect.setEnabled(false)
                    scope.launch { PcConnect.switchTo(context, null) }
                }) { Text(L.pc.connectPc.turnOffReopen) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text(L.common.cancel) } }
        )
    }
    renaming?.let { pc ->
        var name by remember(pc.id) { mutableStateOf(pc.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(L.pc.connectPc.renamePc, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text(L.common.name) }) },
            confirmButton = { Button(enabled = name.isNotBlank(), onClick = { PcConnect.rename(pc.id, name); renaming = null }) { Text(L.common.save) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(L.common.cancel) } }
        )
    }
    removing?.let { pc ->
        val inUse = pc.id == active?.id
        AlertDialog(
            onDismissRequest = { removing = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Remove ${pc.name}?", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    L.pc.connectPc.valueThisPhoneForgetsThePc((if (inUse) L.pc.connectPc.youAreUsingItNowSwitch else "")),
                    fontSize = 13.5.sp, lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    enabled = !inUse,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = { PcConnect.remove(pc.id); removing = null }
                ) { Text(L.common.remove) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(L.common.cancel) } }
        )
    }
}

@Composable
private fun PcRow(pc: PairedPc, inUse: Boolean, reachable: Boolean?, onConnect: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (inUse) QuotaGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        border = if (inUse) BorderStroke(1.dp, QuotaGreen.copy(alpha = 0.4f)) else null
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(osIcon(pc.os), null, tint = PcAccent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(pc.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pc.user}@${pc.lastAddress}", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (label, color) = when {
                        inUse -> L.common.inUse to QuotaGreen
                        reachable == true -> L.pc.connectPc.reachable to QuotaGreen
                        reachable == false -> L.pc.connectPc.notReachable to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        else -> L.common.checking to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    }
                    Box(Modifier.size(6.dp).background(color, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text("$label · ${lastUsedText(pc)}" + if (pc.shellAllowed) L.pc.connectPc.terminalOnPc else "",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            if (!inUse) TextButton(onClick = onConnect) { Text(L.common.connect) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, L.common.more, Modifier.size(20.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(L.common.rename) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(L.common.remove) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ adding a PC

private sealed interface AddStep {
    data object Pick : AddStep
    data class Code(val name: String, val host: String, val port: Int) : AddStep
    data class Working(val name: String, val text: String) : AddStep
    data class Done(val pc: PairedPc) : AddStep
    data class Failed(val name: String, val host: String, val port: Int, val message: String, val pairedPc: PairedPc? = null) : AddStep
}

/** Find the PC on the Wi-Fi (or type its address), type the code VS Code shows, pair. */
@Composable
fun AddPcDialog(onDismiss: () -> Unit, onConnect: (PairedPc) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { PcConnect.init(context) }
    var step by remember { mutableStateOf<AddStep>(AddStep.Pick) }

    Dialog(onDismissRequest = { if (step !is AddStep.Working) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 620.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Computer, null, tint = PcAccent)
                    Spacer(Modifier.width(10.dp))
                    Text(L.pc.connectPc.addAPc, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    if (step !is AddStep.Working) IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, L.common.close) }
                }
                when (val s = step) {
                    AddStep.Pick -> PickPc { name, host, port -> step = AddStep.Code(name, host, port) }
                    is AddStep.Code -> CodeEntry(s.name, s.host, s.port, onBack = { step = AddStep.Pick }) { code ->
                        step = AddStep.Working(s.name, L.common.connecting)
                        scope.launch {
                            step = try {
                                AddStep.Done(PcConnect.pair(s.host, s.port, code) { text -> step = AddStep.Working(s.name, text) })
                            } catch (e: PcConnect.PairingException) {
                                // paired but the SSH test failed: the PC is saved, say what is wrong
                                val saved = PcConnect.list().lastOrNull()?.takeIf { e.message?.startsWith("Paired") == true }
                                AddStep.Failed(s.name, s.host, s.port, e.message ?: L.pc.connectPc.pairingFailed, saved)
                            } catch (e: Exception) {
                                AddStep.Failed(s.name, s.host, s.port, e.message ?: e.javaClass.simpleName)
                            }
                        }
                    }
                    is AddStep.Working -> {
                        Text(L.pc.connectPc.pairingWithName(s.name), fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Hint(s.text)
                    }
                    is AddStep.Done -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = QuotaGreen)
                            Spacer(Modifier.width(8.dp))
                            Text(L.pc.connectPc.pairedWithName(s.pc.name), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        Hint(L.pc.connectPc.connectNowToUseItsAgents)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(L.common.later) }
                            Button(onClick = { onConnect(s.pc) }, modifier = Modifier.weight(1f)) { Text(L.pc.connectPc.connectNow) }
                        }
                    }
                    is AddStep.Failed -> {
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)) {
                            Text(s.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                        }
                        if (s.pairedPc != null) Hint(L.pc.connectPc.nameIsSavedFixTheSsh(s.pairedPc.name))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { step = AddStep.Pick }, modifier = Modifier.weight(1f)) { Text(L.common.back) }
                            Button(onClick = { step = AddStep.Code(s.name, s.host, s.port) }, modifier = Modifier.weight(1f)) { Text(L.common.tryAgain) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickPc(onPick: (name: String, host: String, port: Int) -> Unit) {
    val context = LocalContext.current
    var nearby by remember { mutableStateOf<List<PcConnect.NearbyPc>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            PcConnect.discover(context).collect { nearby = it }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            searchError = e.message
        }
    }

    Text(L.pc.connectPc.onThePcOpenAntigemIn, fontSize = 13.sp, lineHeight = 18.sp)
    if (nearby.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(searchError ?: L.pc.connectPc.lookingForPcsOnThisWi, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
    }
    nearby.forEach { pc ->
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
            modifier = Modifier.fillMaxWidth().clickable { onPick(pc.name, pc.host, pc.port) }
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(osIcon(pc.os), null, tint = PcAccent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pc.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(pc.host, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
                Icon(Icons.Outlined.ChevronRight, null)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    if (!manual) {
        TextButton(onClick = { manual = true }) { Text(L.pc.connectPc.notListedEnterTheAddressShown) }
        Hint("Some routers hide devices from each other (guest Wi-Fi, \"client isolation\"); then typing the address works too.")
    } else {
        val parsed = parseAddress(address)
        OutlinedTextField(
            value = address, onValueChange = { address = it.trim().take(60) }, singleLine = true,
            label = { Text(L.pc.connectPc.addressShownOnThePc) }, placeholder = { Text("192.168.1.10:$DEFAULT_PAIR_PORT") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth()
        )
        Button(enabled = parsed != null, onClick = { parsed?.let { (h, p) -> onPick(h, h, p) } }, modifier = Modifier.fillMaxWidth()) { Text(L.pc.connectPc.next) }
    }
}

/** "192.168.1.10" / "192.168.1.10:1250" / "[fe80::1]:1250" -> host and pairing port. */
private fun parseAddress(text: String): Pair<String, Int>? {
    val t = text.trim()
    if (t.isEmpty() || t.contains(' ') || t.contains('/')) return null
    if (t.startsWith("[")) {
        val host = t.substringAfter('[').substringBefore(']').ifBlank { return null }
        val port = t.substringAfter("]:", "").toIntOrNull() ?: DEFAULT_PAIR_PORT
        return host to port
    }
    val parts = t.split(':')
    return when (parts.size) {
        1 -> t to DEFAULT_PAIR_PORT
        2 -> parts[0].ifBlank { return null } to (parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null)
        else -> t to DEFAULT_PAIR_PORT // a bare IPv6 address
    }
}

@Composable
private fun CodeEntry(name: String, host: String, port: Int, onBack: () -> Unit, onPair: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Text(L.pc.connectPc.pairWithName(name), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    Text("$host:$port", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    OutlinedTextField(
        value = code,
        onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
        singleLine = true,
        label = { Text(L.pc.connectPc.n6DigitCodeShownOnThe) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 22.sp, letterSpacing = 6.sp),
        modifier = Modifier.fillMaxWidth()
    )
    Hint(L.pc.connectPc.theCodeIsOnlyValidWhile)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(L.common.back) }
        Button(enabled = code.length == 6, onClick = { onPair(code) }, modifier = Modifier.weight(1f)) { Text(L.pc.connectPc.pair) }
    }
}

// ------------------------------------------------------------------------------------------------ side bar

/**
 * The side bar's PC button (next to Themes): green while the PC in use is connected, the warning color while it
 * cannot be reached (its SSH connection is down).
 */
@Composable
fun PcDrawerButton(onClick: () -> Unit) {
    val pcs by PcConnect.pcs.collectAsState()
    val activeId by PcConnect.activeId.collectAsState()
    val ssh by ai.antigem.data.remote.RemoteServerManager.sshState.collectAsState()
    val inUse = pcs.any { it.id == activeId } && PcConnect.active() != null
    val color = when {
        !inUse -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        ssh.connected -> QuotaGreen
        else -> ai.antigem.theme.AppColors.warning
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(8.dp), color = Color.Transparent, modifier = Modifier.padding(end = 4.dp)) {
        Box {
            Icon(
                Icons.Outlined.Computer,
                contentDescription = if (!inUse) L.pc.connectPc.connectToPc else if (ssh.connected) L.pc.connectPc.pcConnected else L.pc.connectPc.pcNotReachable,
                tint = color,
                modifier = Modifier.padding(8.dp).size(20.dp)
            )
            if (inUse) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(7.dp).background(color, CircleShape))
        }
    }
}

/**
 * A PC is in use but cannot be reached a while after the app opens (PC off, VS Code closed, another Wi-Fi): says so
 * once, and offers to keep trying or to go back to this phone's own agents (Termux, Ubuntu chroot) or the server.
 */
@Composable
fun PcUnreachablePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pcs by PcConnect.pcs.collectAsState()
    val activeId by PcConnect.activeId.collectAsState()
    val enabled by PcConnect.enabled.collectAsState()
    val pc = pcs.firstOrNull { it.id == activeId }?.takeIf { enabled }
    val ssh by ai.antigem.data.remote.RemoteServerManager.sshState.collectAsState()
    var show by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(pc?.id, retryTick) {
        if (pc == null || asked) return@LaunchedEffect
        // the connection gets its chance first
        delay(12_000)
        while (true) {
            if (ai.antigem.data.remote.RemoteServerManager.sshState.value.connected) return@LaunchedEffect
            if (PcConnect.reachableAddress(pc, 2_000) == null) {
                show = true
                asked = true
                return@LaunchedEffect
            }
            delay(5_000)
        }
    }
    // it came back while the question was open
    LaunchedEffect(ssh.connected) { if (ssh.connected) show = false }

    if (!show || pc == null) return
    val back = PcConnect.ownModeLabel()
    AlertDialog(
        onDismissRequest = { if (!busy) show = false },
        icon = { Icon(osIcon(pc.os), null, tint = ai.antigem.theme.AppColors.warning) },
        title = { Text(L.pc.connectPc.nameIsNotReachable(pc.name), fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Text(
                L.pc.connectPc.antigemUsesTheAgentsOnName(pc.name, pc.orderedAddresses().joinToString(", "), pc.sshPort, (ssh.message.takeIf { it.isNotBlank() && !ssh.connected }?.let { L.pc.connectPc.lastTryValue(it) } ?: "")),
                fontSize = 13.5.sp, lineHeight = 19.sp
            )
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch { PcConnect.switchTo(context, null) }
            }) { Text(if (busy) L.pc.connectPc.reopening else back, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy, onClick = {
                    show = false
                    asked = false
                    ai.antigem.data.remote.RemoteServerManager.start(context, force = false)
                    retryTick++
                }) { Text(L.common.retry) }
                TextButton(enabled = !busy, onClick = { show = false }) { Text(L.pc.connectPc.keepWaiting) }
            }
        }
    )
}

/** The side bar's PC sheet: what is in use, switch to a paired PC or back, add a PC, open the settings page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcQuickSheet(onDismiss: () -> Unit, onManage: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PcConnect.init(context) }
    val pcs by PcConnect.pcs.collectAsState()
    val activeId by PcConnect.activeId.collectAsState()
    val active = pcs.firstOrNull { it.id == activeId }
    val reach = rememberReachability(pcs)
    val sshState by ai.antigem.data.remote.RemoteServerManager.sshState.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var switchTarget by remember { mutableStateOf<PairedPc?>(null) }
    var switchBack by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = ai.antigem.ui.components.rememberSheetStateFullUnderTalkBack()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L.pc.connectPc.connectToPc, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L.pc.connectPc.nowUsing, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Box(Modifier.size(8.dp).background(if (active != null) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(active?.name ?: if (ai.antigem.data.preferences.AuthPreferences.storedRemoteMode) L.pc.connectPc.yourServer else L.common.thisPhone,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            // the PC in use cannot be reached: why, and the way back is the button below
            if (active != null && !sshState.connected) {
                Text(L.pc.connectPc.notReachableMessage(sshState.message), fontSize = 12.sp, color = ai.antigem.theme.AppColors.warning)
            }
            if (pcs.isEmpty()) Hint(L.pc.connectPc.noPcPairedYet)
            pcs.forEach { pc ->
                val inUse = pc.id == active?.id
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (inUse) QuotaGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !inUse) { switchTarget = pc }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(osIcon(pc.os), null, tint = PcAccent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pc.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(pc.lastAddress, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        val (label, color) = when {
                            inUse && sshState.connected -> L.pc.connectPc.inUseConnected to QuotaGreen
                            inUse -> L.pc.connectPc.inUseNotReachable to ai.antigem.theme.AppColors.warning
                            reach[pc.id] == true -> L.pc.connectPc.reachable to QuotaGreen
                            reach[pc.id] == false -> L.pc.connectPc.notReachable to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            else -> "…" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        }
                        Box(Modifier.size(7.dp).background(color, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(label, fontSize = 11.5.sp, color = color)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(L.pc.connectPc.addPc)
                }
                if (active != null) OutlinedButton(onClick = { switchBack = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(PcConnect.ownModeLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onManage, modifier = Modifier.align(Alignment.End)) { Text(L.pc.connectPc.managePcs) }
        }
    }

    if (showAdd) AddPcDialog(onDismiss = { showAdd = false }, onConnect = { pc -> showAdd = false; switchTarget = pc })
    switchTarget?.let { SwitchConfirm(it) { switchTarget = null } }
    if (switchBack) SwitchConfirm(null) { switchBack = false }
}

// ------------------------------------------------------------------------------------------------ Settings › Servers

/** Settings › Servers while a PC is in use: its form would show the PC's values, so it waits until the switch back. */
@Composable
fun PcServersNotice(cardBg: Color, cardBorder: BorderStroke, onOpenConnectPc: () -> Unit) {
    val active = PcConnect.active()
    var switchBack by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(cardBg, cardBorder) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Computer, null, tint = QuotaGreen)
                Spacer(Modifier.width(10.dp))
                Text(L.pc.connectPc.connectedToValue(active?.name ?: L.pc.connectPc.aPc), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Hint(L.pc.connectPc.connectToPcIsInUse)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenConnectPc, modifier = Modifier.weight(1f)) { Text(L.pc.connectPc.connectToPc) }
                Button(onClick = { switchBack = true }, modifier = Modifier.weight(1f)) { Text(PcConnect.ownModeLabel()) }
            }
        }
    }
    if (switchBack) SwitchConfirm(null) { switchBack = false }
}

// ------------------------------------------------------------------------------------------------ bits

@Composable
private fun Card(cardBg: Color, cardBorder: BorderStroke, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = cardBg, border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, lineHeight = 16.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp).background(PcAccent.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PcAccent)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}
