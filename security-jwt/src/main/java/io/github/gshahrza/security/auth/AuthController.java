package io.github.gshahrza.security.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.github.gshahrza.security.token.RefreshTokenStore;
import io.github.gshahrza.security.token.RefreshTokenStore.InvalidRefreshTokenException;
import io.github.gshahrza.security.token.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues tokens. In a larger system this is a separate authorization server (Keycloak,
 * Spring Authorization Server, Auth0...) and the API services only validate tokens.
 */
@RestController
class AuthController {

    record Login(@NotBlank String username, @NotBlank String password) {
    }

    record Refresh(@NotBlank @JsonProperty("refresh_token") String refreshToken) {
    }

    /** Field names as in OAuth 2.0 (RFC 6749), so standard client libraries understand them. */
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("token_type") String tokenType,
                         @JsonProperty("expires_in") long expiresIn,
                         @JsonProperty("refresh_token") String refreshToken) {
    }

    private final AuthenticationManager authenticationManager;
    private final UserDetailsService users;
    private final TokenService tokens;
    private final RefreshTokenStore refreshTokens;
    private final RSAKey rsaKey;

    AuthController(AuthenticationManager authenticationManager, UserDetailsService users, TokenService tokens,
                   RefreshTokenStore refreshTokens, RSAKey rsaKey) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.rsaKey = rsaKey;
    }

    @PostMapping("/api/auth/login")
    TokenResponse login(@Valid @RequestBody Login login) {
        Authentication auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password()));
        return new TokenResponse(tokens.accessToken(auth.getName(), auth.getAuthorities()), "Bearer",
                tokens.accessTokenSeconds(), refreshTokens.issue(auth.getName()));
    }

    /** New access token + new refresh token; the old refresh token cannot be used again. */
    @PostMapping("/api/auth/refresh")
    TokenResponse refresh(@Valid @RequestBody Refresh refresh) {
        var rotation = refreshTokens.rotate(refresh.refreshToken());
        // Roles are read again: a user whose role was removed does not keep it until the refresh token expires
        var user = users.loadUserByUsername(rotation.username());
        return new TokenResponse(tokens.accessToken(user.getUsername(), user.getAuthorities()), "Bearer",
                tokens.accessTokenSeconds(), rotation.newToken());
    }

    /** The access token stays valid until it expires (max 15 min): that is the price of stateless tokens. */
    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody Refresh refresh) {
        refreshTokens.revoke(refresh.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** Public keys only: other services verify our tokens with this (spring.security.oauth2.resourceserver.jwt.jwk-set-uri). */
    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return new JWKSet(rsaKey).toJSONObject(true);
    }

    /** Same message for "no such user" and "wrong password": do not tell attackers which usernames exist. */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Map<String, String>> badCredentials(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid_grant", "message", "Invalid username or password"));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<Map<String, String>> badRefresh(InvalidRefreshTokenException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_grant", "message", e.getMessage()));
    }
}
