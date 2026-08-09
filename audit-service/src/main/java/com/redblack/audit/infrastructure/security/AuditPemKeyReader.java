package com.redblack.audit.infrastructure.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class AuditPemKeyReader {
    private AuditPemKeyReader() { }
    static RSAPublicKey readPublic(String path) {
        try {
            if (path == null || path.isBlank()) throw new IllegalStateException("JWT_PUBLIC_KEY_PATH is required");
            String content = Files.readString(Path.of(path)).replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(content)));
        } catch (Exception exception) { throw new IllegalStateException("Unable to read JWT public key", exception); }
    }
}
