package io.github.gshahrza.observability.checkout;

import io.github.gshahrza.observability.checkout.CheckoutService.CheckoutRequest;
import io.github.gshahrza.observability.checkout.CheckoutService.CheckoutResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CheckoutController {

    private final CheckoutService service;

    CheckoutController(CheckoutService service) {
        this.service = service;
    }

    /** The trace id is also returned as a header: support can search for it in Grafana. */
    @PostMapping("/api/checkout")
    ResponseEntity<CheckoutResult> checkout(@RequestBody CheckoutRequest request) {
        CheckoutResult result = service.checkout(request);
        return ResponseEntity.ok().header("X-Trace-Id", result.traceId()).body(result);
    }
}
