package com.example.gemini.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== BUILD WITH GOOGLE PLUGINS ====================

@Serializable
class GetBuildWithGooglePluginsRequestDto

@Serializable
data class GetBuildWithGooglePluginsResponseDto(
    val plugins: List<BuildWithGooglePluginItemDto> = emptyList()
)

@Serializable
data class BuildWithGooglePluginItemDto(
    val plugin: BuildWithGooglePluginDto? = null,
    val gstatic: GstaticLinkDto? = null,
    val versionShas: Map<String, String> = emptyMap(),
    val visibility: String = "PUBLIC"
)

@Serializable
data class BuildWithGooglePluginDto(
    val name: String = "",
    val uid: String = "",
    val description: String = "",
    val trustLevel: String = "TRUSTED",
    val local: PluginLocalConfigDto? = null,
    val remote: PluginRemoteConfigDto? = null
)

@Serializable
data class GstaticLinkDto(
    val link: String = ""
)

@Serializable
data class PluginLocalConfigDto(
    val commands: Map<String, PluginCommandSpecDto> = emptyMap()
)

@Serializable
data class PluginCommandSpecDto(
    val commandTemplate: PluginCommandTemplateDto? = null,
    val variables: List<PluginConfigVariableDto> = emptyList()
)

@Serializable
data class PluginCommandTemplateDto(
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap()
)

@Serializable
data class PluginConfigVariableDto(
    val name: String = "",
    val title: String = "",
    val description: String = ""
)

@Serializable
data class PluginRemoteConfigDto(
    val remoteTemplate: PluginRemoteTemplateDto? = null
)

@Serializable
data class PluginRemoteTemplateDto(
    val serverUrl: String = ""
)

// ==================== DOWNLOAD PLUGIN ====================

@Serializable
data class DownloadBuildWithGooglePluginRequestDto(
    val pluginId: String = ""
)

@Serializable
data class DownloadBuildWithGooglePluginResponseDto(
    val success: Boolean = true,
    val message: String = ""
)

// ==================== DELETE PLUGIN ====================

@Serializable
data class DeletePluginRequestDto(
    val pluginId: String = ""
)

@Serializable
data class DeletePluginResponseDto(
    val success: Boolean = true,
    val message: String = ""
)

// ==================== GET ALL PLUGINS ====================

@Serializable
class GetAllPluginsRequestDto

@Serializable
data class GetAllPluginsResponseDto(
    val plugins: List<InstalledPluginDto> = emptyList()
)

@Serializable
data class InstalledPluginDto(
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val version: String = "",
    val logo: String = "",
    val path: String = "",
    val isGlobal: Boolean = false,
    val skills: List<InstalledSkillDto> = emptyList()
)

@Serializable
data class InstalledSkillDto(
    val name: String = "",
    val description: String = "",
    val path: String = "",
    val content: String = "",
    val baseDir: String = "",
    val scope: SkillScopeDto? = null
)

// ==================== GET ALL SKILLS ====================

@Serializable
data class GetAllSkillsRequestDto(
    val workspaceUris: List<String> = emptyList()
)

@Serializable
data class GetAllSkillsResponseDto(
    val skills: List<SkillDefinitionDto> = emptyList()
)

@Serializable
data class SkillDefinitionDto(
    val path: String = "",
    val name: String = "",
    val displayName: String? = null,
    val description: String = "",
    val content: String = "",
    val isBuiltin: Boolean = false,
    val pluginName: String? = null,
    val logo: String? = null,
    val baseDir: String = "",
    val discoveredIn: String = "",
    val discoveryCategory: String = "DISCOVERY_CATEGORY_GLOBAL",
    val scope: SkillScopeDto? = null
)

@Serializable
data class SkillScopeDto(
    val globalScope: GlobalScopeDto? = null,
    val workspaceScope: WorkspaceScopeDto? = null
)

@Serializable
class GlobalScopeDto

@Serializable
data class WorkspaceScopeDto(
    val workspaceUri: String = ""
)

// ==================== AVAILABLE CASCADE PLUGINS ====================

@Serializable
data class GetAvailableCascadePluginsRequestDto(
    val os: String = "linux",
    val searchQuery: String = ""
)

@Serializable
data class GetAvailableCascadePluginsResponseDto(
    val plugins: List<AvailableCascadePluginDto> = emptyList()
)

@Serializable
data class AvailableCascadePluginDto(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val link: String = "",
    val readme: String = "",
    val trustLevel: String = "TRUSTED",
    val local: CascadePluginLocalDto? = null,
    val remote: CascadePluginRemoteDto? = null
)

@Serializable
data class CascadePluginLocalDto(
    val commands: Map<String, CascadePluginCommandWrapperDto> = emptyMap()
)

@Serializable
data class CascadePluginCommandWrapperDto(
    val template: CascadePluginCommandTemplateDto? = null
)

@Serializable
data class CascadePluginCommandTemplateDto(
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap()
)

@Serializable
data class CascadePluginRemoteDto(
    val template: CascadePluginRemoteTemplateDto? = null
)

@Serializable
data class CascadePluginRemoteTemplateDto(
    val serverUrl: String = "",
    val authProviderType: String = ""
)

// ==================== FILE I/O ====================

@Serializable
data class WriteFileRequestDto(
    val uri: String = "",
    val content: String = "",
    val overwrite: Boolean = true
)

@Serializable
class WriteFileResponseDto
