# Visa Client - mTLS + MLE

Spring Boot client that calls the Visa-like API server with Mutual TLS and Message Level Encryption. Mirrors the original Visa `PushFundsAndQueryAPIWithMLE` sample code pattern.

## How It Works

The client runs as a `CommandLineRunner` that executes two API calls:

```
1. Push Funds (POST)  ──►  Encrypt request  ──►  mTLS + Basic Auth  ──►  Decrypt response
2. Transaction Query (GET)  ──►  mTLS + Basic Auth  ──►  Decrypt response
```

## Source Files

| File | Description |
|------|-------------|
| `VisaClientApplication.java` | Entry point + `CommandLineRunner` demo (builds payload, calls APIs, prints results) |
| `config/SSLConfig.java` | Creates `SSLContext` bean with client keystore + CA truststore for mTLS |
| `service/VisaApiService.java` | API caller: mTLS connection, Basic Auth, MLE encrypt/decrypt |
| `service/MLEService.java` | JWE encrypt/decrypt using RSA-OAEP-256 + A128GCM |
| `model/EncryptedPayload.java` | `{"encData": "<JWE>"}` wrapper |

## Key Classes

### SSLConfig

Creates an `SSLContext` for mTLS:
- `KeyManagerFactory` loaded from `client-keystore.p12` (client identity)
- `TrustManagerFactory` loaded from `client-truststore.p12` (trusts server's CA)
- The `SSLContext` bean is injected into `VisaApiService`

### VisaApiService

Core API client that mirrors the original Visa sample code:

```java
// Push Funds: encrypt → send → decrypt
public Map<String, Object> pushFunds(String payload)

// Transaction Query: send → decrypt
public Map<String, Object> queryTransaction(String acquiringBin, String txnId)

// Low-level HTTP call with mTLS + Basic Auth
private String invokeAPI(String path, String method, String body)
```

**`invokeAPI()` flow:**
1. Opens `HttpURLConnection` to `https://localhost:8443`
2. Sets `SSLSocketFactory` from the injected `SSLContext` (mTLS)
3. Sets `Authorization: Basic <credentials>` header
4. Sets `keyId` header for MLE key identification
5. Sends the request body (for POST)
6. Returns the response body

### MLEService

Handles JWE operations:
- **`encryptPayload(String)`**: Encrypts request with server's MLE public cert → JWE token
- **`decryptPayload(String)`**: Decrypts response JWE token with client's MLE private key

Supports both PKCS#1 (`BEGIN RSA PRIVATE KEY`) and PKCS#8 (`BEGIN PRIVATE KEY`) PEM formats.

## Comparison with Original Visa Code

| Original Visa Sample | This Client |
|----------------------|-------------|
| `VISA_BASE_URL = "https://sandbox.api.visa.com"` | `visa.base-url = "https://localhost:8443"` |
| `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` | `ssl.key-store` / `ssl.key-store-password` (in YAML) |
| `USER_ID` / `PASSWORD` constants | `visa.auth.user-id` / `visa.auth.password` (in YAML) |
| `MLE_CLIENT_PRIVATE_KEY_PATH` | `mle.client-private-key-path` (in YAML) |
| `MLE_SERVER_PUBLIC_CERTIFICATE_PATH` | `mle.server-public-cert-path` (in YAML) |
| `KEY_ID` constant | `mle.key-id` (in YAML) |
| `getEncryptedPayload()` static method | `MLEService.encryptPayload()` Spring bean |
| `getDecryptedPayload()` static method | `MLEService.decryptPayload()` Spring bean |
| `invokeAPI()` static method | `VisaApiService.invokeAPI()` with injected `SSLContext` |
| `main()` method | `CommandLineRunner` bean |
