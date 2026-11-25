package ai.antigem.data.local.chroot

/** Shell scripts run as root inside the chroot (by `chrootctl exec --as-root`, with /bin/sh = dash). */
object ChrootScripts {
    private val PACKAGE = Regex("^[a-z0-9][a-z0-9+.:-]{0,80}$")

    /** A shell single-quoted word. */
    fun sq(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    fun isPackageName(name: String) = PACKAGE.matches(name)

    /**
     * Android's network groups (paranoid networking: only members of aid_inet may open sockets), Android's usb group
     * (its /dev/bus/usb devices are root:usb 0660: fastboot, adb and other USB OTG tools work without sudo), apt
     * without its sandbox user, and the user [user] with AntiGem's uid [uid] (moved there when it exists with another
     * uid), in sudo and those groups.
     */
    fun userScript(user: String, uid: Int, password: String, sudoNoPassword: Boolean, keepSudoers: Boolean): String = """
set -e
U=${sq(user)}
NEW_UID=$uid
PASS=${sq(password)}
echo "== Android network groups"
add_group() { getent group "${'$'}1" >/dev/null 2>&1 || groupadd -g "${'$'}2" "${'$'}1" 2>/dev/null || groupadd "${'$'}1"; }
add_group aid_inet 3003
add_group aid_net_raw 3004
add_group aid_net_admin 3005
add_group aid_usb 1018
usermod -a -G aid_inet,aid_net_raw root 2>/dev/null || true
if getent passwd _apt >/dev/null 2>&1; then usermod -a -G aid_inet _apt 2>/dev/null || true; fi
mkdir -p /etc/apt/apt.conf.d
echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/01-antigem-sandbox
[ -f /etc/default/locale ] || echo 'LANG=C.UTF-8' > /etc/default/locale
# a clean image (the prebuilt) has an empty machine-id: this install gets its own (dbus and the desktops need one)
if [ ! -s /etc/machine-id ]; then od -An -tx1 -N16 /dev/urandom | tr -d ' \n' > /etc/machine-id && echo >> /etc/machine-id; fi
if [ -f /var/lib/dbus/machine-id ] && [ ! -L /var/lib/dbus/machine-id ] && [ ! -s /var/lib/dbus/machine-id ]; then
    ln -sf /etc/machine-id /var/lib/dbus/machine-id
fi

echo "== User ${'$'}U (uid ${'$'}NEW_UID)"
OTHER=${'$'}(getent passwd "${'$'}NEW_UID" | cut -d: -f1 || true)
if [ -n "${'$'}OTHER" ] && [ "${'$'}OTHER" != "${'$'}U" ]; then
    echo "uid ${'$'}NEW_UID already belongs to ${'$'}OTHER in this rootfs" >&2
    exit 1
fi
if id -u "${'$'}U" >/dev/null 2>&1; then
    OLD_UID=${'$'}(id -u "${'$'}U")
    G=${'$'}(id -gn "${'$'}U")
    OLD_GID=${'$'}(getent group "${'$'}G" | cut -d: -f3)
    if [ "${'$'}OLD_UID" != "${'$'}NEW_UID" ]; then
        echo "moving ${'$'}U from uid ${'$'}OLD_UID to ${'$'}NEW_UID"
        usermod -u "${'$'}NEW_UID" "${'$'}U"
    fi
    if [ "${'$'}OLD_GID" = "${'$'}OLD_UID" ] && [ "${'$'}OLD_GID" != "${'$'}NEW_UID" ] && ! getent group "${'$'}NEW_UID" >/dev/null 2>&1; then
        groupmod -g "${'$'}NEW_UID" "${'$'}G"
        H=${'$'}(getent passwd "${'$'}U" | cut -d: -f6)
        if [ -d "${'$'}H" ]; then find "${'$'}H" -xdev -gid "${'$'}OLD_GID" -exec chgrp -h "${'$'}NEW_UID" {} + 2>/dev/null || true; fi
    fi
else
    if ! getent group "${'$'}NEW_UID" >/dev/null 2>&1; then
        if getent group "${'$'}U" >/dev/null 2>&1; then groupmod -g "${'$'}NEW_UID" "${'$'}U"; else groupadd -g "${'$'}NEW_UID" "${'$'}U"; fi
    fi
    useradd -l -m -s /bin/bash -u "${'$'}NEW_UID" -g "${'$'}NEW_UID" "${'$'}U"
fi
usermod -a -G aid_inet,aid_net_raw "${'$'}U"
usermod -a -G aid_usb "${'$'}U" 2>/dev/null || true
if getent group sudo >/dev/null 2>&1; then usermod -a -G sudo "${'$'}U"; fi
H=${'$'}(getent passwd "${'$'}U" | cut -d: -f6)
mkdir -p "${'$'}H/.local/bin"
chown "${'$'}U:" "${'$'}H" "${'$'}H/.local" "${'$'}H/.local/bin"
# a home without the usual files (a clean image leaves the homes out): Ubuntu's defaults (.profile puts ~/.local/bin on PATH)
for f in /etc/skel/.[!.]* /etc/skel/*; do
    [ -e "${'$'}f" ] || continue
    b=${'$'}{f##*/}
    if [ ! -e "${'$'}H/${'$'}b" ]; then cp -a "${'$'}f" "${'$'}H/${'$'}b" && chown -R "${'$'}U:" "${'$'}H/${'$'}b"; fi
done
if [ -n "${'$'}PASS" ]; then printf '%s:%s\n' "${'$'}U" "${'$'}PASS" | chpasswd; fi

mkdir -p /etc/sudoers.d
if [ ${if (keepSudoers) 1 else 0} = 1 ] && grep -rqs "^${'$'}U[[:space:]]" /etc/sudoers.d; then
    echo "keeping the sudo rule of ${'$'}U"
else
    echo "${'$'}U ALL=(ALL:ALL) ${if (sudoNoPassword) "NOPASSWD: " else ""}ALL" > /etc/sudoers.d/90-antigem
    chmod 440 /etc/sudoers.d/90-antigem
fi
echo "== User ready"
""".trimStart()

    /** apt: update, optionally upgrade, install [packages] (and Node.js 22 from NodeSource), clean the cache. */
    fun packagesScript(packages: List<String>, nodeJs: Boolean, upgrade: Boolean): String {
        val names = packages.filter { isPackageName(it) }.distinct()
        return buildString {
            appendLine("set -e")
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            appendLine("APT='apt-get -q -y -o Dpkg::Use-Pty=0'")
            appendLine("echo '== apt-get update'")
            appendLine("apt-get -q update")
            if (upgrade) {
                appendLine("echo '== Upgrading installed packages'")
                appendLine("\$APT upgrade")
            }
            if (names.isNotEmpty()) {
                appendLine("echo '== Installing: ${names.joinToString(" ")}'")
                appendLine("\$APT install --no-install-recommends ${names.joinToString(" ")}")
            }
            if (nodeJs) {
                appendLine("echo '== Node.js 22 (NodeSource)'")
                appendLine("NODE_MAJOR=\$(node -v 2>/dev/null | sed 's/^v//' | cut -d. -f1)")
                appendLine("if [ -z \"\$NODE_MAJOR\" ] || [ \"\$NODE_MAJOR\" -lt 22 ]; then")
                appendLine("    \$APT install --no-install-recommends ca-certificates curl gnupg")
                appendLine("    curl -fsSL https://deb.nodesource.com/setup_22.x -o /tmp/nodesource_setup.sh")
                appendLine("    bash /tmp/nodesource_setup.sh")
                appendLine("    rm -f /tmp/nodesource_setup.sh")
                appendLine("    \$APT install nodejs")
                appendLine("fi")
                appendLine("node -v")
            }
            appendLine("apt-get clean")
            appendLine("echo '== Packages done'")
        }
    }

    fun passwordScript(user: String, password: String): String =
        "set -e\nprintf '%s:%s\\n' ${sq(user)} ${sq(password)} | chpasswd\necho 'password changed'\n"

    fun sudoScript(user: String, noPassword: Boolean): String =
        "set -e\nmkdir -p /etc/sudoers.d\necho ${sq("$user ALL=(ALL:ALL) ${if (noPassword) "NOPASSWD: " else ""}ALL")} > /etc/sudoers.d/90-antigem\n" +
            "chmod 440 /etc/sudoers.d/90-antigem\necho 'sudo rule saved'\n"

    const val CLEAN_SCRIPT = "set -e\napt-get clean\nrm -rf /var/lib/apt/lists/*\necho 'apt cache cleaned'\n"
}
