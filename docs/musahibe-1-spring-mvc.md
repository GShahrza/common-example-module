# Müsahibə sualları: I hissə, Spring MVC və HTTP streaming

[Mündəricat](README.md) · Fəsillər: [1](01-servlet-modeli.md) · [2](02-sse.md) · [3](03-yeniden-qosulma.md) · [4](04-ndjson.md) · [5](05-fayl-streaming.md) · [6](06-production.md)

Spring MVC, servlet modeli, virtual thread-lər və HTTP streaming üzrə ən çox verilən 30 sual. Cavabı açmazdan əvvəl özünüz cavab verməyə çalışın; mötərizədəki linklər ətraflı izaha aparır.

---

### Spring MVC-nin əsasları

<details>
<summary><b>1. <code>DispatcherServlet</code> nədir və sorğu Spring MVC-də hansı yolla keçir?</b></summary>

`DispatcherServlet` front controller-dir: bütün sorğular ondan keçir. Yol belədir:

1. Servlet filtrləri (Spring Security, CORS, encoding).
2. `DispatcherServlet`.
3. `HandlerMapping`: hansı controller metodunun çağırılacağını tapır.
4. `HandlerInterceptor.preHandle`.
5. `HandlerAdapter` metodu çağırır: arqument resolver-lər `@RequestBody`, `@PathVariable` və s. doldurur, validasiya işləyir.
6. Qaytarılan dəyər `HttpMessageConverter` ilə (JSON üçün Jackson) cavaba yazılır.
7. `postHandle` / `afterCompletion`.

Xəta olanda `HandlerExceptionResolver`-lər (`@ExceptionHandler`, `@ControllerAdvice`) işə düşür.
</details>

<details>
<summary><b>2. Filter ilə <code>HandlerInterceptor</code> arasında fərq nədir?</b></summary>

- **Filter** Servlet spesifikasiyasının hissəsidir: `DispatcherServlet`-dən **əvvəl** işləyir, bütün sorğuları görür (statik resurslar da), request və response-u bükə bilər (security, CORS, loglama, sıxılma).
- **Interceptor** Spring MVC-nin hissəsidir: handler məlum olandan sonra işləyir və hansı controller metodunun çağırılacağını bilir.

Qayda: aşağı səviyyəli, HTTP-yə aid işlər üçün filter; handler-ə aid işlər (məs. annotasiyaya görə yoxlama) üçün interceptor.
</details>

<details>
<summary><b>3. <code>@Controller</code> ilə <code>@RestController</code> arasında fərq nədir?</b></summary>

`@RestController` = `@Controller` + `@ResponseBody`: metodun qaytardığı dəyər view adı kimi yox, birbaşa cavabın body-si kimi (message converter ilə JSON-a) yazılır. `@Controller` isə adətən server tərəfdə render olunan view-lər (Thymeleaf) üçündür; body qaytarmaq üçün metodda `@ResponseBody` lazımdır.
</details>

<details>
<summary><b>4. Spring MVC-də xətaları mərkəzləşdirilmiş şəkildə necə idarə etmək olar?</b></summary>

`@RestControllerAdvice` + `@ExceptionHandler` metodları exception tipinə görə cavab qaytarır. Spring 6+-da standart format **Problem Details** (RFC 9457, `ProblemDetail`, `application/problem+json`) dəstəklənir. `ResponseEntityExceptionHandler`-i genişləndirmək Spring-in öz xətalarını (400 validasiya, 405, 415) da eyni formata salır. `ResponseStatusException` isə yerində status atmaq üçündür.
</details>

<details>
<summary><b>5. Validasiya necə işləyir? <code>@Valid</code> ilə <code>@Validated</code> fərqi?</b></summary>

- `@Valid` (Jakarta Bean Validation) `@RequestBody` və ya obyekt parametrində annotasiyaları (`@NotBlank`, `@Positive`) yoxlayır; uğursuzluqda `MethodArgumentNotValidException` → 400 atılır.
- `@Validated` (Spring) validasiya **qruplarını** dəstəkləyir. Sinfin üstündə olanda isə metod parametrlərini birbaşa (`@RequestParam @Min(1) int size`) yoxlamağa imkan verir: `ConstraintViolationException` və ya Spring 6.1+-da `HandlerMethodValidationException`.
</details>

### Servlet modeli və thread-lər ([1-ci fəsil](01-servlet-modeli.md))

