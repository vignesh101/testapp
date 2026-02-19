# Certificate Generation Guide - mTLS & MLE

This document explains every certificate and key generated, the OpenSSL/keytool commands used, why each artifact exists, and how they connect to the mTLS and MLE security flows.

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Certificate Architecture](#certificate-architecture)
- [Step-by-Step Generation](#step-by-step-generation)
  - [Step 1: Certificate Authority (CA)](#step-1-certificate-authority-ca)
  - [Step 2: Server TLS Certificate](#step-2-server-tls-certificate)
  - [Step 3: Client TLS Certificate](#step-3-client-tls-certificate)
  - [Step 4: Server Truststore](#step-4-server-truststore)
  - [Step 5: Client Truststore](#step-5-client-truststore)
  - [Step 6: MLE Server Key Pair](#step-6-mle-server-key-pair)
  - [Step 7: MLE Client Key Pair](#step-7-mle-client-key-pair)
- [Generated Files Summary](#generated-files-summary)
- [Verifying Certificates](#verifying-certificates)
- [Regenerating Certificates](#regenerating-certificates)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

| Tool | Purpose | Check |
|------|---------|-------|
| **OpenSSL** | Generate keys, certificates, PKCS12 keystores | `openssl version` |
| **keytool** | Create truststores (ships with JDK) | `keytool -help` |

On Windows, OpenSSL is available via Git Bash (`C:\Program Files\Git\usr\bin\openssl.exe`).

---

## Quick Start

```bash
cd certs
bash generate-certs.sh
```

This generates all 10 files needed for mTLS + MLE in one step.

---

## Certificate Architecture

The system uses two independent sets of certificates for two different purposes:

```
┌──────────────────────────────────────────────────────────────────┐
│                    mTLS (Transport Security)                      │
│                                                                   │
│   Certificates are signed by a common CA.                        │
│   Both sides verify each other via the CA trust chain.           │
│                                                                   │
│           ┌──────────┐                                           │
│           │  CA Root  │  (Self-signed, trust anchor)             │
│           │  ca.crt   │                                          │
│           └────┬─────┘                                           │
│                │ signs                                            │
│        ┌───────┴────────┐                                        │
│        ▼                ▼                                        │
│   ┌──────────┐    ┌──────────┐                                   │
│   │  Server   │    │  Client   │                                 │
│   │  TLS Cert │    │  TLS Cert │                                 │
│   │  (CN=     │    │  (CN=     │                                 │
│   │ localhost)│    │visa-client│                                  │
│   └──────────┘    └──────────┘                                   │
│                                                                   │
│   Packaged into PKCS12 keystores:                                │
│   server-keystore.p12    client-keystore.p12                     │
│                                                                   │
│   CA cert imported into truststores:                              │
│   server-truststore.p12  client-truststore.p12                   │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                    MLE (Payload Security)                         │
│                                                                   │
│   Self-signed RSA key pairs. NOT part of the CA trust chain.     │
│   Used only for JWE encryption/decryption of JSON payloads.     │
│                                                                   │
│   ┌────────────────────┐    ┌────────────────────┐               │
│   │  MLE Server Pair    │    │  MLE Client Pair    │             │
│   │                     │    │                     │             │
│   │  Public:  shared    │    │  Public:  shared    │             │
│   │   with client       │    │   with server       │             │
│   │                     │    │                     │             │
│   │  Private: kept on   │    │  Private: kept on   │             │
│   │   server only       │    │   client only        │             │
│   └────────────────────┘    └────────────────────┘               │
│                                                                   │
│   mle-server-public.pem   mle-client-public.pem                  │
│   mle-server-private.pem  mle-client-private.pem                 │
└──────────────────────────────────────────────────────────────────┘
```

**Key distinction**: mTLS certificates are CA-signed and form a trust chain. MLE certificates are self-signed and are only used for JWE payload encryption -- they are exchanged out-of-band (shared as files).

---

## Step-by-Step Generation

### Step 1: Certificate Authority (CA)

The CA is the trust anchor. Both server and client trust certificates signed by this CA.

```bash
# Generate CA private key (RSA 2048-bit)
openssl genrsa -out ca.key 2048
```

**What this does**: Creates a 2048-bit RSA private key for the CA. This key signs all TLS certificates.

```bash
# Generate self-signed CA certificate (valid 365 days)
openssl req -new -x509 -key ca.key -out ca.crt -days 365 \
  -subj "/C=US/ST=California/L=Foster City/O=Visa Inc/OU=Visa CA/CN=Visa Local CA"
```

**What this does**: Creates a self-signed X.509 certificate using the CA's private key. The `-subj` flag sets the Distinguished Name (DN) fields:

| Field | Value | Meaning |
|-------|-------|---------|
| C | US | Country |
| ST | California | State |
| L | Foster City | Locality |
| O | Visa Inc | Organization |
| OU | Visa CA | Organizational Unit |
| CN | Visa Local CA | Common Name (identifies this cert) |

**Output files**:
- `ca.key` -- CA private key (used to sign other certs, deleted after generation)
- `ca.crt` -- CA certificate (imported into truststores)

---

### Step 2: Server TLS Certificate

The server's TLS identity certificate, signed by the CA.

```bash
# Generate server private key
openssl genrsa -out server-tls.key 2048
```

```bash
# Create a Certificate Signing Request (CSR)
openssl req -new -key server-tls.key -out server-tls.csr \
  -subj "/C=US/ST=California/L=Foster City/O=Visa Inc/OU=Server/CN=localhost"
```

**What this does**: Creates a CSR -- a formal request to the CA to sign the server's public key. The CN is `localhost` because the server runs locally.

```bash
# Create extensions file for Subject Alternative Name (SAN)
cat > server-ext.cnf <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage=digitalSignature, keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EOF
```

**Extension fields explained**:

| Extension | Value | Purpose |
|-----------|-------|---------|
| `basicConstraints` | `CA:FALSE` | This is NOT a CA certificate, cannot sign other certs |
| `keyUsage` | `digitalSignature, keyEncipherment` | Can be used for TLS handshake and key exchange |
| `extendedKeyUsage` | `serverAuth` | Can only be used for server authentication (not client) |
| `subjectAltName` | `DNS:localhost,IP:127.0.0.1` | Valid hostnames -- clients verify this during TLS |

```bash
# Sign the CSR with the CA to produce the server certificate
openssl x509 -req -in server-tls.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server-tls.crt -days 365 -extfile server-ext.cnf
```

**What this does**: The CA signs the server's CSR, producing a certificate that chains back to the CA. Any client that trusts the CA will also trust this server certificate.

```bash
# Package server cert + key + CA cert into PKCS12 keystore
openssl pkcs12 -export -in server-tls.crt -inkey server-tls.key -certfile ca.crt \
  -out server-keystore.p12 -name server -passout pass:changeit
```

**What this does**: Creates a PKCS12 keystore containing:
- The server's signed certificate
- The server's private key
- The CA certificate (as part of the chain)
- Alias: `server` (referenced in Spring Boot config as `key-alias`)
- Password: `changeit`

**Why PKCS12**: Spring Boot's embedded Tomcat reads keystores in PKCS12 format for SSL configuration.

**Output file**: `server-keystore.p12`

---

### Step 3: Client TLS Certificate

The client's TLS identity certificate, also signed by the same CA.

```bash
# Generate client private key
openssl genrsa -out client-tls.key 2048

# Create CSR
openssl req -new -key client-tls.key -out client-tls.csr \
  -subj "/C=US/ST=California/L=Foster City/O=Visa Inc/OU=Client/CN=visa-client"
```

**CN is `visa-client`**: Unlike the server (CN=localhost), the client's CN identifies the client application. The server can inspect this CN to identify which client is connecting.

```bash
# Extensions for client certificate
cat > client-ext.cnf <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage=digitalSignature, keyEncipherment
extendedKeyUsage=clientAuth
EOF
```

**Key difference from server**: `extendedKeyUsage=clientAuth` -- this cert can only be used for client authentication, not server authentication. This prevents misuse.

```bash
# Sign with CA
openssl x509 -req -in client-tls.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out client-tls.crt -days 365 -extfile client-ext.cnf

# Package into PKCS12 keystore
openssl pkcs12 -export -in client-tls.crt -inkey client-tls.key -certfile ca.crt \
  -out client-keystore.p12 -name client -passout pass:changeit
```

**Output file**: `client-keystore.p12`

---

### Step 4: Server Truststore

The server's truststore contains the CA certificate. This tells the server to trust any certificate signed by this CA (including the client's certificate).

```bash
keytool -importcert -alias ca -file ca.crt \
  -keystore server-truststore.p12 -storetype PKCS12 \
  -storepass changeit -noprompt
```

**What this does**: Creates a PKCS12 truststore and imports the CA certificate. When a client connects with mTLS, the server checks the client's certificate against this truststore.

**`-noprompt` flag**: Automatically trusts the certificate without asking for confirmation.

**Output file**: `server-truststore.p12`

---

### Step 5: Client Truststore

The client's truststore contains the same CA certificate. This tells the client to trust the server's certificate.

```bash
keytool -importcert -alias ca -file ca.crt \
  -keystore client-truststore.p12 -storetype PKCS12 \
  -storepass changeit -noprompt
```

**Output file**: `client-truststore.p12`

**Why separate truststores?**: In production, server and client may trust different CAs. Keeping them separate follows the principle of least privilege.

---

### Step 6: MLE Server Key Pair

A self-signed RSA key pair used for encrypting/decrypting **request** payloads.

```bash
# Generate RSA private key
openssl genrsa -out mle-server-private.key 2048
```

```bash
# Generate self-signed certificate containing the public key
openssl req -new -x509 -key mle-server-private.key -out mle-server-public.pem \
  -days 365 \
  -subj "/C=US/ST=California/L=Foster City/O=Visa Inc/OU=MLE Server/CN=mle-server"
```

**Why a self-signed cert instead of just a public key?**: The Visa MLE specification uses X.509 certificates to distribute public keys. The certificate wraps the RSA public key in a standardized format that includes metadata (issuer, validity, etc.).

```bash
# Convert private key to PKCS#1 format (Visa standard)
openssl rsa -in mle-server-private.key -out mle-server-private.pem -traditional
```

**Why PKCS#1?**: The original Visa sample code expects `-----BEGIN RSA PRIVATE KEY-----` (PKCS#1 format). Newer OpenSSL versions default to `-----BEGIN PRIVATE KEY-----` (PKCS#8 format). The `-traditional` flag forces PKCS#1 output. Our code supports both formats.

**PKCS#1 vs PKCS#8**:

| Format | Header | Parsing Method |
|--------|--------|----------------|
| PKCS#1 | `-----BEGIN RSA PRIVATE KEY-----` | BouncyCastle ASN1 parsing (Visa standard) |
| PKCS#8 | `-----BEGIN PRIVATE KEY-----` | Java `PKCS8EncodedKeySpec` (simpler) |

**Output files**:
- `mle-server-private.pem` -- Server keeps this secret, uses it to decrypt incoming requests
- `mle-server-public.pem` -- Shared with the client, client uses it to encrypt requests

**How keys are used**:
```
Client                                  Server
  │                                       │
  │  Encrypt request with                 │
  │  mle-server-public.pem               │
  │  ──── {"encData":"<JWE>"} ──────────► │
  │                                       │  Decrypt request with
  │                                       │  mle-server-private.pem
```

---

### Step 7: MLE Client Key Pair

A self-signed RSA key pair used for encrypting/decrypting **response** payloads.

```bash
# Generate RSA private key
openssl genrsa -out mle-client-private.key 2048

# Generate self-signed certificate containing the public key
openssl req -new -x509 -key mle-client-private.key -out mle-client-public.pem \
  -days 365 \
  -subj "/C=US/ST=California/L=Foster City/O=Visa Inc/OU=MLE Client/CN=mle-client"

# Convert to PKCS#1 format
openssl rsa -in mle-client-private.key -out mle-client-private.pem -traditional
```

**Output files**:
- `mle-client-private.pem` -- Client keeps this secret, uses it to decrypt responses
- `mle-client-public.pem` -- Shared with the server, server uses it to encrypt responses

**How keys are used**:
```
Client                                  Server
  │                                       │
  │                                       │  Encrypt response with
  │                                       │  mle-client-public.pem
  │  ◄──── {"encData":"<JWE>"} ────────  │
  │                                       │
  │  Decrypt response with               │
  │  mle-client-private.pem              │
```

---

## Generated Files Summary

After running `generate-certs.sh`, these files exist in the `certs/` directory:

### mTLS Files (Transport Security)

| File | Type | Contains | Used By | Spring Boot Config |
|------|------|----------|---------|-------------------|
| `ca.crt` | X.509 Certificate | CA public certificate | Imported into truststores | -- |
| `server-keystore.p12` | PKCS12 Keystore | Server cert + private key + CA cert | Server | `server.ssl.key-store` |
| `server-truststore.p12` | PKCS12 Truststore | CA certificate | Server | `server.ssl.trust-store` |
| `client-keystore.p12` | PKCS12 Keystore | Client cert + private key + CA cert | Client | `ssl.key-store` |
| `client-truststore.p12` | PKCS12 Truststore | CA certificate | Client | `ssl.trust-store` |

### MLE Files (Payload Security)

| File | Type | Format | Contains | Used By | Config Key |
|------|------|--------|----------|---------|------------|
| `mle-server-private.pem` | RSA Private Key | PKCS#1 PEM | Server's MLE private key | Server | `mle.server-private-key-path` |
| `mle-server-public.pem` | X.509 Certificate | PEM | Server's MLE public key | Client | `mle.server-public-cert-path` |
| `mle-client-private.pem` | RSA Private Key | PKCS#1 PEM | Client's MLE private key | Client | `mle.client-private-key-path` |
| `mle-client-public.pem` | X.509 Certificate | PEM | Client's MLE public key | Server | `mle.client-public-cert-path` |

### Temporary Files (Deleted After Generation)

| File | Purpose |
|------|---------|
| `*.key` | Raw private keys (packaged into .p12/.pem, then deleted) |
| `*.csr` | Certificate Signing Requests (consumed during signing, then deleted) |
| `*.cnf` | OpenSSL extension configs (consumed during signing, then deleted) |
| `*.srl` | CA serial number tracker (used by OpenSSL, then deleted) |

---

## Verifying Certificates

### View certificate details

```bash
# View CA certificate
openssl x509 -in ca.crt -text -noout

# View MLE server public certificate
openssl x509 -in mle-server-public.pem -text -noout

# View server keystore contents
keytool -list -v -keystore server-keystore.p12 -storetype PKCS12 -storepass changeit

# View truststore contents
keytool -list -v -keystore server-truststore.p12 -storetype PKCS12 -storepass changeit
```

### Verify certificate chain

```bash
# Verify server cert was signed by the CA
openssl verify -CAfile ca.crt server-tls.crt

# Verify client cert was signed by the CA
openssl verify -CAfile ca.crt client-tls.crt
```

Expected output: `server-tls.crt: OK`

### Verify MLE private key matches public cert

```bash
# These two commands should output the same modulus
openssl rsa -in mle-server-private.pem -modulus -noout
openssl x509 -in mle-server-public.pem -modulus -noout
```

### Test SSL connection

```bash
# Test mTLS connection (while server is running)
openssl s_client -connect localhost:8443 \
  -cert client-tls.crt -key client-tls.key \
  -CAfile ca.crt
```

---

## Regenerating Certificates

To regenerate all certificates (e.g., when they expire or keys are compromised):

```bash
cd certs
bash generate-certs.sh
```

The script automatically cleans old files before generating new ones.

**Important**: After regeneration, both server and client must be restarted to pick up the new certificates.

---

## Troubleshooting

### `Illegal base64 character 2d`

The PEM file has `-----BEGIN PRIVATE KEY-----` (PKCS#8) but the code expected `-----BEGIN RSA PRIVATE KEY-----` (PKCS#1). Fix by converting:

```bash
openssl rsa -in key.pem -out key.pem -traditional
```

Or use our code which handles both formats automatically.

### `PKIX path building failed` / `unable to find valid certification path`

The truststore doesn't contain the CA that signed the other side's certificate. Verify:

```bash
keytool -list -keystore client-truststore.p12 -storetype PKCS12 -storepass changeit
```

Should show the CA certificate.

### `No subject alternative names matching IP address 127.0.0.1 found`

The server certificate's SAN doesn't include the hostname/IP being used. Check with:

```bash
openssl x509 -in server-tls.crt -text -noout | grep -A1 "Subject Alternative Name"
```

Should show `DNS:localhost, IP Address:127.0.0.1`.

### `Received fatal alert: bad_certificate`

The client is not presenting its certificate. Verify the client keystore is configured and contains the client cert + key:

```bash
keytool -list -v -keystore client-keystore.p12 -storetype PKCS12 -storepass changeit
```

### `password was incorrect` or `keystore was tampered with`

Wrong password. All keystores and truststores use password `changeit`.

### Git Bash converts `/C=US` to `C:/Program Files/Git/C=US`

Git Bash on Windows converts paths starting with `/`. The script sets `MSYS_NO_PATHCONV=1` to prevent this. If running commands manually, prefix with:

```bash
MSYS_NO_PATHCONV=1 openssl req -new -x509 ... -subj "/C=US/..."
```
