package io.github.gshahrza.ai.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Methods the model may decide to call. Spring AI sends their names, descriptions and parameter
 * schemas to the model; when the model answers "call getOrderStatus(7)", Spring AI runs the Java
 * method and sends the result back to the model, which then writes the final answer.
 *
 * <p>A new instance per request records which tools were called, so the demo page can show it.
 */
public class OrderTools {

    public record ToolCall(String tool, String arguments, String result) {
    }

    private final OrderStore store;
    private final List<ToolCall> calls = Collections.synchronizedList(new ArrayList<>());

    public OrderTools(OrderStore store) {
        this.store = store;
    }

    @Tool(description = "Get an order by its numeric id: customer, product and current status")
    public String getOrder(@ToolParam(description = "The order id, a number between 1 and 1000") long orderId) {
        String result = store.find(orderId)
                .map(o -> "Order " + o.id() + ": customer " + o.customer() + ", product " + o.product()
                        + ", status " + o.status())
                .orElse("Order " + orderId + " does not exist");
        calls.add(new ToolCall("getOrder", "orderId=" + orderId, result));
        return result;
    }

    @Tool(description = "Cancel an order. Only orders with status NEW or PAID can be cancelled")
    public String cancelOrder(@ToolParam(description = "The order id to cancel") long orderId) {
        String result = store.find(orderId)
                .map(o -> switch (o.status()) {
                    case NEW, PAID -> {
                        store.save(new OrderStore.Order(o.id(), o.customer(), o.product(), OrderStore.Status.CANCELLED));
                        yield "Order " + orderId + " was cancelled";
                    }
                    default -> "Order " + orderId + " cannot be cancelled, its status is " + o.status();
                })
                .orElse("Order " + orderId + " does not exist");
        calls.add(new ToolCall("cancelOrder", "orderId=" + orderId, result));
        return result;
    }

    public List<ToolCall> calls() {
        return List.copyOf(calls);
    }
}
