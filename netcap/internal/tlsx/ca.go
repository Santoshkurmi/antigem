// Package tlsx holds the HTTPS side of the engine: the per-install CA, the per-host certificates it signs, a small
// ClientHello parser (SNI and ALPN before any handshake) and the list of hosts whose app refused the CA.
package tlsx

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha1"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"time"
)

// CA is the certificate authority that signs the per-host certificates.
type CA struct {
	Cert    *x509.Certificate
	Key     crypto.Signer
	CertPEM []byte
	KeyPEM  []byte // PKCS#8
}

// NewCA creates a new CA (ECDSA P-256, valid 10 years). Every install makes its own; the key never leaves the device.
func NewCA() (*CA, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	serial, err := randSerial()
	if err != nil {
		return nil, err
	}
	pub, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return nil, err
	}
	ski := sha1.Sum(pub)
	short := hex.EncodeToString(ski[:3])
	now := time.Now()
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "AntiGem Network Interceptor " + short, Organization: []string{"AntiGem"}},
		NotBefore:             now.Add(-48 * time.Hour),
		NotAfter:              now.AddDate(10, 0, 0),
		IsCA:                  true,
		BasicConstraintsValid: true,
		MaxPathLenZero:        true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign | x509.KeyUsageDigitalSignature,
		SubjectKeyId:          ski[:],
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, err
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, err
	}
	return LoadCA(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}))
}

// LoadCA parses a PEM certificate and its PEM (PKCS#8 or EC) private key.
func LoadCA(certPEM, keyPEM []byte) (*CA, error) {
	cb, _ := pem.Decode(certPEM)
	if cb == nil || cb.Type != "CERTIFICATE" {
		return nil, errors.New("CA certificate: no PEM CERTIFICATE block")
	}
	cert, err := x509.ParseCertificate(cb.Bytes)
	if err != nil {
		return nil, fmt.Errorf("CA certificate: %w", err)
	}
	kb, _ := pem.Decode(keyPEM)
	if kb == nil {
		return nil, errors.New("CA key: no PEM block")
	}
	var signer crypto.Signer
	if k, err := x509.ParsePKCS8PrivateKey(kb.Bytes); err == nil {
		s, ok := k.(crypto.Signer)
		if !ok {
			return nil, errors.New("CA key: not a signing key")
		}
		signer = s
	} else if k, err2 := x509.ParseECPrivateKey(kb.Bytes); err2 == nil {
		signer = k
	} else {
		return nil, fmt.Errorf("CA key: %w", err)
	}
	return &CA{Cert: cert, Key: signer, CertPEM: certPEM, KeyPEM: keyPEM}, nil
}

// AndroidName is the file name Android's certificate store uses for this CA: OpenSSL's old subject hash
// (first 4 bytes of MD5 over the DER subject, little-endian) + ".0".
func (ca *CA) AndroidName() string {
	sum := md5.Sum(ca.Cert.RawSubject)
	return fmt.Sprintf("%08x.0", binary.LittleEndian.Uint32(sum[:4]))
}

// Fingerprint is the SHA-256 fingerprint of the CA certificate (hex, colon-free).
func (ca *CA) Fingerprint() string {
	sum := sha256Sum(ca.Cert.Raw)
	return hex.EncodeToString(sum[:])
}

func randSerial() (*big.Int, error) {
	return rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 127))
}
