package com.visa.client.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visa.client.model.EncryptedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Service for calling the Visa-like API server.
 * Handles:
 * - mTLS connection setup via SSLContext
 * - Basic Authentication
 * - MLE encryption of requests and decryption of responses
 *
 * Mirrors the original Visa PushFundsAndQueryAPIWithMLE pattern.
 */
@Service
public class VisaApiService {

    private static final Logger log = LoggerFactory.getLogger(VisaApiService.class);

    private final SSLContext sslContext;
    private final MLEService mleService;
    private final ObjectMapper objectMapper;

    @Value("${visa.base-url}")
    private String visaBaseUrl;

    @Value("${visa.auth.user-id}")
    private String userId;

    @Value("${visa.auth.password}")
    private String password;

    public VisaApiService(SSLContext sslContext, MLEService mleService, ObjectMapper objectMapper) {
        this.sslContext = sslContext;
        this.mleService = mleService;
        this.objectMapper = objectMapper;
    }

    /**
     * Push Funds Transaction (OCT) - sends money to a recipient.
     *
     * Flow:
     * 1. Build the push funds request JSON
     * 2. Encrypt request payload using MLE (server's public key)
     * 3. Send POST request over mTLS with Basic Auth
     * 4. Receive MLE-encrypted response
     * 5. Decrypt response using MLE (client's private key)
     *
     * @param pushFundsPayload - JSON string of the push funds request
     * @return Decrypted response as a Map
     */
    public Map<String, Object> pushFunds(String pushFundsPayload) throws Exception {
        log.info("##########################################################");
        log.info("  Push Funds Transaction (OCT)");
        log.info("##########################################################");

        // Step 1: Encrypt the request payload
        String encryptedPayload = mleService.encryptPayload(pushFundsPayload);
        String requestBody = objectMapper.writeValueAsString(new EncryptedPayload(encryptedPayload));
        log.info("Request encrypted successfully");

        // Step 2: Send the request via mTLS
        String encryptedResponseStr = invokeAPI(
                "/visadirect/fundstransfer/v1/pushfundstransactions",
                "POST",
                requestBody
        );

        // Step 3: Decrypt the response
        EncryptedPayload encryptedResponse = objectMapper.readValue(encryptedResponseStr, EncryptedPayload.class);
        log.info("Encrypted Response: {}", encryptedResponse.getEncData().substring(0, 50) + "...");

        String decryptedResponse = mleService.decryptPayload(encryptedResponse.getEncData());
        log.info("Decrypted Response: {}", decryptedResponse);

        return objectMapper.readValue(decryptedResponse, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Transaction Query - queries a previously completed transaction.
     *
     * Flow:
     * 1. Send GET request with query params over mTLS with Basic Auth
     * 2. Receive MLE-encrypted response
     * 3. Decrypt response using MLE (client's private key)
     *
     * @param acquiringBin - Acquiring BIN
     * @param transactionIdentifier - Transaction identifier from push funds response
     * @return Decrypted response as a Map
     */
    public Map<String, Object> queryTransaction(String acquiringBin, String transactionIdentifier) throws Exception {
        log.info("##########################################################");
        log.info("  Transaction Query");
        log.info("##########################################################");

        String queryParams = "acquiringBIN=" + acquiringBin +
                "&transactionIdentifier=" + transactionIdentifier;

        // Send GET request (no encrypted request body for GET)
        String encryptedResponseStr = invokeAPI(
                "/visadirect/v1/transactionquery?" + queryParams,
                "GET",
                null
        );

        // Decrypt the response
        EncryptedPayload encryptedResponse = objectMapper.readValue(encryptedResponseStr, EncryptedPayload.class);
        log.info("Encrypted Response: {}", encryptedResponse.getEncData().substring(0, 50) + "...");

        String decryptedResponse = mleService.decryptPayload(encryptedResponse.getEncData());
        log.info("Decrypted Response: {}", decryptedResponse);

        return objectMapper.readValue(decryptedResponse, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Invoke the Visa API with mTLS and Basic Auth.
     * This method mirrors the original Visa sample code pattern.
     *
     * @param resourcePath - API resource path (e.g., /visadirect/fundstransfer/v1/pushfundstransactions)
     * @param httpMethod - HTTP method (GET, POST)
     * @param payload - Request body (null for GET requests)
     * @return Response body as string
     */
    private String invokeAPI(String resourcePath, String httpMethod, String payload) throws Exception {
        String url = visaBaseUrl + resourcePath;
        log.info("Calling API: {} {}", httpMethod, url);

        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();

        // Configure mTLS
        if (con instanceof HttpsURLConnection httpsConn) {
            httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
        }

        // Configure request
        con.setRequestMethod(httpMethod);
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("keyId", mleService.getKeyId());

        // Basic Authentication
        byte[] encodedAuth = Base64.getEncoder().encode(
                (userId + ":" + password).getBytes(StandardCharsets.UTF_8));
        con.setRequestProperty("Authorization", "Basic " + new String(encodedAuth));

        // Send payload if present
        if (payload != null && !payload.isBlank()) {
            con.setDoOutput(true);
            con.setDoInput(true);
            try (OutputStream os = con.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
        }

        // Read response
        int status = con.getResponseCode();
        log.info("HTTP Status: {}", status);

        BufferedReader reader;
        if (status == 200) {
            reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
        } else {
            reader = new BufferedReader(new InputStreamReader(con.getErrorStream()));
            log.error("API call failed with status: {}", status);
        }

        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line);
        }
        reader.close();
        con.disconnect();

        return content.toString();
    }
}
