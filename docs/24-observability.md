# 24. Observability: trace, metrika və log

[← 23. Spring Security və JWT](23-security-jwt.md) · [Mündəricat](README.md)

**Hissə IV: Əlavə mövzular** · **Kod:** [`CheckoutService`](../observability/src/main/java/io/github/gshahrza/observability/checkout/CheckoutService.java), [`InventoryService`](../observability/src/main/java/io/github/gshahrza/observability/inventory/InventoryService.java), [`PaymentController`](../observability/src/main/java/io/github/gshahrza/observability/payment/PaymentController.java), [`PaymentGatewayHealth`](../observability/src/main/java/io/github/gshahrza/observability/payment/PaymentGatewayHealth.java), [`application.yml`](../observability/src/main/resources/application.yml), [`logback-spring.xml`](../observability/src/main/resources/logback-spring.xml) · **Demo:** http://localhost:8087, Grafana: http://localhost:3000

```bash
docker run -d --name lgtm -p 3000:3000 -p 4317:4317 -p 4318:4318 grafana/otel-lgtm
./gradlew :observability:bootRun
```

---

## Həyatdan analogiya

Böyük xəstəxana. Xəstə "özümü pis hiss edirəm" deyir. Həkim üç alətdən istifadə edir:

- **Monitor** (nəbz, təzyiq, temperatur): rəqəmlərdir, zamanla dəyişir, həddən çıxanda siqnal verir. Nə vaxtdan və nə qədər pis olduğunu göstərir, **niyə** olduğunu isə demir.
- **Xəstənin yol xəritəsi:** qəbul → analiz → rentgen → həkim. Hər mərhələnin nə qədər çəkdiyi yazılıb. Xəstə 4 saat gözləyibsə, **harada** ilişdiyi görünür: rentgen növbəsində.
- **Tibbi qeydlər:** hər mərhələdə həkim nə gördüyünü, hansı dərmanı verdiyini, nə baş verdiyini yazır. **Niyə** sualının cavabı buradadır.

Üçünü birləşdirən isə xəstənin **qolundakı bilərzikdir**: eyni nömrə monitorda da, xəritədə də, qeydlərdə də var.

Observability-də monitor **metrikadır**, yol xəritəsi **trace**, qeydlər **log**, bilərzikdəki nömrə isə **trace id**-dir.

## Problem

İstifadəçi yazır: "Sifarişim 8 saniyə çəkdi və xəta verdi." Qarşınızda 12 servis, 40 pod və hər birinin minlərlə log sətri var.

- **Hansı servisdə?** Sifariş checkout, inventory, payment və notification-dan keçir.
- **Yalnız bu istifadəçidə, yoxsa hamıda?** Son yarım saatda xəta faizi artıbmı?
- **Niyə?** Timeout, bazada lock, yoxsa xarici API?

Loglarda axtarış ("error") minlərlə sətir verir, və onların hansının **bu** sorğuya aid olduğu bilinmir. Metrika "p99 latency artıb" deyir, amma hansı sorğuda və harada olduğunu demir. Üç məlumat mənbəyi var, amma onlar bir-biri ilə bağlı deyil.

## Üç siqnal

| Siqnal | Sual | Nədir | Alət (bu modulda) |
|---|---|---|---|
| **Metrika** | Nə qədər? Nə vaxtdan? | Zamanla aqreqat rəqəmlər: sayğac, müddət paylanması, cari dəyər | Micrometer → OTLP → Prometheus (Mimir) |
| **Trace** | Harada? | Bir sorğunun servislər üzrə yolu: span-lar ağacı, hər birinin müddəti | Micrometer Tracing → OpenTelemetry → Tempo |
| **Log** | Niyə? | Konkret hadisənin təsviri, konteksti ilə | Logback → OpenTelemetry → Loki |

Üçünü **trace id** bağlayır. Metrika qrafikindəki sıçrayışdan həmin anın trace-inə (exemplar), trace-dən isə o sorğunun loglarına keçmək olur.

**Monitoring** əvvəlcədən bilinən suallara cavab verir ("CPU 90%-dən yuxarıdırmı?"). **Observability** isə əvvəlcədən bilinməyən suallara da cavab axtarmağa imkan verir ("niyə yalnız Android istifadəçilərində, yalnız bu məhsulda yavaşdır?").

## Micrometer və OpenTelemetry

```
Kod ──► Micrometer (API: Observation, MeterRegistry, Tracer)
             │
             ├── metrikalar ─► OTLP registry ───────────┐
             ├── trace-lər  ─► Micrometer Tracing bridge ─► OpenTelemetry SDK ─► OTLP ─► Collector ─► Tempo / Prometheus / Loki
             └── loglar     ─► OpenTelemetryAppender ────┘
```

- **Micrometer** kodun danışdığı API-dir: metrikalar və trace-lər üçün **SLF4J** kimidir. Kod vendor-dan asılı olmur.
- **OpenTelemetry (OTel)** telemetriyanın standartıdır: məlumat modeli, SDK və **OTLP** protokolu. İstənilən backend (Grafana, Datadog, New Relic, Elastic, Honeycomb) OTLP qəbul edir.
- **Collector** telemetriyanı qəbul edir, emal edir (filtr, sampling, atribut əlavəsi) və backend-lərə paylayır.

