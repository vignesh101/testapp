package com.visa.client.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;

/**
 * SSL/TLS configuration for mTLS (Mutual TLS) client connections.
 * Configures:
 * - Client keystore (client certificate + private key for client authentication)
 * - Truststore (CA certificate to verify server's certificate)
 */
@Configuration
public class SSLConfig {

    private static final Logger log = LoggerFactory.getLogger(SSLConfig.class);

    @Value("${ssl.key-store}")
    private String keyStorePath;

    @Value("${ssl.key-store-password}")
    private String keyStorePassword;

    @Value("${ssl.key-store-type}")
    private String keyStoreType;

    @Value("${ssl.trust-store}")
    private String trustStorePath;

    @Value("${ssl.trust-store-password}")
    private String trustStorePassword;

    @Value("${ssl.trust-store-type}")
    private String trustStoreType;

    @Bean
    public SSLContext sslContext() throws Exception {
        log.info("Configuring mTLS SSLContext...");
        log.info("  Client keystore: {}", keyStorePath);
        log.info("  Truststore:      {}", trustStorePath);

        // Load client keystore (contains client cert + private key)
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);
        try (FileInputStream fis = new FileInputStream(keyStorePath)) {
            keyStore.load(fis, keyStorePassword.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, keyStorePassword.toCharArray());

        // Load truststore (contains CA cert to verify server)
        KeyStore trustStore = KeyStore.getInstance(trustStoreType);
        try (FileInputStream fis = new FileInputStream(trustStorePath)) {
            trustStore.load(fis, trustStorePassword.toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        // Create SSL context with client auth + server trust
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        log.info("mTLS SSLContext configured successfully");
        return sslContext;
    }
}
