package com.visa.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wrapper for MLE-encrypted payloads.
 * Matches the Visa MLE format: {"encData": "<JWE compact serialization>"}
 */
public class EncryptedPayload {

    @JsonProperty("encData")
    private String encData;

    public EncryptedPayload() {
    }

    public EncryptedPayload(String encData) {
        this.encData = encData;
    }

    public String getEncData() {
        return encData;
    }

    public void setEncData(String encData) {
        this.encData = encData;
    }
}
