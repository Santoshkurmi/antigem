package com.example.gemini.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.gemini.data.remote.dto.BuildWithGooglePluginItemDto
import com.example.gemini.data.remote.dto.InstalledPluginDto
import com.example.gemini.data.remote.dto.SkillDefinitionDto
import com.example.gemini.theme.*
import com.example.gemini.ui.components.MarkdownContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsAndPluginsSubScreen(
    skills: List<SkillDefinitionDto>,
    isSkillsLoading: Boolean,
    skillsFilterScope: String,
    onSetSkillsFilterScope: (String) -> Unit,
    onRefreshSkills: () -> Unit,
    installedPlugins: List<InstalledPluginDto>,
    isInstalledPluginsLoading: Boolean,
    onRefreshInstalledPlugins: () -> Unit,
    googlePlugins: List<BuildWithGooglePluginItemDto>,
    isGooglePluginsLoading: Boolean,
    installingGooglePluginId: String?,
    deletingPluginId: String? = null,
    onRefreshGooglePlugins: () -> Unit,
    onInstallGooglePlugin: (String, String) -> Unit,
    onDeletePlugin: (String, String) -> Unit = { _, _ -> },
    statusMessage: String?,
    errorMessage: String?,
    onClearStatus: () -> Unit,
    cardBg: Color,
    cardBorder: BorderStroke
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Skills, 1: Installed Plugins, 2: Google Marketplace
    var selectedSkillForDetail by remember { mutableStateOf<SkillDefinitionDto?>(null) }

    LaunchedEffect(Unit) {
        onRefreshSkills()
        onRefreshInstalledPlugins()
        onRefreshGooglePlugins()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status/Error Banners
            if (!errorMessage.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Text(text = errorMessage, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClearStatus, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = QuotaGreen.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, QuotaGreen.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = QuotaGreen, modifier = Modifier.size(18.dp))
                        Text(text = statusMessage, fontSize = 12.5.sp, color = QuotaGreen, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClearStatus, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Top Navigation Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)) }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Skills (${skills.size})", fontSize = 13.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Plugins (${installedPlugins.size})", fontSize = 13.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.Storefront, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Store", fontSize = 13.sp, fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                )
            }

            // Tab Content
            when (selectedTab) {
                0 -> SkillsTabView(
                    skills = skills,
                    isLoading = isSkillsLoading,
                    filterScope = skillsFilterScope,
                    onSetScope = onSetSkillsFilterScope,
                    onRefresh = onRefreshSkills,
                    onSkillClick = { selectedSkillForDetail = it }
                )
                1 -> InstalledPluginsTabView(
                    plugins = installedPlugins,
                    isLoading = isInstalledPluginsLoading,
                    deletingPluginId = deletingPluginId,
                    onRefresh = onRefreshInstalledPlugins,
                    onDeletePlugin = onDeletePlugin,
                    onSkillClick = { skillName ->
                        val matching = skills.find { it.name.equals(skillName, ignoreCase = true) }
                        if (matching != null) {
                            selectedSkillForDetail = matching
                        }
                    }
                )
                2 -> GooglePluginsStoreTabView(
                    catalog = googlePlugins,
                    isLoading = isGooglePluginsLoading,
                    installedPlugins = installedPlugins,
                    installingPluginId = installingGooglePluginId,
                    onRefresh = onRefreshGooglePlugins,
                    onInstall = onInstallGooglePlugin
                )
            }
        }
    }

    // Detail Dialog for Skill Markdown
    selectedSkillForDetail?.let { skill ->
        SkillDetailMarkdownDialog(
            skill = skill,
            onDismiss = { selectedSkillForDetail = null }
        )
    }
}