Spring Boot 4-də bir starter kifayətdir:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'org.springframework.boot:spring-boot-starter-opentelemetry'   // tracing + OTLP export
implementation 'org.springframework.boot:spring-boot-starter-aspectj'        // @Observed üçün
implementation 'io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.0-alpha'   // loglar
```

## Həll, addım-addım

`observability` modulunda üç "servis" (checkout, inventory, payment) bir tətbiqdədir, amma bir-birini **HTTP ilə** çağırır. Ona görə trace konteksti real mikroservislərdəki kimi header-lə ötürülür.

### 1. Heç kod yazmadan nə gəlir

Starter-lər qoşulan kimi Spring Boot bunları təmin edir:

- **Hər HTTP sorğu üçün** server span-ı və `http.server.requests` timer-i (`uri`, `method`, `status`, `outcome` tag-ları ilə).
- **Boot-un `RestClient.Builder`-i ilə** qurulan client-in hər çağırışı üçün client span-ı, `http.client.requests` timer-i və **`traceparent` header-i**.
- **Log pattern-ində** trace id və span id: `[observability,59d61e9a8b4250c7cdbf9eadaac803fc-333c505d4da6407a]`.
- **JVM** (heap, GC, thread-lər), Tomcat, HikariCP metrikaları.
- `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`.

### 2. Trace-in servislər arası ötürülməsi

```
checkout (span A) ──POST /api/inventory/BOOK/reserve──►  inventory (span B, parent A)
                   traceparent: 00-<traceId>-<spanA>-01
                  ──POST /api/payments──────────────►   payment   (span C, parent A)
```

`traceparent` (W3C Trace Context standartı) dörd hissədən ibarətdir: versiya, **trace id** (bütün sorğu üçün eyni), **parent span id** (çağıran span) və flag-lar (sampled). Qəbul edən servis yeni span yaradır, onun parent-i isə header-dəki span olur. Beləliklə, servislərdən keçən bir ağac yaranır.

Demo-dan real cavab: üç servisin **hamısında eyni trace id**, span id-lər isə fərqlidir (testdə yoxlanılır):

```json
{"status":"success","traceId":"59d61e9a8b4250c7cdbf9eadaac803fc","hops":[
  {"service":"checkout", "traceId":"59d61e9a...","spanId":"333c505d4da6407a","millis":153},
  {"service":"inventory","traceId":"59d61e9a...","spanId":"0d84100c2cf1e3f5","millis":10},
  {"service":"payment",  "traceId":"59d61e9a...","spanId":"af4bcf376ec1d8ae","millis":33}]}
```

Tempo-da bu trace **7 span** kimi görünür:

- checkout server span-ı;
- `checkout` observation-ı;
- iki HTTP client span-ı;
- inventory server span-ı və `reserve-stock`;
- payment server span-ı.

**Ən çox rast gəlinən səhv:** `RestClient.create()` və ya `new RestTemplate()` ilə yaradılan client instrumentasiyasızdır. Header getmir və trace servislər arasında **qırılır**. Həmişə Spring-in inject etdiyi `RestClient.Builder` / `WebClient.Builder` / `RestTemplateBuilder`-dən istifadə edin. Kafka-da eyni header mesaj header-ində gedir (`spring.kafka.template.observation-enabled`, `spring.kafka.listener.observation-enabled`).

### 3. Öz ölçmələriniz: Observation API

```java
public CheckoutResult checkout(CheckoutRequest request) {
    return Observation.createNotStarted("checkout.process", observations)
            .contextualName("checkout")                                // span-ın adı
            .lowCardinalityKeyValue("sku", request.sku())              // metrika tag-ı + span atributu
            .highCardinalityKeyValue("customer", request.customer())   // YALNIZ span atributu
            .observe(() -> process(request));
}
```

**Observation** Micrometer-in əsas ideyasıdır: kod "bu əməliyyatı müşahidə et" deyir, handler-lər isə ondan **eyni anda** timer (`checkout.process` metrikası) və span (`checkout`) yaradır. Kod iki dəfə yazılmır.

Annotasiya ilə eyni şey:

```java
@Observed(name = "inventory.reserve", contextualName = "reserve-stock")
public boolean reserve(String sku, int quantity) { ... }
```

Mövcud span-a atribut əlavə etmək üçün isə `Tracer` istifadə olunur:

```java
tracer.currentSpan().tag("payment.customer", request.customer());   // Tempo-da axtarıla bilir
```

### 4. Metrika növləri

| Növ | Nə vaxt | Bu modulda |
|---|---|---|
| **Counter** | Yalnız artan say | `orders.placed{result=success\|out_of_stock\|payment_failed}` |
| **Timer** | Müddət və say (paylanma, percentile) | `checkout.process`, `http.server.requests` |
| **DistributionSummary** | Müddət olmayan paylanma | `orders.amount` (AZN) |
| **Gauge** | Artıb-azalan cari dəyər | `inventory.stock{sku}` |

```java
Counter.builder("orders.placed").tag("result", status).register(meters).increment();

