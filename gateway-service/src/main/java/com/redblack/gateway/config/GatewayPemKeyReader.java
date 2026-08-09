package com.redblack.gateway.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class GatewayPemKeyReader {
    private GatewayPemKeyReader() {
    }

    static RSAPublicKey readPublic(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("JWT_PUBLIC_KEY_PATH must point to an X.509 RSA public key");
        }
        try {
            String pem = Files.readString(Path.of(path));
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read JWT public key", exception);
        }
    }
}