// ==================== TAB 1: SKILLS LIST & SCOPE ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillsTabView(
    skills: List<SkillDefinitionDto>,
    isLoading: Boolean,
    filterScope: String,
    onSetScope: (String) -> Unit,
    onRefresh: () -> Unit,
    onSkillClick: (SkillDefinitionDto) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Scope Filter Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filterScope == "GLOBAL",
                    onClick = { onSetScope("GLOBAL") },
                    label = { Text("Global (Default)", fontSize = 11.5.sp) },
                    leadingIcon = if (filterScope == "GLOBAL") {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    } else null,
                    shape = RoundedCornerShape(8.dp)
                )
                FilterChip(
                    selected = filterScope == "WORKSPACE",
                    onClick = { onSetScope("WORKSPACE") },
                    label = { Text("Project / Workspace", fontSize = 11.5.sp) },
                    leadingIcon = if (filterScope == "WORKSPACE") {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    } else null,
                    shape = RoundedCornerShape(8.dp)
                )
                FilterChip(
                    selected = filterScope == "ALL",
                    onClick = { onSetScope("ALL") },
                    label = { Text("All", fontSize = 11.5.sp) },
                    shape = RoundedCornerShape(8.dp)
                )
            }

            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = GeminiBlue)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                }
            }
        }

        if (isLoading && skills.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GeminiBlue)
            }
        } else if (skills.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (filterScope == "WORKSPACE") "No workspace-scoped skills (.agents/skills/) found for active project." else "No skills discovered.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(skills, key = { it.path.ifBlank { it.name } }) { skill ->
                    SkillItemCard(skill = skill, onClick = { onSkillClick(skill) })
                }
            }
        }
    }
}

