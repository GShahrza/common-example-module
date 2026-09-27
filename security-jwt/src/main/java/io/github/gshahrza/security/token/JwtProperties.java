package io.github.gshahrza.security.token;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jwt")
public record JwtProperties(String issuer, Duration accessTokenTtl, Duration refreshTokenTtl, Duration sessionMaxAge,
                            Duration clockSkew, String privateKey, String publicKey) {
}
