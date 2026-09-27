package io.github.gshahrza.security.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.github.gshahrza.security.token.JwtProperties;
import io.github.gshahrza.security.token.RefreshTokenStore;
import io.github.gshahrza.security.token.RefreshTokenStore.InvalidRefreshTokenException;
import io.github.gshahrza.security.token.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues tokens. In a larger system this is a separate authorization server (Keycloak,
 * Spring Authorization Server, Auth0...) and the API services only validate tokens.
 *
 * Two ways to carry the refresh token:
 *   mobile apps: in the JSON body, stored by the app in Keychain / Keystore;
 *   browsers:    in an HttpOnly cookie ("cookie": true at login), so JavaScript never sees it
 *                and an XSS bug cannot steal it.
 */
@RestController
class AuthController {

    static final String COOKIE = "refresh_token";
    /** The cookie is sent only to the auth endpoints, not with every API call. */
    static final String COOKIE_PATH = "/api/auth";

    /** cookie is optional (mobile apps do not send it): Boolean, not boolean. */
    record Login(@NotBlank String username, @NotBlank String password, Boolean cookie) {
    }

    /** refresh_token may be missing from the body when it comes in the cookie. */
    record Refresh(@JsonProperty("refresh_token") String refreshToken) {
    }

    /** Field names as in OAuth 2.0 (RFC 6749), so standard client libraries understand them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("token_type") String tokenType,
                         @JsonProperty("expires_in") long expiresIn,
                         @JsonProperty("refresh_token") String refreshToken) {
    }

    private final AuthenticationManager authenticationManager;
    private final UserDetailsService users;
    private final TokenService tokens;
    private final RefreshTokenStore refreshTokens;
    private final JwtProperties properties;
    private final RSAKey rsaKey;

    AuthController(AuthenticationManager authenticationManager, UserDetailsService users, TokenService tokens,
                   RefreshTokenStore refreshTokens, JwtProperties properties, RSAKey rsaKey) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
        this.rsaKey = rsaKey;
    }

    @PostMapping("/api/auth/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody Login login,
                                        @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String device) {
        Authentication auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password()));
        String refreshToken = refreshTokens.issue(auth.getName(), device);
        return respond(tokens.accessToken(auth.getName(), auth.getAuthorities()), refreshToken, Boolean.TRUE.equals(login.cookie()));
    }

    /**
     * New access token + new refresh token; the old refresh token cannot be used again.
     * The answer uses the same channel as the request: cookie in, cookie out.
     */
    @PostMapping("/api/auth/refresh")
    ResponseEntity<TokenResponse> refresh(@RequestBody(required = false) Refresh body,
                                          @CookieValue(name = COOKIE, required = false) String cookie) {
        boolean fromBody = body != null && body.refreshToken() != null;
        String token = fromBody ? body.refreshToken() : cookie;
        if (token == null || token.isBlank()) {
            throw new InvalidRefreshTokenException("No refresh token");
        }
        var rotation = refreshTokens.rotate(token);
        // Roles are read again: a user whose role was removed does not keep it until the login ends
        var user = users.loadUserByUsername(rotation.username());
        return respond(tokens.accessToken(user.getUsername(), user.getAuthorities()), rotation.newToken(), !fromBody);
    }

    /** The access token stays valid until it expires (max 15 min): that is the price of stateless tokens. */
    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(@RequestBody(required = false) Refresh body,
                                @CookieValue(name = COOKIE, required = false) String cookie) {
        String token = body != null && body.refreshToken() != null ? body.refreshToken() : cookie;
        if (token != null) {
            refreshTokens.revoke(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    /** "Where am I logged in?": one entry per login (device), like in Google or Facebook settings. */
    @GetMapping("/api/me/sessions")
    List<RefreshTokenStore.Session> sessions(Authentication authentication) {
        return refreshTokens.sessions(authentication.getName());
    }

    /** Log out one device (e.g. a lost phone). */
    @DeleteMapping("/api/me/sessions/{familyId}")
    ResponseEntity<Void> endSession(@PathVariable String familyId, Authentication authentication) {
        return refreshTokens.revokeSession(authentication.getName(), familyId)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
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

    /** A refused refresh also clears the cookie, so the browser does not keep sending a dead token. */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<Map<String, String>> badRefresh(InvalidRefreshTokenException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .body(Map.of("error", "invalid_grant", "message", e.getMessage()));
    }

    private ResponseEntity<TokenResponse> respond(String accessToken, String refreshToken, boolean inCookie) {
        TokenResponse body = new TokenResponse(accessToken, "Bearer", tokens.accessTokenSeconds(), inCookie ? null : refreshToken);
        var response = ResponseEntity.ok();
        if (inCookie) {
            response.header(HttpHeaders.SET_COOKIE, cookie(refreshToken, properties.refreshTokenTtl()).toString());
        }
        return response.body(body);
    }

    /**
     * HttpOnly: invisible to JavaScript. Secure: HTTPS only (browsers treat http://localhost as secure).
     * SameSite=Strict: not sent with requests started from other sites, which blocks CSRF on /refresh.
     */
    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Strict")
                .path(COOKIE_PATH).maxAge(maxAge)
                .build();
    }
}