<details>
<summary><b>6. Thread-per-request modeli nədir və onun məhdudiyyəti nədir?</b></summary>

Servlet konteyneri (Tomcat) hər sorğu üçün pool-dan bir thread götürür və cavab tamamlanana qədər onu saxlayır. Kod sadə və ardıcıldır, bloklayan I/O (JDBC, HTTP) təbiidir. Məhdudiyyət thread sayıdır (Tomcat default 200): hər sorğu yavaş asılılığı gözləyirsə, thread-lər bitir və yeni sorğular növbədə qalır. Platform thread-ləri bahalıdır (yaddaş, context switch), ona görə minlərlə açmaq olmur.
</details>

<details>
<summary><b>7. Virtual thread-lər nədir və Spring MVC-yə nə verir?</b></summary>

Java 21-in yüngül thread-ləridir: JVM onları az sayda OS thread-i (carrier) üzərində planlaşdırır. Virtual thread bloklayan I/O-da gözləyəndə carrier-i buraxır. Nəticədə minlərlə, hətta milyonlarla eyni vaxtlı gözləyən sorğu ucuz olur. Spring Boot-da `spring.threads.virtual.enabled: true` Tomcat-ın, `@Async`-in və scheduling-in virtual thread-lərdən istifadə etməsini təmin edir. Adi, bloklayan kod (JPA, JDBC, RestClient) dəyişmədən miqyaslanır: bu, WebFlux-a keçmədən yüksək eyni vaxtlılıq deməkdir.
</details>

<details>
<summary><b>8. Virtual thread-lərin tələləri nədir?</b></summary>

- **Pinning:** `synchronized` blokunun içində bloklanan virtual thread carrier-i buraxmırdı (Java 24-də JEP 491 ilə əsasən aradan qaldırılıb); native çağırışlar da pin edir.
- **Resurs limitləri qalır:** DB bağlantı pool-u (məs. 10 bağlantı) darboğaz olur, və minlərlə virtual thread bağlantı gözləyir.
- **`ThreadLocal`:** hər virtual thread-in öz nüsxəsi var, və milyonlarla thread-də böyük `ThreadLocal`-lar yaddaşı doldurur.
- **CPU ağır işlər:** virtual thread sürətləndirmir.
- **Pool etməyin:** hər tapşırıq üçün yeni virtual thread yaradılır.
</details>

<details>
<summary><b>9. Spring MVC-də asinxron sorğu emalı necə işləyir? (<code>Callable</code>, <code>DeferredResult</code>, emitter-lər)</b></summary>

Controller `Callable`, `DeferredResult`, `CompletableFuture`, `ResponseBodyEmitter`, `SseEmitter` və ya `StreamingResponseBody` qaytaranda Servlet 3 async rejimi işə düşür. Tomcat thread-i azad olur, cavab isə başqa thread-dən (öz executor-unuzdan və ya hadisədən) sonra tamamlanır. Bu, uzun gözləmələrdə (long polling, streaming) konteyner thread-lərini boşaltmaq üçündür. Timeout-lar `spring.mvc.async.request-timeout` və ya emitter-in öz timeout-u ilə idarə olunur.
</details>

<details>
<summary><b>10. Spring MVC + virtual thread-lər, yoxsa WebFlux?</b></summary>

- **Spring MVC + virtual thread-lər:** adi CRUD, JPA/JDBC və bloklayan kitabxanalar; komanda reaktivi bilmir; sadə debug və stack trace. Java 21-dən sonra əksər servislər üçün default seçimdir.
- **WebFlux:** tam reaktiv stack (R2DBC, reaktiv client-lər), axınların birləşdirilməsi və çevrilməsi, backpressure, çoxlu uzunömürlü bağlantı (gateway, BFF, streaming).

Ətraflı: [18-ci fəsil](18-secim.md).
</details>

### SSE və streaming ([2](02-sse.md), [3](03-yeniden-qosulma.md), [4](04-ndjson.md), [5](05-fayl-streaming.md))

<details>
<summary><b>11. Cavabı hissə-hissə göndərməyin yolları hansılardır?</b></summary>

- **SSE** (`text/event-stream`): serverdən brauzerə hadisələr axını; brauzerdə `EventSource` var.
- **NDJSON / JSON Lines:** hər sətir ayrı JSON obyekti; böyük siyahılar üçün.
- **Chunked transfer encoding ilə fayl streaming** (`StreamingResponseBody`).
- **WebSocket:** iki tərəfli.
- **gRPC streaming:** servislər arası.
- **Long polling:** köhnə üsul.

