# Common Example Module: Java və Spring Boot nümunələri

Java 21 və Spring Boot 4 ilə backend-də tez-tez lazım olan mövzuların işlək nümunələri. Hər modul ayrıca mikroservisdir (öz portu, öz README-si, testləri və Dockerfile-ı var). Əksəriyyətinin brauzerdə açılan demo səhifəsi var.

| Modul | Nə göstərir | Port | Status |
|---|---|---|---|
| [`mvc-streaming`](mvc-streaming) | Spring MVC (servlet): SSE, NDJSON, fayl streaming | 8080 | ✅ |
| [`webflux-streaming`](webflux-streaming) | WebFlux: adi request-lərdən fərqi (thread modeli, `Mono.zip`), reaktiv streaming, backpressure | 8081 | ✅ |
| [`grpc-streaming`](grpc-streaming) | gRPC: unary, server, client və bidirectional streaming; brauzer üçün HTTP gateway | 8082 (demo), 9090 (gRPC) | ✅ |
| [`spring-ai`](spring-ai) | Spring AI + Ollama: streaming chat, structured output, tool calling, RAG | 8083 | ✅ |
| [`kafka-events`](kafka-events) | Event-driven: transactional outbox, idempotent consumer, retry topic, DLT, saga | 8084 | ✅ |
| [`spring-batch`](spring-batch) | Spring Batch 6: chunk, skip/filter, restart, JobInstance, tasklet | 8085 | ✅ |
| [`websocket-chat`](websocket-chat) | WebSocket + STOMP: otaqlar, şəxsi mesaj, onlayn siyahısı, xam WebSocket ilə müqayisə | 8086 | ✅ |

## 📖 Bələdçi (qısa kitab)

Streaming mövzuları (ilk üç modul) kitab üslubunda, ayrıca fəsillərdə izah olunub: həyatdan analogiya, problem, bu repodakı kodla addım-addım həll, tələlər və tapşırıqlar (3 hissə, 19 fəsil). Digər modulların ətraflı izahı öz README-lərindədir.
**[Oxumağa başla →](docs/README.md)**

## İşə salma qaydası

Hər modul müstəqil işləyir: yalnız lazım olanı işə salın. Ümumi tələb **Java 21** (və ya yalnız Docker) olmasıdır. Əmrlər repo kökündən icra olunur.

### Streaming modulları (əlavə heç nə lazım deyil)

```bash
./gradlew :mvc-streaming:bootRun      # http://localhost:8080
./gradlew :webflux-streaming:bootRun  # http://localhost:8081
./gradlew :grpc-streaming:bootRun     # http://localhost:8082 (demo), gRPC localhost:9090
```

Üçünü birlikdə Docker ilə işə salmaq üçün: `docker compose up --build`; dayandırmaq üçün: `docker compose down`.

### spring-ai (Ollama lazımdır)

Ardıcıllıqla:

```bash
# 1. Ollama-nı işə salın
docker run -d --name ollama -p 11434:11434 -v ollama:/root/.ollama ollama/ollama
# 2. Modelləri yükləyin (bir dəfə)
docker exec ollama ollama pull qwen2.5:3b
docker exec ollama ollama pull nomic-embed-text
# 3. Tətbiqi işə salın
./gradlew :spring-ai:bootRun          # http://localhost:8083
```

Ətraflı: [spring-ai/README.md](spring-ai/README.md).

### kafka-events (Kafka lazımdır)

Ardıcıllıqla:

```bash
# 1. Kafka-nı işə salın (KRaft, ZooKeeper-siz)
docker run -d --name kafka -p 9092:9092 apache/kafka:4.1.0
# 2. Tətbiqi işə salın
./gradlew :kafka-events:bootRun       # http://localhost:8084
```

Ətraflı: [kafka-events/README.md](kafka-events/README.md).

### spring-batch (əlavə heç nə lazım deyil, baza H2)

```bash
./gradlew :spring-batch:bootRun       # http://localhost:8085
```

Ətraflı: [spring-batch/README.md](spring-batch/README.md).

### websocket-chat (əlavə heç nə lazım deyil)

```bash
./gradlew :websocket-chat:bootRun     # http://localhost:8086 (iki tab-da açın)
```

Ətraflı: [websocket-chat/README.md](websocket-chat/README.md).

### Testlər

```bash
./gradlew build                       # bütün modulların testləri; xarici servis lazım deyil
```

## Struktur

```
.
├── compose.yaml            # streaming modulları, bir əmrlə
├── settings.gradle         # Gradle multi-module
├── mvc-streaming/          # Spring MVC (Tomcat)
│   ├── Dockerfile          # multi-stage: Gradle ilə build, JRE ilə run
│   ├── README.md           # ətraflı izah
│   └── src/
├── webflux-streaming/      # WebFlux (Netty)
│   ├── Dockerfile
│   ├── README.md
│   └── src/
├── grpc-streaming/         # gRPC server + HTTP gateway
│   ├── Dockerfile
│   ├── README.md
│   └── src/main/proto/     # API müqaviləsi (.proto)
├── spring-ai/              # Spring AI + Ollama
├── kafka-events/           # Kafka: outbox, idempotency, retry, DLT, saga
├── spring-batch/           # Spring Batch: CSV import, skip, restart
└── websocket-chat/         # WebSocket + STOMP chat
```

## Hansını nə vaxt seçməli?

Qısa cavab [gRPC modulunun README-sinin sonundadır](grpc-streaming/README.md#üç-modulun-müqayisəsi-hansını-nə-vaxt).
