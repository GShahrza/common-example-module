package io.github.gshahrza.streaming.grpc.gateway;

import io.github.gshahrza.streaming.grpc.proto.Order;
import java.time.Instant;

/** JSON shape for the browser. Protobuf messages are converted at the edge of the system. */
public record OrderDto(long id, String customer, String amount, String status, String createdAt) {

    static OrderDto from(Order o) {
        return new OrderDto(o.getId(), o.getCustomer(), o.getAmount(), o.getStatus().name(),
                Instant.ofEpochSecond(o.getCreatedAt().getSeconds()).toString());
    }
}
