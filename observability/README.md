# observability: trace, metrika və log (Micrometer + OpenTelemetry + Grafana)

Production-da "sistem yavaşdır" və ya "sifarişim keçmədi" şikayəti gələndə üç sual verilir, və hər birinə bir "pillar" cavab verir:

| Sual | Pillar | Alət | Bu modulda |
|---|---|---|---|
| **Nə qədər? Nə vaxtdan?** (xəta faizi, p99 latency, dəqiqədə sifariş) | **Metrika** | Micrometer → Prometheus | `orders.placed`, `checkout.process`, `inventory.stock`, `http.server.requests` |
| **Harada?** (sorğu hansı servisdə, hansı addımda ləngidi) | **Trace** | Micrometer Tracing → OpenTelemetry → Tempo | checkout → inventory → payment, bir trace id ilə |
| **Niyə?** (konkret xəta mesajı, parametrlər) | **Log** | Logback → OpenTelemetry → Loki | hər log sətrində trace id və span id |

Üçü **trace id** ilə bir-birinə bağlanır: metrika qrafikindəki sıçrayışdan həmin anın trace-inə, trace-dən isə o sorğunun loglarına keçmək olur.

📖 Kitab üslubunda izah və 30 müsahibə sualı: [24. Observability: trace, metrika və log](../docs/24-observability.md).

## İşə salma qaydası

Ardıcıllıqla:

### 1. Grafana stack-i işə salın (bir konteyner)

```bash
docker run -d --name lgtm -p 3000:3000 -p 4317:4317 -p 4318:4318 grafana/otel-lgtm
```

`grafana/otel-lgtm` bir konteynerdə bunları qaldırır: OpenTelemetry Collector (OTLP qəbul edir, port 4318), Prometheus (metrikalar), Tempo (trace-lər), Loki (loglar) və Grafana (UI, port 3000). Bu, lokal inkişaf üçündür; production-da hər biri ayrıca qurulur və ya Grafana Cloud istifadə olunur.

### 2. Tətbiqi işə salın

```bash
./gradlew :observability:bootRun
```

və ya Docker ilə:

```bash
docker build -f observability/Dockerfile -t observability .
docker run --rm -p 8087:8087 --add-host=host.docker.internal:host-gateway observability
```

Grafana olmadan da işləyir: `OTEL_EXPORT=false ./gradlew :observability:bootRun`. Bu halda trace id-lər loglarda görünür, metrikalar isə `/actuator/prometheus`-da, amma heç nə göndərilmir.

### 3. Trafik yaradın və baxın

1. **http://localhost:8087**: bir neçə sifariş verin, sonra "Yük: 50 sorğu" düyməsini basın.
2. Cədvəldə hər sorğunun trace id-si və hər servisin span id-si görünür: trace id eynidir, span id fərqlidir.
3. **http://localhost:3000** (Grafana):
   - **Trace:** trace id-yə klikləyin, və ya *Explore → Tempo* bölməsində trace id-ni yapışdırın. Şəlalə (waterfall) görünüşündə hər servisin nə qədər vaxt apardığı görünür; "Yavaş ödəniş" düyməsi ilə yaranan trace-də ləngiyən `payment` span-ı dərhal seçilir.
   - **Log:** span-da *Logs for this span* düyməsini basın, və ya *Explore → Loki* bölməsində `{service_name="observability"} | trace_id="<id>"` sorğusunu yazın.
   - **Metrika:** *Explore → Prometheus* bölməsində, məsələn:
     - `sum by (result) (rate(orders_placed_total[1m]))`: dəqiqədə sifarişlər, nəticəyə görə;
     - `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))`: p95 latency;
     - `inventory_stock`.

Metrikalar hər 10 saniyədən bir göndərilir (`step: 10s`), ona görə qrafiklər bir az gecikmə ilə yenilənir.

---

## Necə işləyir

