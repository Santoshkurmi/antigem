package com.example.gemini.data.remote.services

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient.GlobalPermissionGrants
import com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings
import com.example.gemini.data.remote.AgyHubClient.ProjectItem
import exa.language_server_pb.AgentPermissionPreset
import exa.language_server_pb.AgentSettingPolicy
import exa.language_server_pb.ArtifactReviewMode
import exa.language_server_pb.CascadeCommandsAutoExecution
import exa.language_server_pb.DeleteMediaArtifactRequest
import exa.language_server_pb.GetAllSkillsRequest
import exa.language_server_pb.JetboxSubscribeToStateRequest
import exa.language_server_pb.JetboxWriteStateRequest
import exa.language_server_pb.Jetbox_state_pb_UserSettings
import exa.language_server_pb.Media
import exa.language_server_pb.PermissionGrants
import exa.language_server_pb.PermissionGrantsConfig
import exa.language_server_pb.Project
import exa.language_server_pb.ProjectSettings
import exa.language_server_pb.ProjectUpdatesStreamRequest
import exa.language_server_pb.ReadFileRequest
import exa.language_server_pb.ReadProjectsRequest
import exa.language_server_pb.Resource
import exa.language_server_pb.Resources
import exa.language_server_pb.SaveMediaAsArtifactRequest
import exa.language_server_pb.UpdateProjectRequest
import exa.language_server_pb.UserConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.decodeBase64

/**
 * Dedicated RPC service for workspace projects, global user settings, file reading, and media artifacts.
 * Uses typed Square Wire AgyLanguageService gRPC client.
 */
class AgyProjectService {
    data class SkillItem(
        val name: String,
        val description: String,
        val path: String = "",
        val pluginName: String? = null,
        val content: String = ""
    )

    companion object {
        private const val TAG = "AgyProjectService"
        val instance by lazy { AgyProjectService() }
    }

    /**
     * Fetches all registered skills directly from AGY Hub via GetAllSkills RPC.
     */
    suspend fun fetchAllSkills(hubUrl: String = AuthPreferences.currentHubUrl): Result<List<SkillItem>> = withContext(Dispatchers.IO) {
        val req = GetAllSkillsRequest()
        AgyLanguageService.GetAllSkills().executeSafely(req).map { res ->
            res.skills.mapNotNull { spec ->
                if (spec.name.isNotBlank()) {
                    SkillItem(
                        name = spec.name,
                        description = spec.description,
                        path = spec.path,
                        pluginName = spec.plugin_name.takeIf { it.isNotBlank() },
                        content = spec.content
                    )
                } else null
            }
        }
    }

    /**
     * Reads a file via LanguageServerService/ReadFile RPC.
     * Returns base64 encoded content string.
     */
    suspend fun readFileAsBase64(
        uri: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        val formattedUri = if (uri.startsWith("file://") || uri.startsWith("http://") || uri.startsWith("https://")) {
            uri
        } else {
            "file://$uri"
        }
        val req = ReadFileRequest(uri = formattedUri)
        AgyLanguageService.ReadFile().executeSafely(req).map { res ->
            res.content.base64()
        }
    }

    /**
     * Saves a media file onto the daemon host as an artifact and returns its persistent uri.
     */
    suspend fun saveMediaAsArtifact(
        mimeType: String,
        base64Data: String,
        description: String,
        thumbnailBase64: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        val rawBytes = base64Data.decodeBase64() ?: ByteString.EMPTY
        val thumbBytes = if (thumbnailBase64.isNotBlank()) thumbnailBase64.decodeBase64() ?: ByteString.EMPTY else ByteString.EMPTY
        val req = SaveMediaAsArtifactRequest(
            media = Media(
                mime_type = mimeType,
                inline_data = rawBytes,
                description = description,
                thumbnail = thumbBytes
            )
        )
        AgyLanguageService.SaveMediaAsArtifact().executeSafely(req).map { res ->
            res.uri
        }
    }

    /**
     * Deletes a media artifact file on the host.
     */
    suspend fun deleteMediaArtifact(
        uri: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val req = DeleteMediaArtifactRequest(uri = uri)
        AgyLanguageService.DeleteMediaArtifact().executeSafely(req).map { }
    }

