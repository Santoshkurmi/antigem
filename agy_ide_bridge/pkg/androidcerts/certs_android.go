package androidcerts

import (
	"os"
	"path/filepath"
)

// Go trusts Android's system roots (/system/etc/security/cacerts). Android 7.0 has no Let's Encrypt root there
// (ISRG Root X1 came in 7.1.1, X2 in 14), so HTTPS to Let's Encrypt sites such as raw.githubusercontent.com fails
// with "x509: certificate signed by unknown authority"; the app's network_security_config.xml does not reach Go.
// SSL_CERT_FILE = the local environment's CA bundle (Mozilla's roots, ISRG included) fixes that for the bridge and
// the programs it starts, which inherit it:
//   - Go programs (agy): the Android build reads the file on top of the system folder (it only adds roots); agy's
//     Linux build (x86_64) gets roots at all (it looks in /etc/ssl, which Android does not have)
//   - curl, git, pip, Codex and other OpenSSL/Termux programs: the bundle they already read by default
//   - Node and Bun (Claude Code, OpenCode): ignore the variable, they carry their own roots
func init() {
	if os.Getenv("SSL_CERT_FILE") != "" {
		return
	}
	var files []string
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		files = append(files, filepath.Join(prefix, "etc", "tls", "cert.pem"))
	}
	if exe, err := os.Executable(); err == nil { // the bridge lives in $PREFIX/bin
		files = append(files, filepath.Join(filepath.Dir(exe), "..", "etc", "tls", "cert.pem"))
	}
	for _, file := range files {
		if _, err := os.Stat(file); err == nil {
			os.Setenv("SSL_CERT_FILE", file)
			return
		}
	}
}