### Asılılıqlar

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'       // /actuator/health, /metrics, /prometheus
implementation 'org.springframework.boot:spring-boot-starter-opentelemetry'  // Boot 4: tracing + OTLP export (trace, metrika, log)
implementation 'org.springframework.boot:spring-boot-starter-aspectj'        // @Observed annotasiyası üçün
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'                   // /actuator/prometheus (pull modeli)
implementation 'io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.0-alpha'  // loglar → OTLP
```

**Micrometer** tətbiq kodunun danışdığı API-dir (SLF4J loglar üçün necədirsə, Micrometer də metrika və trace üçün elədir). **OpenTelemetry** isə məlumatı standart formatda (OTLP) göndərən tərəfdir. Kod Micrometer ilə yazılır; backend-i (Grafana, Datadog, New Relic, Elastic) dəyişmək üçün yalnız konfiqurasiyanı dəyişmək kifayətdir.

### Avtomatik olaraq nə gəlir

Heç bir kod yazmadan Spring Boot bunları təmin edir:

- hər HTTP sorğu üçün server span-ı və `http.server.requests` timer-i (`uri`, `status`, `outcome` tag-ları ilə);
- Boot-un `RestClient.Builder`-i ilə qurulan client-in hər çağırışı üçün client span-ı, `http.client.requests` timer-i və **`traceparent` header-i**. Qarşı tərəf trace-i məhz bu header vasitəsilə davam etdirir (W3C Trace Context standartı);
- log pattern-ində `[observability,<traceId>-<spanId>]`;
- JVM metrikaları (heap, GC, thread-lər), Tomcat, HikariCP və s.

`RestClient`-i `RestClient.builder()` ilə özünüz yaratsanız, instrumentasiya **olmayacaq** və trace servislər arasında qırılacaq. Həmişə Spring-in inject etdiyi `RestClient.Builder`-dən istifadə edin (`WebClient.Builder`, `RestTemplateBuilder` üçün də eyni qayda keçərlidir).

### Öz metrika və span-larınız

**Observation API**: bir çağırış həm timer, həm də span yaradır.

```java
Observation.createNotStarted("checkout.process", observations)
        .lowCardinalityKeyValue("sku", request.sku())              // metrika tag-ı + span atributu
        .highCardinalityKeyValue("customer", request.customer())   // YALNIZ span atributu
        .observe(() -> process(request));
```

**`@Observed`**: eyni şeyin annotasiya ilə yazılışı (`InventoryService.reserve`).

**Biznes metrikaları** birbaşa `MeterRegistry` ilə yaradılır:

| Növ | Nə vaxt | Nümunə |
|---|---|---|
| `Counter` | yalnız artan say | `orders.placed{result=success\|out_of_stock\|payment_failed}` |
| `Timer` | müddət + say | `checkout.process` (Observation yaradır) |
| `DistributionSummary` | müddət olmayan paylanma | `orders.amount` (AZN) |
| `Gauge` | artıb-azalan cari dəyər | `inventory.stock{sku}` |

### Kardinallıq qaydası

Metrikada **hər unikal tag kombinasiyası ayrıca time series**-dir. `customer` tag-ı qoysanız, 1 milyon müştəri 1 milyon series edir və Prometheus çökür. Qayda belədir:

- **Metrika tag-ları:** az və sabit dəyərlər (`status`, `sku`, `result`, `uri` template-i `/api/orders/{id}`).
- **Span atributları:** unikal dəyərlər (`customer`, `orderId`, `userId`).

Micrometer `lowCardinalityKeyValue` / `highCardinalityKeyValue` ayrımı ilə buna məcbur edir. Spring də `uri` tag-ında `/api/orders/42` yox, `/api/orders/{id}` yazır.

### Trace-in servislər arası ötürülməsi

```
checkout (span A) ──POST /api/inventory/BOOK/reserve──►  inventory (span B, parent A)
                   traceparent: 00-<traceId>-<spanA>-01
                  ──POST /api/payments──────────────►   payment   (span C, parent A)
