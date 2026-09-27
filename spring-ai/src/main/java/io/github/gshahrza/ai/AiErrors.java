package io.github.gshahrza.ai;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/** The most common error in this module: Ollama is not running or the model is still downloading. */
@RestControllerAdvice
public class AiErrors {

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, String>> ollamaUnavailable(ResourceAccessException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "Cannot reach Ollama. Is it running on the configured base-url?",
                "detail", String.valueOf(e.getMessage())));
    }
}
