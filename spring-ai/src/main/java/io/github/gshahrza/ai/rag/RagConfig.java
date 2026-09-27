package io.github.gshahrza.ai.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RagConfig {

    /**
     * In-memory vector store: enough for a few hundred chunks. For real data use PostgreSQL
     * with pgvector (spring-ai-starter-vector-store-pgvector); the VectorStore interface stays the same.
     */
    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
