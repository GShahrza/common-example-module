package io.github.gshahrza.streaming.grpc.gateway;

import io.github.gshahrza.streaming.grpc.proto.OrderServiceGrpc;
import io.grpc.ManagedChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * gRPC client side. The channel "orders" is configured in application.yml
 * (spring.grpc.client.channel.orders.target); stubs are generated from the .proto file.
 */
@Configuration(proxyBeanMethods = false)
public class GrpcClientConfig {

    @Bean
    ManagedChannel ordersChannel(GrpcChannelFactory channels) {
        return channels.createChannel("orders");
    }

    /** Blocking stub: simple synchronous calls, streams come back as an Iterator. */
    @Bean
    OrderServiceGrpc.OrderServiceBlockingStub orderBlockingStub(ManagedChannel ordersChannel) {
        return OrderServiceGrpc.newBlockingStub(ordersChannel);
    }

    /** Async stub: needed for client streaming and bidirectional streaming. */
    @Bean
    OrderServiceGrpc.OrderServiceStub orderAsyncStub(ManagedChannel ordersChannel) {
        return OrderServiceGrpc.newStub(ordersChannel);
    }
}
