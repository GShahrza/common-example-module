package io.github.gshahrza.ai;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.web.client.ResourceAccessException;

/**
 * The model is checked (and downloaded if missing) at startup, so the app does not start without Ollama.
 * Instead of a long stack trace, Spring Boot prints this description and action.
 */
public class OllamaNotRunningFailureAnalyzer extends AbstractFailureAnalyzer<ResourceAccessException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ResourceAccessException cause) {
        if (cause.getMessage() == null || !cause.getMessage().contains("/api/")) {
            return null; // not an Ollama call, let Spring Boot report it normally
        }
        return new FailureAnalysis(
                "Could not reach Ollama: " + cause.getMessage(),
                """
                Start Ollama before this application (see spring-ai/README.md, "How to run"):

                    docker run -d --name ollama -p 11434:11434 -v ollama:/root/.ollama ollama/ollama

                or set OLLAMA_BASE_URL if it runs elsewhere. The models are downloaded on the first start.""",
                cause);
    }
}
