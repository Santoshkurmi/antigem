package com.example.gemini.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtensionSkillsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun onlyTopLevelMarkdownFilesAreSkills() {
        val plugin = temporary.newFolder("yt")
        File(plugin, "skills/youtube_upload").mkdirs()
        File(plugin, "skills/youtube_upload.md").writeText("# Upload a video\n\nUploads a **video** to [YouTube](https://youtube.com).\n\nMore")
        File(plugin, "skills/search.md").writeText("---\nname: Search\ndescription: \"Find videos\"\n---\nBody")
        File(plugin, "skills/youtube_upload/thumbnails.md").writeText("x")
        File(plugin, "skills/notes.txt").writeText("x")
        val skills = ExtensionSkills.list(plugin)
        assertEquals(listOf("search", "youtube_upload"), skills.map { it.name })
        assertEquals("Search" to "Find videos", skills[0].title to skills[0].description)
        assertEquals("Upload a video" to "Uploads a video to YouTube.", skills[1].title to skills[1].description)
        assertEquals(File(plugin, "skills/youtube_upload").absolutePath, skills[1].detailDir)
        assertEquals(null, skills[0].detailDir)
    }

    @Test fun noHeadingUsesFileNameAndFirstParagraph() {
        assertEquals("plain" to "First line second line", ExtensionSkills.describe("\n- list\n\nFirst line\nsecond line\n\nNext", "plain"))
    }
}
