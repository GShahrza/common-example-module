package io.github.gshahrza.security.api;

import io.github.gshahrza.security.token.RefreshTokenStore;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Everything under /api/admin requires ROLE_ADMIN (SecurityConfig). */
@RestController
class AdminController {

    private final RefreshTokenStore refreshTokens;

    AdminController(RefreshTokenStore refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @GetMapping("/api/admin/sessions")
    List<Map<String, Object>> sessions() {
        return List.of("aynur", "rashad", "admin").stream()
                .map(u -> Map.<String, Object>of("username", u, "activeLogins", refreshTokens.activeSessions(u)))
                .toList();
    }

    /** Log a user out everywhere: their refresh tokens stop working; access tokens expire within 15 min. */
    @PostMapping("/api/admin/users/{username}/revoke")
    Map<String, Object> revoke(@PathVariable String username) {
        return Map.of("username", username, "revokedTokens", refreshTokens.revokeAll(username));
    }
}
