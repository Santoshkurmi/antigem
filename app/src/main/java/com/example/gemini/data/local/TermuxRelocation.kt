package com.example.gemini.data.local

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Runs Termux packages in this app without proot and without rebuilding them. Termux packages are built for
 * /data/data/com.termux; [RELOCATED_PACKAGE] has a package name of the same length (10 bytes), so every
 * "com.termux" in their files can be rewritten byte for byte: nothing inside a binary moves.
 *
 * - The bootstrap or rootfs: [relocateTree] after it is extracted.
 * - Packages installed later: an apt `DPkg::Pre-Install-Pkgs` hook ([ensureAptHook]) rewrites each .deb (paths,
 *   file contents, symlinks, maintainer scripts) before dpkg unpacks it.
 */
object TermuxRelocation {

    private const val TAG = "TermuxRelocation"
    const val TERMUX_PACKAGE = "com.termux"
    const val RELOCATED_PACKAGE = "ai.antigem"

    /** Zip-based files keep CRCs and dex files a checksum: rewriting bytes in them would corrupt them, so they are skipped. */
    private val SKIPPED_EXTENSIONS = setOf("apk", "jar", "zip", "aar", "whl", "dex", "jmod")
    private const val CHUNK = 256 * 1024

    data class Stats(var files: Int = 0, var patchedFiles: Int = 0, var links: Int = 0)

    /** The package name Termux files are rewritten for, or null when none is needed (com.termux itself). */
    fun targetPackage(context: Context): String? =
        context.packageName.takeIf { it == RELOCATED_PACKAGE && it.length == TERMUX_PACKAGE.length }

    /** Rewrites [root] (recursively, without following symlinks) when this app needs it; null otherwise. */
    fun relocateIfNeeded(context: Context, root: File, onProgress: ((Int) -> Unit)? = null): Stats? {
        val pkg = targetPackage(context) ?: return null
        return relocateTree(root, pkg, onProgress)
    }

    fun relocateTree(root: File, newPackage: String, onProgress: ((Int) -> Unit)? = null): Stats {
        val old = TERMUX_PACKAGE.toByteArray(Charsets.US_ASCII)
        val new = newPackage.toByteArray(Charsets.US_ASCII)
        require(old.size == new.size) { "$newPackage must have the length of $TERMUX_PACKAGE" }
        val stats = Stats()
        if (!root.exists()) return stats
        val dirs = ArrayDeque<File>().apply { add(root) }
        while (dirs.isNotEmpty()) {
            val children = dirs.removeLast().listFiles() ?: continue
            for (f in children) {
                val mode = try { Os.lstat(f.absolutePath).st_mode } catch (_: Exception) { continue }
                when {
                    OsConstants.S_ISLNK(mode) -> if (relink(f, newPackage)) stats.links++
                    OsConstants.S_ISDIR(mode) -> dirs.add(f)
                    OsConstants.S_ISREG(mode) -> {
                        stats.files++
                        if (patchFile(f, mode, old, new)) stats.patchedFiles++
                        if (stats.files % 500 == 0) onProgress?.invoke(stats.files)
                    }
                }
            }
        }
        return stats
    }

    private fun relink(link: File, newPackage: String): Boolean {
        val target = try { Os.readlink(link.absolutePath) } catch (_: Exception) { return false }
        if (!target.contains(TERMUX_PACKAGE)) return false
        return try {
            link.delete()
            Os.symlink(target.replace(TERMUX_PACKAGE, newPackage), link.absolutePath)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Relinking ${link.absolutePath} -> $target failed: ${e.message}")
            false
        }
    }

