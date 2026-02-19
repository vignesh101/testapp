package com.visa.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory transaction store.
 * Stores completed push funds transactions for later query.
 */
@Service
public class TransactionStore {

    private static final Logger log = LoggerFactory.getLogger(TransactionStore.class);

    // Key: transactionIdentifier, Value: transaction data as Map
    private final ConcurrentHashMap<String, Map<String, Object>> transactions = new ConcurrentHashMap<>();

    public void save(String transactionIdentifier, Map<String, Object> transactionData) {
        transactions.put(transactionIdentifier, transactionData);
        log.info("Transaction stored: {}", transactionIdentifier);
    }

    public Map<String, Object> findByIdentifierAndBin(String transactionIdentifier, String acquiringBin) {
        Map<String, Object> txn = transactions.get(transactionIdentifier);
        if (txn != null) {
            String storedBin = String.valueOf(txn.get("acquiringBin"));
            if (storedBin.equals(acquiringBin)) {
                return txn;
            }
        }
        return null;
    }
}
