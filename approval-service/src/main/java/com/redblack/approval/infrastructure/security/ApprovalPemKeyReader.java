package com.redblack.approval.infrastructure.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class ApprovalPemKeyReader {
    private ApprovalPemKeyReader() {
    }

    static RSAPublicKey readPublic(String path) {
        try {
            if (path == null || path.isBlank()) {
                throw new IllegalStateException("JWT_PUBLIC_KEY_PATH is required");
            }
            String pem = Files.readString(Path.of(path));
            byte[] encoded = decode(pem, "PUBLIC KEY");
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read JWT public key", exception);
        }
    }

    private static byte[] decode(String pem, String type) {
        String content = pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(content);
    }
}
