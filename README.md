# Common Example Module: Java və Spring Boot nümunələri

Java 21 və Spring Boot 4 ilə backend-də tez-tez lazım olan mövzuların işlək nümunələri. Hər modul ayrıca mikroservisdir (öz portu, öz README-si, testləri və Dockerfile-ı var). Əksəriyyətinin brauzerdə açılan demo səhifəsi var.

| Modul | Nə göstərir | Port | Status |
|---|---|---|---|
| [`mvc-streaming`](mvc-streaming) | Spring MVC (servlet): SSE, NDJSON, fayl streaming | 8080 | ✅ |
| [`webflux-streaming`](webflux-streaming) | WebFlux: adi request-lərdən fərqi (thread modeli, `Mono.zip`), reaktiv streaming, backpressure | 8081 | ✅ |
| [`grpc-streaming`](grpc-streaming) | gRPC: unary, server, client və bidirectional streaming; brauzer üçün HTTP gateway | 8082 (demo), 9090 (gRPC) | ✅ |
| [`spring-ai`](spring-ai) | Spring AI + Ollama: streaming chat, structured output, tool calling, RAG | 8083 | ✅ |

## 📖 Bələdçi (qısa kitab)

Hər mövzu kitab üslubunda, ayrıca fəsildə izah olunub: həyatdan analogiya, problem, bu repodakı kodla addım-addım həll, tələlər və tapşırıqlar. 3 hissə, 19 fəsil.
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
└── spring-ai/              # Spring AI + Ollama
    ├── Dockerfile
    ├── README.md
    └── src/
```

## Hansını nə vaxt seçməli?

Qısa cavab [gRPC modulunun README-sinin sonundadır](grpc-streaming/README.md#üç-modulun-müqayisəsi-hansını-nə-vaxt).
