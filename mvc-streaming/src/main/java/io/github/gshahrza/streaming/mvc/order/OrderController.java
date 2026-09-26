package io.github.gshahrza.streaming.mvc.order;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final int MAX_COUNT = 1_000_000;

    private final OrderRepository repository;
    private final ExecutorService executor;
    private final JsonMapper jsonMapper;

    public OrderController(OrderRepository repository, ExecutorService streamingExecutor, JsonMapper jsonMapper) {
        this.repository = repository;
        this.executor = streamingExecutor;
        this.jsonMapper = jsonMapper;
    }

    /**
     * For comparison: the classic way. The whole list is built in memory and nothing is sent until the
     * last row is ready, so the first byte arrives only after all pages have been "queried".
     */
    @GetMapping("/api/orders")
    public List<Order> all(@RequestParam(defaultValue = "1000") int count) {
        return repository.stream(validate(count, 50_000)).toList();
    }

    /**
     * Example 3: NDJSON (one JSON object per line) with {@link ResponseBodyEmitter}.
     * Each page is written and flushed as soon as it is read; the browser parses lines as they arrive.
     */
    @GetMapping("/api/orders/stream")
    public ResponseEntity<ResponseBodyEmitter> stream(@RequestParam(defaultValue = "1000") int count) {
        int total = validate(count, MAX_COUNT);
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        executor.execute(() -> {
            try {
                for (List<Order> page : (Iterable<List<Order>>) repository.streamPages(total)::iterator) {
                    // One write per page instead of per row: fewer flushes, same streaming effect
                    String lines = page.stream()
                            .map(jsonMapper::writeValueAsString)
                            .collect(Collectors.joining("\n", "", "\n"));
                    emitter.send(lines);
                }
                emitter.complete();
            } catch (IOException e) {
                log.debug("NDJSON client disconnected: {}", e.getMessage());
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            }
        });
        return ResponseEntity.ok().contentType(NDJSON).body(emitter);
    }

    /**
     * Example 4: file download with {@link StreamingResponseBody}. Rows are written straight to the
     * response output stream, so memory use stays flat even for a million rows and the download starts at once.
     */
    @GetMapping("/api/orders/export.csv")
    public ResponseEntity<StreamingResponseBody> exportCsv(@RequestParam(defaultValue = "100000") int count) {
        int total = validate(count, MAX_COUNT);
        StreamingResponseBody body = out -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            writer.write("id,customer,amount,status,createdAt\n");
            for (List<Order> page : (Iterable<List<Order>>) repository.streamPages(total)::iterator) {
                for (Order o : page) {
                    writer.write(o.id() + "," + csv(o.customer()) + "," + o.amount() + "," + o.status() + ","
                            + o.createdAt() + "\n");
                }
                writer.flush();
            }
            writer.flush();
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("orders.csv").build().toString())
                .body(body);
    }

    private static int validate(int count, int max) {
        if (count < 1 || count > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "count must be between 1 and " + max);
        }
        return count;
    }

    private static String csv(String value) {
        return value.contains(",") || value.contains("\"")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }
}