```

Demo-da üç "servis" bir tətbiqdədir, amma bir-birini **HTTP ilə** çağırır. Ona görə trace ayrı servislərdə olduğu kimi header ilə ötürülür (testdə yoxlanılır: üç servisin trace id-si eyni, span id-ləri fərqlidir).

Kafka və RabbitMQ mesajlarında da eyni header mesaj header-ində daşınır. Spring Kafka-da `spring.kafka.template.observation-enabled` və `spring.kafka.listener.observation-enabled` ilə bu da avtomatik olur.

### Loglar

`logback-spring.xml` loqları iki yerə göndərir: konsola və `OpenTelemetryAppender`-ə. Sonuncu hər log hadisəsinə cari trace id və span id-ni əlavə edib Loki-yə göndərir. Grafana-da trace-dən loglara və loglardan trace-ə keçid bu sayədə işləyir.

Kubernetes-də bunun alternativi loqları stdout-a **strukturlaşdırılmış JSON** kimi yazmaq (`logging.structured.format.console: ecs` və ya `logstash`) və onları Promtail, Fluent Bit və ya Alloy ilə toplamaqdır.

### Health

`PaymentGatewayHealth` xüsusi `HealthIndicator`-dur: ardıcıl 5 xətadan sonra `DOWN` olur. `/actuator/health` bütün komponentləri göstərir. Kubernetes-də `/actuator/health/liveness` və `/actuator/health/readiness` probe-ları ayrıca istifadə olunur.

Xarici asılılığı liveness-ə qoşmayın: payment gateway düşəndə sizin pod-larınız restart olunmamalıdır.

### Sampling

`management.tracing.sampling.probability: 1.0` hər sorğunu saxlayır; bu, demo üçündür. Yüksək trafikdə 0.01-0.1 seçilir və ya **tail sampling** qurulur: collector yalnız xətalı və yavaş trace-ləri saxlayır. Metrikalar sampling-dən asılı deyil, onlar həmişə tamdır.

## Konfiqurasiya

| Dəyişən | Default | Nə üçün |
|---|---|---|
| `OTLP_URL` | `http://localhost:4318` | OTLP collector ünvanı (trace, metrika, log) |
| `OTEL_EXPORT` | `true` | `false`: heç nə göndərilmir (Grafana olmadan işləmək üçün) |
| `payment.failure-rate` | `0.1` | Ödənişlərin neçə faizi 502 ilə uğursuz olsun |

## Testlər

```bash
./gradlew :observability:test
```

Testlərdə tracing default olaraq söndürülür; `@AutoConfigureTracing` onu yandırır. Export isə söndürülüb, ona görə Grafana lazım deyil.

| Test | Nəyi yoxlayır |
|---|---|
| `oneTraceSpansAllServicesCalledOverHttp` | 3 servis HTTP ilə çağırılır, trace id eyni, span id-lər fərqli; `X-Trace-Id` header-i |
| `outOfStockStopsBeforePayment` | stok yoxdursa, payment çağırılmır |
| `businessAndTechnicalMetricsAreRecorded` | Counter, Observation timer-i, `@Observed`, HTTP client metrikası, Gauge |
| `customHealthIndicatorIsPartOfHealth` | xüsusi health indicator |

## Tələlər

- **Instrumentasiyasız client:** `new RestTemplate()` və ya `RestClient.create()` trace-i qırır.
- **Yüksək kardinallıq:** metrika tag-ına id, e-poçt və ya tam URL yazmayın.
- **Virtual thread və `@Async`:** trace konteksti `ThreadLocal`-dadır. Onu başqa thread-ə ötürmək üçün `ContextPropagatingTaskDecorator`-u bean kimi təyin edin; Spring Boot onu öz executor-larına (`@Async`, scheduling) tətbiq edir. Özünüz yaratdığınız executor-larda isə onu əl ilə qoşun.
- **Versiya uyğunluğu:** `opentelemetry-logback-appender` Boot-un idarə etdiyi OpenTelemetry versiyası ilə uyğun olmalıdır. Uyğun olmasa, runtime-da `NoClassDefFoundError` alınır (Boot 4.1 üçün 2.28.x).
