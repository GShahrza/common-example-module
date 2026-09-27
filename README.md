# Spring Boot streaming nümunələri

Serverdən frontend-ə cavabı **hissə-hissə** (stream) göndərməyin Java 21 və Spring Boot 4 ilə nümunələri. Hər modul ayrıca, işlək tətbiqdir və brauzerdə açılan demo səhifəsi var.

| Modul | Nə göstərir | Port | Status |
|---|---|---|---|
| [`mvc-streaming`](mvc-streaming) | Spring MVC (servlet): SSE, NDJSON, fayl streaming | 8080 | ✅ |
| [`webflux-streaming`](webflux-streaming) | WebFlux: adi request-lərdən fərqi (thread modeli, `Mono.zip`), reaktiv streaming, backpressure | 8081 | ✅ |
| [`grpc-streaming`](grpc-streaming) | gRPC: unary, server, client və bidirectional streaming; brauzer üçün HTTP gateway | 8082 (demo), 9090 (gRPC) | ✅ |

## 📖 Bələdçi (qısa kitab)

Hər mövzu kitab üslubunda, ayrıca fəsildə izah olunub: həyatdan analogiya, problem, bu repodakı kodla addım-addım həll, tələlər və tapşırıqlar. 3 hissə, 19 fəsil.
**[Oxumağa başla →](docs/README.md)**

## Bir əmrlə işə salmaq

Yalnız Docker lazımdır (Java və ya Gradle quraşdırmağa ehtiyac yoxdur):

```bash
docker compose up --build
```

Sonra brauzerdə açın:
- Spring MVC: **http://localhost:8080**
- WebFlux: **http://localhost:8081**
- gRPC: **http://localhost:8082** (demo səhifə); gRPC server `localhost:9090`

Dayandırmaq üçün `Ctrl+C` və ya `docker compose down`.

## Docker-siz işə salmaq

Java 21 lazımdır:

```bash
./gradlew :mvc-streaming:bootRun     # Spring MVC, port 8080
./gradlew :webflux-streaming:bootRun # WebFlux, port 8081
./gradlew :grpc-streaming:bootRun    # gRPC 9090 + gateway 8082
./gradlew build                      # bütün testlər
```

## Struktur

```
.
├── compose.yaml            # bütün modullar, bir əmrlə
├── settings.gradle         # Gradle multi-module
├── mvc-streaming/          # Spring MVC (Tomcat)
│   ├── Dockerfile          # multi-stage: Gradle ilə build, JRE ilə run
│   ├── README.md           # ətraflı izah
│   └── src/
├── webflux-streaming/      # WebFlux (Netty)
│   ├── Dockerfile
│   ├── README.md
│   └── src/
└── grpc-streaming/         # gRPC server + HTTP gateway
    ├── Dockerfile
    ├── README.md
    └── src/main/proto/     # API müqaviləsi (.proto)
```

## Hansını nə vaxt seçməli?

Qısa cavab [gRPC modulunun README-sinin sonundadır](grpc-streaming/README.md#üç-modulun-müqayisəsi-hansını-nə-vaxt).