Ümumi fikir budur ki, cavabın hamısı hazır olmadan ilk hissə göndərilir.
</details>

<details>
<summary><b>12. Server-Sent Events nədir? WebSocket-dən fərqi nədir?</b></summary>

SSE adi HTTP cavabıdır. Server `Content-Type: text/event-stream` ilə bağlantını açıq saxlayır və `event:`, `data:`, `id:` sətirləri ilə hadisələr göndərir. **Bir istiqamətlidir** (server → klient). Brauzerdə avtomatik yenidən qoşulma və `Last-Event-ID` var, proxy və HTTP infrastrukturu ilə uyğundur. **WebSocket** isə iki istiqamətli, ayrıca protokoldur (`Upgrade`); chat və oyun üçündür. Yalnız serverdən yeniləmə lazımdırsa (bildiriş, progress, LLM cavabı), SSE daha sadədir.
</details>

<details>
<summary><b>13. <code>SseEmitter</code>, <code>ResponseBodyEmitter</code> və <code>StreamingResponseBody</code> arasında fərq nədir?</b></summary>

- **`SseEmitter`:** SSE formatında hadisələr (`event`, `id`, `data`, comment, `retry`).
- **`ResponseBodyEmitter`:** hər `send()` message converter ilə yazılır və flush olunur; NDJSON kimi formatlar üçün.
- **`StreamingResponseBody`:** birbaşa `OutputStream`-ə yazırsınız; fayl, CSV və binary məlumat üçün, message converter-siz.

Üçü də async rejimdə işləyir və Tomcat thread-ini tutmur. Yazmaq isə sizin thread-inizdə gedir.
</details>

<details>
<summary><b>14. SSE-də əlaqə kəsiləndə itən hadisələr necə bərpa olunur?</b></summary>

Hər hadisəyə `id` verilir. Brauzer yenidən qoşulanda son aldığı id-ni `Last-Event-ID` header-ində göndərir, server isə ondan sonrakı hadisələri göndərir. Bunun üçün server hadisələri müəyyən müddət saxlamalıdır (yaddaş, Redis, baza). Uzun iş və onun izlənməsi ayrı saxlanılır: `POST` işi başladır, `GET` SSE ilə izləyir, və əlaqənin kəsilməsi işi dayandırmır. Ətraflı: [3-cü fəsil](03-yeniden-qosulma.md).
</details>

<details>
<summary><b>15. SSE stream başladıqdan sonra xəta olsa, nə etmək lazımdır?</b></summary>

HTTP status və header-lər artıq göndərilib (200), onları dəyişmək mümkün deyil. Xəta **hadisə kimi** göndərilir (`event: error`) və stream bağlanır. Buna görə yoxlamalar (resurs mövcuddurmu, icazə varmı) stream açılmazdan **əvvəl** edilir, ki 404 və 403 normal cavab kimi qaytarılsın.
</details>

<details>
<summary><b>16. NDJSON nədir və nə vaxt adi JSON massivindən yaxşıdır?</b></summary>

Newline-delimited JSON: hər sətirdə bir tam JSON obyekti. Böyük siyahıda adi JSON massivi yalnız sonda "tamamlanır", ona görə klient hamısını gözləməli və yaddaşa yükləməlidir. NDJSON isə sətir-sətir göndərilir və emal olunur. Modulda ölçülüb: 2000 sətirdə ilk sətir 3036 ms əvəzinə 155 ms-də gəldi ([4-cü fəsil](04-ndjson.md)). Klientlər hər sətri ayrıca parse edir.
</details>

<details>
<summary><b>17. Milyon sətirlik CSV-ni yaddaşı doldurmadan necə yükləmək olar?</b></summary>

`StreamingResponseBody` ilə sətirləri birbaşa response `OutputStream`-ə yazmaq və mənbəni də hissə-hissə oxumaq: səhifələmə, cursor, JDBC `fetchSize`, və ya Spring Data-da `Stream<T>` + `@Transactional(readOnly = true)`. `Content-Disposition: attachment` fayl adını verir. Client bağlantını kəsəndə `IOException` alınır, və bu an istehsal dayandırılmalıdır. Ətraflı: [5-ci fəsil](05-fayl-streaming.md).
</details>

