package io.github.gshahrza.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity   // @PreAuthorize on controller/service methods
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        var entryPoint = new BearerTokenAuthenticationEntryPoint();
        var accessDenied = new BearerTokenAccessDeniedHandler();
        http
                // No cookies, no session: the browser does not send the token by itself, so CSRF
                // (a forged request riding on the user's cookie) is not possible here.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/.well-known/jwks.json").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/orders").hasAuthority("SCOPE_orders:write")
                        .anyRequest().authenticated())      // deny by default: a new endpoint is never public by accident
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(authoritiesFromJwt())))
                // 401/403 keep the standard WWW-Authenticate header and get a JSON body for mobile/SPA clients
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> {
                            entryPoint.commence(request, response, ex);
                            json(response, "unauthorized", ex.getMessage());
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            accessDenied.handle(request, response, ex);
                            json(response, "forbidden", "You do not have permission for this resource");
                        }));
        return http.build();
    }

    /**
     * Turns the JWT into Spring Security authorities:
     *   "scope": "orders:read orders:write"  → SCOPE_orders:read, SCOPE_orders:write
     *   "roles": ["ADMIN"]                    → ROLE_ADMIN (so hasRole("ADMIN") works)
     */
    static JwtAuthenticationConverter authoritiesFromJwt() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities());
        return converter;
    }

    static Converter<Jwt, Collection<GrantedAuthority>> authorities() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        return jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            }
            return authorities;
        };
    }

    /** Demo users. Passwords are stored as BCrypt hashes ({bcrypt}$2a$...), never in plain text. */
    @Bean
    UserDetailsService users(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("aynur").password(encoder.encode("aynur123")).roles("USER").build(),
                User.withUsername("rashad").password(encoder.encode("rashad123")).roles("USER").build(),
                User.withUsername("admin").password(encoder.encode("admin123")).roles("USER", "ADMIN").build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();   // bcrypt by default
    }

    /** Checks username + password at login. Used only by /api/auth/login, not on every request. */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${cors.allowed-origins}") List<String> origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setExposedHeaders(List.of("WWW-Authenticate"));
        cors.setAllowCredentials(true);   // lets a SPA on another origin send the refresh cookie
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private static void json(HttpServletResponse response, String error, String message) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String safe = String.valueOf(message).replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + safe + "\"}");
    }
}
