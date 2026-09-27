package io.github.gshahrza.ai.extract;

import java.math.BigDecimal;
import java.util.List;

/** The target type. Spring AI turns it into a JSON schema for the model and parses the answer back. */
public record OrderDraft(
        String customer,
        List<Item> items,
        BigDecimal total,
        String currency,
        String deliveryCity) {

    public record Item(String product, int quantity, BigDecimal unitPrice) {
    }
}
