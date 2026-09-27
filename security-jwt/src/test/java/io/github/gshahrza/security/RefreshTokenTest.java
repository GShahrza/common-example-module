package io.github.gshahrza.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/** Refresh tokens: storage, the browser cookie, concurrency and limits. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefreshTokenTest {

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;

    RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    ResponseEntity<Map<String, Object>> login(String user, boolean cookie, String device) {
        return http().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.USER_AGENT, device)
                .body(Map.of("username", user, "password", user + "123", "cookie", cookie))
                .retrieve().toEntity(new ParameterizedTypeReference<>() { });
    }

    ResponseEntity<Map<String, Object>> refreshWithBody(String token) {
        return http().post().uri("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refresh_token", token)).retrieve().toEntity(new ParameterizedTypeReference<>() { });
    }

    ResponseEntity<Map<String, Object>> refreshWithCookie(String cookieValue) {
        return http().post().uri("/api/auth/refresh").header(HttpHeaders.COOKIE, "refresh_token=" + cookieValue)
                .retrieve().toEntity(new ParameterizedTypeReference<>() { });
    }

    static String cookieValue(ResponseEntity<?> response) {
        String header = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return header.substring("refresh_token=".length(), header.indexOf(';'));
    }

    static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void onlyTheHashOfTheTokenIsStored() throws Exception {
        String token = (String) login("aynur", false, "test").getBody().get("refresh_token");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM refresh_token WHERE token_hash = ?").param(sha256(token))
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM refresh_token WHERE token_hash = ?").param(token)
                .query(Long.class).single()).isZero();
    }

    @Test
    void browserGetsTheRefreshTokenOnlyAsHttpOnlyCookie() {
        var login = login("aynur", true, "Chrome");

        assertThat(login.getBody()).containsKey("access_token").doesNotContainKey("refresh_token");
        String setCookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth");

        String first = cookieValue(login);
        var refreshed = refreshWithCookie(first);
        assertThat(refreshed.getStatusCode().value()).isEqualTo(200);
        assertThat(refreshed.getBody()).containsKey("access_token").doesNotContainKey("refresh_token");
        assertThat(cookieValue(refreshed)).isNotEqualTo(first);

        var reused = refreshWithCookie(first);
        assertThat(reused.getStatusCode().value()).isEqualTo(401);
        assertThat(reused.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");   // dead cookie removed
    }

    @Test
    void twoRefreshesWithTheSameTokenAtOnceSucceedOnlyOnce() throws Exception {
        String token = (String) login("rashad", false, "test").getBody().get("refresh_token");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return refreshWithBody(token).getStatusCode().value();
                };
                results.add(executor.submit(call));
            }
            start.countDown();
            long ok = 0;
            for (var result : results) {
                ok += result.get() == 200 ? 1 : 0;
            }
            assertThat(ok).isEqualTo(1);
        }
    }

    @Test
    void userSeesAndEndsTheirOwnSessions() {
        var phone = login("admin", false, "Android 15 / ShopApp 3.2");
        var laptop = login("admin", false, "Firefox on Linux");
        String access = (String) laptop.getBody().get("access_token");

        List<Map<String, Object>> sessions = http().get().uri("/api/me/sessions")
                .headers(h -> h.setBearerAuth(access)).retrieve().body(new ParameterizedTypeReference<>() { });
        assertThat(sessions).extracting(s -> s.get("device")).contains("Android 15 / ShopApp 3.2", "Firefox on Linux");

        String phoneFamily = (String) sessions.stream().filter(s -> "Android 15 / ShopApp 3.2".equals(s.get("device")))
                .findFirst().orElseThrow().get("familyId");
        var ended = http().delete().uri("/api/me/sessions/{id}", phoneFamily).headers(h -> h.setBearerAuth(access))
                .retrieve().toBodilessEntity();
        assertThat(ended.getStatusCode().value()).isEqualTo(204);

        assertThat(refreshWithBody((String) phone.getBody().get("refresh_token")).getStatusCode().value()).isEqualTo(401);
        assertThat(refreshWithBody((String) laptop.getBody().get("refresh_token")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void expiredRefreshTokenIsRefused() throws Exception {
        String token = (String) login("aynur", false, "test").getBody().get("refresh_token");
        jdbc.sql("UPDATE refresh_token SET expires_at = DATEADD('MINUTE', -1, CURRENT_TIMESTAMP) WHERE token_hash = ?")
                .param(sha256(token)).update();

        var response = refreshWithBody(token);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).containsEntry("message", "Refresh token expired");
    }

    @Test
    void activeLoginStillEndsAtItsMaximumAge() throws Exception {
        String token = (String) login("aynur", false, "test").getBody().get("refresh_token");
        jdbc.sql("UPDATE refresh_token SET family_expires = DATEADD('MINUTE', -1, CURRENT_TIMESTAMP) WHERE token_hash = ?")
                .param(sha256(token)).update();

        var response = refreshWithBody(token);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat((String) response.getBody().get("message")).contains("maximum age");
    }
}
