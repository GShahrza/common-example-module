package io.github.gshahrza.security.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * RS256: the private key signs (only this service has it), the public key verifies (anyone can
 * have it, see /.well-known/jwks.json). Other services can validate our tokens without sharing
 * a secret, which HS256 (one shared secret) would require.
 */
@Configuration
class JwtKeyConfig {

    @Bean
    RSAKey rsaKey(JwtProperties properties) throws Exception {
        RSAPublicKey publicKey;
        RSAPrivateKey privateKey;
        if (properties.privateKey() != null && !properties.privateKey().isBlank()) {
            publicKey = RsaKeyConverters.x509().convert(stream(properties.publicKey()));
            privateKey = RsaKeyConverters.pkcs8().convert(stream(properties.privateKey()));
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            publicKey = (RSAPublicKey) pair.getPublic();
            privateKey = (RSAPrivateKey) pair.getPrivate();
        }
        // kid: lets verifiers pick the right key during key rotation (old and new key both published)
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(UUID.randomUUID().toString()).build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey rsaKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    /** Checks signature, exp/nbf (with 60 s clock skew) and that we are the issuer. */
    @Bean
    JwtDecoder jwtDecoder(RSAKey rsaKey, JwtProperties properties) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static ByteArrayInputStream stream(String pem) {
        return new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));
    }
}
