package io.github.gshahrza.observability.inventory;

import io.github.gshahrza.observability.Hop;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Plays the "inventory service". */
@RestController
class InventoryController {

    private final InventoryService inventory;
    private final Tracer tracer;

    InventoryController(InventoryService inventory, Tracer tracer) {
        this.inventory = inventory;
        this.tracer = tracer;
    }

    @PostMapping("/api/inventory/{sku}/reserve")
    ResponseEntity<Hop> reserve(@PathVariable String sku, @RequestParam int quantity) {
        long start = System.nanoTime();
        boolean reserved = inventory.reserve(sku, quantity);
        Hop hop = Hop.of(tracer, "inventory", start, reserved ? "reserved" : "out of stock");
        return ResponseEntity.status(reserved ? HttpStatus.OK : HttpStatus.CONFLICT).body(hop);
    }

    @GetMapping("/api/inventory")
    Map<String, Integer> levels() {
        return inventory.levels();
    }
}
