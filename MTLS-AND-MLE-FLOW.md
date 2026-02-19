# mTLS and MLE Flow - Detailed Technical Documentation

This document provides a deep-dive into how Mutual TLS (mTLS) and Message Level Encryption (MLE) work in this project, covering the protocol-level handshake, the JWE encryption internals, and how each code component participates.

---

## Table of Contents

- [Overview: Three Security Layers](#overview-three-security-layers)
- [Layer 1: mTLS Flow](#layer-1-mtls-flow)
  - [What is mTLS?](#what-is-mtls)
  - [Standard TLS vs mTLS](#standard-tls-vs-mtls)
  - [mTLS Handshake Step-by-Step](#mtls-handshake-step-by-step)
  - [mTLS in the Code](#mtls-in-the-code)
- [Layer 2: Basic Authentication Flow](#layer-2-basic-authentication-flow)
- [Layer 3: MLE Flow](#layer-3-mle-flow)
  - [What is MLE?](#what-is-mle)
  - [Why MLE on Top of TLS?](#why-mle-on-top-of-tls)
  - [JWE Format Deep Dive](#jwe-format-deep-dive)
  - [MLE Request Encryption Flow](#mle-request-encryption-flow)
  - [MLE Response Encryption Flow](#mle-response-encryption-flow)
  - [MLE in the Code](#mle-in-the-code)
- [Complete Request Lifecycle](#complete-request-lifecycle)
- [Key Distribution Model](#key-distribution-model)

---

## Overview: Three Security Layers

Every API call passes through three security layers in this order:

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 3: MLE (Message Level Encryption)                        │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  JSON payload encrypted as JWE (RSA-OAEP-256 + A128GCM)  │  │
│  │  {"encData": "eyJlbmMiOiJBMTI4R0NNIi..."}               │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
│  Layer 2: Basic Authentication                                   │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Authorization: Basic base64("userId:password")           │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
│  Layer 1: mTLS (Mutual TLS)                                      │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  TLS 1.2/1.3 with client + server certificate exchange    │  │
│  │  Transport-level encryption (AES-256-GCM or similar)      │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
│  Network (TCP/IP)                                                │
└─────────────────────────────────────────────────────────────────┘
```

---

## Layer 1: mTLS Flow

### What is mTLS?

**Mutual TLS (mTLS)** is an extension of standard TLS where **both** the client and server authenticate each other using X.509 certificates. In standard TLS, only the server proves its identity. In mTLS, the client must also present a certificate that the server validates.

### Standard TLS vs mTLS

```
Standard TLS (one-way):          mTLS (two-way):

Client        Server             Client        Server
  │               │                │               │
  │  ClientHello  │                │  ClientHello  │
  │──────────────►│                │──────────────►│
  │               │                │               │
  │  ServerHello  │                │  ServerHello  │
  │  + Server     │                │  + Server     │
  │    Certificate│                │    Certificate│
  │◄──────────────│                │  + CertReq ◄──┤  ← Server REQUESTS
  │               │                │◄──────────────│    client certificate
  │               │                │               │
  │  (no client   │                │  Client       │
  │   cert sent)  │                │  Certificate  │  ← Client SENDS
  │               │                │──────────────►│    its certificate
  │               │                │               │
  │  [Encrypted   │                │  [Encrypted   │
  │   channel]    │                │   channel]    │
  │◄─────────────►│                │◄─────────────►│
```

### mTLS Handshake Step-by-Step

```
visa-client                                              visa-server
(port 8080)                                              (port 8443)
     │                                                        │
     │  ┌─────────────────────────────────────────────────┐   │
     │  │ STEP 1: TCP Connection                           │   │
     │  │ Client opens TCP socket to localhost:8443         │   │
     │  └─────────────────────────────────────────────────┘   │
     │                                                        │
     │  ══════════════ TLS HANDSHAKE BEGINS ═══════════════   │
     │                                                        │
     │  ┌─────────────────────────────────────────────────┐   │
     │  │ STEP 2: ClientHello                              │   │
     │  │ ● Supported TLS versions (TLS 1.2, 1.3)         │   │
     │  │ ● Supported cipher suites                        │   │
     │  │ ● Client random (32 bytes)                       │   │
     │──┤ ● SNI: localhost                                 ├──►│
     │  └─────────────────────────────────────────────────┘   │
     │                                                        │
     │  ┌─────────────────────────────────────────────────┐   │
     │  │ STEP 3: ServerHello + Server Certificate         │   │
     │  │ ● Selected TLS version and cipher suite          │   │
     │  │ ● Server random (32 bytes)                       │   │
     │  │ ● Server Certificate (from server-keystore.p12)  │   │
     │  │   CN=localhost, signed by CA                     │   │
     │  │ ● CertificateRequest ← CRITICAL FOR mTLS        │   │
     │  │   "Send me your client certificate"              │   │
     │◄─┤   Acceptable CAs: [Visa Local CA]               ├───│
     │  └─────────────────────────────────────────────────┘   │
     │                                                        │
     │  ┌─────────────────────────────────────────────────┐   │
     │  │ STEP 4: Client verifies Server Certificate       │   │
     │  │                                                  │   │
     │  │ client-truststore.p12 contains CA cert           │   │
     │  │   ● Is server cert signed by trusted CA? ✓       │   │
     │  │   ● Is CN=localhost matching the hostname? ✓     │   │
     │  │   ● Is SAN=DNS:localhost,IP:127.0.0.1? ✓         │   │
     │  │   ● Is cert within validity period? ✓            │   │
     │  │   ● Is extendedKeyUsage=serverAuth? ✓            │   │
     │  │                                                  │   │
     │  │ Result: SERVER IDENTITY VERIFIED                 │   │
     │  └─────────────────────────────────────────────────┘   │
     │                                                        │
     │  ┌─────────────────────────────────────────────────┐   │
     │  │ STEP 5: Client Certificate + Key Exchange        │   │
     │  │ ● Client Certificate (from client-keystore.p12)  │   │
     │  │   CN=visa-client, signed by CA                   │   │
     │  │ ● CertificateVerify (proves client owns the key) │   │
     │  │ ● Pre-master secret (encrypted with server's     │   │
     │──┤   public key)                                    ├──►│
     │  └─────────────────────────────────────────────────┘   │
     │                                                        │
     │  ┌─────────────────────────────────────────────────┐   │
     │  │ STEP 6: Server verifies Client Certificate       │   │
     │  │                                                  │   │
     │  │ server-truststore.p12 contains CA cert           │   │
     │  │   ● Is client cert signed by trusted CA? ✓       │   │
     │  │   ● Is CertificateVerify signature valid? ✓      │   │
     │  │   ● Is cert within validity period? ✓            │   │
     │  │   ● Is extendedKeyUsage=clientAuth? ✓            │   │
     │  │                                                  │   │
     │  │ Result: CLIENT IDENTITY VERIFIED                 │   │
     │  └─────────────────────────────────────────────────┘   │
     │                                                        │
     │  ┌─────────────────────────────────────────────────┐   │
     │  │ STEP 7: Session Keys Derived                     │   │
     │  │ ● Both sides derive symmetric session keys       │   │
     │  │   from pre-master secret + randoms               │   │
     │  │ ● All subsequent data is encrypted with AES      │   │
     │  └─────────────────────────────────────────────────┘   │
     │                                                        │
     │  ══════════ TLS HANDSHAKE COMPLETE ════════════════    │
     │                                                        │
     │  ┌─────────────────────────────────────────────────┐   │
     │  │ STEP 8: Encrypted Application Data               │   │
     │  │ ● HTTP requests/responses flow over the          │   │
     │  │   encrypted TLS channel                          │   │
     │  │ ● Both sides are mutually authenticated          │   │
     │◄─┤ ● Data is encrypted with session keys            ├──►│
     │  └─────────────────────────────────────────────────┘   │
     │                                                        │
```

### mTLS in the Code

#### Server Side (`application.yml`)

```yaml
server:
  ssl:
    enabled: true                    # Enable HTTPS
    key-store: server-keystore.p12   # Server's cert + private key
    key-store-password: changeit
    key-alias: server                # Alias within the keystore
    client-auth: need                # REQUIRE client certificate (mTLS)
    trust-store: server-truststore.p12  # CA cert (to verify client)
    trust-store-password: changeit
```

`client-auth: need` is the critical setting. It tells Tomcat to send a `CertificateRequest` during the TLS handshake and reject connections without a valid client certificate.

**Alternatives for `client-auth`**:
| Value | Behavior |
|-------|----------|
| `none` | Standard TLS (no client cert) |
| `want` | Request client cert but don't require it |
| `need` | **Require** client cert (mTLS) |

#### Client Side (`SSLConfig.java`)

```java
// Load client keystore (contains client cert + private key)
KeyStore keyStore = KeyStore.getInstance("PKCS12");
keyStore.load(new FileInputStream(keyStorePath), password);
KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
kmf.init(keyStore, password);

// Load truststore (contains CA cert to verify server)
KeyStore trustStore = KeyStore.getInstance("PKCS12");
trustStore.load(new FileInputStream(trustStorePath), password);
TrustManagerFactory tmf = TrustManagerFactory.getInstance(defaultAlgorithm);
tmf.init(trustStore);

// Create SSLContext with both key managers and trust managers
SSLContext sslContext = SSLContext.getInstance("TLS");
sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
```

**How it connects**: The `SSLContext` bean is injected into `VisaApiService`, which applies it to every HTTPS connection:

```java
HttpsURLConnection httpsConn = ...;
httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
```

---

## Layer 2: Basic Authentication Flow

After the mTLS handshake completes and the encrypted channel is established, the client sends HTTP Basic Auth credentials.

```
Client                                                    Server
  │                                                         │
  │  HTTP Header (inside TLS encrypted channel):            │
  │                                                         │
  │  Authorization: Basic MVdNMlRUNElIUFhDO...             │
  │                                                         │
  │  (Base64 decode → "userId:password")                    │
  │──────────────────────────────────────────────────────►  │
  │                                                         │
  │                              SecurityConfig.java        │
  │                              ┌────────────────────────┐ │
  │                              │ BasicAuthenticationFilter│
  │                              │ ● Decode Base64 header │ │
  │                              │ ● Extract userId +     │ │
  │                              │   password              │ │
  │                              │ ● Look up user in      │ │
  │                              │   InMemoryUserDetails   │ │
  │                              │ ● BCrypt verify password│ │
  │                              │ ● Grant ROLE_API_USER  │ │
  │                              └────────────────────────┘ │
  │                                                         │
  │  If credentials invalid:                                │
  │◄─── HTTP 401 Unauthorized ──────────────────────────── │
  │                                                         │
  │  If credentials valid:                                  │
  │  Request proceeds to controller                         │
  │                                                         │
```

**Code flow**:
1. `VisaApiService.invokeAPI()` encodes credentials: `Base64(userId + ":" + password)`
2. Sets header: `Authorization: Basic <encoded>`
3. `SecurityConfig.securityFilterChain()` enables `httpBasic()`
4. Spring's `BasicAuthenticationFilter` intercepts, decodes, and verifies
5. `InMemoryUserDetailsManager` stores the valid user with BCrypt-hashed password

---

## Layer 3: MLE Flow

### What is MLE?

**Message Level Encryption (MLE)** encrypts the actual JSON payload of API requests and responses using JWE (JSON Web Encryption). Even if TLS were compromised, the payload remains encrypted.

### Why MLE on Top of TLS?

```
Without MLE:                          With MLE:

TLS decrypted at                      TLS decrypted at
load balancer/proxy                   load balancer/proxy
     │                                     │
     ▼                                     ▼
{ "amount": "124.05",                 { "encData": "eyJlbmMi..." }
  "pan": "4957...0496" }
     │                                     │
  PLAINTEXT visible to                  STILL ENCRYPTED
  anyone with access to                 Only the application
  the proxy/logs/memory                 with the private key
                                        can read the payload
```

**MLE protects against**:
- TLS termination at load balancers/proxies/CDNs
- Logging systems capturing sensitive payloads
- Memory dumps on intermediate servers
- Man-in-the-middle attacks if TLS is compromised
- Internal network sniffing between services

### JWE Format Deep Dive

The MLE payload is a JWE token in **compact serialization** format with 5 Base64URL-encoded parts separated by dots:

```
eyJlbmMiOi...  .  kM1Qvp3F...  .  YwAALJ...  .  dGVzdC...  .  5eym8T...
     │                │              │             │              │
     │                │              │             │              │
   Header      Encrypted Key    Init Vector    Ciphertext      Auth Tag
```

#### Part 1: JWE Header (JOSE Header)

```json
{
  "alg": "RSA-OAEP-256",
  "enc": "A128GCM",
  "kid": "7f591161-6b5f-4136-80b8-2ae8a44ad9eb",
  "iat": 1771518680214
}
```

| Field | Value | Purpose |
|-------|-------|---------|
| `alg` | `RSA-OAEP-256` | **Key wrapping algorithm**: How the Content Encryption Key (CEK) is encrypted. Uses RSA with OAEP padding and SHA-256 |
| `enc` | `A128GCM` | **Content encryption algorithm**: How the actual payload is encrypted. AES-128 in Galois/Counter Mode |
| `kid` | `7f591161-...` | **Key ID**: Identifies which RSA key pair to use for decryption. Allows key rotation |
| `iat` | `1771518680214` | **Issued At**: Timestamp (milliseconds) when the JWE was created. Prevents replay attacks |

#### Part 2: Encrypted Key

The Content Encryption Key (CEK) -- a random 128-bit AES key -- encrypted with the recipient's RSA public key using RSA-OAEP-256.

#### Part 3: Initialization Vector (IV)

A random 96-bit IV for AES-128-GCM encryption. Ensures the same plaintext produces different ciphertext each time.

#### Part 4: Ciphertext

The actual JSON payload encrypted with AES-128-GCM using the CEK and IV.

#### Part 5: Authentication Tag

A 128-bit GCM authentication tag that ensures the ciphertext hasn't been tampered with (integrity protection).

#### How JWE Encryption Works Internally

```
                    ┌──────────────────────┐
                    │  Generate random CEK  │  (128-bit AES key)
                    │  Generate random IV   │  (96-bit)
                    └──────────┬───────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                                  ▼
┌─────────────────────────┐        ┌─────────────────────────┐
│  RSA-OAEP-256 Encrypt   │        │  AES-128-GCM Encrypt    │
│                         │        │                         │
│  Input: CEK             │        │  Input: JSON payload    │
│  Key: Recipient's RSA   │        │  Key: CEK               │
│        public key       │        │  IV: Random IV          │
│                         │        │                         │
│  Output: Encrypted Key  │        │  Output: Ciphertext     │
│  (Part 2 of JWE)        │        │  + Auth Tag             │
│                         │        │  (Parts 4 & 5 of JWE)   │
└─────────────────────────┘        └─────────────────────────┘
              │                                  │
              ▼                                  ▼
┌─────────────────────────────────────────────────────────────┐
│  JWE Compact Serialization:                                  │
│  Base64URL(Header).Base64URL(EncKey).Base64URL(IV)           │
│  .Base64URL(Ciphertext).Base64URL(AuthTag)                   │
└─────────────────────────────────────────────────────────────┘
```

#### How JWE Decryption Works Internally

```
┌─────────────────────────────────────────────────────────────┐
│  JWE Token: Header.EncKey.IV.Ciphertext.AuthTag              │
└──────────────────────────┬──────────────────────────────────┘
                           │
              ┌────────────┼────────────────┐
              ▼                              ▼
┌─────────────────────────┐    ┌─────────────────────────┐
│  RSA-OAEP-256 Decrypt   │    │                         │
│                         │    │  (wait for CEK)         │
│  Input: Encrypted Key   │    │                         │
│  Key: Recipient's RSA   │    │                         │
│        PRIVATE key      │    │                         │
│                         │    │                         │
│  Output: CEK            │───►│  AES-128-GCM Decrypt    │
│                         │    │                         │
└─────────────────────────┘    │  Input: Ciphertext      │
                               │  Key: CEK               │
                               │  IV: From JWE           │
                               │  Auth Tag: Verify       │
                               │                         │
                               │  Output: JSON payload   │
                               └─────────────────────────┘
```

### MLE Request Encryption Flow

```
visa-client                                              visa-server
     │                                                        │
     │  ┌──────────────────────────────────────────────────┐  │
     │  │ CLIENT: MLEService.encryptPayload()              │  │
     │  │                                                  │  │
     │  │ Input: Plain JSON request                        │  │
     │  │ {"amount":"124.05","senderName":"Mohammed",...}   │  │
     │  │                                                  │  │
     │  │ 1. Build JWE Header:                             │  │
     │  │    alg=RSA-OAEP-256, enc=A128GCM                 │  │
     │  │    kid="7f591161-...", iat=<timestamp>            │  │
     │  │                                                  │  │
     │  │ 2. Load server's MLE public key:                 │  │
     │  │    mle-server-public.pem → RSAPublicKey           │  │
     │  │    (X.509 cert → extract public key)             │  │
     │  │                                                  │  │
     │  │ 3. Encrypt:                                      │  │
     │  │    JWEObject.encrypt(new RSAEncrypter(pubKey))    │  │
     │  │    ● Generate random 128-bit CEK                 │  │
     │  │    ● Encrypt CEK with server's RSA public key    │  │
     │  │    ● Encrypt JSON with AES-128-GCM using CEK     │  │
     │  │                                                  │  │
     │  │ 4. Serialize to compact form:                    │  │
     │  │    "eyJlbmMiOi...kM1Qvp...YwAA...dGVz...5ey..."  │  │
     │  │                                                  │  │
     │  │ 5. Wrap in EncryptedPayload:                     │  │
     │  │    {"encData": "eyJlbmMiOi..."}                  │  │
     │  └──────────────────────────────────────────────────┘  │
     │                                                        │
     │  ─── POST {"encData":"eyJlbmMiOi..."} ─────────────►  │
     │       (over mTLS + Basic Auth)                         │
     │                                                        │
     │  ┌──────────────────────────────────────────────────┐  │
     │  │ SERVER: FundsTransferController                   │  │
     │  │                                                  │  │
     │  │ 1. Receive EncryptedPayload (Jackson deserialize) │  │
     │  │    encData = "eyJlbmMiOi..."                     │  │
     │  │                                                  │  │
     │  │ 2. Call MLEService.decryptPayload(encData)        │  │
     │  │                                                  │  │
     │  │ SERVER: MLEService.decryptPayload()               │  │
     │  │                                                  │  │
     │  │ 3. Parse JWE token:                               │  │
     │  │    JWEObject.parse(jweToken)                      │  │
     │  │    ● Extract header (alg, enc, kid, iat)          │  │
     │  │    ● Extract encrypted key, IV, ciphertext, tag   │  │
     │  │                                                  │  │
     │  │ 4. Load server's MLE private key:                 │  │
     │  │    mle-server-private.pem → PrivateKey             │  │
     │  │    (PKCS#1 PEM → BouncyCastle ASN1 → RSAPrivKey) │  │
     │  │                                                  │  │
     │  │ 5. Decrypt:                                       │  │
     │  │    jweObject.decrypt(new RSADecrypter(privKey))    │  │
     │  │    ● Decrypt CEK with server's RSA private key    │  │
     │  │    ● Verify auth tag (integrity check)           │  │
     │  │    ● Decrypt ciphertext with AES-128-GCM + CEK   │  │
     │  │                                                  │  │
     │  │ 6. Result: Original JSON payload                  │  │
     │  │    {"amount":"124.05","senderName":"Mohammed",...} │  │
     │  └──────────────────────────────────────────────────┘  │
     │                                                        │
```

### MLE Response Encryption Flow

```
visa-client                                              visa-server
     │                                                        │
     │  ┌──────────────────────────────────────────────────┐  │
     │  │ SERVER: FundsTransferController                   │  │
     │  │                                                  │  │
     │  │ 1. Process transaction → build response JSON      │  │
     │  │    {"transactionIdentifier":"381228649430015",    │  │
     │  │     "actionCode":"00","approvalCode":"718777",...}│  │
     │  │                                                  │  │
     │  │ 2. Call MLEService.encryptPayload(responseJson)   │  │
     │  │                                                  │  │
     │  │ SERVER: MLEService.encryptPayload()               │  │
     │  │                                                  │  │
     │  │ 3. Load CLIENT's MLE public key:                  │  │
     │  │    mle-client-public.pem → RSAPublicKey            │  │
     │  │    (Note: uses CLIENT's key, not server's!)       │  │
     │  │                                                  │  │
     │  │ 4. Encrypt response with client's public key      │  │
     │  │    JWEObject.encrypt(new RSAEncrypter(clientPub))  │  │
     │  │                                                  │  │
     │  │ 5. Return EncryptedPayload                        │  │
     │  │    {"encData": "eyJlbmMiOi..."}                   │  │
     │  └──────────────────────────────────────────────────┘  │
     │                                                        │
     │  ◄── HTTP 200 {"encData":"eyJlbmMiOi..."} ──────────  │
     │       (over mTLS channel)                              │
     │                                                        │
     │  ┌──────────────────────────────────────────────────┐  │
     │  │ CLIENT: VisaApiService                            │  │
     │  │                                                  │  │
     │  │ 1. Receive response, deserialize EncryptedPayload │  │
     │  │                                                  │  │
     │  │ 2. Call MLEService.decryptPayload(encData)        │  │
     │  │                                                  │  │
     │  │ CLIENT: MLEService.decryptPayload()               │  │
     │  │                                                  │  │
     │  │ 3. Load CLIENT's MLE private key:                 │  │
     │  │    mle-client-private.pem → PrivateKey             │  │
     │  │    (Note: uses CLIENT's key, not server's!)       │  │
     │  │                                                  │  │
     │  │ 4. Decrypt:                                       │  │
     │  │    jweObject.decrypt(new RSADecrypter(clientPriv)) │  │
     │  │                                                  │  │
     │  │ 5. Result: Original response JSON                 │  │
     │  │    {"transactionIdentifier":"381228649430015",...} │  │
     │  └──────────────────────────────────────────────────┘  │
     │                                                        │
```

### MLE in the Code

#### Client-Side Encryption (`visa-client/service/MLEService.java`)

```java
public String encryptPayload(String plaintext) throws JOSEException {
    // 1. Build JWE header with Visa-required parameters
    JWEHeader.Builder headerBuilder = new JWEHeader.Builder(
        JWEAlgorithm.RSA_OAEP_256,    // Key wrapping: RSA with OAEP + SHA-256
        EncryptionMethod.A128GCM       // Content encryption: AES-128-GCM
    );
    headerBuilder.keyID(keyId);                              // Key identifier
    headerBuilder.customParam("iat", System.currentTimeMillis()); // Issued-at

    // 2. Create JWE object with header + payload
    JWEObject jweObject = new JWEObject(headerBuilder.build(), new Payload(plaintext));

    // 3. Encrypt using recipient's RSA public key
    //    Internally: generate random CEK → RSA-encrypt CEK → AES-encrypt payload
    jweObject.encrypt(new RSAEncrypter(serverPublicKey));

    // 4. Serialize to compact form: header.encKey.iv.ciphertext.tag
    return jweObject.serialize();
}
```

#### Server-Side Decryption (`visa-server/service/MLEService.java`)

```java
public String decryptPayload(String jweToken) throws ParseException, JOSEException {
    // 1. Parse the JWE compact serialization
    JWEObject jweObject = JWEObject.parse(jweToken);

    // 2. Decrypt using server's RSA private key
    //    Internally: RSA-decrypt CEK → verify auth tag → AES-decrypt payload
    jweObject.decrypt(new RSADecrypter(serverPrivateKey));

    // 3. Extract the decrypted plaintext
    return jweObject.getPayload().toString();
}
```

#### RSA Private Key Loading (PKCS#1 format -- Visa Standard)

```java
// PEM file content:
// -----BEGIN RSA PRIVATE KEY-----
// MIIEowIBAAKCAQEA2uVs/WZl...
// -----END RSA PRIVATE KEY-----

// 1. Strip PEM headers and whitespace
String base64Content = pemContent
    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
    .replace("-----END RSA PRIVATE KEY-----", "")
    .replaceAll("\\s", "");

// 2. Base64 decode to DER bytes
byte[] decoded = Base64.getDecoder().decode(base64Content);

// 3. Parse ASN1 structure (PKCS#1 RSA key format)
//    SEQUENCE { version, modulus, publicExponent, privateExponent, ... }
ASN1Sequence sequence = (ASN1Sequence) ASN1Sequence.fromByteArray(decoded);
Enumeration<?> elements = sequence.getObjects();

BigInteger version = ((ASN1Integer) elements.nextElement()).getValue();      // 0
BigInteger modulus = ((ASN1Integer) elements.nextElement()).getValue();       // n
((ASN1Integer) elements.nextElement()).getValue();                           // e (skip)
BigInteger privateExponent = ((ASN1Integer) elements.nextElement()).getValue(); // d

// 4. Create RSA private key from modulus + private exponent
RSAPrivateKeySpec keySpec = new RSAPrivateKeySpec(modulus, privateExponent);
PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);
```

#### RSA Public Key Loading (X.509 Certificate)

```java
// PEM file content:
// -----BEGIN CERTIFICATE-----
// MIIC+zCCAeO...
// -----END CERTIFICATE-----

// 1. Strip PEM headers and decode
byte[] decoded = Base64.getDecoder().decode(base64Content);

// 2. Parse as X.509 certificate and extract public key
Certificate cert = CertificateFactory.getInstance("X.509")
    .generateCertificate(new ByteArrayInputStream(decoded));
RSAPublicKey publicKey = (RSAPublicKey) cert.getPublicKey();
```

---

## Complete Request Lifecycle

Here is the complete lifecycle of a Push Funds API call through all three security layers:

```
CLIENT APPLICATION
       │
       ▼
┌──────────────────────────────────────────────────────────────┐
│  1. BUILD PAYLOAD                                             │
│     {"amount":"124.05","recipientPrimaryAccountNumber":...}   │
└──────────────────────────┬───────────────────────────────────┘
                           │ plain JSON
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  2. MLE ENCRYPT (Layer 3)                                     │
│     MLEService.encryptPayload(json)                           │
│     ● Uses: mle-server-public.pem (server's RSA public key)  │
│     ● JWE: RSA-OAEP-256 + A128GCM                            │
│     ● Output: {"encData": "eyJlbmMiOiJBMTI4R0NNIi..."}      │
└──────────────────────────┬───────────────────────────────────┘
                           │ encrypted JSON
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  3. ADD BASIC AUTH (Layer 2)                                   │
│     Authorization: Basic MVdNMlRUNElIUFhDOERR...             │
│     keyId: 7f591161-6b5f-4136-80b8-2ae8a44ad9eb              │
└──────────────────────────┬───────────────────────────────────┘
                           │ HTTP request
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  4. mTLS HANDSHAKE (Layer 1)                                  │
│     SSLContext → SSLSocketFactory → HttpsURLConnection        │
│     ● Client presents: client-keystore.p12 certificate        │
│     ● Client verifies: server cert against client-truststore  │
│     ● TLS encrypted channel established                       │
└──────────────────────────┬───────────────────────────────────┘
                           │ TLS encrypted
                           ▼
═══════════════════ NETWORK ════════════════════════════════════
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  5. mTLS VERIFICATION (Layer 1)                               │
│     ● Server verifies: client cert against server-truststore  │
│     ● TLS channel established                                 │
└──────────────────────────┬───────────────────────────────────┘
                           │ decrypted at TLS level
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  6. BASIC AUTH CHECK (Layer 2)                                │
│     SecurityConfig → BasicAuthenticationFilter                │
│     ● Decode Authorization header                             │
│     ● Verify userId + BCrypt(password)                        │
│     ● Grant ROLE_API_USER                                     │
└──────────────────────────┬───────────────────────────────────┘
                           │ authenticated request
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  7. MLE DECRYPT (Layer 3)                                     │
│     MLEService.decryptPayload(encData)                        │
│     ● Uses: mle-server-private.pem (server's RSA private key) │
│     ● Decrypts JWE → original JSON                            │
└──────────────────────────┬───────────────────────────────────┘
                           │ plain JSON
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  8. PROCESS TRANSACTION                                       │
│     FundsTransferController.processTransaction()              │
│     ● Generate approval code                                  │
│     ● Mask PAN: 4957030420210496 → 4957****0496              │
│     ● Store in TransactionStore                               │
│     ● Build response JSON                                     │
└──────────────────────────┬───────────────────────────────────┘
                           │ response JSON
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  9. MLE ENCRYPT RESPONSE (Layer 3)                            │
│     MLEService.encryptPayload(responseJson)                   │
│     ● Uses: mle-client-public.pem (CLIENT's RSA public key)  │
│     ● Output: {"encData": "eyJlbmMiOi..."}                   │
└──────────────────────────┬───────────────────────────────────┘
                           │ encrypted response
                           ▼
═══════════════════ NETWORK (TLS) ══════════════════════════════
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  10. CLIENT MLE DECRYPT (Layer 3)                             │
│      MLEService.decryptPayload(encData)                       │
│      ● Uses: mle-client-private.pem (client's RSA private key)│
│      ● Output: original response JSON                         │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
                    CLIENT APPLICATION
                    (displays results)
```

---

## Key Distribution Model

This diagram shows which keys/certs are shared between which parties:

```
┌──────────────────────┐              ┌──────────────────────┐
│      CLIENT          │              │      SERVER          │
│                      │              │                      │
│  Has (private):      │              │  Has (private):      │
│  ● client-keystore   │              │  ● server-keystore   │
│    .p12              │              │    .p12              │
│  ● mle-client-       │              │  ● mle-server-       │
│    private.pem       │              │    private.pem       │
│                      │              │                      │
│  Has (shared):       │              │  Has (shared):       │
│  ● client-truststore │◄── CA cert ──►│  ● server-truststore │
│    .p12              │   (same CA)  │    .p12              │
│  ● mle-server-       │◄── shared ──►│  ● mle-client-       │
│    public.pem        │  (exchanged) │    public.pem        │
│                      │              │                      │
│  Knows:              │              │  Knows:              │
│  ● userId            │◄── same ────►│  ● userId            │
│  ● password          │  credentials │  ● password          │
│  ● keyId             │◄── same ────►│  ● keyId             │
└──────────────────────┘              └──────────────────────┘

Distribution:
  CA cert         → Both truststores (trust anchor)
  MLE server pub  → Shared with client (for request encryption)
  MLE client pub  → Shared with server (for response encryption)
  Private keys    → NEVER shared, stay on their respective machines
  Credentials     → Provisioned independently on both sides
```
