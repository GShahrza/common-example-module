package io.github.gshahrza.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

/** Stand-ins for Ollama, so the tests run anywhere (CI has no LLM). */
@TestConfiguration(proxyBeanMethods = false)
public class FakeModels {

    @Bean
    FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }

    @Bean
    EmbeddingModel fakeEmbeddingModel() {
        return new BagOfWordsEmbeddingModel();
    }

    /** Answers with a configurable text, streamed word by word, and remembers every prompt it got. */
    public static class FakeChatModel implements ChatModel {

        public final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
        public volatile Function<Prompt, String> answer = p -> "Hello from the fake model";

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer.apply(prompt)))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            String[] words = answer.apply(prompt).split(" ");
            return Flux.range(0, words.length).map(i -> new ChatResponse(List.of(
                    new Generation(new AssistantMessage(i == 0 ? words[i] : " " + words[i])))));
        }
    }

    /**
     * Deterministic embeddings: every word is hashed into one of 512 buckets. Texts that share
     * words get similar vectors, which is enough to test that retrieval finds the right chapter.
     */
    public static class BagOfWordsEmbeddingModel implements EmbeddingModel {

        private static final int DIMENSIONS = 512;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                embeddings.add(new Embedding(vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        static float[] vector(String text) {
            float[] v = new float[DIMENSIONS];
            Arrays.stream(text.toLowerCase().split("[^\\p{L}\\p{N}-]+"))
                    .filter(w -> w.length() > 2)
                    .forEach(w -> v[Math.floorMod(w.hashCode(), DIMENSIONS)] += 1);
            double norm = 0;
            for (float x : v) {
                norm += x * x;
            }
            if (norm > 0) {
                for (int i = 0; i < v.length; i++) {
                    v[i] /= (float) Math.sqrt(norm);
                }
            }
            return v;
        }
    }
}
