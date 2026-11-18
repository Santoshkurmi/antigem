package ai.antigem.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The byte scanner that rewrites com.termux in Termux files (binaries included): every match it reports must be one
 * the simple whole-array reference finds, across chunk boundaries, with the kept ".termuxam" suffix and the
 * paths-only mode used for the home folder.
 */
class TermuxRelocationTest {

    private val old = "com.termux".toByteArray()

    /** Reference: left to right, non-overlapping, same rules as TermuxRelocation, on the whole array at once. */
    private fun reference(bytes: ByteArray, pathsOnly: Boolean): List<Long> {
        val keptList = listOf(".termuxam", ".x11.").map { it.toByteArray() }
        val out = ArrayList<Long>()
        var i = 0
        while (i <= bytes.size - old.size) {
            if ((old.indices).all { bytes[i + it] == old[it] }) {
                val keptHere = keptList.any { kept ->
                    i + old.size + kept.size <= bytes.size && kept.indices.all { bytes[i + old.size + it] == kept[it] }
                }
                val inPath = !pathsOnly || (i > 0 && bytes[i - 1] == '/'.code.toByte())
                if (!keptHere && inPath) out.add(i.toLong())
                i += old.size
            } else i++
        }
        return out
    }

    private fun scan(bytes: ByteArray, pathsOnly: Boolean): List<Long> {
        val f = File.createTempFile("reloc", ".bin")
        try {
            f.writeBytes(bytes)
            return TermuxRelocation.findAll(f, old, pathsOnly)
        } finally {
            f.delete()
        }
    }

    @Test
    fun simpleCases() {
        val text = "#!/data/data/com.termux/files/usr/bin/sh\nexec app_process / com.termux.termuxam.Am\n" +
            "-n com.termux/com.termux.app.TermuxOpenReceiver\n" +
            "app_process / com.termux.x11.Loader; am broadcast -a com.termux.x11.CHANGE_PREFERENCE -p com.termux.x11\n"
        val bytes = text.toByteArray()
        val full = String(TermuxRelocation.rewrite(bytes, "ai.antigem"))
        assertEquals(
            "#!/data/data/ai.antigem/files/usr/bin/sh\nexec app_process / com.termux.termuxam.Am\n" +
                "-n ai.antigem/ai.antigem.app.TermuxOpenReceiver\n" +
                "app_process / com.termux.x11.Loader; am broadcast -a com.termux.x11.CHANGE_PREFERENCE -p ai.antigem.x11\n",
            full
        )
        val paths = String(TermuxRelocation.rewrite(bytes, "ai.antigem", pathsOnly = true))
        assertEquals(
            "#!/data/data/ai.antigem/files/usr/bin/sh\nexec app_process / com.termux.termuxam.Am\n" +
                "-n com.termux/ai.antigem.app.TermuxOpenReceiver\n" +
                "app_process / com.termux.x11.Loader; am broadcast -a com.termux.x11.CHANGE_PREFERENCE -p com.termux.x11\n",
            paths
        )
        assertEquals(reference(bytes, false), scan(bytes, false))
        assertEquals(reference(bytes, true), scan(bytes, true))
    }

    @Test
    fun matchesAcrossChunkBoundariesAgreeWithReference() {
        val rnd = Random(42)
        val pieces = listOf("com.termux", "/com.termux", "com.termux.termuxam", "/com.termux.termuxam", "com.termux.x11.", "com.termux.x11", "com.termu", "/")
            .map { it.toByteArray() }
        repeat(30) {
            val size = rnd.nextInt(1, 900_000)
            val bytes = ByteArray(size) { rnd.nextInt(97, 100).toByte() } // a..c: never part of a match by accident
            // plant pieces everywhere, densest near the 256 KB chunk edges
            repeat(400) {
                val p = pieces[rnd.nextInt(pieces.size)]
                val near = (rnd.nextInt(0, 4) * 256 * 1024 + rnd.nextInt(-30, 30)).coerceIn(0, size)
                val at = if (rnd.nextBoolean()) near else rnd.nextInt(0, size)
                if (at + p.size <= size) System.arraycopy(p, 0, bytes, at, p.size)
            }
            for (pathsOnly in listOf(false, true)) {
                assertEquals("size $size pathsOnly $pathsOnly", reference(bytes, pathsOnly), scan(bytes, pathsOnly))
            }
        }
    }

    /** The scripts written into the Linux system (apt hook, dpkg wrapper) must be valid shell; copies land in build/. */
    @Test
    fun generatedScriptsAreValidShell() {
        val prefix = "/data/data/ai.antigem/files/usr"
        val relocator = "$prefix/libexec/antigem/relocate-debs"
        val out = File("build/generated-test-scripts").apply { mkdirs() }
        val scripts = mapOf(
            "relocate-debs" to TermuxRelocation.relocateScript(prefix, "ai.antigem"),
            "dpkg" to TermuxRelocation.dpkgWrapper(prefix, relocator),
            "termux-api" to LocalEnvironmentManager.termuxApiDispatcherScript("ai.antigem", external = false, appInstalled = false),
            "termux-api-external" to LocalEnvironmentManager.termuxApiDispatcherScript("ai.antigem", external = true, appInstalled = true),
            "termux-api-external-missing" to LocalEnvironmentManager.termuxApiDispatcherScript("ai.antigem", external = true, appInstalled = false)
        )
        for ((name, text) in scripts) {
            val f = File(out, name).apply { writeText(text) }
            for (shell in listOf("sh", "bash")) {
                val p = ProcessBuilder(shell, "-n", f.absolutePath).redirectErrorStream(true).start()
                val msg = p.inputStream.bufferedReader().readText()
                assertEquals("$shell -n $name: $msg", 0, p.waitFor())
            }
        }
    }

