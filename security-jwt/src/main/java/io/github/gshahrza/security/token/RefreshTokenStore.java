package io.github.gshahrza.security.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Refresh tokens are random strings, not JWTs: they must be revocable, so the server has to
 * remember them anyway. In production this is a database table or Redis.
 *
 * Rotation: every refresh returns a NEW refresh token and marks the old one as used. If a used
 * token shows up again, it was stolen (or leaked): the whole family (all tokens of that login)
 * is revoked, which logs out both the thief and the real user.
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    record Entry(String username, String family, Instant expiresAt, boolean used) {
    }

    public record Rotation(String username, String newToken) {
    }

    public static class InvalidRefreshTokenException extends RuntimeException {
        InvalidRefreshTokenException(String message) {
            super(message);
        }
    }

    private final JwtProperties properties;
    /** Key: SHA-256 of the token. A leaked table does not reveal usable tokens. */
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    RefreshTokenStore(JwtProperties properties) {
        this.properties = properties;
    }

    /** A new login starts a new family. */
    public String issue(String username) {
        return issue(username, UUID.randomUUID().toString());
    }

    public synchronized Rotation rotate(String token) {
        String hash = hash(token);
        Entry entry = tokens.get(hash);
        if (entry == null) {
            throw new InvalidRefreshTokenException("Unknown refresh token");
        }
        if (entry.used()) {
            revokeFamily(entry.family());
            log.warn("Refresh token reuse detected for {}: family {} revoked", entry.username(), entry.family());
            throw new InvalidRefreshTokenException("Refresh token was already used; all sessions of this login were revoked");
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            tokens.remove(hash);
            throw new InvalidRefreshTokenException("Refresh token expired");
        }
        tokens.put(hash, new Entry(entry.username(), entry.family(), entry.expiresAt(), true));
        return new Rotation(entry.username(), issue(entry.username(), entry.family()));
    }

    /** Logout: this login (family) ends; other devices of the same user stay logged in. */
    public void revoke(String token) {
        Optional.ofNullable(tokens.get(hash(token))).ifPresent(e -> revokeFamily(e.family()));
    }

    /** "Log out everywhere", e.g. after a password change or by an admin. */
    public int revokeAll(String username) {
        int before = tokens.size();
        tokens.values().removeIf(e -> e.username().equals(username));
        return before - tokens.size();
    }

    public long activeSessions(String username) {
        return tokens.values().stream().filter(e -> e.username().equals(username) && !e.used())
                .map(Entry::family).distinct().count();
    }

    private String issue(String username, String family) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(hash(token), new Entry(username, family, Instant.now().plus(properties.refreshTokenTtl()), false));
        return token;
    }

    private void revokeFamily(String family) {
        tokens.values().removeIf(e -> e.family().equals(family));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
