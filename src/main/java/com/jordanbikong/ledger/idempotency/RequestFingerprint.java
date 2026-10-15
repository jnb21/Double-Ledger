package com.jordanbikong.ledger.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * SHA-256 of the endpoint plus the parsed request. Hashing the parsed DTO
 * instead of the raw bytes means a retry that only differs in whitespace or
 * field order still counts as the same request.
 */
@Component
public class RequestFingerprint {

    private final JsonMapper json;

    public RequestFingerprint(JsonMapper json) {
        this.json = json;
    }

    public String of(String endpoint, Object request) {
        String canonical = endpoint + "\n" + (request == null ? "" : json.writeValueAsString(request));
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