    /** The dpkg wrapper, run in sh with a fake dpkg (records its arguments) and a fake relocator (records its input). */
    private class WrapperRun(val dir: File) {
        val prefix = File(dir, "usr").apply { File(this, "bin").mkdirs(); File(this, "tmp").mkdirs() }
        val log = File(dir, "log.txt")
        val wrapper = File(dir, "dpkg-wrapper")

        init {
            File(prefix, "bin/dpkg.distrib").apply {
                writeText("#!/bin/sh\necho \"ARGS: \$*\" >> \"\$LOG\"\nfor a in \"\$@\"; do [ -d \"\$a\" ] && ls \"\$a\" | sed 's/^/  in-dir: /' >> \"\$LOG\"; done\nexit 0\n")
                setExecutable(true)
            }
            val relocator = File(dir, "relocate").apply {
                writeText("#!/bin/sh\nwhile IFS= read -r p; do echo \"RELOCATE \${p##*/}\" >> \"\$LOG\"; done\n")
                setExecutable(true)
            }
            wrapper.writeText(TermuxRelocation.dpkgWrapper(prefix.absolutePath, relocator.absolutePath))
        }

        fun run(locked: Boolean, vararg args: String): String {
            log.delete()
            val pb = ProcessBuilder(listOf("sh", wrapper.absolutePath) + args).redirectErrorStream(true)
            pb.environment()["LOG"] = log.absolutePath
            pb.environment()["TMPDIR"] = File(prefix, "tmp").absolutePath
            if (locked) pb.environment()["DPKG_FRONTEND_LOCKED"] = "true" else pb.environment().remove("DPKG_FRONTEND_LOCKED")
            val p = pb.start()
            p.inputStream.readBytes()
            assertEquals(0, p.waitFor())
            return if (log.exists()) log.readText() else ""
        }
    }

    @Test
    fun dpkgWrapperLetsAptsOwnPackagesThroughAndAdaptsTheUsers() {
        val dir = java.nio.file.Files.createTempDirectory("wrapper").toFile()
        try {
            val w = WrapperRun(dir)
            val archives = File(dir, "cache/apt/archives").apply { mkdirs() }
            val debs = (1..6).map { File(archives, "pkg$it.deb").apply { writeText("x") } }
            // apt with many packages: a folder of symlinks to its (already adapted) .debs, dpkg --recursive
            val aptDir = File(dir, "usr/tmp/apt-dpkg-install-abc").apply { mkdirs() }
            debs.forEachIndexed { i, d -> java.nio.file.Files.createSymbolicLink(File(aptDir, "00$i-${d.name}").toPath(), d.toPath()) }
            var log = w.run(true, "--status-fd", "3", "--unpack", "--recursive", aptDir.absolutePath)
            assertEquals("ARGS: --status-fd 3 --unpack --recursive ${aptDir.absolutePath}", log.lines().first())
            assertEquals(6, log.lines().count { it.startsWith("  in-dir: ") })
            assertEquals(false, log.contains("RELOCATE"))
            // apt with few packages: their paths
            log = w.run(true, "--unpack", debs[0].absolutePath, debs[1].absolutePath)
            assertEquals("ARGS: --unpack ${debs[0].absolutePath} ${debs[1].absolutePath}\n", log)
            // apt install ./mine.deb: a rewritten copy of the user's file, the file itself untouched
            val mine = File(dir, "home/mine.deb").apply { parentFile.mkdirs(); writeText("y") }
            log = w.run(true, "--unpack", mine.absolutePath)
            assertEquals(true, log.contains("RELOCATE 1-mine.deb"))
            assertEquals(false, log.contains(mine.absolutePath))
            // dpkg -R -i by hand: a folder of the user's .debs, every one adapted
            val own = File(dir, "home/debs").apply { mkdirs() }
            File(own, "a.deb").writeText("a"); File(own, "b.deb").writeText("b")
            log = w.run(false, "-R", "-i", own.absolutePath)
            assertEquals(2, log.lines().count { it.startsWith("RELOCATE ") })
            assertEquals(false, log.contains("ARGS: -R -i ${own.absolutePath}"))
            assertEquals(2, log.lines().count { it.startsWith("  in-dir: ") })
            // not an install: as it is
            assertEquals("ARGS: -l\n", w.run(false, "-l"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rewriteChangesOnlyTheMatchedBytes() {
        val bytes = "x/com.termux y com.termux.termuxam z com.termux".toByteArray()
        val out = TermuxRelocation.rewrite(bytes, "ai.antigem")
        assertEquals(bytes.size, out.size)
        assertArrayEquals("x/ai.antigem y com.termux.termuxam z ai.antigem".toByteArray(), out)
    }
}
