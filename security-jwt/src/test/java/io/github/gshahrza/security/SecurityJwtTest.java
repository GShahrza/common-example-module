package io.github.gshahrza.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.github.gshahrza.security.token.TokenService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.client.RestClient;

/** Real HTTP, real tokens: the whole login → call → refresh cycle. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityJwtTest {

    @LocalServerPort
    int port;
    @Autowired
    TokenService tokenService;

    RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    Map<String, Object> login(String username, String password) {
        return http().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "password", password))
                .retrieve().body(new ParameterizedTypeReference<>() { });
    }

    String token(String username) {
        return (String) login(username, username + "123").get("access_token");
    }

    ResponseEntity<String> get(String uri, String token) {
        return http().get().uri(uri).headers(h -> {
            if (token != null) {
                h.setBearerAuth(token);
            }
        }).retrieve().toEntity(String.class);
    }

    ResponseEntity<Map<String, Object>> refresh(String refreshToken) {
        return http().post().uri("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refresh_token", refreshToken)).retrieve().toEntity(new ParameterizedTypeReference<>() { });
    }

    @Test
    void withoutTokenTheApiAnswers401WithBearerChallenge() {
        var response = get("/api/me", null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).startsWith("Bearer");
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() {
        assertThat(login("aynur", "wrong")).containsEntry("message", "Invalid username or password");
        assertThat(login("nobody", "wrong")).containsEntry("message", "Invalid username or password");
    }

    @Test
    void loginReturnsOAuthStyleTokensAndTheApiReadsTheClaims() {
        Map<String, Object> tokens = login("aynur", "aynur123");

        assertThat(tokens).containsEntry("token_type", "Bearer").containsEntry("expires_in", 900);
        assertThat((String) tokens.get("access_token")).matches("[\\w-]+\\.[\\w-]+\\.[\\w-]+");   // header.payload.signature
        var me = get("/api/me", (String) tokens.get("access_token"));
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody()).contains("\"username\":\"aynur\"", "ROLE_USER", "SCOPE_orders:write");
    }

    @Test
    void usersSeeOnlyTheirOwnDataAdminsSeeEverything() {
        String aynur = token("aynur");
        String admin = token("admin");

        assertThat(get("/api/users/aynur/orders", aynur).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/users/rashad/orders", aynur).getStatusCode().value()).isEqualTo(403);
        assertThat(get("/api/users/rashad/orders", admin).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void adminEndpointsRequireTheAdminRole() {
        assertThat(get("/api/admin/sessions", token("aynur")).getStatusCode().value()).isEqualTo(403);
        assertThat(get("/api/admin/sessions", token("admin")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void changingThePayloadBreaksTheSignature() {
        String[] parts = token("aynur").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"roles\":[\"USER\"]", "\"roles\":[\"USER\",\"ADMIN\"]");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        var response = get("/api/admin/sessions", forged);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).contains("invalid_token");
    }

    @Test
    void expiredTokenIsRejected() {
        String expired = tokenService.accessToken("aynur", List.of("USER"), "orders:read",
                Instant.now().minus(Duration.ofHours(1)), Duration.ofMinutes(15));

        var response = get("/api/me", expired);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).contains("expired");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("attacker").generate();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(otherKey)));
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder().issuer("http://localhost:8090").subject("admin").claim("roles", List.of("ADMIN"))
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build())).getTokenValue();

        assertThat(get("/api/admin/sessions", token).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void refreshRotatesAndReusingAnOldTokenRevokesTheWholeLogin() {
        String first = (String) login("rashad", "rashad123").get("refresh_token");

        var rotated = refresh(first);
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        String second = (String) rotated.getBody().get("refresh_token");
        assertThat(second).isNotEqualTo(first);
        assertThat(get("/api/me", (String) rotated.getBody().get("access_token")).getStatusCode().value()).isEqualTo(200);

        // the old token again: someone copied it → the whole family is revoked
        assertThat(refresh(first).getStatusCode().value()).isEqualTo(401);
        assertThat(refresh(second).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void logoutEndsTheRefreshToken() {
        String refreshToken = (String) login("aynur", "aynur123").get("refresh_token");

        http().post().uri("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refresh_token", refreshToken)).retrieve().toBodilessEntity();

        assertThat(refresh(refreshToken).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void jwksPublishesOnlyThePublicKey() {
        String jwks = get("/.well-known/jwks.json", null).getBody();

        assertThat(jwks).contains("\"kty\":\"RSA\"", "\"n\":", "\"e\":");
        assertThat(jwks).doesNotContain("\"d\":", "\"p\":", "\"q\":");   // private key parts
    }
}
