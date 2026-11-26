package ai.antigem.ui.settings

import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.i18n.L
import ai.antigem.ui.components.PetPreview
import ai.antigem.ui.components.PetSession
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.mergedIntoRow
import ai.antigem.ui.components.pet.PetKind
import ai.antigem.ui.components.pet.PetLook
import ai.antigem.ui.components.pet.PetPower
import ai.antigem.ui.components.pet.PetSettings
import ai.antigem.ui.components.rememberPetPreviewController
import ai.antigem.ui.components.toggleRowSemantics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Reads and changes Settings → Pets (one JSON preference, edited atomically). */
private class PetPrefsState(val prefs: AuthPreferences, val enabled: Boolean, val settings: PetSettings?)

@Composable
private fun rememberPetPrefs(): Pair<PetPrefsState, ((PetSettings) -> PetSettings) -> Unit> {
    val context = LocalContext.current
    val prefs = remember(context) { AuthPreferences(context) }
    val enabled by prefs.isGemmyEnabled.collectAsState(initial = true)
    val json by prefs.petSettingsJson.collectAsState(initial = null)
    val settings = remember(json) { json?.let { PetSettings.fromJson(it) } }
    val scope = rememberCoroutineScope()
    val update: ((PetSettings) -> PetSettings) -> Unit = { f ->
        scope.launch { prefs.updatePetSettingsJson { old -> f(PetSettings.fromJson(old)).toJson() } }
    }
    return PetPrefsState(prefs, enabled, settings) to update
}

