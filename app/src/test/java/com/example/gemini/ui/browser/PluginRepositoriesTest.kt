package com.example.gemini.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginRepositoriesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun parsesRepositoryInputs() {
        assertEquals(PluginRepo("me", "plugins"), PluginRepositories.parse("me/plugins"))
        assertEquals(PluginRepo("me", "plugins", "dev"), PluginRepositories.parse("me/plugins@dev"))
        assertEquals(PluginRepo("me", "plugins"), PluginRepositories.parse("https://github.com/me/plugins.git"))
        assertEquals(PluginRepo("me", "my.plugins", "feature/x"), PluginRepositories.parse("github.com/me/my.plugins/tree/feature/x/"))
        assertEquals("me/plugins@v2", PluginRepositories.parse(" me/plugins@v2 ").label)
        listOf("plugins", "me/plugins@bad ref", "me/plugins@../x", "https://gitlab.com/me/plugins").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { PluginRepositories.parse(input) }
        }
    }

    @Test fun comparesVersions() {
        assertEquals(1, PluginVersions.compare("1.10.0", "1.9"))
        assertEquals(0, PluginVersions.compare("v1.2", "1.2.0"))
        assertEquals(-1, PluginVersions.compare("1.2.0", "1.2.1"))
        assertNull(PluginVersions.compare("", "1.0"))
        assertNull(PluginVersions.compare("1.0-beta", "1.0"))
        assertEquals("no version", PluginVersions.label(""))
        assertEquals("v1.2", PluginVersions.label("v1.2"))
    }

    @Test fun resolvesIconFilesInsideThePackageOnly() {
        val dir = temporary.newFolder("p")
        File(dir, "icons").mkdirs()
        File(dir, "icons/128.png").writeText("x"); File(dir, "icon.svg").writeText("<svg/>"); File(dir, "notes.txt").writeText("x")
        assertEquals(File(dir, "icons/128.png").canonicalPath, PluginIcons.resolve(dir, "icons/128.png"))
        assertEquals(File(dir, "icon.svg").canonicalPath, PluginIcons.resolve(dir, "icon.svg"))
        listOf("../outside.png", "missing.png", "notes.txt", "", null).forEach { assertEquals("", PluginIcons.resolve(dir, it)) }
    }
}
