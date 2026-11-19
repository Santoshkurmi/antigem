#!/usr/bin/env bash
# Builds libantigem_sigsys.so (see antigem_sigsys.c: why x86_64 Android needs it) and puts it into the x86_64 glibc
# bundle the app ships, app/src/abi/x86_64/assets/glibc-min-x86_64.tar.xz, as glibc/lib/libantigem_sigsys.so.
# Needs gcc for x86_64 Linux (the library uses no libc, so any version works). Run it after changing the C file.
#
#   SIGSYS_LOG=/data/data/ai.antigem/files/usr/tmp/sigsys.log scripts/x86_64-glibc/build.sh
# instead builds a test copy (libantigem_sigsys-log.so here, not put in the bundle) that writes every rewritten call
# ("<syscall number> -> <result>") to that file on the device.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${HERE}/../.." && pwd)"
BUNDLE="${ROOT}/app/src/abi/x86_64/assets/glibc-min-x86_64.tar.xz"

CFLAGS=(-O2 -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fno-tree-loop-distribute-patterns
    -fno-stack-protector -fno-asynchronous-unwind-tables -Wall -Wextra -Werror
    -Wl,-z,noexecstack -Wl,-z,now -Wl,--hash-style=both -Wl,-soname,libantigem_sigsys.so)

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

if [[ -n "${SIGSYS_LOG:-}" ]]; then
    gcc "${CFLAGS[@]}" -DSIGSYS_LOG="\"${SIGSYS_LOG}\"" -o "${HERE}/libantigem_sigsys-log.so" "${HERE}/antigem_sigsys.c"
    echo "Test build (logs to ${SIGSYS_LOG}): ${HERE}/libantigem_sigsys-log.so"
    exit 0
fi

gcc "${CFLAGS[@]}" -o "${WORK}/libantigem_sigsys.so" "${HERE}/antigem_sigsys.c"
# it runs before anything is set up in the process: it must not need any library
if [[ -n "$(nm -D --undefined-only "${WORK}/libantigem_sigsys.so")" ]]; then
    echo "error: libantigem_sigsys.so needs symbols from other libraries:" >&2
    nm -D --undefined-only "${WORK}/libantigem_sigsys.so" >&2
    exit 1
fi

mkdir "${WORK}/bundle"
xz -dc "${BUNDLE}" | tar -x -C "${WORK}/bundle"
install -m 755 "${WORK}/libantigem_sigsys.so" "${WORK}/bundle/glibc/lib/libantigem_sigsys.so"
(cd "${WORK}/bundle" && tar --owner=0 --group=0 --sort=name -cf - glibc) | xz -6 -T1 > "${WORK}/bundle.tar.xz"
mv "${WORK}/bundle.tar.xz" "${BUNDLE}"
echo "Updated ${BUNDLE}:"
xz -dc "${BUNDLE}" | tar -tv | grep -E "libantigem_sigsys|ld-linux"