/**
 * Settings → Pets: on/off, the pet gallery (live previews: tap = it says its name, hold = its signature move, the gear
 * opens [onEditPet]), size and opacity, behaviour, and battery / smoothness (presets or each value by hand).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PetsSettingsScreen(onEditPet: (PetKind) -> Unit) {
    val (state, update) = rememberPetPrefs()
    val s = state.settings ?: return
    val scope = rememberCoroutineScope()
    val hidden by PetSession.hidden.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val cardColor = ai.antigem.theme.AppColors.card
    val accent = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
        // ----- on / off, with the current pet -----
        Surface(shape = RoundedCornerShape(18.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
            val onTogglePet: (Boolean) -> Unit = { on ->
                scope.launch {
                    state.prefs.setGemmyEnabled(on)
                    state.prefs.setHasSeenGemmyIntro(true) // decided here: no need for the intro dialog anymore
                }
                if (on) PetSession.hidden.value = false
            }
            Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp).toggleRowSemantics(state.enabled, onTogglePet), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(96.dp)
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.18f), Color.Transparent)), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    PetPreview(s.kind, s.look(), zoom = 0.95f, modifier = Modifier.alpha(if (state.enabled) 1f else 0.4f))
                }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(L.pets.settings.showPet, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(L.pets.settings.showPetSub, fontSize = 12.sp, lineHeight = 16.sp, color = muted)
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onTogglePet,
                    modifier = Modifier.mergedIntoRow()
                )
            }
        }
        if (hidden && state.enabled) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.1f)).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(L.pets.settings.hiddenNow, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { PetSession.hidden.value = false }) { Text(L.pets.settings.showNow) }
            }
        }

        // ----- the gallery -----
        SectionTitle(L.pets.settings.choose, L.pets.settings.chooseSub)
        PetKind.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { k ->
                    PetCard(
                        kind = k,
                        look = s.look(k),
                        selected = k == s.kind,
                        cardColor = cardColor,
                        onUse = { update { it.copy(kind = k) } },
                        onEdit = { onEditPet(k) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }

        // ----- size & look -----
        SectionTitle(L.pets.settings.sizeLook)
        Surface(shape = RoundedCornerShape(16.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                SliderRow(
                    title = L.pets.settings.size,
                    value = s.size,
                    range = PetSettings.SIZES,
                    steps = 9,
                    label = { "%.1f×".format(it) },
                    onDone = { v -> update { it.copy(size = (v * 10f).roundToInt() / 10f) } }
                )
                SliderRow(
                    title = L.pets.settings.opacity,
                    value = s.opacity,
                    range = PetSettings.OPACITIES,
                    steps = 12,
                    label = { "${(it * 100).roundToInt()}%" },
                    onDone = { v -> update { it.copy(opacity = v) } }
                )
            }
        }

        // ----- behaviour -----
        SectionTitle(L.pets.settings.behaviour)
        Surface(shape = RoundedCornerShape(16.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                ChoiceRow(
                    title = L.pets.settings.activity,
                    options = listOf(L.common.off, L.pets.settings.activityRare, L.pets.settings.activityNormal, L.pets.settings.activityOften),
                    selected = s.activity,
                    onSelect = { i -> update { it.copy(activity = i) } }
                )
                RowDivider()
                SwitchRow(L.pets.settings.wander, L.pets.settings.wanderSub, s.wander) { v -> update { it.copy(wander = v) } }
                RowDivider()
                ChoiceRow(
                    title = L.pets.settings.sleepAfter,
                    options = PetSettings.SLEEP_AFTER.map { secondsLabel(it) },
                    selected = PetSettings.SLEEP_AFTER.indexOf(s.sleepAfterS),
                    onSelect = { i -> update { it.copy(sleepAfterS = PetSettings.SLEEP_AFTER[i]) } }
                )
                RowDivider()
                SwitchRow(L.pets.settings.speech, null, s.speech) { v -> update { it.copy(speech = v) } }
                if (s.speech) {
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        SliderRow(
                            title = L.pets.settings.bubbleTime,
                            value = s.bubbleTime,
                            range = 0.5f..2.5f,
                            steps = 7,
                            label = { "${(it * 4f).roundToInt() / 4f}×" },
                            onDone = { v -> update { it.copy(bubbleTime = (v * 4f).roundToInt() / 4f) } }
                        )
                    }
                }
                RowDivider()
                SwitchRow(L.pets.settings.agentReact, L.pets.settings.agentReactSub, s.agentReact) { v -> update { it.copy(agentReact = v) } }
                RowDivider()
                SwitchRow(L.pets.settings.effects, L.pets.settings.effectsSub, s.effects) { v -> update { it.copy(effects = v) } }
                RowDivider()
                SwitchRow(L.pets.settings.haptics, null, s.haptics) { v -> update { it.copy(haptics = v) } }
                RowDivider()
                SwitchRow(L.pets.settings.hideKeyboard, L.pets.settings.hideKeyboardSub, s.hideWithKeyboard) { v -> update { it.copy(hideWithKeyboard = v) } }
            }
        }

        // ----- battery & smoothness -----
        SectionTitle(L.pets.settings.power, L.pets.settings.powerSub)
        Surface(shape = RoundedCornerShape(16.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                val power = s.power
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PetPower.entries.forEach { p ->
                        FilterChip(selected = power == p, onClick = { update { it.withPower(p) } }, label = { Text(powerName(p)) })
                    }
                    if (power == null) FilterChip(selected = true, onClick = {}, label = { Text(L.pets.settings.custom) })
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when (power) {
                        PetPower.SAVER -> L.pets.settings.saverSub
                        PetPower.BALANCED -> L.pets.settings.balancedSub
                        PetPower.LIVELY -> L.pets.settings.livelySub
                        PetPower.ALWAYS -> L.pets.settings.alwaysSub
                        null -> L.pets.settings.customSub
                    },
                    fontSize = 12.sp, lineHeight = 16.sp, color = muted
                )
                if (s.restAfterS == 0) {
                    Spacer(Modifier.height(8.dp))
                    val warn = ai.antigem.theme.AppColors.warning
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(warn.copy(alpha = 0.12f)).padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Outlined.Warning, null, tint = warn, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(L.pets.settings.alwaysWarning(s.idleFps), fontSize = 12.5.sp, lineHeight = 17.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                ChipsBlock(
                    title = L.pets.settings.goStill,
                    options = PetSettings.REST_AFTER.map { secondsLabel(it) },
                    selected = PetSettings.REST_AFTER.indexOf(s.restAfterS),
                    onSelect = { i -> update { it.copy(restAfterS = PetSettings.REST_AFTER[i]) } }
                )
                ChipsBlock(
                    title = L.pets.settings.idleFps,
                    options = PetSettings.IDLE_FPS.map { L.pets.settings.fps(it) },
                    selected = PetSettings.IDLE_FPS.indexOf(s.idleFps),
                    onSelect = { i -> update { it.copy(idleFps = PetSettings.IDLE_FPS[i]) } }
                )
                ChipsBlock(
                    title = L.pets.settings.moveFps,
                    options = PetSettings.MOVE_FPS.map { if (it == 0) L.pets.settings.screenRate else L.pets.settings.fps(it) },
                    selected = PetSettings.MOVE_FPS.indexOf(s.moveFps),
                    onSelect = { i -> update { it.copy(moveFps = PetSettings.MOVE_FPS[i]) } }
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        TextButton(
            onClick = { update { PetSettings(kind = it.kind, looks = it.looks) } },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text(L.pets.settings.restoreDefaults) }
        Spacer(Modifier.height(24.dp))
    }
}

/** One pet in the gallery: live preview, name, what it is, Use / In use, and a gear for its own page. */
@Composable
private fun PetCard(
    kind: PetKind,
    look: PetLook,
    selected: Boolean,
    cardColor: Color,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.1f) else cardColor)
            .border(if (selected) BorderStroke(1.5.dp, accent) else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)), shape)
            .padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().height(110.dp)) {
            PetPreview(kind, look, zoom = 1.05f, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 0.dp))
            IconButton(onClick = onEdit, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
                Icon(Icons.Outlined.Settings, contentDescription = L.pets.settings.customize, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = L.pets.settings.inUse, tint = accent,
                    modifier = Modifier.align(Alignment.TopStart).padding(9.dp).size(18.dp)
                )
            }
        }
        Text(look.name(kind), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(kind.tag, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        if (selected) {
            Text(
                L.pets.settings.inUse, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = accent,
                modifier = Modifier.height(32.dp).padding(top = 7.dp)
            )
        } else {
            OutlinedButton(onClick = onUse, modifier = Modifier.height(32.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp)) {
                Text(L.pets.settings.use, fontSize = 12.5.sp)
            }
        }
    }
}

