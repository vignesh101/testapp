package com.visa.server.service;

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
 * Message Level Encryption (MLE) service for the server.
 * Handles:
 * - Decryption of incoming client requests (using server's MLE private key)
 * - Encryption of outgoing responses (using client's MLE public certificate)
 *
 * Uses JWE with RSA-OAEP-256 algorithm and A128GCM encryption (Visa MLE standard).
 */
@Service
public class MLEService {

    private static final Logger log = LoggerFactory.getLogger(MLEService.class);

    @Value("${mle.key-id}")
    private String keyId;

    @Value("${mle.server-private-key-path}")
    private String serverPrivateKeyPath;

    @Value("${mle.client-public-cert-path}")
    private String clientPublicCertPath;

    private PrivateKey serverPrivateKey;
    private RSAPublicKey clientPublicKey;

    @PostConstruct
    public void init() throws Exception {
        log.info("Loading MLE keys...");
        log.info("  Server private key: {}", serverPrivateKeyPath);
        log.info("  Client public cert: {}", clientPublicCertPath);

        this.serverPrivateKey = loadRSAPrivateKey(serverPrivateKeyPath);
        this.clientPublicKey = loadRSAPublicKey(clientPublicCertPath);

        log.info("MLE keys loaded successfully. Key ID: {}", keyId);
    }

    /**
     * Decrypt an incoming JWE payload from the client.
     * The client encrypted this with the server's MLE public certificate.
     * We decrypt it with the server's MLE private key.
     *
     * @param jweToken - JWE compact serialization string
     * @return Decrypted plaintext payload
     */
    public String decryptPayload(String jweToken) throws ParseException, JOSEException {
        log.debug("Decrypting incoming MLE payload...");
        JWEObject jweObject = JWEObject.parse(jweToken);
        jweObject.decrypt(new RSADecrypter(serverPrivateKey));
        String decrypted = jweObject.getPayload().toString();
        log.debug("MLE payload decrypted successfully");
        return decrypted;
    }

    /**
     * Encrypt an outgoing response payload for the client.
     * We encrypt this with the client's MLE public certificate.
     * The client will decrypt it with their MLE private key.
     *
     * @param plaintext - Response payload to encrypt
     * @return JWE compact serialization string
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
        jweObject.encrypt(new RSAEncrypter(clientPublicKey));

        String encrypted = jweObject.serialize();
        log.debug("MLE payload encrypted successfully");
        return encrypted;
    }

    /**
     * Load RSA public key from an X.509 PEM certificate file.
     * Handles format: -----BEGIN CERTIFICATE----- ... -----END CERTIFICATE-----
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