@Composable
private fun SkillItemCard(
    skill: SkillDefinitionDto,
    onClick: () -> Unit
) {
    val categoryColor = when (skill.discoveryCategory) {
        "DISCOVERY_CATEGORY_BUILTIN" -> ClaudeTerracotta
        "DISCOVERY_CATEGORY_INSTALLED" -> GeminiBlue
        "DISCOVERY_CATEGORY_WORKSPACE" -> QuotaGreen
        else -> Color(0xFF9C27B0)
    }

    val categoryBadgeText = when (skill.discoveryCategory) {
        "DISCOVERY_CATEGORY_BUILTIN" -> "Built-in"
        "DISCOVERY_CATEGORY_INSTALLED" -> "Installed Plugin"
        "DISCOVERY_CATEGORY_WORKSPACE" -> "Workspace"
        else -> "Global"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (!skill.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = skill.logo,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = categoryColor, modifier = Modifier.size(18.dp))
                    }

                    Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "/${skill.name}",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (!skill.displayName.isNullOrBlank() && skill.displayName != skill.name) {
                                Text(
                                    text = "(${skill.displayName})",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (!skill.pluginName.isNullOrBlank()) {
                            Text(
                                text = "from ${skill.pluginName}",
                                fontSize = 11.sp,
                                color = GeminiBlue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = categoryColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, categoryColor.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = categoryBadgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = categoryColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = skill.description.ifBlank { "Agent capability instructions and workflows" },
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = skill.path.ifBlank { skill.discoveredIn },
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("View SKILL.md", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ==================== TAB 2: INSTALLED PLUGINS ====================

@Composable
private fun InstalledPluginsTabView(
    plugins: List<InstalledPluginDto>,
    isLoading: Boolean,
    deletingPluginId: String?,
    onRefresh: () -> Unit,
    onDeletePlugin: (String, String) -> Unit,
    onSkillClick: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Installed Bundles & Extensions",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = GeminiBlue)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                }
            }
        }

        if (isLoading && plugins.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GeminiBlue)
            }
        } else if (plugins.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No plugin bundles installed in ~/.gemini/config/plugins/.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(plugins, key = { it.name }) { plugin ->
                    InstalledPluginCard(
                        plugin = plugin,
                        isDeleting = deletingPluginId == plugin.name,
                        onDelete = { onDeletePlugin(plugin.name, plugin.displayName.ifBlank { plugin.name }) },
                        onSkillClick = onSkillClick
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledPluginCard(
    plugin: InstalledPluginDto,
    isDeleting: Boolean,
    onDelete: () -> Unit,
    onSkillClick: (String) -> Unit
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Plugin?") },
            text = {
                Text("Are you sure you want to delete '${plugin.displayName.ifBlank { plugin.name }}' (${plugin.name})? This will remove its bundled skills from ~/.gemini/config/plugins/.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (plugin.logo.isNotBlank()) {
                    AsyncImage(
                        model = plugin.logo,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(GeminiBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Extension, contentDescription = null, tint = GeminiBlue, modifier = Modifier.size(20.dp))
                    }
                }

                Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = plugin.displayName.ifBlank { plugin.name },
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (plugin.version.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            ) {
                                Text(
                                    text = "v${plugin.version}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = plugin.name,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (plugin.isGlobal) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = GeminiBlue.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "Global",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = GeminiBlue,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                IconButton(
                    onClick = { showDeleteConfirmDialog = true },
                    enabled = !isDeleting,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Delete Plugin",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Text(
                text = plugin.description,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )

            // Exposed Skills
            if (plugin.skills.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Bundled Skills (${plugin.skills.size}):",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(plugin.skills) { s ->
                            AssistChip(
                                onClick = { onSkillClick(s.name) },
                                label = { Text("/${s.name}", fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(12.dp)) },
                                shape = RoundedCornerShape(6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== TAB 3: GOOGLE PLUGINS STORE ====================

@Composable
private fun GooglePluginsStoreTabView(
    catalog: List<BuildWithGooglePluginItemDto>,
    isLoading: Boolean,
    installedPlugins: List<InstalledPluginDto>,
    installingPluginId: String?,
    onRefresh: () -> Unit,
    onInstall: (String, String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Official 'Build with Google' Plugins",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = GeminiBlue)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                }
            }
        }

        if (isLoading && catalog.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GeminiBlue)
            }
        } else if (catalog.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No official Google plugins catalog available.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(catalog, key = { it.plugin?.uid ?: "" }) { item ->
                    val p = item.plugin ?: return@items
                    val isInstalled = installedPlugins.any { it.name.equals(p.uid, ignoreCase = true) }
                    val isInstalling = installingPluginId == p.uid

                    GoogleCatalogPluginCard(
                        item = item,
                        isInstalled = isInstalled,
                        isInstalling = isInstalling,
                        onInstall = { onInstall(p.uid, p.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GoogleCatalogPluginCard(
    item: BuildWithGooglePluginItemDto,
    isInstalled: Boolean,
    isInstalling: Boolean,
    onInstall: () -> Unit
) {
    val p = item.plugin ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = p.name,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (p.trustLevel.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = GeminiBlue.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = p.trustLevel,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GeminiBlue,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = p.uid,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isInstalled) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = QuotaGreen.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, QuotaGreen.copy(alpha = 0.4f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = QuotaGreen, modifier = Modifier.size(14.dp))
                            Text("Installed", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = QuotaGreen)
                        }
                    }
                } else {
                    Button(
                        onClick = onInstall,
                        enabled = !isInstalling,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        if (isInstalling) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Install", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Text(
                text = p.description,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )

            // Available versions or tool bindings
            if (item.versionShas.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Versions:",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text = item.versionShas.keys.take(3).joinToString(", ") + if (item.versionShas.size > 3) "..." else "",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

// ==================== SKILL DETAIL MARKDOWN DIALOG ====================

@Composable
fun SkillDetailMarkdownDialog(
    skill: SkillDefinitionDto,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "/${skill.name}",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!skill.displayName.isNullOrBlank()) {
                                Text(
                                    text = "• ${skill.displayName}",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Text(
                            text = skill.path.ifBlank { skill.discoveredIn },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                // Markdown Renderer
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (skill.content.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            MarkdownContent(content = skill.content)
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No markdown content found in SKILL.md", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}
