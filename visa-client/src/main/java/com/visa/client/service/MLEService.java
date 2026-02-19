package com.visa.client.service;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSAEncrypter;
import jakarta.annotation.PostConstruct;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.text.ParseException;
import java.util.Base64;
import java.util.Enumeration;

/**
 * Message Level Encryption (MLE) service for the client.
 * Handles:
 * - Encryption of outgoing requests (using server's MLE public certificate)
 * - Decryption of incoming responses (using client's MLE private key)
 *
 * Uses JWE with RSA-OAEP-256 algorithm and A128GCM encryption (Visa MLE standard).
 */
@Service
public class MLEService {

    private static final Logger log = LoggerFactory.getLogger(MLEService.class);

    @Value("${mle.key-id}")
    private String keyId;

    @Value("${mle.server-public-cert-path}")
    private String serverPublicCertPath;

    @Value("${mle.client-private-key-path}")
    private String clientPrivateKeyPath;

    private RSAPublicKey serverPublicKey;
    private PrivateKey clientPrivateKey;

    @PostConstruct
    public void init() throws Exception {
        log.info("Loading MLE keys...");
        log.info("  Server public cert: {}", serverPublicCertPath);
        log.info("  Client private key: {}", clientPrivateKeyPath);

        this.serverPublicKey = loadRSAPublicKey(serverPublicCertPath);
        this.clientPrivateKey = loadRSAPrivateKey(clientPrivateKeyPath);

        log.info("MLE keys loaded successfully. Key ID: {}", keyId);
    }

    /**
     * Encrypt outgoing request payload for the server.
     * Uses the server's MLE public certificate.
     * The server will decrypt with its MLE private key.
     *
     * @param plaintext - Request payload to encrypt
     * @return JSON string with encData field: {"encData": "<JWE>"}
     */
    public String encryptPayload(String plaintext) throws JOSEException {
        log.debug("Encrypting outgoing MLE payload...");

        JWEHeader.Builder headerBuilder = new JWEHeader.Builder(
                JWEAlgorithm.RSA_OAEP_256,
                EncryptionMethod.A128GCM
        );
        headerBuilder.keyID(keyId);
        headerBuilder.customParam("iat", System.currentTimeMillis());

        JWEObject jweObject = new JWEObject(headerBuilder.build(), new Payload(plaintext));
        jweObject.encrypt(new RSAEncrypter(serverPublicKey));

        String jweToken = jweObject.serialize();
        log.debug("MLE payload encrypted successfully");
        return jweToken;
    }

    /**
     * Decrypt incoming response payload from the server.
     * The server encrypted this with the client's MLE public certificate.
     * We decrypt with the client's MLE private key.
     *
     * @param jweToken - JWE compact serialization string
     * @return Decrypted plaintext payload
     */
    public String decryptPayload(String jweToken) throws ParseException, JOSEException {
        log.debug("Decrypting incoming MLE payload...");
        JWEObject jweObject = JWEObject.parse(jweToken);
        jweObject.decrypt(new RSADecrypter(clientPrivateKey));
        String decrypted = jweObject.getPayload().toString();
        log.debug("MLE payload decrypted successfully");
        return decrypted;
    }

    public String getKeyId() {
        return keyId;
    }

    /**
     * Load RSA public key from an X.509 PEM certificate file.
     */
    private RSAPublicKey loadRSAPublicKey(String certPath) throws CertificateException, IOException {
        String pemContent = Files.readString(new File(certPath).toPath(), StandardCharsets.UTF_8);
        String base64Content = pemContent
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(base64Content);
        Certificate cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(decoded));
        return (RSAPublicKey) cert.getPublicKey();
    }

    /**
     * Load RSA private key from a PEM file.
     * Supports both formats:
     * - PKCS#1: -----BEGIN RSA PRIVATE KEY----- (parsed via BouncyCastle ASN1)
     * - PKCS#8: -----BEGIN PRIVATE KEY----- (parsed via PKCS8EncodedKeySpec)
     */
    private PrivateKey loadRSAPrivateKey(String keyPath) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String pemContent = Files.readString(new File(keyPath).toPath(), StandardCharsets.UTF_8);

        if (pemContent.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            // PKCS#1 format - parse via BouncyCastle ASN1 (Visa standard format)
            String base64Content = pemContent
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(base64Content);

            ASN1Sequence sequence = (ASN1Sequence) ASN1Sequence.fromByteArray(decoded);
            Enumeration<?> elements = sequence.getObjects();

            BigInteger version = ((ASN1Integer) elements.nextElement()).getValue();
            if (version.intValue() != 0 && version.intValue() != 1) {
                throw new IllegalArgumentException("Wrong version for RSA private key: " + version);
            }

            BigInteger modulus = ((ASN1Integer) elements.nextElement()).getValue();
            ((ASN1Integer) elements.nextElement()).getValue(); // publicExponent
            BigInteger privateExponent = ((ASN1Integer) elements.nextElement()).getValue();

            RSAPrivateKeySpec keySpec = new RSAPrivateKeySpec(modulus, privateExponent);
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } else {
            // PKCS#8 format
            String base64Content = pemContent
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(base64Content);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        }
    }
}