<details>
<summary><b>18. Client bağlantını kəsəndə server bunu necə bilir və nə etməlidir?</b></summary>

Növbəti yazma cəhdi `IOException` (məs. "Broken pipe") verir. Emitter-lərdə isə `onError` və `onCompletion` callback-ləri çağırılır. Server bunu görəndə istehsalı (sorğuları, hesablamanı) **dayandırmalıdır**, yoxsa heç kimin oxumadığı məlumatı hazırlamağa davam edir. Bunun üçün bayraq (`AtomicBoolean open`) və ya ləğv mexanizmi istifadə olunur.
</details>

### Production ([6-cı fəsil](06-production.md))

<details>
<summary><b>19. Streaming lokalda işləyir, production-da isə cavab bir dəfəyə gəlir. Səbəb nə ola bilər?</b></summary>

- **Proxy buferləməsi:** nginx `proxy_buffering on` cavabı yığıb sonra göndərir. Həlli: `X-Accel-Buffering: no` header-i və ya konfiqurasiya.
- **Gzip:** sıxılma kiçik parçaları buferləyir.
- **CDN və ya load balancer** buferləyir.
- **Kodda flush yoxdur.**
- **Timeout-lar:** proxy-nin idle timeout-u uzun stream-i kəsir.

Yoxlamaq üçün `curl -N` ilə birbaşa və proxy vasitəsilə müqayisə edin.
</details>

<details>
<summary><b>20. Uzun SSE bağlantılarını proxy-lərin bağlamasının qarşısını necə almaq olar?</b></summary>

Mütəmadi olaraq **heartbeat** göndərmək: məsələn, hər 15-30 saniyədə SSE comment sətri (`:ping`). Brauzer comment-ləri görməzdən gəlir, proxy isə əlaqədə trafik görür. Bundan əlavə, proxy və load balancer-lərin idle timeout-larını uyğunlaşdırmaq və server tərəfdə emitter timeout-unu məqsədyönlü seçmək lazımdır.
</details>

<details>
<summary><b>21. Bir çox SSE client olanda serverin resursları necə hesablanır?</b></summary>

Hər açıq SSE bağlantısı bir socket və bir emitter obyektidir. Async rejimdə konteyner thread-i tutulmur, amma yazan tərəf (executor) və yaddaş (buferlər, gözləyən hadisələr) nəzərə alınmalıdır. Limitlər:

- OS fayl deskriptorları (`ulimit`);
- proxy bağlantı limitləri;
- HTTP/1.1-də brauzerin eyni domen üçün ~6 bağlantı limiti (HTTP/2 bunu aradan qaldırır).

Bir neçə instance olanda hadisələri bütün instance-lara paylamaq üçün Redis pub/sub və ya Kafka lazımdır.
</details>

<details>
<summary><b>22. Yavaş client serveri necə yükləyə bilər və bundan necə qorunmaq olar?</b></summary>

Yavaş oxuyan client-ə yazılan məlumat server buferlərində yığılır, blocking yazıda isə thread gözləyir. Qorunma yolları:

- yazma timeout-ları;
- client başına bufer limiti;
- yavaş client-i kəsmək;
- mənbədən istehsalı client-in sürətinə uyğunlaşdırmaq (backpressure, [11-ci fəsil](11-backpressure.md)).
</details>

<details>
<summary><b>23. Chunked transfer encoding nədir?</b></summary>

HTTP/1.1-də cavabın ölçüsü əvvəlcədən məlum olmayanda (`Content-Length` yoxdur) body "chunk"-larla göndərilir: hər chunk-ın ölçüsü və özü, sonda sıfır ölçülü chunk. Streaming cavabların əsasıdır. HTTP/2-də bunun yerinə öz DATA frame-ləri var.
</details>

### Ümumi HTTP və API sualları

<details>
<summary><b>24. İdempotent HTTP metodları hansılardır və niyə vacibdir?</b></summary>

`GET`, `HEAD`, `PUT`, `DELETE` və `OPTIONS` idempotentdir: eyni sorğunu bir neçə dəfə göndərmək serverin vəziyyətini bir dəfə göndərməklə eyni edir. `POST` və `PATCH` isə ümumiyyətlə idempotent deyil. Bu, retry-lar üçün vacibdir: şəbəkə xətasından sonra idempotent sorğunu təkrarlamaq təhlükəsizdir, `POST` üçün isə idempotency açarı lazımdır ([23-cü fəsil](23-security-jwt.md), [22-ci fəsil](22-resilience.md)).
</details>

