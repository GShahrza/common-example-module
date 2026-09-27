package io.github.gshahrza.security.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh tokens are random strings, not JWTs: they must be revocable, so the server stores them
 * anyway. Only a SHA-256 hash is stored: a leaked table does not give usable tokens.
 *
 * Rotation: every refresh returns a NEW token and marks the old one as used. If a used token shows
 * up again, it was copied by someone: the whole family (this login) is revoked, which logs out both
 * the thief and the real user.
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Rotation(String username, String newToken) {
    }

    /** One login = one device, as shown under "active sessions". */
    public record Session(String familyId, String device, Instant startedAt, Instant lastUsedAt, Instant endsAt) {
    }

    public static class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException(String message) {
            super(message);
        }
    }

    private record Row(String username, String familyId, Instant expiresAt, Instant familyExpires, Instant usedAt) {
    }

    private final JdbcClient jdbc;
    private final JwtProperties properties;

    RefreshTokenStore(JdbcClient jdbc, JwtProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** A new login starts a new family. */
    @Transactional
    public String issue(String username, String device) {
        Instant now = now();
        return insert(username, UUID.randomUUID().toString(), device, now, now.plus(properties.sessionMaxAge()));
    }

    /**
     * noRollbackFor: on reuse the family is deleted AND an exception is thrown. Without it the
     * exception would roll the DELETE back and the stolen login would stay valid.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String token) {
        String hash = hash(token);
        Instant now = now();
        // Atomic "check and mark": of two requests with the same token only one gets count = 1
        int updated = jdbc.sql("""
                        UPDATE refresh_token SET used_at = :now
                        WHERE token_hash = :hash AND used_at IS NULL AND expires_at > :now""")
                .param("now", ts(now)).param("hash", hash)
                .update();
        Row row = find(hash).orElseThrow(() -> new InvalidRefreshTokenException("Unknown refresh token"));
        if (updated == 0) {
            if (row.usedAt() != null) {
                revokeFamily(row.familyId());
                log.warn("Refresh token reuse detected for {}: family {} revoked", row.username(), row.familyId());
                throw new InvalidRefreshTokenException("Refresh token was already used; all sessions of this login were revoked");
            }
            throw new InvalidRefreshTokenException("Refresh token expired");
        }
        if (!row.familyExpires().isAfter(now)) {
            revokeFamily(row.familyId());
            throw new InvalidRefreshTokenException("Session reached its maximum age; please log in again");
        }
        String device = jdbc.sql("SELECT device FROM refresh_token WHERE token_hash = ?").param(hash).query(String.class).optional().orElse(null);
        return new Rotation(row.username(), insert(row.username(), row.familyId(), device, now, row.familyExpires()));
    }

    /** Logout: this login (family) ends; other devices of the same user stay logged in. */
    @Transactional
    public void revoke(String token) {
        find(hash(token)).ifPresent(row -> revokeFamily(row.familyId()));
    }

    /** "Log out everywhere", e.g. after a password change or by an admin. */
    @Transactional
    public int revokeAll(String username) {
        return jdbc.sql("DELETE FROM refresh_token WHERE username = ?").param(username).update();
    }

    @Transactional
    public boolean revokeSession(String username, String familyId) {
        return jdbc.sql("DELETE FROM refresh_token WHERE username = ? AND family_id = ?")
                .params(username, familyId).update() > 0;
    }

    public List<Session> sessions(String username) {
        return jdbc.sql("""
                        SELECT family_id, MAX(device) AS device, MIN(created_at) AS started, MAX(created_at) AS last_used,
                               MAX(family_expires) AS ends
                        FROM refresh_token WHERE username = ? AND expires_at > ?
                        GROUP BY family_id ORDER BY last_used DESC""")
                .params(username, ts(now()))
                .query((rs, i) -> new Session(rs.getString("family_id"), rs.getString("device"),
                        rs.getTimestamp("started").toInstant(), rs.getTimestamp("last_used").toInstant(),
                        rs.getTimestamp("ends").toInstant()))
                .list();
    }

    public long activeSessions(String username) {
        return sessions(username).size();
    }

    /** Expired rows are useless: even reuse detection only matters while a token could still be valid. */
    @Scheduled(fixedDelayString = "PT1H")
    @Transactional
    public void deleteExpired() {
        int deleted = jdbc.sql("DELETE FROM refresh_token WHERE expires_at <= ?").param(ts(now())).update();
        if (deleted > 0) {
            log.info("Deleted {} expired refresh tokens", deleted);
        }
    }

    private String insert(String username, String familyId, String device, Instant now, Instant familyExpires) {
        byte[] bytes = new byte[32];   // 256 random bits: cannot be guessed
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = now.plus(properties.refreshTokenTtl());
        jdbc.sql("""
                        INSERT INTO refresh_token (token_hash, username, family_id, device, created_at, expires_at, family_expires)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(hash(token), username, familyId, device == null ? null : device.substring(0, Math.min(200, device.length())),
                        ts(now), ts(expires.isAfter(familyExpires) ? familyExpires : expires), ts(familyExpires))
                .update();
        return token;
    }

    private Optional<Row> find(String hash) {
        return jdbc.sql("SELECT username, family_id, expires_at, family_expires, used_at FROM refresh_token WHERE token_hash = ?")
                .param(hash)
                .query((rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()))
                .optional();
    }

    private void revokeFamily(String familyId) {
        jdbc.sql("DELETE FROM refresh_token WHERE family_id = ?").param(familyId).update();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