/**
 * One pet's own page: a big live preview with "Try it" buttons for every reaction, what it is, Use, its name, colors
 * (live mini previews) and its extra option (tails, axe, bell).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PetEditScreen(kind: PetKind) {
    val (state, update) = rememberPetPrefs()
    val s = state.settings ?: return
    val look = s.look(kind)
    val name = look.name(kind)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val cardColor = ai.antigem.theme.AppColors.card
    val accent = MaterialTheme.colorScheme.primary
    val controller = rememberPetPreviewController()
    var thinking by remember(kind) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
        // ----- the stage -----
        Box(
            Modifier
                .fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.03f)))),
            contentAlignment = Alignment.BottomCenter
        ) {
            PetPreview(kind, look, zoom = 2.2f, controller = controller, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(L.pets.settings.tryIt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = muted)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TryChip("👋", L.pets.settings.tryHi) { controller.wave() }
            TryChip("👆", L.pets.settings.tryTap) { controller.tap() }
            TryChip(kind.specialEmoji, kind.specialLabel(look)) { controller.special() }
            TryChip(kind.treatEmoji, L.pets.common.treat) { controller.treat() }
            TryChip("🤔", if (thinking) L.pets.settings.tryStopThinking else L.pets.settings.tryThink, selected = thinking) { thinking = controller.toggleThinking() }
            TryChip("🎉", L.common.done) { controller.celebrate() }
            TryChip("💃", L.components.floatingMascot.dance) { controller.dance() }
            TryChip("🌀", L.pets.settings.trySpin) { controller.spin() }
            TryChip("😴", L.components.floatingMascot.nap) { controller.nap() }
            TryChip("💫", L.pets.settings.tryDizzy) { controller.dizzy() }
            TryChip("😤", L.pets.settings.tryAngry) { controller.angry() }
        }

        Spacer(Modifier.height(16.dp))
        Text(kind.about, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(12.dp))
        if (s.kind == kind) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(L.pets.settings.isYourPet(name), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = accent)
            }
        } else {
            Button(onClick = { update { it.copy(kind = kind) } }, modifier = Modifier.fillMaxWidth()) { Text(L.pets.settings.useName(name)) }
        }

        // ----- name -----
        SectionTitle(L.common.name)
        var nick by remember(kind) { mutableStateOf(look.nickname) }
        LaunchedEffect(nick) {
            if (nick == look.nickname) return@LaunchedEffect
            delay(450)
            update { it.withLook(kind, it.look(kind).copy(nickname = nick.take(24))) }
        }
        OutlinedTextField(
            value = nick,
            onValueChange = { nick = it.take(24) },
            singleLine = true,
            placeholder = { Text(kind.displayName) },
            supportingText = { Text(L.pets.settings.nicknameHint(kind.displayName)) },
            modifier = Modifier.fillMaxWidth()
        )

        // ----- colors -----
        SectionTitle(L.pets.settings.colors)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kind.lookNames.forEachIndexed { i, label ->
                val sel = look.palette == i
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) accent.copy(alpha = 0.12f) else cardColor)
                        .border(if (sel) BorderStroke(1.5.dp, accent) else BorderStroke(1.dp, Color.Transparent), RoundedCornerShape(14.dp))
                        .clickable { update { it.withLook(kind, it.look(kind).copy(palette = i)) } }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PetPreview(kind, look.copy(palette = i), zoom = 0.62f, interactive = false, showBubble = false, haptics = false)
                    Text(label, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }

        // ----- its extra option -----
        val optionLabel = kind.optionLabel
        if (optionLabel != null) {
            SectionTitle(optionLabel)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kind.optionValues.forEachIndexed { i, v ->
                    FilterChip(
                        selected = look.option(kind) == i,
                        onClick = { update { it.withLook(kind, it.look(kind).copy(option = i)) } },
                        label = { Text(v) }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        TextButton(
            onClick = {
                nick = ""
                update { it.copy(looks = it.looks - kind) }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text(L.pets.settings.resetLook) }
        Spacer(Modifier.height(24.dp))
    }
}

// ---- small building blocks ----

@Composable
private fun SectionTitle(title: String, sub: String? = null) {
    Spacer(Modifier.height(20.dp))
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    if (sub != null) Text(sub, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.semantics { role = Role.Switch; toggleableState = ToggleableState(checked) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.mergedIntoRow())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, o -> FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(o, fontSize = 12.5.sp) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsBlock(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o -> FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(o, fontSize = 12.5.sp) }) }
    }
    Spacer(Modifier.height(8.dp))
}

/** A slider that shows its value while dragging and saves once, when let go. */
@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: (Float) -> String,
    onDone: (Float) -> Unit
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(label(draft), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onDone(draft) }, valueRange = range, steps = steps)
    }
}

@Composable
private fun TryChip(emoji: String, label: String, selected: Boolean = false, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$emoji  $label", fontSize = 12.5.sp) }
    )
}

private fun secondsLabel(s: Int): String = when {
    s <= 0 -> L.common.never
    s < 60 -> L.pets.settings.secondsShort(s)
    else -> L.pets.settings.minutesShort(s / 60)
}

private fun powerName(p: PetPower): String = when (p) {
    PetPower.SAVER -> L.pets.settings.saver
    PetPower.BALANCED -> L.pets.settings.balanced
    PetPower.LIVELY -> L.pets.settings.lively
    PetPower.ALWAYS -> L.pets.settings.always
}
