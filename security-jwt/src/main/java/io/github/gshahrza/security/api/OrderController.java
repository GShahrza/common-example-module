package io.github.gshahrza.security.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    record Order(long id, String owner, String product, BigDecimal amount, Instant createdAt) {
    }

    record NewOrder(String product, BigDecimal amount) {
    }

    private final AtomicLong ids = new AtomicLong(100);
    private final Map<String, List<Order>> orders = new ConcurrentHashMap<>(Map.of(
            "aynur", new CopyOnWriteArrayList<>(List.of(new Order(1, "aynur", "Kitab", new BigDecimal("25"), Instant.now()))),
            "rashad", new CopyOnWriteArrayList<>(List.of(new Order(2, "rashad", "Telefon", new BigDecimal("900"), Instant.now())))));

    /** Who am I? Everything comes from the token itself; no database lookup. */
    @GetMapping("/api/me")
    Map<String, Object> me(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("username", jwt.getSubject());
        me.put("roles", jwt.getClaimAsStringList("roles"));
        me.put("scope", jwt.getClaimAsString("scope"));
        me.put("authorities", authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList());
        me.put("issuedAt", jwt.getIssuedAt());
        me.put("expiresAt", jwt.getExpiresAt());
        me.put("tokenId", jwt.getId());
        return me;
    }

    /** My orders: the owner is taken from the token, never from a request parameter. */
    @GetMapping("/api/orders")
    List<Order> myOrders(Authentication authentication) {
        return orders.getOrDefault(authentication.getName(), List.of());
    }

    /** Needs the scope orders:write (checked in SecurityConfig). */
    @PostMapping("/api/orders")
    @ResponseStatus(HttpStatus.CREATED)
    Order create(@RequestBody NewOrder request, Authentication authentication) {
        Order order = new Order(ids.incrementAndGet(), authentication.getName(), request.product(), request.amount(), Instant.now());
        orders.computeIfAbsent(authentication.getName(), u -> new CopyOnWriteArrayList<>()).add(order);
        return order;
    }

    /**
     * Someone else's data by URL: /api/users/rashad/orders. Being logged in is not enough; the
     * user must be the owner (or an admin). Forgetting this check is IDOR, one of the most
     * common API vulnerabilities (OWASP API Security #1: Broken Object Level Authorization).
     */
    @GetMapping("/api/users/{username}/orders")
    @PreAuthorize("#username == authentication.name or hasRole('ADMIN')")
    List<Order> ordersOf(@PathVariable String username) {
        return orders.getOrDefault(username, List.of());
    }
}
