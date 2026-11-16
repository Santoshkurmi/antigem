# glibc runtime libraries for dsh on Android

The official Node.js linux-arm64 build (which runs DeepSeek Harness on Android through the app's bundled glibc
loader) and node-pty's prebuilt addon need three libraries the app's minimal glibc runtime does not ship. The
Android/arm64 build of the bridge embeds them (`glibc_android.go`) and writes them to `~/.local/share/dsh/lib`.

| File | Source package (Debian 13 "trixie", arm64) | sha256 |
|---|---|---|
| `libstdc++.so.6` (= `libstdc++.so.6.0.33`) | `libstdc++6_14.2.0-19_arm64.deb` | `fff1f0050a33ddf29c44634c7639ac16e2573a61e3daab4fcc4c007b432ccf19` |
| `libgcc_s.so.1` | `libgcc-s1_14.2.0-19_arm64.deb` | `9a146967dc38c3820c5dc21c3209812655f66b6551264072b187bd0e787947e0` |
| `libutil.so.1` | `libc6_2.41-12+deb13u4_arm64.deb` | `7a9ee1e1a208b3721b1cacc5c68beb8f03c588cc270da5842af0c2de368d5354` |

`libstdc++` and `libgcc_s` are GCC runtime libraries (GPLv3 with the GCC Runtime Library Exception); `libutil.so.1`
is a glibc compatibility stub (LGPL-2.1+), whose code lives in `libc.so.6` since glibc 2.34.
