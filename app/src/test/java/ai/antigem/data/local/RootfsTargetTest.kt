package ai.antigem.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Where rootfs/backup archive entries are extracted: a "files/" inside a package's own path must stay in the path. */
class RootfsTargetTest {

    private val usr = File("/f/usr")
    private val home = File("/f/home")
    private fun t(path: String) = LocalEnvironmentManager.rootfsTarget(path, usr, home)?.path

    @Test
    fun backupLayout() {
        assertEquals("/f/usr/bin/bash", t("usr/bin/bash"))
        assertEquals("/f/home/.bashrc", t("home/.bashrc"))
        assertEquals("/f/home/projects/app", t("./home/projects/app/"))
        assertNull(t("usr/"))
        assertNull(t("home"))
    }

    @Test
    fun filesFolderInsideAPackageStaysInThePath() {
        assertEquals(
            "/f/usr/libexec/installed-tests/termux-exec/exec/files/print-args-binary",
            t("usr/libexec/installed-tests/termux-exec/exec/files/print-args-binary")
        )
        assertEquals("/f/home/projects/x/files/home/notes.txt", t("home/projects/x/files/home/notes.txt"))
    }

    @Test
    fun absoluteAndPrefixedLayouts() {
        assertEquals("/f/usr/bin/sh", t("data/data/com.termux/files/usr/bin/sh"))
        assertEquals("/f/home/.profile", t("/data/user/0/com.termux/files/home/.profile"))
        assertEquals("/f/usr/lib/libc++.so", t("backup/files/usr/lib/libc++.so"))
    }

    @Test
    fun bootstrapLayoutIsRelativeToUsr() {
        assertEquals("/f/usr/bin/apt", t("bin/apt"))
        assertEquals("/f/usr/lib/apt/methods/http", t("./lib/apt/methods/http"))
    }
}
