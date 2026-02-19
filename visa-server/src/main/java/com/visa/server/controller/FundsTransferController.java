package com.visa.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visa.server.model.EncryptedPayload;
import com.visa.server.service.MLEService;
import com.visa.server.service.TransactionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Visa Direct Funds Transfer API controller.
 * Simulates the Visa Push Funds Transaction (OCT) and Transaction Query APIs.
 *
 * All request/response payloads are MLE-encrypted using JWE.
 */
@RestController
public class FundsTransferController {

    private static final Logger log = LoggerFactory.getLogger(FundsTransferController.class);

    private final MLEService mleService;
    private final TransactionStore transactionStore;
    private final ObjectMapper objectMapper;

    public FundsTransferController(MLEService mleService, TransactionStore transactionStore, ObjectMapper objectMapper) {
        this.mleService = mleService;
        this.transactionStore = transactionStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Push Funds Transaction (OCT) - POST
     *
     * Flow:
     * 1. Receive MLE-encrypted request: {"encData": "<JWE>"}
     * 2. Decrypt the JWE to get the push funds request JSON
     * 3. Process the transaction (simulate approval)
     * 4. Create response JSON
     * 5. Encrypt response as JWE
     * 6. Return MLE-encrypted response: {"encData": "<JWE>"}
     */
    @PostMapping("/visadirect/fundstransfer/v1/pushfundstransactions")
    public ResponseEntity<EncryptedPayload> pushFunds(
            @RequestBody EncryptedPayload encryptedRequest,
            @RequestHeader("keyId") String keyId) {

        try {
            log.info("=== Push Funds Transaction (OCT) ===");
            log.info("Received MLE-encrypted request with keyId: {}", keyId);
            log.info("Encrypted request (JWE): {}", encryptedRequest.getEncData());

            // Step 1: Decrypt the incoming request
            String decryptedRequest = mleService.decryptPayload(encryptedRequest.getEncData());
            log.info("Decrypted request payload: {}", decryptedRequest);

            // Step 2: Parse the request
            Map<String, Object> requestData = objectMapper.readValue(
                    decryptedRequest, new TypeReference<Map<String, Object>>() {});

            // Step 3: Process the transaction (simulate Visa processing)
            Map<String, Object> responseData = processTransaction(requestData);

            // Step 4: Serialize the response
            String responseJson = objectMapper.writeValueAsString(responseData);
            log.info("Response payload: {}", responseJson);

            // Step 5: Encrypt the response
            String encryptedResponse = mleService.encryptPayload(responseJson);
            log.info("Response encrypted successfully");

            return ResponseEntity.ok(new EncryptedPayload(encryptedResponse));

        } catch (Exception e) {
            log.error("Error processing push funds transaction", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Transaction Query API - GET
     *
     * Flow:
     * 1. Receive query parameters (acquiringBIN, transactionIdentifier)
     * 2. Look up the transaction
     * 3. Create response JSON
     * 4. Encrypt response as JWE
     * 5. Return MLE-encrypted response: {"encData": "<JWE>"}
     */
    @GetMapping("/visadirect/v1/transactionquery")
    public ResponseEntity<EncryptedPayload> queryTransaction(
            @RequestParam("acquiringBIN") String acquiringBin,
            @RequestParam("transactionIdentifier") String transactionIdentifier,
            @RequestHeader("keyId") String keyId) {

        try {
            log.info("=== Transaction Query ===");
            log.info("Query: acquiringBIN={}, transactionIdentifier={}", acquiringBin, transactionIdentifier);

            // Look up the transaction
            Map<String, Object> txnData = transactionStore.findByIdentifierAndBin(
                    transactionIdentifier, acquiringBin);

            Map<String, Object> responseData = new LinkedHashMap<>();
            if (txnData != null) {
                responseData.put("statusIdentifier", "COMPLETED");
                responseData.put("transactionIdentifier", transactionIdentifier);
                responseData.put("acquiringBin", acquiringBin);
                responseData.put("actionCode", "00");
                responseData.put("approvalCode", txnData.get("approvalCode"));
                responseData.put("responseCode", "5");
                responseData.put("transmissionDateTime", txnData.get("transmissionDateTime"));
                responseData.put("originalAmount", txnData.get("amount"));
                responseData.put("recipientPrimaryAccountNumber",
                        maskPan(String.valueOf(txnData.get("recipientPrimaryAccountNumber"))));
            } else {
                responseData.put("statusIdentifier", "NOT_FOUND");
                responseData.put("transactionIdentifier", transactionIdentifier);
                responseData.put("acquiringBin", acquiringBin);
                responseData.put("errorMessage", "Transaction not found");
            }

            // Encrypt the response
            String responseJson = objectMapper.writeValueAsString(responseData);
            log.info("Query response payload: {}", responseJson);

            String encryptedResponse = mleService.encryptPayload(responseJson);
            return ResponseEntity.ok(new EncryptedPayload(encryptedResponse));

        } catch (Exception e) {
            log.error("Error processing transaction query", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Simulate Visa transaction processing.
     * In production, this would connect to the payment network.
     */
    private Map<String, Object> processTransaction(Map<String, Object> request) {
        String transactionIdentifier = String.valueOf(
                request.getOrDefault("transactionIdentifier", generateTransactionId()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("transactionIdentifier", transactionIdentifier);
        response.put("actionCode", "00");
        response.put("approvalCode", generateApprovalCode());
        response.put("responseCode", "5");
        response.put("transmissionDateTime",
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));
        response.put("amount", request.get("amount"));
        response.put("recipientPrimaryAccountNumber",
                maskPan(String.valueOf(request.get("recipientPrimaryAccountNumber"))));
        response.put("senderName", request.get("senderName"));
        response.put("recipientName", request.get("recipientName"));
        response.put("merchantCategoryCode", request.get("merchantCategoryCode"));
        response.put("acquiringBin", request.get("acquiringBin"));
        response.put("feeProgramIndicator", "123");

        // Store transaction for query
        Map<String, Object> storedData = new LinkedHashMap<>(response);
        storedData.putAll(request);
        storedData.put("approvalCode", response.get("approvalCode"));
        storedData.put("transmissionDateTime", response.get("transmissionDateTime"));
        transactionStore.save(transactionIdentifier, storedData);

        log.info("Transaction processed: id={}, actionCode=00 (approved)", transactionIdentifier);
        return response;
    }

    private String generateTransactionId() {
        return String.valueOf(100000000000000L + new Random().nextLong(900000000000000L));
    }

    private String generateApprovalCode() {
        return String.format("%06d", new Random().nextInt(999999));
    }

    private String maskPan(String pan) {
        if (pan != null && pan.length() > 8) {
            return pan.substring(0, 4) + "****" + pan.substring(pan.length() - 4);
        }
        return pan;
    }
}
