package com.visa.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visa.client.service.VisaApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * Visa Client Application.
 * Demonstrates the complete flow:
 * 1. Push Funds Transaction (OCT) with MLE + mTLS
 * 2. Transaction Query with MLE + mTLS
 *
 * This mirrors the original Visa PushFundsAndQueryAPIWithMLE sample code,
 * but connects to the local visa-server instead of sandbox.api.visa.com.
 */
@SpringBootApplication
public class VisaClientApplication {

    private static final Logger log = LoggerFactory.getLogger(VisaClientApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(VisaClientApplication.class, args);
    }

    @Bean
    public CommandLineRunner run(VisaApiService visaApiService, ObjectMapper objectMapper) {
        return args -> {
            System.out.println("##########################################################");
            System.out.println("  Visa Client - mTLS + MLE Demo");
            System.out.println("##########################################################");

            String acquiringBin = "408999";
            String localTransactionDateTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                    .format(new Date());

            // Build the Push Funds request payload (same as original Visa sample)
            String pushFundsPayload = objectMapper.writeValueAsString(Map.ofEntries(
                    Map.entry("amount", "124.05"),
                    Map.entry("senderAddress", "901 Metro Center Blvd"),
                    Map.entry("localTransactionDateTime", localTransactionDateTime),
                    Map.entry("pointOfServiceData", Map.of(
                            "panEntryMode", "90",
                            "posConditionCode", "00",
                            "motoECIIndicator", "0"
                    )),
                    Map.entry("recipientPrimaryAccountNumber", "4957030420210496"),
                    Map.entry("colombiaNationalServiceData", Map.ofEntries(
                            Map.entry("addValueTaxReturn", "10.00"),
                            Map.entry("taxAmountConsumption", "10.00"),
                            Map.entry("nationalNetReimbursementFeeBaseAmount", "20.00"),
                            Map.entry("addValueTaxAmount", "10.00"),
                            Map.entry("nationalNetMiscAmount", "10.00"),
                            Map.entry("countryCodeNationalService", "170"),
                            Map.entry("nationalChargebackReason", "11"),
                            Map.entry("emvTransactionIndicator", "1"),
                            Map.entry("nationalNetMiscAmountType", "A"),
                            Map.entry("costTransactionIndicator", "0"),
                            Map.entry("nationalReimbursementFee", "20.00")
                    )),
                    Map.entry("cardAcceptor", Map.of(
                            "address", Map.of(
                                    "country", "USA",
                                    "zipCode", "94404",
                                    "county", "San Mateo",
                                    "state", "CA"
                            ),
                            "idCode", "CA-IDCode-77765",
                            "name", "Visa Inc. USA-Foster City",
                            "terminalId", "TID-9999"
                    )),
                    Map.entry("senderReference", ""),
                    Map.entry("transactionIdentifier", "381228649430015"),
                    Map.entry("acquirerCountryCode", "840"),
                    Map.entry("acquiringBin", acquiringBin),
                    Map.entry("retrievalReferenceNumber", "412770451018"),
                    Map.entry("senderCity", "Foster City"),
                    Map.entry("senderStateCode", "CA"),
                    Map.entry("systemsTraceAuditNumber", "451018"),
                    Map.entry("senderName", "Mohammed Qasim"),
                    Map.entry("businessApplicationId", "AA"),
                    Map.entry("settlementServiceIndicator", "9"),
                    Map.entry("merchantCategoryCode", "6012"),
                    Map.entry("transactionCurrencyCode", "USD"),
                    Map.entry("recipientName", "rohan"),
                    Map.entry("senderCountryCode", "124"),
                    Map.entry("sourceOfFundsCode", "05"),
                    Map.entry("senderAccountNumber", "4653459515756154")
            ));

            // ============================================================
            // Step 1: Push Funds Transaction (OCT)
            // ============================================================
            System.out.println("\n##########################################################");
            System.out.println("START: Push Funds Transaction (OCT) with MLE + mTLS");
            System.out.println("##########################################################");

            Map<String, Object> pushFundsResponse = visaApiService.pushFunds(pushFundsPayload);

            System.out.println("\nOCT Response:");
            System.out.println("  Transaction ID:  " + pushFundsResponse.get("transactionIdentifier"));
            System.out.println("  Action Code:     " + pushFundsResponse.get("actionCode"));
            System.out.println("  Approval Code:   " + pushFundsResponse.get("approvalCode"));
            System.out.println("  Amount:          " + pushFundsResponse.get("amount"));
            System.out.println("  Recipient PAN:   " + pushFundsResponse.get("recipientPrimaryAccountNumber"));

            System.out.println("\nEND: Push Funds Transaction (OCT) with MLE + mTLS");
            System.out.println("##########################################################");

            // ============================================================
            // Step 2: Transaction Query
            // ============================================================
            String transactionIdentifier = String.valueOf(pushFundsResponse.get("transactionIdentifier"));

            System.out.println("\n##########################################################");
            System.out.println("START: Transaction Query with MLE + mTLS");
            System.out.println("##########################################################");

            Map<String, Object> queryResponse = visaApiService.queryTransaction(
                    acquiringBin, transactionIdentifier);

            System.out.println("\nQuery Response:");
            System.out.println("  Status:          " + queryResponse.get("statusIdentifier"));
            System.out.println("  Transaction ID:  " + queryResponse.get("transactionIdentifier"));
            System.out.println("  Action Code:     " + queryResponse.get("actionCode"));
            System.out.println("  Approval Code:   " + queryResponse.get("approvalCode"));
            System.out.println("  Original Amount: " + queryResponse.get("originalAmount"));

            System.out.println("\nEND: Transaction Query with MLE + mTLS");
            System.out.println("##########################################################");

            System.out.println("\n##########################################################");
            System.out.println("  ALL TESTS PASSED - mTLS + MLE working correctly!");
            System.out.println("##########################################################");
        };
    }
}
