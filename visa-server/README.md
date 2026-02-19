# Visa Server - mTLS + MLE

Spring Boot server simulating the Visa Direct API with Mutual TLS and Message Level Encryption.

## API Endpoints

### Push Funds Transaction (OCT)

```
POST /visadirect/fundstransfer/v1/pushfundstransactions
```

**Headers:**
| Header | Value |
|--------|-------|
| `Authorization` | `Basic <base64(userId:password)>` |
| `Content-Type` | `application/json` |
| `keyId` | MLE key identifier |

**Request Body (MLE-encrypted):**
```json
{
  "encData": "<JWE compact serialization>"
}
```

**Decrypted Request Payload:**
```json
{
  "amount": "124.05",
  "recipientPrimaryAccountNumber": "4957030420210496",
  "senderName": "Mohammed Qasim",
  "recipientName": "rohan",
  "acquiringBin": "408999",
  "transactionIdentifier": "381228649430015",
  "businessApplicationId": "AA",
  "merchantCategoryCode": "6012",
  "transactionCurrencyCode": "USD",
  "localTransactionDateTime": "2026-02-19T22:01:19",
  "pointOfServiceData": {
    "panEntryMode": "90",
    "posConditionCode": "00",
    "motoECIIndicator": "0"
  },
  "cardAcceptor": {
    "name": "Visa Inc. USA-Foster City",
    "idCode": "CA-IDCode-77765",
    "terminalId": "TID-9999",
    "address": {
      "country": "USA",
      "state": "CA",
      "zipCode": "94404",
      "county": "San Mateo"
    }
  },
  "senderAccountNumber": "4653459515756154",
  "senderAddress": "901 Metro Center Blvd",
  "senderCity": "Foster City",
  "senderStateCode": "CA",
  "senderCountryCode": "124",
  "sourceOfFundsCode": "05",
  "acquirerCountryCode": "840",
  "retrievalReferenceNumber": "412770451018",
  "systemsTraceAuditNumber": "451018",
  "settlementServiceIndicator": "9"
}
```

**Decrypted Response Payload:**
```json
{
  "transactionIdentifier": "381228649430015",
  "actionCode": "00",
  "approvalCode": "718777",
  "responseCode": "5",
  "transmissionDateTime": "2026-02-19T22:01:20",
  "amount": "124.05",
  "recipientPrimaryAccountNumber": "4957****0496",
  "senderName": "Mohammed Qasim",
  "recipientName": "rohan",
  "merchantCategoryCode": "6012",
  "acquiringBin": "408999",
  "feeProgramIndicator": "123"
}
```

### Transaction Query

```
GET /visadirect/v1/transactionquery?acquiringBIN={bin}&transactionIdentifier={id}
```

**Headers:**
| Header | Value |
|--------|-------|
| `Authorization` | `Basic <base64(userId:password)>` |
| `keyId` | MLE key identifier |

**Decrypted Response Payload:**
```json
{
  "statusIdentifier": "COMPLETED",
  "transactionIdentifier": "381228649430015",
  "acquiringBin": "408999",
  "actionCode": "00",
  "approvalCode": "718777",
  "responseCode": "5",
  "transmissionDateTime": "2026-02-19T22:01:20",
  "originalAmount": "124.05",
  "recipientPrimaryAccountNumber": "4957****0496"
}
```

## Source Files

| File | Description |
|------|-------------|
| `VisaServerApplication.java` | Application entry point, starts HTTPS server on port 8443 |
| `config/SecurityConfig.java` | Spring Security config: Basic Auth, stateless sessions, BCrypt |
| `controller/FundsTransferController.java` | REST endpoints for push funds and transaction query |
| `service/MLEService.java` | JWE encrypt/decrypt using RSA-OAEP-256 + A128GCM |
| `service/TransactionStore.java` | In-memory `ConcurrentHashMap` transaction storage |
| `model/EncryptedPayload.java` | `{"encData": "<JWE>"}` wrapper |

## Transaction Processing

The server simulates Visa transaction processing:

1. Parses the decrypted push funds request
2. Generates a 6-digit approval code
3. Masks the recipient PAN (e.g., `4957030420210496` → `4957****0496`)
4. Stores the transaction in memory for later query
5. Returns action code `00` (approved) with the response details
