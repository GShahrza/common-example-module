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
| [`observability`](observability) | Micrometer + OpenTelemetry: trace (Tempo), metrika (Prometheus), log (Loki), health | 8087 | ✅ |
| [`redis-cache`](redis-cache) | Redis: `@Cacheable`/`@CachePut`/`@CacheEvict`, TTL, cache stampede (`sync`), sorted set | 8088 | ✅ |
| [`resilience`](resilience) | Resilience4j: circuit breaker, retry, rate limiter, bulkhead, timeout, fallback | 8089 | ✅ |
| [`security-jwt`](security-jwt) | Spring Security + JWT: login, RS256, rollar/scope-lar, IDOR qorunması, JWKS; refresh token: bazada, rotation, `HttpOnly` cookie, klientdə avtomatik refresh | 8090 | ✅ |
| [`r2dbc`](r2dbc) | R2DBC + PostgreSQL: reaktiv CRUD, `DatabaseClient`, reaktiv tranzaksiya, bazadan NDJSON axını, R2DBC və JDBC-nin ölçülmüş müqayisəsi | 8091 | ✅ |

## 📖 Bələdçi (qısa kitab)

Streaming mövzuları (ilk üç modul), Spring Batch, Kafka, Redis, Resilience4j, Spring Security + JWT, Observability və Spring AI kitab üslubunda, ayrıca fəsillərdə izah olunub: həyatdan analogiya, problem, bu repodakı kodla addım-addım həll, tələlər və tapşırıqlar (4 hissə, 26 fəsil). I, II və III hissələrin (Spring MVC, WebFlux, gRPC) sonunda, həmçinin Kafka, Redis, Resilience4j, Security, Observability və Spring AI fəsillərinin sonunda 30-ar müsahibə sualı var. WebSocket üçün də ayrıca 30 sual yazılıb: [docs/musahibe-websocket.md](docs/musahibe-websocket.md). Digər modulların ətraflı izahı öz README-lərindədir.
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

### observability (Grafana stack lazımdır)

Ardıcıllıqla:

```bash
# 1. Grafana + Tempo + Loki + Prometheus + OTel Collector (bir konteyner)
docker run -d --name lgtm -p 3000:3000 -p 4317:4317 -p 4318:4318 grafana/otel-lgtm
# 2. Tətbiqi işə salın
./gradlew :observability:bootRun      # http://localhost:8087, Grafana: http://localhost:3000
```

Ətraflı: [observability/README.md](observability/README.md).

### redis-cache (Redis lazımdır)

Ardıcıllıqla:

```bash
# 1. Redis-i işə salın
docker run -d --name redis -p 6379:6379 redis:8-alpine
# 2. Tətbiqi işə salın
./gradlew :redis-cache:bootRun        # http://localhost:8088
```

Ətraflı: [redis-cache/README.md](redis-cache/README.md).

### resilience (əlavə heç nə lazım deyil)

```bash
./gradlew :resilience:bootRun         # http://localhost:8089
```

Ətraflı: [resilience/README.md](resilience/README.md).

### security-jwt (əlavə heç nə lazım deyil)

```bash
./gradlew :security-jwt:bootRun       # http://localhost:8090 (aynur / aynur123, admin / admin123)
```

Ətraflı: [security-jwt/README.md](security-jwt/README.md).

### r2dbc (PostgreSQL lazımdır)

Ardıcıllıqla:

```bash
# 1. PostgreSQL-i işə salın
docker run -d --name postgres -p 5432:5432 \
  -e POSTGRES_DB=shop -e POSTGRES_USER=shop -e POSTGRES_PASSWORD=shop postgres:17-alpine
# 2. Tətbiqi işə salın (Flyway cədvəlləri və 100 000 sifarişi yaradır)
./gradlew :r2dbc:bootRun              # http://localhost:8091
```

Ətraflı: [r2dbc/README.md](r2dbc/README.md).

### Testlər

```bash
./gradlew build                       # bütün modulların testləri
```

Kafka, Ollama və Grafana testlərdə lazım deyil (embedded Kafka, saxta modellər). `redis-cache` və `r2dbc` testləri isə real Redis və PostgreSQL-i Testcontainers ilə qaldırır, ona görə Docker işləməlidir.

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
├── websocket-chat/         # WebSocket + STOMP chat
├── observability/          # Micrometer, OpenTelemetry, Grafana
├── redis-cache/            # Redis cache, TTL, stampede
├── resilience/             # Resilience4j
├── security-jwt/           # Spring Security + JWT
└── r2dbc/                  # R2DBC + PostgreSQL
```

## Hansını nə vaxt seçməli?

Qısa cavab [gRPC modulunun README-sinin sonundadır](grpc-streaming/README.md#üç-modulun-müqayisəsi-hansını-nə-vaxt).