Gauge.builder("inventory.stock", level, AtomicInteger::get).tag("sku", "BOOK").register(registry);
```

Prometheus-da (real cavabdan):

```
orders_placed_total{result="success"} 46
orders_placed_total{result="payment_failed"} 5
inventory_stock{sku="BOOK"} 998
http_client_requests_seconds_count{uri="/api/inventory/{sku}/reserve?quantity={q}",status="200",...} 1
```

**Percentile-lər.** Orta latency aldadıcıdır: 99 sorğu 10 ms, 1 sorğu 10 saniyə olsa, orta ~110 ms olur və problem görünmür. p95 və p99 baxılır. Bunun üçün histogram lazımdır:

```yaml
management.metrics.distribution.percentiles-histogram:
  http.server.requests: true
  checkout.process: true
```

```promql
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))
```

Percentile-ləri pod-da hesablayıb göndərmək (`percentiles: 0.95`) pod-lar arasında **toplana bilməz**. Histogram bucket-ları isə toplanır, ona görə tövsiyə olunan yol budur.

### 5. Kardinallıq: metrikaların ən təhlükəli tələsi

Metrikada hər unikal tag kombinasiyası **ayrıca time series**-dir. `customer` tag-ı qoysanız, 1 milyon müştəri 1 milyon series edir: Prometheus-un yaddaşı dolur, sorğular yavaşlayır, hesab isə böyüyür.

| Metrika tag-ı ola bilər (az, sabit dəyər) | Metrika tag-ı OLMAZ (yalnız span atributu və ya log) |
|---|---|
| `status`, `method`, `outcome`, `result` | `userId`, `customer`, `email` |
| `uri` template-i: `/api/orders/{id}` | Tam URL: `/api/orders/42` |
| `sku` (məhdud kataloq), `region` | `orderId`, `traceId`, `sessionId` |

Micrometer bunu API-də ayırır: `lowCardinalityKeyValue` metrikaya və span-a, `highCardinalityKeyValue` isə **yalnız** span-a gedir. Spring də `uri` tag-ına dəyər yox, template yazır: `/api/inventory/{sku}/reserve?quantity={q}`.

### 6. Loglar: trace id ilə

```xml
<appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender"/>
<root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="OTEL"/>
</root>
```

```java
@Bean
InitializingBean installOpenTelemetryAppender(OpenTelemetry openTelemetry) {
    return () -> OpenTelemetryAppender.install(openTelemetry);   // Logback Spring-dən əvvəl qalxır, sonra qoşulur
}
```

Hər log hadisəsi trace id və span id ilə birlikdə Loki-yə gedir. Real nəticədə bir trace-in 4 log sətri Loki-də trace id ilə tapıldı:

```logql
{service_name="observability"} | trace_id="b29896c269bdff51f7726cb759b39a0b"
```

```
Checkout started: customer=Aynur sku=LAPTOP quantity=1
Reserved 1 x LAPTOP, 49 left
Charged 2400 to Aynur
Checkout finished: success
```

Trace id log mətninin içində deyil, **structured metadata**-dadır. Ona görə `|= "<id>"` (mətn axtarışı) tapmır, `| trace_id="<id>"` isə tapır. Bunu modulu yoxlayarkən öyrəndim.

**Versiya tələsi:** `opentelemetry-logback-appender` Boot-un idarə etdiyi OpenTelemetry versiyası ilə uyğun olmalıdır. Uyğun olmayanda tətbiq işləyərkən `NoClassDefFoundError` alınır. Boot 4.1 OTel 1.62 istifadə edir, ona görə appender-in 2.28.x versiyası seçilib.

Kubernetes-də alternativ variant: loqları stdout-a **strukturlaşdırılmış JSON** kimi yazmaq (`logging.structured.format.console: ecs`) və agent (Alloy, Fluent Bit) ilə toplamaq.

### 7. Health

```java
@Component
public class PaymentGatewayHealth implements HealthIndicator {
    public Health health() {
        return (consecutiveFailures.get() >= 5 ? Health.down() : Health.up())
                .withDetail("consecutiveFailures", consecutiveFailures.get()).build();
    }
}
```

`/actuator/health` bütün komponentləri göstərir. Kubernetes-də:

- **liveness** (`/actuator/health/liveness`): "proses sağdırmı?" Uğursuz olsa, pod **restart** olunur.
- **readiness** (`/actuator/health/readiness`): "trafik qəbul edə bilərmi?" Uğursuz olsa, pod trafikdən **çıxarılır**.

Xarici asılılıqları (payment gateway, digər servislər) liveness-ə **qoşmayın**. Gateway düşəndə sizin bütün pod-larınız restart olunar, amma bu, problemi həll etməz.

### 8. Sampling

```yaml
management.tracing.sampling.probability: 1.0   # demo: hər trace saxlanılır
```

Yüksək trafikdə hər sorğunu saxlamaq bahalıdır. İki yanaşma var:

- **Head sampling:** qərar sorğunun **əvvəlində** verilir (məs. 10%) və `traceparent`-in flag-ı ilə bütün servislərə ötürülür. Sadə və ucuzdur, amma nadir xətalı trace-lər atıla bilər.
- **Tail sampling:** Collector trace-in **sonunda** qərar verir: xətalı, yavaş və nümunə üçün bir hissə normal trace-ləri saxlayır. Daha dəyərlidir, amma Collector-da yaddaş tələb edir.

**Metrikalar sampling-dən asılı deyil**, onlar həmişə tamdır. Ona görə alert-lər metrikalar üzərində qurulur, trace-lər isə araşdırma üçündür.

## SLI, SLO və alert-lər

- **SLI** (indicator), ölçülən göstəricidir: "uğurlu sorğuların payı", "p99 < 300 ms olan sorğuların payı".
- **SLO** (objective), hədəfdir: "30 gündə sorğuların 99.9%-i uğurlu olsun".
- **SLA**: müştəri ilə müqavilədir, cərimələri ilə birlikdə.

`99.9%` 30 gündə təxminən **43 dəqiqə** "icazəli uğursuzluq" deməkdir; buna **error budget** deyilir. Alert-lər simptomlara qurulur (istifadəçi nə hiss edir: xəta faizi, latency) və error budget-in **yanma sürətinə** (burn rate) baxır, səbəblərə isə yox ("CPU 80%"). Yaxşı alert oyadır, çünki istifadəçi əziyyət çəkir, və nə edəcəyinizi bilirsiniz.

Məşhur çərçivələr:

- **RED** (servislər üçün): **R**ate, **E**rrors, **D**uration.
- **USE** (resurslar üçün): **U**tilization, **S**aturation, **E**rrors.
- **Dörd qızıl siqnal** (Google SRE): latency, traffic, errors, saturation.

## Tələlər

- **Instrumentasiyasız HTTP client** (`RestClient.create()`, `new RestTemplate()`) trace-i qırır.
- **Yüksək kardinallıq.** Metrika tag-ına id, e-poçt və ya tam URL yazmaq monitorinq sistemini yıxır.
- **Orta dəyərə baxmaq.** p95/p99 və histogram istifadə edin.
- **Asinxron kodda kontekst itir.** Trace konteksti `ThreadLocal`-dadır. `@Async` və öz executor-larınız üçün `ContextPropagatingTaskDecorator` istifadə edin; Reactor-da isə context propagation avtomatikdir (`Hooks.enableAutomaticContextPropagation()`).
- **Hər şeyi loglamaq.** Log ən bahalı siqnaldır. Səviyyələrdən düzgün istifadə edin; məxfi məlumatı (token, parol, kart) heç vaxt loglamayın.
- **Versiya uyğunsuzluğu.** OTel komponentlərinin versiyaları uyğun olmalıdır (appender ilə SDK).
- **Liveness-ə asılılıq qoşmaq.** Bu, restart dövrünə səbəb olur.
- **Alert yorğunluğu.** Hər şeyə alert qoymaq heç nəyə alert qoymamaq kimidir: vacib siqnal səs-küydə itir.

## Yadda saxla

- Metrika **nə qədər**, trace **harada**, log **niyə** sualına cavab verir; üçünü **trace id** bağlayır.
- Micrometer API-dir, OpenTelemetry isə standart və ötürmə yoludur; backend-i dəyişmək üçün yalnız konfiqurasiya dəyişir.
- Trace servislər arasında `traceparent` header-i ilə keçir; bunun üçün Spring-in instrumentasiyalı client builder-ləri lazımdır.
- **Observation** bir çağırışla həm metrika, həm span verir; `@Observed` də eynisini edir.
- **Kardinallıq:** metrika tag-ına yalnız az və sabit dəyərlər yazılır, id-lər span atributuna gedir.
- p95/p99 histogram-dan hesablanır; alert-lər SLO və simptomlar üzərində qurulur.

## Tapşırıqlar

1. Demo-da "Yavaş ödəniş" basın, trace id-ni Grafana → Explore → Tempo-da açın. Hansı span 1.5 saniyə çəkdi? Oradan "Logs for this span" ilə loglara keçin.
2. `CheckoutService`-də `highCardinalityKeyValue("customer", ...)`-i `lowCardinalityKeyValue` edin və "Yük: 50 sorğu" göndərin. `/actuator/prometheus`-da `checkout_process_seconds_count` neçə sətir oldu? Niyə?
3. `CheckoutService`-də `RestClient.Builder` əvəzinə `RestClient.create(baseUrl)` istifadə edin. Demo-dakı trace id-lərə baxın: inventory və payment-in trace id-si nə oldu?
4. Prometheus-da `sum by (result) (rate(orders_placed_total[1m]))` və p95 sorğusunu işlədin. `payment.failure-rate`-i 0.5 edin: qrafikdə nə dəyişdi?
5. (Çətin) "Uğurlu checkout-ların 99%-i 500 ms-dən tez olsun" SLO-su üçün PromQL ilə SLI yazın və error budget-in 1 saatda nə qədər yandığını hesablayın.

---

## Müsahibə sualları

Ən çox verilən observability sualları. Cavabı açmazdan əvvəl özünüz cavab verməyə çalışın.

### Əsaslar

<details>
<summary><b>1. Observability nədir və monitoring-dən nə ilə fərqlənir?</b></summary>

**Monitoring** əvvəlcədən bilinən suallara cavab verir: müəyyən metrikalar toplanır, həddən çıxanda alert gəlir. **Observability** isə sistemin xarici siqnallarından (metrika, trace, log) onun daxili vəziyyəti haqqında **əvvəlcədən düşünülməmiş** suallara da cavab tapmaq imkanıdır: "niyə yalnız bu region və bu versiyada yavaşdır?" Monitoring observability-nin bir hissəsidir.
</details>

<details>
<summary><b>2. Observability-nin üç pillar-ı hansılardır?</b></summary>

- **Metrikalar:** zamanla aqreqat rəqəmlər (sayğac, histogram, gauge). Ucuzdur, alert və trend üçündür, amma detal vermir.
- **Trace-lər:** bir sorğunun servislər üzrə yolu və hər addımın müddəti. "Harada" sualının cavabıdır.
- **Loglar:** konkret hadisələrin mətni. "Niyə" sualının cavabıdır, amma ən bahalı siqnaldır.

Getdikcə **profilinq** (continuous profiling: CPU və yaddaşın hansı kodda sərf olunduğu) dördüncü siqnal kimi əlavə olunur. Siqnalları trace id birləşdirir.
</details>

<details>
<summary><b>3. OpenTelemetry nədir?</b></summary>

CNCF layihəsidir: telemetriya (trace, metrika, log) üçün vendor-dan asılı olmayan standart. Tərkibi:

- API və SDK-lar (çox dil üçün);
- semantic conventions (atribut adları: `http.request.method`, `db.system`...);
- **OTLP** protokolu;
- **Collector:** qəbul, emal və export üçün ayrıca proses.

Kod bir dəfə instrumentasiya olunur, backend (Grafana, Datadog, Elastic...) isə konfiqurasiya ilə seçilir.
</details>

<details>
<summary><b>4. Micrometer ilə OpenTelemetry arasındakı əlaqə nədir?</b></summary>

**Micrometer** Spring ekosisteminin instrumentasiya API-sidir: `MeterRegistry` (metrikalar), `Observation` (metrika + trace birlikdə), Micrometer Tracing (tracer abstraksiyası). **OpenTelemetry** isə onun altında işləyən implementasiya və ötürmə yolu ola bilər: Micrometer Tracing-in OTel bridge-i span-ları OTel SDK-ya verir, OTLP registry isə metrikaları OTLP ilə göndərir. Spring kodu Micrometer ilə yazılır, export-u OTel edir.
</details>

<details>
<summary><b>5. OpenTelemetry Collector nə üçündür?</b></summary>

Tətbiqlə backend arasında ayrıca prosesdir. Nə edir:

- telemetriyanı qəbul edir (OTLP, Prometheus, Jaeger...);
- emal edir: batch, filtr, həssas məlumatın silinməsi, atribut əlavəsi, **tail sampling**;
- bir və ya bir neçə backend-ə göndərir.

Faydası: tətbiqin konfiqurasiyası sadə qalır, backend-i dəyişmək üçün tətbiqlərə toxunmaq lazım olmur, və yük və sampling mərkəzdən idarə olunur. Adətən agent (hər node-da) və gateway (mərkəzi) rejimində qurulur.
</details>

### Distributed tracing

<details>
<summary><b>6. Trace və span nədir?</b></summary>

**Trace** bir sorğunun bütün sistem üzrə yoludur. **Span** isə bu yolun bir addımıdır: HTTP çağırışı, DB sorğusu, metod. Hər span-ın adı, başlama və bitmə vaxtı, atributları, statusu, event-ləri və **parent span**-ı var. Bütün span-lar eyni **trace id**-ni paylaşır və parent əlaqələri ilə ağac əmələ gətirir. Bu ağac UI-da şəlalə (waterfall) kimi göstərilir.
</details>

<details>
<summary><b>7. Trace konteksti servislər arasında necə ötürülür?</b></summary>

**Context propagation** ilə: çağıran servis trace id-ni və öz span id-sini sorğuya header kimi əlavə edir; qəbul edən servis onu oxuyub yeni, uşaq span yaradır. Standart header W3C Trace Context-dir: `traceparent: 00-<trace-id>-<parent-span-id>-<flags>` (əlavə olaraq `tracestate`). Köhnə sistemlərdə B3 (`X-B3-TraceId`) istifadə olunur. Kafka və digər broker-lərdə eyni məlumat mesaj header-ində gedir.
</details>

<details>
<summary><b>8. Baggage nədir?</b></summary>

Trace konteksti ilə birlikdə bütün servislərə ötürülən açar-dəyər cütləridir (məs. `tenant-id`, `user-tier`). Aşağı servislər onu metrikaya, loga və ya span-a əlavə edə bilər. Hər sorğuda hər servisə gedir, ona görə kiçik saxlanılmalı və məxfi məlumat ehtiva etməməlidir. Spring-də `management.tracing.baggage.remote-fields` və `correlation.fields` ilə idarə olunur.
</details>

<details>
<summary><b>9. Trace servislər arasında niyə "qırıla" bilər?</b></summary>

- **Instrumentasiyasız HTTP client:** `new RestTemplate()`, `RestClient.create()`, əl ilə qurulmuş `HttpClient`.
- **Asinxron keçid:** `@Async`, öz executor-u, `CompletableFuture` context propagation olmadan.
- **Mesaj broker-i:** observation söndürülüb.
- **Propagation formatı uyğun deyil:** bir servis W3C, digəri B3 gözləyir.
- **Proxy və ya gateway** header-i silir.
- **Sampling qərarı** fərqli qəbul olunur.
</details>

<details>
<summary><b>10. Head sampling ilə tail sampling arasında fərq nədir?</b></summary>

- **Head sampling:** qərar sorğunun əvvəlində (təsadüfi, məs. 10%) verilir və bütün servislərə ötürülür. Ucuz və sadədir, amma xətalı və ya yavaş trace-lərin də 90%-i atılır.
- **Tail sampling:** Collector trace-in bütün span-larını gözləyir və sonra qərar verir: bütün xətaları və yavaşları, normalların isə kiçik hissəsini saxlayır. Daha dəyərlidir, amma Collector-da yaddaş və trace-in bütün span-larının eyni instansa çatması tələb olunur.
</details>

<details>
<summary><b>11. Exemplar nədir?</b></summary>

Metrika nöqtəsinə əlavə olunan nümunə trace id-dir. Məsələn, p99 latency qrafikindəki sıçrayışa klikləyəndə həmin ana aid real trace açılır. Beləliklə, metrikadan ("nə vaxt və nə qədər") birbaşa trace-ə ("harada") keçmək olur. Micrometer və Prometheus exemplar-ları dəstəkləyir (`management.tracing.exemplars`).
</details>

### Metrikalar

<details>
<summary><b>12. Counter, gauge, timer və histogram arasında fərq nədir?</b></summary>

- **Counter:** yalnız artan sayğac (sorğu sayı, xəta sayı). Faydalı olan onun sürətidir: `rate()`.
- **Gauge:** anlıq, artıb-azalan dəyər (növbənin uzunluğu, stok, bağlantı sayı).
- **Timer:** hadisələrin sayı və müddəti; percentile üçün histogram ilə.
- **Histogram / DistributionSummary:** dəyərlərin bucket-lar üzrə paylanması (müddət olmayan dəyərlər üçün də, məs. sifariş məbləği).
</details>

<details>
<summary><b>13. Kardinallıq nədir və niyə təhlükəlidir?</b></summary>

Metrikanın unikal tag kombinasiyalarının sayıdır; hər kombinasiya ayrıca time series-dir. `userId`, `orderId`, tam URL və ya e-poçt kimi çoxlu dəyəri olan tag series sayını milyonlara çatdırır. Nəticə: yaddaş və disk xərci, yavaş sorğular, yıxılan Prometheus və böyük hesab. Qayda: metrika tag-ları az və sabit dəyərli olur (`status`, `method`, URI template-i), yüksək kardinallı dəyərlər isə span atributlarına və loglara gedir.
</details>

<details>
<summary><b>14. Niyə orta latency yox, percentile-lər?</b></summary>

Orta dəyər az sayda çox yavaş sorğunu "gizlədir" və istifadəçi təcrübəsini əks etdirmir. p95 və p99 "istifadəçilərin 5%-i və 1%-i ən azı bu qədər gözləyir" deyir. Çoxlu çağırışdan ibarət səhifədə isə p99 demək olar ki, hər istifadəçiyə təsir edir: 20 çağırışlı səhifədə ən azı birinin p99-a düşmə ehtimalı ~18%-dir.
</details>

<details>
<summary><b>15. Percentile-ləri necə hesablamaq lazımdır? Niyə pod-da hesablanmış percentile-ləri toplamaq olmaz?</b></summary>

Percentile-lər riyazi olaraq toplanmır: iki pod-un p99-larının ortası ümumi p99 deyil. Ona görə pod-lar **histogram bucket**-larını (hər intervalda neçə hadisə olduğunu) göndərir, percentile isə backend-də bütün pod-ların bucket-ları toplanandan sonra hesablanır (`histogram_quantile(0.99, sum by (le) (rate(..._bucket[5m])))`). Nəticə təxminidir və bucket sərhədlərindən asılıdır; Micrometer-in `percentiles-histogram` seçimi uyğun bucket-lar yaradır.
</details>

<details>
<summary><b>16. Pull (Prometheus) və push (OTLP) modelləri arasında fərq nədir?</b></summary>

- **Pull:** Prometheus hər N saniyədə tətbiqin `/actuator/prometheus` endpoint-ini oxuyur. Service discovery ilə sadədir, "hədəf düşüb" dərhal görünür, amma qısaömürlü proseslər üçün çətindir.
- **Push:** tətbiq metrikaları özü göndərir (OTLP, Pushgateway). Firewall arxasında, serverless və batch işlərdə rahatdır, backend-i dəyişmək də asandır.

Bu modul ikisini də göstərir: OTLP ilə push edir, eyni zamanda `/actuator/prometheus` açıqdır.
</details>

<details>
<summary><b>17. RED və USE metodları nədir?</b></summary>

- **RED** (servislər üçün): **R**ate (saniyədə sorğu), **E**rrors (xəta sayı və ya faizi), **D**uration (latency paylanması).
- **USE** (resurslar üçün: CPU, disk, pool): **U**tilization (nə qədər məşğuldur), **S**aturation (növbə, gözləmə), **E**rrors.

Google SRE-nin **dörd qızıl siqnalı** (latency, traffic, errors, saturation) ikisini birləşdirir. Hər servisin dashboard-u bunlardan başlayır.
</details>

### Loglar

<details>
<summary><b>18. Structured logging nədir və niyə lazımdır?</b></summary>

Log sətri sərbəst mətn yox, maşının oxuya bildiyi strukturdur (adətən JSON): sahələr `timestamp`, `level`, `logger`, `message`, `traceId`, `userId`, `orderId`... Faydası: sahələr üzrə axtarış və filtr (`orderId=42`), aqreqasiya, regex-siz parsing, və loglarla trace-lərin avtomatik əlaqələndirilməsi. Spring Boot 3.4+-da `logging.structured.format.console: ecs|logstash|gelf` ilə aktivləşir.
</details>

<details>
<summary><b>19. MDC nədir və trace id loglara necə düşür?</b></summary>

MDC (Mapped Diagnostic Context) SLF4J/Logback-in thread-local xəritəsidir: oraya qoyulan dəyərlər həmin thread-in bütün log sətirlərinə avtomatik əlavə olunur. Micrometer Tracing aktiv span-ın `traceId` və `spanId`-sini MDC-yə yazır; Spring Boot-un log pattern-i (`logging.pattern.correlation`) onları sətirə əlavə edir. Asinxron keçiddə MDC də kontekstlə birlikdə ötürülməlidir.
</details>

<details>
<summary><b>20. Log səviyyələrindən necə istifadə etmək lazımdır?</b></summary>

- **ERROR:** kiminsə baxmalı olduğu xəta (əməliyyat uğursuz oldu).
- **WARN:** gözlənilməz, amma idarə olunan vəziyyət (retry, fallback işlədi).
- **INFO:** biznes hadisələri və həyat dövrü (sifariş yaradıldı, tətbiq başladı), ölçülü şəkildə.
- **DEBUG/TRACE:** inkişaf və diaqnostika; production-da adətən söndürülür, lazım olanda runtime-da (`/actuator/loggers`) yandırılır.

Hər sorğuda bir neçə INFO sətri yüksək trafikdə böyük xərc deməkdir.
</details>

<details>
<summary><b>21. Loglarda nə olmamalıdır?</b></summary>

Parollar, token-lər (`Authorization` header-i, refresh token), kart və hesab nömrələri, şəxsi məlumatlar (GDPR/KVKK), sessiya id-ləri, API açarları və tam request body-ləri (orada bunların hər biri ola bilər). Maskalama (`****1234`), allowlist ilə loglama və Collector-da həssas sahələrin silinməsi istifadə olunur.
</details>

### Spring Boot və praktika

<details>
<summary><b>22. Spring Boot Actuator nədir və hansı endpoint-ləri vacibdir?</b></summary>

Tətbiqin idarə və monitorinq endpoint-ləri: `health` (liveness və readiness ilə), `metrics`, `prometheus`, `info`, `loggers` (runtime-da səviyyəni dəyişmək), `env`, `threaddump`, `heapdump`. Production-da yalnız lazım olanlar açılır (`management.endpoints.web.exposure.include`), həssas olanlar (`env`, `heapdump`) isə qorunur və ya ayrı porta (`management.server.port`) çıxarılır.
</details>

<details>
<summary><b>23. Liveness ilə readiness probe arasında fərq nədir?</b></summary>

- **Liveness:** "proses sağdırmı, yoxsa ilişib?" Uğursuz olsa, Kubernetes pod-u **restart** edir.
- **Readiness:** "hazırdırmı?" Uğursuz olsa, pod **trafikdən çıxarılır**, restart olunmur (start zamanı, yüklənmə, müvəqqəti həddən artıq yük).

Xarici asılılıqlar liveness-də olmamalıdır (restart onları düzəltmir və bütün pod-lar eyni anda restart olunur). Readiness-ə də diqqətlə qoşulmalıdır.
</details>

<details>
<summary><b>24. <code>@Observed</code> və Observation API nə verir?</b></summary>

Bir müşahidədən bir neçə handler eyni anda nəticə yaradır: **timer** metrikası, **span** və istəyə görə log və ya xüsusi məntiq. Kod bir dəfə yazılır. `lowCardinalityKeyValue` metrika tag-ı və span atributu olur, `highCardinalityKeyValue` isə yalnız span atributu. `@Observed` annotasiyası (AOP ilə) eyni şeyi metod səviyyəsində edir. Spring-in öz instrumentasiyaları (HTTP, JDBC, Kafka) da bu API ilə yazılıb.
</details>

<details>
<summary><b>25. Asinxron kodda trace konteksti necə ötürülür?</b></summary>

Kontekst `ThreadLocal`-dadır və başqa thread-ə avtomatik keçmir. Yollar:

- `ContextPropagatingTaskDecorator`-u bean kimi təyin etmək (Spring Boot onu öz `@Async` və scheduling executor-larına tətbiq edir), öz executor-larınızda isə onu əl ilə qoşmaq;
- `ContextSnapshot` ilə əl ilə köçürmək;
- Reactor-da `Hooks.enableAutomaticContextPropagation()`;
- Kafka və digər broker-lərdə observation-ı yandırmaq.

Virtual thread-lərdə də eyni qayda keçərlidir.
</details>

### Əməliyyat və alert

<details>
<summary><b>26. SLI, SLO və SLA arasında fərq nədir?</b></summary>

- **SLI:** ölçülən göstərici (uğurlu sorğuların payı, 300 ms-dən tez cavabların payı).
- **SLO:** daxili hədəf (30 gündə 99.9%).
- **SLA:** müştəri ilə müqavilə və pozulduqda cərimələr; adətən SLO-dan yumşaq olur.

SLO-lar istifadəçinin hiss etdiyi davranışa əsaslanmalıdır, texniki metrikalara (CPU) yox.
</details>

<details>
<summary><b>27. Error budget və burn rate nədir?</b></summary>

**Error budget** SLO-nun icazə verdiyi uğursuzluqdur: 99.9% SLO 30 gündə ~43 dəqiqə və ya sorğuların 0.1%-i deməkdir. Budget qalıbsa, komanda risk götürüb sürətlə deploy edə bilər; bitibsə, stabilliyə fokuslanır. **Burn rate** budget-in nə qədər sürətlə xərcləndiyidir: burn rate 1 budget-in düz 30 günə çatması, 14.4 isə budget-in hər saatda 2%-inin yanması (30 günlük budget-in ~2 günə bitməsi) deməkdir. Alert-lər burn rate-ə qurulur (qısa və uzun pəncərə ilə): həm sürətli, həm də yavaş "yanma" tutulur, səs-küy isə az olur.
</details>

<details>
<summary><b>28. Yaxşı alert necə olmalıdır?</b></summary>

- **Simptoma** əsaslanır (istifadəçi əziyyət çəkir: xəta faizi, latency, SLO burn rate), səbəbə yox (CPU 80%).
- **Hərəkətə keçməyi** tələb edir: hər alert-in runbook-u olur.
- **Az səs-küylüdür:** yalançı alert-lər vacib olanları gizlədir (alert fatigue).
- **Ciddiliyə görə** yönləndirilir: gecə oyadan (page) və iş saatında baxılan (ticket).
- **Kontekst** verir: dashboard, trace və log linkləri.
</details>

<details>
<summary><b>29. Production-da gözlənilməz yavaşlığı necə araşdırarsınız?</b></summary>

1. **Metrikalar:** nə vaxtdan başladı, hansı endpoint, hansı instance və ya region, hansı deploy-dan sonra? Xəta faizi, trafik və saturation dəyişibmi?
2. **Exemplar və ya trace axtarışı:** yavaş trace-ləri tapıb şəlaləyə baxmaq: hansı span uzundur (DB, xarici API, lock gözləməsi)?
3. **Həmin trace-in logları:** xəta, retry, timeout mesajları.
4. **Resurs metrikaları:** DB pool-un doluluğu, GC, thread-lər, CPU throttling.
5. **Dəyişikliklər:** deploy, konfiqurasiya, asılılıqların statusu.
6. Hipotez yoxlanandan sonra düzəliş və **postmortem**.
</details>

<details>
<summary><b>30. Observability-nin xərcini necə idarə etmək olar?</b></summary>

- Metrikalarda kardinallığa nəzarət: lazımsız tag və metrikaları söndürmək (`management.metrics.enable.*`).
- Trace-lərdə sampling (xüsusən tail sampling).
- Loglarda səviyyələr, sampling və qısa retention; DEBUG-u söndürmək.
- Collector-da filtr və aqreqasiya.
- Siqnala görə fərqli retention: metrikalar uzun, loglar qısa.
- Nəyin həqiqətən istifadə olunduğunu ölçmək: heç kimin baxmadığı dashboard və metrikaları silmək.
</details>

---

[← 23. Spring Security və JWT](23-security-jwt.md) · [Mündəricat](README.md)
