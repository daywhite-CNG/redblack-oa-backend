package com.redblack.identity.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class PemKeyReader {
    private PemKeyReader() {
    }

    static RSAPrivateKey readPrivate(String path) {
        try {
            byte[] bytes = decode(path, "PRIVATE KEY");
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load JWT private key", exception);
        }
    }

    static RSAPublicKey readPublic(String path) {
        try {
            byte[] bytes = decode(path, "PUBLIC KEY");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load JWT public key", exception);
        }
    }

    private static byte[] decode(String path, String label) throws Exception {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("JWT key path must be configured");
        }
        String pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII)
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(pem);
    }
}
