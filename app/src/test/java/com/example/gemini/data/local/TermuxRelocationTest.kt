package com.example.gemini.data.local

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

    @Test
    fun rewriteChangesOnlyTheMatchedBytes() {
        val bytes = "x/com.termux y com.termux.termuxam z com.termux".toByteArray()
        val out = TermuxRelocation.rewrite(bytes, "ai.antigem")
        assertEquals(bytes.size, out.size)
        assertArrayEquals("x/ai.antigem y com.termux.termuxam z ai.antigem".toByteArray(), out)
    }
}