    private fun patchFile(file: File, mode: Int, old: ByteArray, new: ByteArray): Boolean {
        if (file.extension.lowercase() in SKIPPED_EXTENSIONS) return false
        return try {
            val offsets = findAll(file, old)
            if (offsets.isEmpty()) return false
            val perms = mode and 0xFFF
            val ownerWritable = (perms and 0x80) != 0 // S_IWUSR
            if (!ownerWritable) Os.chmod(file.absolutePath, perms or 0x80)
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    for (offset in offsets) {
                        raf.seek(offset)
                        raf.write(new)
                    }
                }
            } finally {
                if (!ownerWritable) try { Os.chmod(file.absolutePath, perms) } catch (_: Exception) {}
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Rewriting ${file.absolutePath} failed: ${e.message}")
            false
        }
    }

    /** Offsets of every [pattern] in [file], read in chunks; "com.termux" cannot overlap itself, so matches never do. */
    private fun findAll(file: File, pattern: ByteArray): List<Long> {
        val result = ArrayList<Long>()
        val n = pattern.size
        val first = pattern[0]
        val buf = ByteArray(CHUNK + n - 1)
        RandomAccessFile(file, "r").use { raf ->
            var base = 0L // file offset of buf[0]
            var carry = 0 // bytes kept at the start of buf from the previous chunk
            while (true) {
                val read = raf.read(buf, carry, buf.size - carry)
                if (read <= 0) break
                val len = carry + read
                val last = len - n
                var i = 0
                while (i <= last) {
                    if (buf[i] == first) {
                        var k = 1
                        while (k < n && buf[i + k] == pattern[k]) k++
                        if (k == n) {
                            result.add(base + i)
                            i += n
                            continue
                        }
                    }
                    i++
                }
                // a match can still start in the last n-1 bytes: keep them for the next chunk
                val keepFrom = (last + 1).coerceAtLeast(0)
                carry = len - keepFrom
                System.arraycopy(buf, keepFrom, buf, 0, carry)
                base += keepFrom
            }
        }
        return result
    }

    /**
     * Installs the apt hook that rewrites every package apt is about to install (`pkg install`, `apt upgrade`, ...).
     * Called after each install and terminal start; only writes what changed.
     */
    fun ensureAptHook(context: Context) {
        val pkg = targetPackage(context) ?: return
        try {
            val prefixDir = LocalEnvironmentManager.getPrefixDir(context)
            val aptConfDir = File(prefixDir, "etc/apt/apt.conf.d")
            if (!File(prefixDir, "etc/apt").isDirectory) return
            val prefix = "/data/data/$pkg/files/usr"
            val hookPath = "$prefix/libexec/antigem/relocate-debs"
            writeIfChanged(File(prefixDir, "libexec/antigem/relocate-debs"), hookScript(prefix, pkg), executable = true)
            writeIfChanged(
                File(aptConfDir, "99antigem-relocate"),
                """
                |// Written by AntiGem (edits are overwritten). Termux packages are built for $TERMUX_PACKAGE: this hook
                |// rewrites each .deb for $pkg before dpkg installs it.
                |DPkg::Pre-Install-Pkgs { "$hookPath"; };
                |""".trimMargin(),
                executable = false
            )
        } catch (e: Exception) {
            Log.w(TAG, "apt relocation hook setup failed: ${e.message}")
        }
    }

    private fun writeIfChanged(file: File, content: String, executable: Boolean) {
        if (file.isFile && file.readText() == content) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(content)
        try { Os.chmod(tmp.absolutePath, if (executable) 0x1ED else 0x1A4) } catch (_: Exception) {} // 0755 / 0644
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun hookScript(prefix: String, pkg: String): String {
        val skipped = SKIPPED_EXTENSIONS.joinToString("|") { "*.$it" }
        val oldRegex = TERMUX_PACKAGE.replace(".", "\\.")
        return """
            |#!$prefix/bin/sh
            |# Written by AntiGem (edits are overwritten). Termux packages are built for /data/data/$TERMUX_PACKAGE.
            |# apt runs this before dpkg installs them (DPkg::Pre-Install-Pkgs in apt.conf.d/99antigem-relocate) with the
            |# .deb paths on stdin. Each .deb is rewritten for $pkg: every "$TERMUX_PACKAGE" in paths, file contents,
            |# symlinks and maintainer scripts becomes "$pkg" (same length, so binaries keep their layout).
            |OLD='$TERMUX_PACKAGE'
            |NEW='$pkg'
            |export LC_ALL=C
            |umask 022
            |TMP="${'$'}{TMPDIR:-$prefix/tmp}"
            |
            |relocate_deb() {
            |    work=${'$'}(mktemp -d "${'$'}TMP/antigem-deb.XXXXXX") || return 1
            |    if ! dpkg-deb -R "${'$'}1" "${'$'}work/pkg"; then rm -rf "${'$'}work"; return 1; fi
            |    if [ -d "${'$'}work/pkg/data/data/${'$'}OLD" ]; then
            |        mv "${'$'}work/pkg/data/data/${'$'}OLD" "${'$'}work/pkg/data/data/${'$'}NEW" || { rm -rf "${'$'}work"; return 1; }
            |    fi
            |    grep -rlF "${'$'}OLD" "${'$'}work/pkg" 2>/dev/null | while IFS= read -r f; do
            |        case "${'$'}f" in $skipped) continue ;; esac
            |        [ -L "${'$'}f" ] && continue
            |        [ -w "${'$'}f" ] || chmod u+w "${'$'}f"
            |        sed -i "s/$oldRegex/${'$'}NEW/g" "${'$'}f"
            |    done
            |    find "${'$'}work/pkg" -type l | while IFS= read -r l; do
            |        t=${'$'}(readlink "${'$'}l")
            |        case "${'$'}t" in *"${'$'}OLD"*) ln -sfn "${'$'}(printf '%s' "${'$'}t" | sed "s/$oldRegex/${'$'}NEW/g")" "${'$'}l" ;; esac
            |    done
            |    chmod 0755 "${'$'}work/pkg/DEBIAN"
            |    for s in preinst postinst prerm postrm config; do
            |        [ -f "${'$'}work/pkg/DEBIAN/${'$'}s" ] && chmod 0755 "${'$'}work/pkg/DEBIAN/${'$'}s"
            |    done
            |    if ! dpkg-deb --root-owner-group -Znone -b "${'$'}work/pkg" "${'$'}work/out.deb" >/dev/null; then
            |        rm -rf "${'$'}work"; return 1
            |    fi
            |    mv -f "${'$'}work/out.deb" "${'$'}1"
            |    status=${'$'}?
            |    rm -rf "${'$'}work"
            |    return ${'$'}status
            |}
            |
            |fail=0
            |while IFS= read -r deb; do
            |    case "${'$'}deb" in *.deb) ;; *) continue ;; esac
            |    [ -f "${'$'}deb" ] || continue
            |    echo "AntiGem: adapting ${'$'}{deb##*/} for ${'$'}NEW"
            |    relocate_deb "${'$'}deb" || { echo "AntiGem: could not adapt ${'$'}deb" >&2; fail=1; }
            |done
            |exit ${'$'}fail
            |""".trimMargin()
    }
}