<details>
<summary><b>25. API versiyalamanın yolları hansılardır?</b></summary>

- URL-də (`/api/v1/orders`): ən sadə və görünən; mobil tətbiqlər üçün tövsiyə olunur.
- Header-də (`Accept: application/vnd.shop.v2+json` və ya xüsusi header).
- Query parametri.

Spring Framework 7-də API versioning daxili dəstəklənir (`@RequestMapping(version = ...)`). Əsas qayda: geriyə uyğun dəyişikliklər (yeni sahə) versiya tələb etmir, sındıran dəyişikliklər isə yeni versiya tələb edir.
</details>

<details>
<summary><b>26. Böyük siyahılarda səhifələmə necə edilir? Offset və cursor fərqi?</b></summary>

- **Offset/limit** (`?page=5&size=20`): sadədir, amma böyük offset-də baza hamısını keçməli olur (yavaş). Yeni sətirlər əlavə olunanda isə sətirlər təkrarlanır və ya itir.
- **Cursor (keyset)** (`?after=12345`): son görülən açardan sonrakıları istəyir; sabit sürətlidir və dəyişikliklərə davamlıdır, amma "37-ci səhifəyə keç" mümkün deyil.

Mobil sonsuz scroll üçün cursor, admin cədvəlləri üçün offset uyğundur.
</details>

<details>
<summary><b>27. CORS nədir və Spring MVC-də necə konfiqurasiya olunur?</b></summary>

Brauzerin başqa origin-dən (domen, port, protokol) API çağırışına icazə mexanizmidir: server `Access-Control-Allow-Origin` və digər header-lərlə icazə verir; "mürəkkəb" sorğulardan əvvəl brauzer `OPTIONS` preflight göndərir. Spring-də `@CrossOrigin`, `WebMvcConfigurer.addCorsMappings` və ya Spring Security ilə `CorsConfigurationSource` istifadə olunur. CORS server-i qorumur, yalnız brauzerin davranışını idarə edir ([23-cü fəsil](23-security-jwt.md)).
</details>

<details>
<summary><b>28. HTTP keep-alive və HTTP/2 multiplexing nədir?</b></summary>

- **Keep-alive** (HTTP/1.1): bir TCP bağlantısı ardıcıl bir neçə sorğu üçün istifadə olunur, hər dəfə yeni handshake edilmir. Amma bir bağlantıda eyni anda yalnız bir sorğu gedir (head-of-line blocking).
- **HTTP/2 multiplexing:** bir bağlantıda çoxlu paralel "stream", binary framing və header sıxılması (HPACK). gRPC bunun üzərində qurulub ([12-ci fəsil](12-grpc-nedir.md)).
</details>

<details>
<summary><b>29. <code>RestTemplate</code>, <code>RestClient</code> və <code>WebClient</code> arasında fərq nədir?</b></summary>

- **`RestTemplate`:** köhnə, sinxron HTTP client; maintenance rejimindədir.
- **`RestClient`** (Spring 6.1+): müasir, fluent, sinxron client; virtual thread-lərlə Spring MVC-də tövsiyə olunan seçimdir.
- **`WebClient`:** reaktiv, non-blocking client (`Mono`/`Flux`); WebFlux və streaming üçün.

Hər üçü Spring-in inject etdiyi builder ilə yaradılanda observability (trace, metrika) ilə instrumentasiya olunur ([24-cü fəsil](24-observability.md)).
</details>

<details>
<summary><b>30. Streaming endpoint-i necə test etmək olar?</b></summary>

Yalnız məzmunu yox, **vaxtı** da yoxlamaq lazımdır: cavab buferlənmiş ola bilər və məzmun yenə düzgün görünər. Modulda istifadə olunan yollar:

- real HTTP client ilə ilk hissənin gəlmə vaxtını ölçmək;
- SSE hadisələrinin ardıcıllığını və `id`-lərini yoxlamaq;
- `Last-Event-ID` ilə yenidən qoşulmanı sınamaq;
- client-in bağlantını kəsdiyi halda istehsalın dayandığını yoxlamaq.

Qeyd: `MockMvc` async cavabları dəstəkləyir (`asyncDispatch`), amma real streaming davranışını tam göstərmir.
</details>

---

[Mündəricat](README.md) · Növbəti: [II hissə, WebFlux sualları →](musahibe-2-webflux.md)
