package io.github.gshahrza.r2dbc.order;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data R2DBC maps a record to a row. There is no JPA here: no lazy loading, no
 * @OneToMany, no dirty checking. productId is just a column; joins are written by hand.
 */
@Table("orders")
public record Order(@Id Long id, String customer, Long productId, int quantity, BigDecimal amount,
                    String status, Instant createdAt) {
}
