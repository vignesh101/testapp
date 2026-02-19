# Visa-like API with mTLS + MLE (Message Level Encryption)

Two Spring Boot applications demonstrating a production-grade Visa-like payment API secured with **Mutual TLS (mTLS)** and **Message Level Encryption (MLE)** using JWE.

### Detailed Documentation

| Document | Description |
|----------|-------------|
| [MTLS-AND-MLE-FLOW.md](MTLS-AND-MLE-FLOW.md) | Deep-dive into mTLS handshake, JWE internals, complete request lifecycle through all 3 security layers |
| [certs/CERTIFICATES.md](certs/CERTIFICATES.md) | Step-by-step certificate generation guide with every OpenSSL/keytool command explained |
| [visa-server/README.md](visa-server/README.md) | Server API reference with sample request/response payloads |
| [visa-client/README.md](visa-client/README.md) | Client code walkthrough and mapping to original Visa sample code |

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Security Layers](#security-layers)
- [End-to-End Flow](#end-to-end-flow)
  - [Push Funds Transaction (OCT)](#1-push-funds-transaction-oct---post)
  - [Transaction Query](#2-transaction-query---get)
- [Certificate & Key Infrastructure](#certificate--key-infrastructure)
- [Project Structure](#project-structure)
- [Code Documentation](#code-documentation)
  - [Server Components](#server-components)
  - [Client Components](#client-components)
- [Configuration Reference](#configuration-reference)
- [How to Run](#how-to-run)
- [Sample Credentials](#sample-credentials)

---

## Architecture Overview

```
┌─────────────────────┐         mTLS (TLS 1.2/1.3)         ┌─────────────────────┐
│                     │  ◄──── Client Cert Authentication ──►│                     │
│    visa-client      │         + Server Cert Verification   │    visa-server      │
│    (Port 8080)      │                                      │    (Port 8443)      │
│                     │         Basic Authentication         │                     │
│  ┌───────────────┐  │  ──── Authorization: Basic xxxxxxx ──►  ┌───────────────┐  │
│  │VisaApiService │  │                                      │  │SecurityConfig │  │
│  └───────┬───────┘  │         MLE Encrypted Request        │  └───────────────┘  │
│          │          │  ──── {"encData": "<JWE token>"} ────►│                     │
│  ┌───────┴───────┐  │                                      │  ┌───────────────┐  │
│  │  MLEService   │  │         MLE Encrypted Response       │  │  MLEService   │  │
│  │  (encrypt/    │  │  ◄── {"encData": "<JWE token>"} ─────│  │  (decrypt/    │  │
│  │   decrypt)    │  │                                      │  │   encrypt)    │  │
│  └───────────────┘  │                                      │  └───────┬───────┘  │
│                     │                                      │          │          │
│  ┌───────────────┐  │                                      │  ┌───────┴───────┐  │
│  │  SSLConfig    │  │                                      │  │FundsTransfer  │  │
│  │  (mTLS)       │  │                                      │  │  Controller   │  │
│  └───────────────┘  │                                      │  └───────┬───────┘  │
│                     │                                      │          │          │
└─────────────────────┘                                      │  ┌───────┴───────┐  │
                                                             │  │Transaction    │  │
                                                             │  │   Store       │  │
                                                             │  └───────────────┘  │
                                                             └─────────────────────┘
```

---

## Security Layers

The system implements **three layers of security**, matching the Visa API pattern:

### Layer 1: Mutual TLS (mTLS) - Transport Security

```
┌──────────┐                              ┌──────────┐
│  Client   │──── Client Certificate ────► │  Server   │
│           │◄─── Server Certificate ───── │           │
│           │                              │           │
│ Keystore: │  Both sides verify each     │ Keystore: │
│ client-   │  other's certificate via    │ server-   │
│ keystore  │  their truststores (both    │ keystore  │
│ .p12      │  trust the same CA)         │ .p12      │
│           │                              │           │
│Truststore:│                              │Truststore:│
│ client-   │                              │ server-   │
│ truststore│                              │ truststore│
│ .p12      │                              │ .p12      │
└──────────┘                              └──────────┘
```

- **What**: Both client and server authenticate each other using X.509 certificates
- **How**: Certificates are signed by the same CA. Each side has a truststore containing the CA cert
- **Config**: `server.ssl.client-auth: need` enforces client certificate on the server

### Layer 2: Basic Authentication - Application Identity

```
Authorization: Basic base64(userId:password)
```

- **What**: Standard HTTP Basic Auth over the mTLS-secured connection
- **How**: Client sends `userId:password` Base64-encoded in the `Authorization` header
- **Config**: Spring Security `InMemoryUserDetailsManager` with BCrypt password encoding

### Layer 3: Message Level Encryption (MLE) - Payload Security

```
┌──────────────────────────────────────────────────────────────┐
│                    JWE Header                                 │
│  {                                                            │
│    "alg": "RSA-OAEP-256",     ← RSA key wrapping algorithm   │
│    "enc": "A128GCM",          ← Content encryption algorithm  │
│    "kid": "7f591161-...",     ← Key identifier                │
│    "iat": 1771518680214       ← Issued-at timestamp           │
│  }                                                            │
├──────────────────────────────────────────────────────────────┤
│                    JWE Payload                                │
│  (The actual JSON request/response, encrypted)                │
└──────────────────────────────────────────────────────────────┘
```

- **What**: Request and response payloads are encrypted using JWE (JSON Web Encryption)
- **Algorithm**: RSA-OAEP-256 (key wrapping) + A128GCM (content encryption)
- **Key Pairs**: Separate RSA key pairs for request encryption and response encryption

#### MLE Key Usage Matrix

| Direction | Encrypt With | Decrypt With |
|-----------|-------------|-------------|
| Client → Server (Request) | Server's MLE public cert (`mle-server-public.pem`) | Server's MLE private key (`mle-server-private.pem`) |
| Server → Client (Response) | Client's MLE public cert (`mle-client-public.pem`) | Client's MLE private key (`mle-client-private.pem`) |

---

## End-to-End Flow

### 1. Push Funds Transaction (OCT) - POST

```
POST /visadirect/fundstransfer/v1/pushfundstransactions
```

```
CLIENT                                                  SERVER
  │                                                       │
  │  Step 1: Build push funds JSON payload                │
  │  {                                                    │
  │    "amount": "124.05",                                │
  │    "recipientPrimaryAccountNumber": "4957...0496",    │
  │    "senderName": "Mohammed Qasim",                    │
  │    ...                                                │
  │  }                                                    │
  │                                                       │
  │  Step 2: MLE Encrypt request                          │
  │  ┌─────────────────────────────────────┐              │
  │  │ MLEService.encryptPayload()         │              │
  │  │ ● Load server's MLE public cert     │              │
  │  │ ● Create JWE header (RSA-OAEP-256,  │              │
  │  │   A128GCM, keyId, iat)              │              │
  │  │ ● Encrypt payload → JWE token       │              │
  │  │ ● Wrap: {"encData": "<JWE>"}        │              │
  │  └─────────────────────────────────────┘              │
  │                                                       │
  │  Step 3: Send over mTLS + Basic Auth                  │
  │  ─────────────────────────────────────────────────►   │
  │  Headers:                                             │
  │    Authorization: Basic <base64(userId:password)>     │
  │    keyId: 7f591161-6b5f-4136-80b8-2ae8a44ad9eb       │
  │    Content-Type: application/json                     │
  │  Body: {"encData": "eyJlbmMiOiJBMTI4R0NNIi..."}     │
  │                                                       │
  │                                 Step 4: mTLS handshake│
  │                                 ● Verify client cert  │
  │                                 ● Basic Auth check    │
  │                                                       │
  │                                 Step 5: MLE Decrypt   │
  │                          ┌──────────────────────────┐ │
  │                          │ MLEService.decryptPayload │ │
  │                          │ ● Parse JWE token        │ │
  │                          │ ● Decrypt with server's  │ │
  │                          │   MLE private key         │ │
  │                          │ ● Return plain JSON       │ │
  │                          └──────────────────────────┘ │
  │                                                       │
  │                                 Step 6: Process txn   │
  │                          ┌──────────────────────────┐ │
  │                          │ processTransaction()      │ │
  │                          │ ● Validate fields         │ │
  │                          │ ● Generate approvalCode   │ │
  │                          │ ● Mask PAN (4957****0496) │ │
  │                          │ ● Store in TransactionStore│
  │                          │ ● Build response JSON     │ │
  │                          └──────────────────────────┘ │
  │                                                       │
  │                                 Step 7: MLE Encrypt   │
  │                          ┌──────────────────────────┐ │
  │                          │ MLEService.encryptPayload │ │
  │                          │ ● Encrypt response with  │ │
  │                          │   client's MLE public cert│ │
  │                          │ ● Wrap: {"encData":"<JWE>"}│
  │                          └──────────────────────────┘ │
  │                                                       │
  │   ◄─────────────────────────────────────────────────  │
  │  HTTP 200                                             │
  │  Body: {"encData": "eyJlbmMiOiJBMTI4R0NNIi..."}     │
  │                                                       │
  │  Step 8: MLE Decrypt response                         │
  │  ┌─────────────────────────────────────┐              │
  │  │ MLEService.decryptPayload()         │              │
  │  │ ● Parse JWE token                   │              │
  │  │ ● Decrypt with client's MLE         │              │
  │  │   private key                        │              │
  │  │ ● Return plain JSON response        │              │
  │  └─────────────────────────────────────┘              │
  │                                                       │
  │  Step 9: Display result                               │
  │  Transaction ID:  381228649430015                     │
  │  Action Code:     00 (Approved)                       │
  │  Approval Code:   718777                              │
  │  Amount:          124.05                              │
  │  Recipient PAN:   4957****0496                        │
  │                                                       │
```

### 2. Transaction Query - GET

```
GET /visadirect/v1/transactionquery?acquiringBIN=408999&transactionIdentifier=381228649430015
```

```
CLIENT                                                  SERVER
  │                                                       │
  │  Step 1: Build query URL with params                  │
  │  (No request body encryption for GET)                 │
  │                                                       │
  │  Step 2: Send over mTLS + Basic Auth                  │
  │  ─────────────────────────────────────────────────►   │
  │  Headers:                                             │
  │    Authorization: Basic <base64(userId:password)>     │
  │    keyId: 7f591161-6b5f-4136-80b8-2ae8a44ad9eb       │
  │                                                       │
  │                                 Step 3: Look up txn   │
  │                          ┌──────────────────────────┐ │
  │                          │ TransactionStore          │ │
  │                          │ .findByIdentifierAndBin() │ │
  │                          └──────────────────────────┘ │
  │                                                       │
  │                                 Step 4: MLE Encrypt   │
  │                                 response              │
  │                                                       │
  │   ◄─────────────────────────────────────────────────  │
  │  HTTP 200                                             │
  │  Body: {"encData": "<JWE encrypted response>"}       │
  │                                                       │
  │  Step 5: MLE Decrypt response                         │
  │  Status:          COMPLETED                           │
  │  Transaction ID:  381228649430015                     │
  │  Action Code:     00                                  │
  │  Original Amount: 124.05                              │
  │                                                       │
```

---

## Certificate & Key Infrastructure

```
                    ┌─────────────┐
                    │  CA (Root)   │
                    │  ca.crt      │
                    └──────┬──────┘
                           │ signs
              ┌────────────┼────────────┐
              ▼                         ▼
     ┌─────────────────┐      ┌─────────────────┐
     │  Server TLS Cert │      │  Client TLS Cert │
     │  server-keystore │      │  client-keystore │
     │  .p12            │      │  .p12            │
     └─────────────────┘      └─────────────────┘
           (mTLS)                    (mTLS)

     ┌─────────────────┐      ┌─────────────────┐
     │  MLE Server Keys │      │  MLE Client Keys │
     │  (Self-signed)   │      │  (Self-signed)   │
     │                  │      │                  │
     │  Private: decrypt│      │  Private: decrypt│
     │    requests      │      │    responses     │
     │  Public: encrypt │      │  Public: encrypt │
     │    requests      │      │    responses     │
     └─────────────────┘      └─────────────────┘
           (MLE)                     (MLE)
```

### Generated Files

| File | Purpose | Used By |
|------|---------|---------|
| `ca.crt` | CA certificate (trust anchor) | Both truststores |
| `server-keystore.p12` | Server TLS identity (cert + private key) | Server (mTLS) |
| `server-truststore.p12` | CA cert to verify client certificates | Server (mTLS) |
| `client-keystore.p12` | Client TLS identity (cert + private key) | Client (mTLS) |
| `client-truststore.p12` | CA cert to verify server certificate | Client (mTLS) |
| `mle-server-private.pem` | RSA private key (PKCS#1) | Server (decrypt requests) |
| `mle-server-public.pem` | X.509 certificate with RSA public key | Client (encrypt requests) |
| `mle-client-private.pem` | RSA private key (PKCS#1) | Client (decrypt responses) |
| `mle-client-public.pem` | X.509 certificate with RSA public key | Server (encrypt responses) |

---

## Project Structure

```
visa-projects/
├── README.md                           ← This file
├── certs/
│   ├── generate-certs.sh               ← Certificate generation script
│   └── (generated .p12 and .pem files)
│
├── visa-server/                        ← Spring Boot Server (Visa API)
│   ├── pom.xml
│   └── src/main/
│       ├── resources/
│       │   └── application.yml         ← Server config (SSL, auth, MLE paths)
│       └── java/com/visa/server/
│           ├── VisaServerApplication.java
│           ├── config/
│           │   └── SecurityConfig.java
│           ├── controller/
│           │   └── FundsTransferController.java
│           ├── service/
│           │   ├── MLEService.java
│           │   └── TransactionStore.java
│           └── model/
│               └── EncryptedPayload.java
│
└── visa-client/                        ← Spring Boot Client
    ├── pom.xml
    └── src/main/
        ├── resources/
        │   └── application.yml         ← Client config (SSL, auth, MLE paths)
        └── java/com/visa/client/
            ├── VisaClientApplication.java
            ├── config/
            │   └── SSLConfig.java
            ├── service/
            │   ├── MLEService.java
            │   └── VisaApiService.java
            └── model/
                └── EncryptedPayload.java
```

---

## Code Documentation

### Server Components

#### `VisaServerApplication.java`
Entry point for the server. Runs on HTTPS port 8443 with mTLS enabled via Spring Boot's embedded Tomcat SSL configuration.

#### `SecurityConfig.java`
Configures Spring Security with:
- **Basic Authentication**: Validates `userId` and `password` from `application.yml`
- **Stateless sessions**: No HTTP session or cookies (REST API pattern)
- **BCrypt password encoding**: Passwords are hashed, not stored in plain text
- **All endpoints secured**: Every request requires authentication

#### `FundsTransferController.java`
REST controller exposing two Visa Direct API endpoints:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/visadirect/fundstransfer/v1/pushfundstransactions` | POST | Push Funds (OCT) - sends money to a recipient |
| `/visadirect/v1/transactionquery` | GET | Queries a previously completed transaction |

**Push Funds flow**:
1. Receives `EncryptedPayload` with `encData` (JWE token)
2. Calls `MLEService.decryptPayload()` to get the plain JSON request
3. Calls `processTransaction()` to simulate Visa processing
4. Generates approval code, masks PAN, stores transaction
5. Calls `MLEService.encryptPayload()` to encrypt the response
6. Returns `EncryptedPayload` with encrypted response

**Transaction Query flow**:
1. Receives `acquiringBIN` and `transactionIdentifier` as query params
2. Looks up the transaction in `TransactionStore`
3. Encrypts and returns the result

#### `MLEService.java` (Server)
Handles JWE encryption and decryption on the server side.

**Key loading** (at startup via `@PostConstruct`):
- `mle-server-private.pem` → `PrivateKey` (for decrypting incoming requests)
- `mle-client-public.pem` → `RSAPublicKey` (for encrypting outgoing responses)

**Supports two PEM formats**:
- **PKCS#1** (`-----BEGIN RSA PRIVATE KEY-----`): Parsed via BouncyCastle ASN1 — this is the Visa standard format
- **PKCS#8** (`-----BEGIN PRIVATE KEY-----`): Parsed via Java's `PKCS8EncodedKeySpec`

**Public key loading**: Reads X.509 PEM certificate, extracts `RSAPublicKey` from it.

#### `TransactionStore.java`
Thread-safe in-memory store using `ConcurrentHashMap`. Stores transactions by `transactionIdentifier` for later query. In production, this would be replaced by a database.

#### `EncryptedPayload.java`
Simple POJO matching the Visa MLE envelope format:
```json
{"encData": "<JWE compact serialization>"}
```

### Client Components

#### `VisaClientApplication.java`
Entry point with a `CommandLineRunner` that demonstrates the full flow:
1. Builds a push funds request payload (same data as the original Visa sample)
2. Calls `VisaApiService.pushFunds()` — encrypts, sends, decrypts
3. Extracts `transactionIdentifier` from the response
4. Calls `VisaApiService.queryTransaction()` — sends query, decrypts response
5. Prints both results

#### `SSLConfig.java`
Programmatically configures the `SSLContext` bean for mTLS:
- **KeyManagerFactory**: Loads `client-keystore.p12` (client's TLS cert + private key)
- **TrustManagerFactory**: Loads `client-truststore.p12` (CA cert to verify server)
- Creates `SSLContext` with both key managers and trust managers

This `SSLContext` bean is injected into `VisaApiService` for HTTPS connections.

#### `MLEService.java` (Client)
Mirror of the server's MLEService but with reversed key usage:

| Operation | Key Used |
|-----------|----------|
| Encrypt outgoing requests | Server's MLE public cert (`mle-server-public.pem`) |
| Decrypt incoming responses | Client's MLE private key (`mle-client-private.pem`) |

Same JWE parameters: RSA-OAEP-256 + A128GCM, with `keyId` and `iat` in the header.

#### `VisaApiService.java`
The main API client service. Mirrors the original Visa `PushFundsAndQueryAPIWithMLE` sample code pattern.

**`pushFunds(String payload)`**:
1. Encrypts the request payload via `MLEService.encryptPayload()`
2. Wraps in `EncryptedPayload` JSON
3. Calls `invokeAPI()` with POST
4. Deserializes response to `EncryptedPayload`
5. Decrypts via `MLEService.decryptPayload()`
6. Returns the plain response as a Map

**`queryTransaction(String acquiringBin, String transactionIdentifier)`**:
1. Builds query string parameters
2. Calls `invokeAPI()` with GET (no request body)
3. Decrypts the encrypted response
4. Returns the plain response as a Map

**`invokeAPI(String resourcePath, String httpMethod, String payload)`**:
1. Opens `HttpURLConnection` to `https://localhost:8443 + resourcePath`
2. Sets `SSLSocketFactory` from the mTLS `SSLContext` bean
3. Sets Basic Auth header: `Authorization: Basic base64(userId:password)`
4. Sets `keyId` header for MLE key identification
5. Sends payload (if POST)
6. Reads and returns the response body

---

## Configuration Reference

### Server (`visa-server/src/main/resources/application.yml`)

```yaml
server:
  port: 8443                    # HTTPS port
  ssl:
    enabled: true
    key-store: file:<path>/server-keystore.p12    # Server's TLS identity
    key-store-password: changeit
    key-store-type: PKCS12
    key-alias: server
    client-auth: need                              # REQUIRE client certificate (mTLS)
    trust-store: file:<path>/server-truststore.p12 # CA cert (trusts client certs)
    trust-store-password: changeit

visa:
  auth:
    user-id: "<userId>"         # Basic Auth credentials
    password: "<password>"

mle:
  key-id: "<uuid>"              # MLE key identifier (sent in JWE header)
  server-private-key-path: <path>/mle-server-private.pem   # Decrypt requests
  client-public-cert-path: <path>/mle-client-public.pem    # Encrypt responses
```

### Client (`visa-client/src/main/resources/application.yml`)

```yaml
visa:
  base-url: "https://localhost:8443"    # Server URL
  auth:
    user-id: "<userId>"                 # Must match server's configured credentials
    password: "<password>"

ssl:
  key-store: <path>/client-keystore.p12        # Client's TLS identity
  trust-store: <path>/client-truststore.p12    # CA cert (trusts server cert)

mle:
  key-id: "<uuid>"
  server-public-cert-path: <path>/mle-server-public.pem    # Encrypt requests
  client-private-key-path: <path>/mle-client-private.pem   # Decrypt responses
```

---

## How to Run

### Prerequisites
- Java 17+
- Maven 3.6+
- OpenSSL (available via Git Bash on Windows)

### Step 1: Generate Certificates

```bash
cd certs
bash generate-certs.sh
```

### Step 2: Update Paths in application.yml

Update the certificate paths in both `visa-server/src/main/resources/application.yml` and `visa-client/src/main/resources/application.yml` to point to your `certs/` directory using absolute paths.

### Step 3: Start the Server

```bash
cd visa-server
mvn spring-boot:run
```

Wait for: `Visa Server started on https://localhost:8443`

### Step 4: Run the Client

```bash
cd visa-client
mvn spring-boot:run
```

Expected output:
```
OCT Response:
  Transaction ID:  381228649430015
  Action Code:     00
  Approval Code:   718777
  Amount:          124.05
  Recipient PAN:   4957****0496

Query Response:
  Status:          COMPLETED
  Transaction ID:  381228649430015
  Action Code:     00
  Original Amount: 124.05

ALL TESTS PASSED - mTLS + MLE working correctly!
```

---

## Sample Credentials

| Credential | Value |
|------------|-------|
| User ID | `1WM2TT4IHPXC8DQ5I3CH21n1rEBGK-Eyv_oLdzE2VZpDqRn_U` |
| Password | `19JRVdej9` |
| MLE Key ID | `7f591161-6b5f-4136-80b8-2ae8a44ad9eb` |
| Keystore Password | `changeit` |
| Acquiring BIN | `408999` |

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Spring Boot Starter Web | 3.2.5 | REST API framework |
| Spring Boot Starter Security | 3.2.5 | Basic Auth + security filters |
| Nimbus JOSE+JWT | 9.37.3 | JWE encryption/decryption (MLE) |
| Bouncy Castle | 1.77 | PKCS#1 RSA private key parsing |
