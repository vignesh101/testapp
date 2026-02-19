package com.visa.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VisaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(VisaServerApplication.class, args);
        System.out.println("##########################################################");
        System.out.println("  Visa Server started on https://localhost:8443");
        System.out.println("  mTLS: ENABLED (client certificate required)");
        System.out.println("  MLE:  ENABLED (JWE RSA-OAEP-256 / A128GCM)");
        System.out.println("##########################################################");
    }
}
