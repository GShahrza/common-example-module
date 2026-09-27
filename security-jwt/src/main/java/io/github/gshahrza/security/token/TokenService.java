package io.github.gshahrza.security.token;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    TokenService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /**
     * The access token carries everything a service needs to authorise a request, so no
     * database or session lookup is needed. Keep it small and never put secrets in it:
     * the payload is only Base64, anyone holding the token can read it.
     */
    public String accessToken(String username, Collection<? extends GrantedAuthority> authorities) {
        List<String> roles = authorities.stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).toList();
        return accessToken(username, roles, scopesFor(roles), Instant.now(), properties.accessTokenTtl());
    }

    /** Also used by tests to create expired or otherwise special tokens. */
    public String accessToken(String username, List<String> roles, String scope, Instant issuedAt, java.time.Duration ttl) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(username)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .claim("scope", scope)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessTokenSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    /** What the token allows. Roles say who you are, scopes say what this token may do. */
    static String scopesFor(List<String> roles) {
        return roles.contains("ADMIN") ? "orders:read orders:write users:admin" : "orders:read orders:write";
    }
}
