# Spring Boot streaming nümunələri

Serverdən frontend-ə cavabı **hissə-hissə** (stream) göndərməyin Java 21 və Spring Boot 4 ilə nümunələri. Hər modul ayrıca, işlək tətbiqdir və brauzerdə açılan demo səhifəsi var.

| Modul | Nə göstərir | Port | Status |
|---|---|---|---|
| [`mvc-streaming`](mvc-streaming) | Spring MVC (servlet): SSE, NDJSON, fayl streaming | 8080 | ✅ |
| `webflux-streaming` | WebFlux: reaktiv streaming, adi request-lərdən fərqi | 8081 | planlaşdırılıb |
| `grpc-streaming` | gRPC: server, client və bidirectional streaming | 9090 | planlaşdırılıb |

## Bir əmrlə işə salmaq

Yalnız Docker lazımdır (Java və ya Gradle quraşdırmağa ehtiyac yoxdur):

```bash
docker compose up --build
```

Sonra brauzerdə açın: **http://localhost:8080**

Dayandırmaq üçün `Ctrl+C` və ya `docker compose down`.

## Docker-siz işə salmaq

Java 21 lazımdır:

```bash
./gradlew :mvc-streaming:bootRun     # tətbiq
./gradlew build                      # bütün testlər
```

## Struktur

```
.
├── compose.yaml            # bütün modullar, bir əmrlə
├── settings.gradle         # Gradle multi-module
└── mvc-streaming/
    ├── Dockerfile          # multi-stage: Gradle ilə build, JRE ilə run
    ├── README.md           # ətraflı izah
    └── src/
```
