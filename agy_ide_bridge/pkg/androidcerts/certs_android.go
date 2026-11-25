package androidcerts

import (
	"crypto/x509"
	"os"
	"path/filepath"
)

// Go trusts Android's system roots (/system/etc/security/cacerts). Android 7.0 has no Let's Encrypt root there
// (ISRG Root X1 came in 7.1.1, X2 in 14), so HTTPS to Let's Encrypt sites such as raw.githubusercontent.com fails
// with "x509: certificate signed by unknown authority"; the app's network_security_config.xml does not reach Go.
// Also trust the local environment's CA bundle (Mozilla's roots, ISRG included): SSL_CERT_FILE adds that file to
// the system folder Go reads. It is set only while the roots load, so programs the bridge starts keep their own
// environment.
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
		if _, err := os.Stat(file); err != nil {
			continue
		}
		os.Setenv("SSL_CERT_FILE", file)
		x509.SystemCertPool() // Go loads the roots once per process: now, while the variable is set
		os.Unsetenv("SSL_CERT_FILE")
		return
	}
}
