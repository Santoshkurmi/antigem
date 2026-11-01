package com.example.gemini.data.remote

import com.example.gemini.ui.browser.BrowserExtension
import com.example.gemini.ui.browser.BrowserExtensionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PromptExpansionTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun clear() { CustomSlashCommands.commands.clear(); BrowserExtensionManager.instance.extensions.clear() }

    private fun plugin(id: String, tools: Int): File {
        val dir = temporary.newFolder(id)
        File(dir, "skills").mkdirs()
        File(dir, "skills/upload.md").writeText("# Upload")
        val skill = com.example.gemini.ui.browser.ExtensionSkill("upload", "Upload", "", File(dir, "skills/upload.md").absolutePath, null)
        BrowserExtensionManager.instance.extensions.add(BrowserExtension(id, id, "1", "", toolCount = tools, skillCount = 1, dirPath = dir.absolutePath, skills = listOf(skill)))
        return dir
    }

    @Test fun pluginWithToolsAsksForMcpOnlyIfTheTaskNeedsIt() {
        val dir = plugin("yt", tools = 2)
        val sent = PromptExpansion.expand("/plu:yt:upload my video")
        assertTrue(sent.contains(File(dir, "skills/upload.md").absolutePath))
        assertTrue(sent.contains("Check whether the task depends on them"))
        assertTrue(sent.contains("\"extension_yt\""))
        assertEquals("/plu:yt:upload my video", PromptExpansion.collapse(sent))
    }

    @Test fun mentionsOtherPluginsSkillsForDependencies() {
        plugin("yt", tools = 2)
        val notes = plugin("notes", tools = 0)
        val sent = PromptExpansion.expand("/plu:yt:upload")
        assertTrue(sent.contains("depends on another custom AntiGem plugin"))
        assertTrue(sent.contains("notes (${File(notes, "skills").absolutePath}/)"))
        assertFalse(PromptExpansion.expand("/plu:yt").substringAfter("Other installed plugins with skills:").contains("yt ("))
    }

    @Test fun skillOnlyPluginNeverAsksForMcp() {
        plugin("notes", tools = 0)
        val sent = PromptExpansion.expand("/plu:notes:upload")
        assertTrue(sent.contains("no tools of its own"))
        assertFalse(sent.contains("MCP settings"))
    }

    @Test fun customCommandExpandsAndCollapsesBack() {
        CustomSlashCommands.save(CustomSlashCommand("review", expansion = "Review the diff carefully."))
        val sent = PromptExpansion.expand("/review then summarize")
        assertTrue(sent.contains("Review the diff carefully."))
        assertEquals("/review then summarize", PromptExpansion.collapse(sent))
    }

    @Test fun argumentsAreConsumedAndShownWithTheCommand() {
        CustomSlashCommands.save(CustomSlashCommand("fix", expansion = "Fix this bug: \$ARGUMENTS"))
        val sent = PromptExpansion.expand("please /fix the \"login\" <crash>")
        assertTrue(sent.contains("Fix this bug: the \"login\" <crash>"))
        assertEquals("please /fix the \"login\" <crash>", PromptExpansion.collapse(sent))
    }

    @Test fun builtInPluginCommandTakesTheIdeaAsArguments() {
        CustomSlashCommands.restoreDefaults()
        val sent = PromptExpansion.expand("/custom_plugin automate YouTube uploads")
        assertTrue(sent.contains("What it should do: automate YouTube uploads"))
        assertTrue(sent.contains("webview_extension_"))
        assertEquals("/custom_plugin automate YouTube uploads", PromptExpansion.collapse(sent))
    }

    @Test fun unknownCommandsAndPathsAreLeftAlone() {
        CustomSlashCommands.save(CustomSlashCommand("usr", expansion = "x"))
        listOf("/compact", "see /usr/bin", "/plu:missing:skill do it", "a/usr").forEach { assertEquals(it, PromptExpansion.expand(it)) }
    }
}