    /**
     * Fetches live daemon user settings snapshot by subscribing to JetboxSubscribeToState
     * and reading the very first frame pushed by the daemon.
     */
    suspend fun fetchGlobalUserSettings(hubUrl: String = AuthPreferences.currentHubUrl): Result<GlobalUserSettings> = withContext(Dispatchers.IO) {
        try {
            val update = withTimeoutOrNull(5000L) {
                AgyLanguageService.JetboxSubscribeToState()
                    .asFlowSafely(JetboxSubscribeToStateRequest())
                    .firstOrNull()
            } ?: return@withContext Result.failure(Exception("JetboxSubscribeToState timeout"))

            val userSettings = update.user_config?.user_settings ?: update.state?.user_settings

            val autoExec = userSettings?.auto_execution_policy?.name ?: "CASCADE_COMMANDS_AUTO_EXECUTION_OFF"
            val fileAccess = if (userSettings?.allow_agent_access_non_workspace_files == true) {
                "AGENT_SETTING_POLICY_ALLOW"
            } else {
                "AGENT_SETTING_POLICY_ASK"
            }
            val artifactReview = userSettings?.artifact_review_mode?.name ?: "ARTIFACT_REVIEW_MODE_ALWAYS"
            val sandbox = userSettings?.enable_terminal_sandbox ?: false

            val grants = userSettings?.global_permission_grants
            val allowList = grants?.allow ?: emptyList()
            val denyList = grants?.deny ?: emptyList()
            val askList = grants?.ask ?: emptyList()

            Result.success(
                GlobalUserSettings(
                    autoExecutionPolicy = autoExec,
                    nonWorkspaceFileAccessPolicy = fileAccess,
                    artifactReviewMode = artifactReview,
                    enableTerminalSandbox = sandbox,
                    globalPermissionGrants = GlobalPermissionGrants(
                        allow = allowList,
                        deny = denyList,
                        ask = askList
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchGlobalUserSettings failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Updates daemon user settings via JetboxWriteState
     */
    suspend fun writeGlobalUserSettings(
        autoExecutionPolicy: String? = null,
        nonWorkspaceFileAccessPolicy: String? = null,
        artifactReviewMode: String? = null,
        enableTerminalSandbox: Boolean? = null,
        globalPermissionGrants: GlobalPermissionGrants? = null,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val autoExecEnum = autoExecutionPolicy?.let { safeValueOf<CascadeCommandsAutoExecution>(it) }
        val artifactReviewEnum = artifactReviewMode?.let { safeValueOf<ArtifactReviewMode>(it) }
        val allowNonWorkspace = nonWorkspaceFileAccessPolicy?.let { it == "AGENT_SETTING_POLICY_ALLOW" }

        val grantsConfig = globalPermissionGrants?.let {
            PermissionGrantsConfig(
                allow = it.allow,
                deny = it.deny,
                ask = it.ask
            )
        }

        val userSettings = Jetbox_state_pb_UserSettings(
            auto_execution_policy = autoExecEnum ?: CascadeCommandsAutoExecution.CASCADE_COMMANDS_AUTO_EXECUTION_UNSPECIFIED,
            artifact_review_mode = artifactReviewEnum ?: ArtifactReviewMode.ARTIFACT_REVIEW_MODE_UNSPECIFIED,
            allow_agent_access_non_workspace_files = allowNonWorkspace ?: false,
            enable_terminal_sandbox = enableTerminalSandbox ?: false,
            global_permission_grants = grantsConfig
        )

        val req = JetboxWriteStateRequest(
            user_config = UserConfig(user_settings = userSettings)
        )
        AgyLanguageService.JetboxWriteState().executeSafely(req).map { }
    }

    /**
     * Discovers all projects registered in the daemon via ProjectUpdatesStream and ReadProjects.
     */
    suspend fun fetchAllProjects(hubUrl: String = AuthPreferences.currentHubUrl): Result<List<ProjectItem>> = withContext(Dispatchers.IO) {
        try {
            val update = withTimeoutOrNull(5000L) {
                AgyLanguageService.ProjectUpdatesStream()
                    .asFlowSafely(ProjectUpdatesStreamRequest())
                    .firstOrNull()
            }

            val projectIds = update?.project_list?.project_ids?.ifEmpty {
                listOf("default-cli-project", "outside-of-project")
            } ?: listOf("default-cli-project", "outside-of-project")

            val req = ReadProjectsRequest(ids = projectIds)
            val readRes = AgyLanguageService.ReadProjects().executeSafely(req)
            if (readRes.isFailure) {
                return@withContext Result.failure(readRes.exceptionOrNull() ?: Exception("ReadProjects failed"))
            }

            val projects = readRes.getOrThrow().projects
            val items = projects.map { p ->
                val pid = p.id
                val name = p.name.ifBlank { pid }
                val settings = p.settings
                val autoExec = settings?.auto_execution_policy?.name?.takeIf {
                    it.isNotBlank() && it != "CASCADE_COMMANDS_AUTO_EXECUTION_UNSPECIFIED"
                }
                val fileAccess = settings?.file_access_policy?.name?.takeIf {
                    it.isNotBlank() && it != "AGENT_SETTING_POLICY_UNSPECIFIED"
                }
                val artifactReview = settings?.artifact_review_mode?.name?.takeIf {
                    it.isNotBlank() && it != "ARTIFACT_REVIEW_MODE_UNSPECIFIED"
                }
                val sandbox = settings?.sandbox_mode

                val isInheriting = settings == null || (
                    autoExec == null && fileAccess == null && artifactReview == null && sandbox == null
                )

                ProjectItem(
                    id = pid,
                    name = name,
                    autoExecutionPolicy = autoExec,
                    fileAccessPolicy = fileAccess,
                    artifactReviewMode = artifactReview,
                    sandboxMode = sandbox,
                    isInheritingGlobal = isInheriting
                )
            }

            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "fetchAllProjects error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Updates a specific project's settings via UpdateProject RPC.
     */
    suspend fun updateProjectSettings(
        projectId: String,
        projectName: String = "",
        folderUris: List<String> = emptyList(),
        autoExecutionPolicy: String? = null,
        fileAccessPolicy: String? = null,
        artifactReviewMode: String? = null,
        sandboxMode: Boolean? = null,
        inheritGlobal: Boolean = false,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val autoExecEnum = autoExecutionPolicy?.let { safeValueOf<CascadeCommandsAutoExecution>(it) }
        val fileAccessEnum = fileAccessPolicy?.let { safeValueOf<AgentSettingPolicy>(it) }
        val artifactReviewEnum = artifactReviewMode?.let { safeValueOf<ArtifactReviewMode>(it) }

        val resList = folderUris.map { f ->
            val norm = if (f.startsWith("file://")) f else "file://$f"
            Resource(folder_uri = norm)
        }

        val projectSettings = if (inheritGlobal) {
            null
        } else {
            ProjectSettings(
                auto_execution_policy = autoExecEnum ?: CascadeCommandsAutoExecution.CASCADE_COMMANDS_AUTO_EXECUTION_UNSPECIFIED,
                file_access_policy = fileAccessEnum ?: AgentSettingPolicy.AGENT_SETTING_POLICY_UNSPECIFIED,
                artifact_review_mode = artifactReviewEnum ?: ArtifactReviewMode.ARTIFACT_REVIEW_MODE_UNSPECIFIED,
                sandbox_mode = sandboxMode ?: false
            )
        }

        val project = Project(
            id = projectId,
            name = projectName,
            project_resources = if (resList.isNotEmpty()) Resources(resources = resList) else null,
            permission_grants = PermissionGrants(
                permission_grants = PermissionGrantsConfig(allow = listOf("read_url(example.com)"))
            ),
            settings = projectSettings
        )

        val req = UpdateProjectRequest(project = project)
        AgyLanguageService.UpdateProject().executeSafely(req).map { }
    }

    private inline fun <reified T : Enum<T>> safeValueOf(name: String): T? =
        try { enumValueOf<T>(name) } catch (_: Exception) { null }
}
