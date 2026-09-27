package io.github.gshahrza.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The usual way to test authorisation rules: jwt() puts an already validated token into the
 * request, so no login, key or signature is needed. Only the rules are tested.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MethodSecurityTest {

    @Autowired
    MockMvc mvc;

    @Test
    void creatingAnOrderNeedsTheWriteScope() throws Exception {
        String body = "{\"product\":\"Kitab\",\"amount\":25}";

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().jwt(j -> j.subject("aynur")).authorities(new SimpleGrantedAuthority("SCOPE_orders:read"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().jwt(j -> j.subject("aynur")).authorities(new SimpleGrantedAuthority("SCOPE_orders:write"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner").value("aynur"));
    }

    @Test
    void ownershipRuleUsesTheTokenSubject() throws Exception {
        mvc.perform(get("/api/users/rashad/orders").with(jwt().jwt(j -> j.subject("rashad"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/rashad/orders").with(jwt().jwt(j -> j.subject("aynur"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/rashad/orders")
                        .with(jwt().jwt(j -> j.subject("admin")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void rolesClaimBecomesRoleAuthorities() throws Exception {
        // the real converter from SecurityConfig: "roles": ["ADMIN"] → ROLE_ADMIN
        mvc.perform(get("/api/admin/sessions")
                        .with(jwt().jwt(j -> j.subject("admin").claim("roles", java.util.List.of("ADMIN")))
                                .authorities(SecurityConfig.authorities())))
                .andExpect(status().isOk());
    }
}
