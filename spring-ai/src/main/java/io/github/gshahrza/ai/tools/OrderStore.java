package io.github.gshahrza.ai.tools;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** A tiny in-memory "orders database" the model can query and change through tools. */
@Component
public class OrderStore {

    public enum Status { NEW, PAID, SHIPPED, DELIVERED, CANCELLED }

    public record Order(long id, String customer, String product, Status status) {
    }

    private static final String[] CUSTOMERS = {"Aysel", "Murad", "Leyla", "Kamran", "Nigar", "Tural", "Sevda", "Elvin"};
    private static final String[] PRODUCTS = {"Laptop", "Phone", "Headphones", "Monitor", "Keyboard"};
    private static final Status[] INITIAL = {Status.NEW, Status.PAID, Status.SHIPPED, Status.DELIVERED};

    private final Map<Long, Order> changed = new ConcurrentHashMap<>();

    public Optional<Order> find(long id) {
        if (id < 1 || id > 1000) {
            return Optional.empty();
        }
        return Optional.of(changed.getOrDefault(id, new Order(id, CUSTOMERS[(int) (id % CUSTOMERS.length)],
                PRODUCTS[(int) (id % PRODUCTS.length)], INITIAL[(int) (id % INITIAL.length)])));
    }

    public void save(Order order) {
        changed.put(order.id(), order);
    }
}
