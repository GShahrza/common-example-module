package io.github.gshahrza.ai.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * RAG step 1, done once at startup in the background: read the book (docs/*.md), split it into
 * chunks, turn every chunk into a vector with the embedding model and store it.
 */
@Component
public class DocsIngestion {

    public enum State { NOT_STARTED, INGESTING, READY, FAILED }

    public record Status(State state, int files, int chunks, long elapsedMs, String error) {
    }

    private static final Logger log = LoggerFactory.getLogger(DocsIngestion.class);

    private final VectorStore vectorStore;
    private final String docsPattern;
    private final AtomicReference<Status> status =
            new AtomicReference<>(new Status(State.NOT_STARTED, 0, 0, 0, null));

    public DocsIngestion(VectorStore vectorStore, @Value("${rag.docs}") String docsPattern) {
        this.vectorStore = vectorStore;
        this.docsPattern = docsPattern;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ingestInBackground() {
        Thread.ofVirtual().name("rag-ingestion").start(this::ingest);
    }

    public void ingest() {
        long start = System.nanoTime();
        status.set(new Status(State.INGESTING, 0, 0, 0, null));
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources(docsPattern);
            List<Document> chunks = new ArrayList<>();
            for (Resource file : files) {
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)   // "---" separates sections
                        .withIncludeCodeBlock(true)
                        .withAdditionalMetadata(Map.of("file", String.valueOf(file.getFilename())))
                        .build();
                List<Document> sections = new MarkdownDocumentReader(file, config).get();
                // Long sections are split further: an embedding describes a short text best
                chunks.addAll(TokenTextSplitter.builder().withChunkSize(350).build().apply(sections));
            }
            vectorStore.add(chunks);
            status.set(new Status(State.READY, files.length, chunks.size(), millisSince(start), null));
            log.info("RAG: {} chunks from {} files indexed in {} ms", chunks.size(), files.length, millisSince(start));
        } catch (IOException | RuntimeException e) {
            status.set(new Status(State.FAILED, 0, 0, millisSince(start), String.valueOf(e.getMessage())));
            log.warn("RAG ingestion failed: {}", e.getMessage());
        }
    }

    public Status status() {
        return status.get();
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
