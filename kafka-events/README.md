# kafka-events: event-driven arxitektura (Kafka)

Mikroservislər arasında event-lərlə işləməyin production-da ən çox rast gəlinən problemləri və onların həlli, bir sifariş saga-sı üzərində.

| Problem | Həll | Kod |
|---|---|---|
| Bazaya yazdım, Kafka-ya göndərə bilmədim (və ya əksinə) | **Transactional outbox** | `outbox/` |
| Eyni mesaj iki dəfə gəldi, pul iki dəfə çıxıldı | **Idempotent consumer** | `PaymentService.charge` |
| Xarici servis müvəqqəti işləmir | **Non-blocking retry** (retry topic-lər) | `@RetryableTopic` |
| Mesaj heç cür emal olunmur | **Dead letter topic (DLT)** | `@DltHandler` |
| Bir neçə servisə yayılmış əməliyyat (distributed transaction yoxdur) | **Saga + kompensasiya** | `OrderService.apply` |

📖 Kitab üslubunda izah və 30 müsahibə sualı: [20. Kafka: event-driven arxitektura və etibarlı mesajlaşma](../docs/20-kafka.md).

## İşə salma qaydası

Ardıcıllıqla:

### 1. Kafka-nı işə salın

```bash
docker run -d --name kafka -p 9092:9092 apache/kafka:4.1.0
```

Bu, tək node-lu (KRaft rejimində, ZooKeeper-siz) broker-dir və `localhost:9092`-də qulaq asır. Topic-ləri tətbiq özü yaradır.

### 2. Tətbiqi işə salın

```bash
./gradlew :kafka-events:bootRun
```

və ya Docker ilə:

```bash
docker build -f kafka-events/Dockerfile -t kafka-events .
docker run --rm -p 8084:8084 --add-host=host.docker.internal:host-gateway \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 kafka-events
```

Qeyd: `apache/kafka` image-i özünü `localhost:9092` kimi elan edir. Tətbiq konteynerdə işləyirsə, Kafka-nı `docker compose` ilə eyni şəbəkədə qaldırmaq daha rahatdır. Sadə yol birinci variantdır (`bootRun`).

### 3. Brauzerdə açın

**http://localhost:8084**: sifariş vermək, "təkrar göndər" düyməsi, ödənişlər, DLT və outbox cədvəlləri.

Və ya terminaldan:

```bash
curl -X POST localhost:8084/api/orders -H 'Content-Type: application/json' -d '{"customer":"Aynur","amount":250}'
curl localhost:8084/api/orders/1          # bir az sonra: CONFIRMED
```

| Məbləğ | Nə baş verir |
|---|---|
| ≤ 1000 | Ödəniş keçir → `CONFIRMED` |
| > 1000 | Ödəniş rədd edilir → `CANCELLED` (kompensasiya) |
| 13 | Ödəniş gateway-i "işləmir" → 2 retry → DLT → `CANCELLED` |

Maraqlı təcrübə: tətbiq işləyərkən Kafka-nı dayandırın (`docker stop kafka`) və sifariş verin. API yenə `202` qaytarır, sifariş `PENDING` qalır, event isə outbox-da "⏳" ilə gözləyir. `docker start kafka` etdikdə hamısı özü davam edir.

Bazası H2 (yaddaşda) olduğu üçün tətbiq yenidən başladıqda məlumat silinir.

---

## Necə işləyir

```
POST /api/orders
   │  TX { INSERT orders (PENDING); INSERT outbox_event (OrderPlaced) }
   ▼
OutboxRelay (hər 300 ms) ──► topic "orders" (key = orderId)
                                  │
                                  ▼
                   PaymentListener (group payment-service)
                   TX { dedup yoxla; INSERT payment; INSERT outbox_event (PaymentResult) }
                      │ xəta → orders-retry-500 → orders-retry-1000 → orders-dlt → @DltHandler
                      ▼
OutboxRelay ──► topic "payments" ──► PaymentResultListener (group order-service)
                                        TX { orders: PENDING → CONFIRMED / CANCELLED }
```

Real layihədə sifariş və ödəniş ayrı servislər olur. Burada rahatlıq üçün bir tətbiqdədirlər, amma bir-birini yalnız Kafka vasitəsilə "görürlər": fərqli consumer group-lar, fərqli paketlər və ortaq çağırış yoxdur.

### 1. Transactional outbox

**Problem (dual write).** Belə kod sadə görünür:

```java
@Transactional
public Order place(...) {
    orders.save(order);
    kafka.send("orders", event);   // ❌
}
```

Amma iki ayrı sistemə yazırıq: baza və Kafka. Ümumi tranzaksiya yoxdur:

- Kafka-ya göndərildi, sonra baza commit-i uğursuz oldu. Nəticədə ödəniş servisi mövcud olmayan sifarişdən pul çıxır.
- Baza commit olundu, Kafka əlçatan deyil. Nəticədə sifariş əbədi `PENDING` qalır.

**Həll.** Event-i Kafka-ya yox, **eyni bazada** `outbox_event` cədvəlinə, eyni tranzaksiyada yazırıq. Ya hər ikisi yazılır, ya heç biri. Ayrıca `OutboxRelay` cədvəli oxuyub Kafka-ya göndərir və broker təsdiq etdikdən sonra `publishedAt` qeyd edir.

```java
@Transactional(propagation = Propagation.MANDATORY)   // tranzaksiyasız çağırmaq = bug
public void add(String topic, Object key, Object event) { ... }
```

`MANDATORY` bu pattern-in qoruyucusudur: kimsə `outbox.add`-i tranzaksiyasız çağırsa, dərhal exception alır (testdə yoxlanılır).

Relay göndərib `publishedAt` yazmazdan əvvəl çöksə, mesaj **yenidən** göndəriləcək. Yəni outbox **at-least-once** verir; dublikatlar mümkündür və consumer-lər buna hazır olmalıdır (növbəti bənd).

Production-da relay üçün polling əvəzinə çox vaxt **CDC** istifadə olunur (Debezium: bazanın WAL-ını oxuyub Kafka-ya yazır). Prinsip eynidir.

### 2. Idempotent consumer

Kafka-da dublikat normaldır: producer retry, consumer rebalance, outbox relay-in təkrarı. "Exactly-once" iddiası yalnız Kafka daxilində işləyir; sizin bazanıza yazmaq bu zəmanətə daxil deyil.

**Həll.** Hər event-in unikal `eventId`-si var (header-də; outbox sətrinin id-si). Consumer onu `processed_message` cədvəlinə yazır, **biznes dəyişikliyi ilə eyni tranzaksiyada**:

```java
@Transactional
public void charge(String eventId, OrderPlaced order) {
    if (processed.existsById(eventId)) return;          // artıq emal olunub
    processed.save(new ProcessedMessage(eventId));      // PK = eventId
    payments.save(...);
    outbox.add(Topics.PAYMENTS, ...);
}
```

İki paralel çatdırılma eyni anda `existsById` yoxlamasından keçsə belə, primary key ikincisinin commit-ini buraxmır. O zaman exception baş verir, retry edilir və təkrar cəhddə `existsById` artıq `true` qaytarır.

Demo səhifədəki "Təkrar göndər" düyməsi eyni event-i (eyni `eventId` ilə) bir də Kafka-ya yazır. Ödəniş cədvəlində yeni sətir yaranmır, yalnız "atlanan dublikat" sayğacı artır.

Bəzi əməliyyatlar **təbiətən idempotentdir**: `Order.complete` yalnız `PENDING` sifarişi dəyişir. Ona görə `PaymentResult` iki dəfə gəlsə də, zərəri yoxdur. Mümkün olanda belə dizayn ən ucuz həlldir.

### 3. Retry və DLT

```java
@RetryableTopic(attempts = "3", backOff = @BackOff(delay = 500, multiplier = 2),
                include = PaymentGatewayException.class)
@KafkaListener(topics = Topics.ORDERS, groupId = "payment-service")
```

Spring Kafka avtomatik olaraq `orders-retry-500`, `orders-retry-1000` və `orders-dlt` topic-lərini yaradır. Uğursuz mesaj növbəti topic-ə köçürülür və gecikmə ilə yenidən emal olunur. Bu **non-blocking retry**-dir: əsas `orders` topic-i gözləmədən axmağa davam edir. Adi (blocking) retry-da isə bir "xəstə" mesaj bütün partition-u saxlayardı.

Hansı xətaları retry etməli:

- **Retry et:** müvəqqəti xətalar (timeout, 503, lock).
- **Retry etmə:** biznes "yox" cavabı (limit aşılıb) və parse xətası; bunlar 100 dəfə yoxlasanız da düzəlməyəcək.

Burada limit aşılması exception deyil, adi nəticədir (`success=false`). `include` isə yalnız `PaymentGatewayException`-ı retry edir.

`@DltHandler` son dayanacaqdır: mesaj `dead_letter` cədvəlinə yazılır (sonra baxmaq və əl ilə təkrar göndərmək üçün). Saga-nın asılı qalmaması üçün sifariş kompensasiya olunur.

Bu nümunədə retry-lar arasında dublikat yoxlaması problem yaratmır, çünki exception tranzaksiyanı geri qaytarır və `processed_message` sətri də silinir.

### 4. Saga

Sifariş, ödəniş və anbar ayrı servislərdirsə, onları bir ACID tranzaksiyaya salmaq olmur (2PC/XA mikroservislərdə praktik deyil). **Saga** əməliyyatı lokal tranzaksiyalar zəncirinə bölür. Hər addım növbətini event ilə tetikləyir, uğursuzluqda isə əvvəlki addımlar **kompensasiya** olunur (geri qaytarılmır, tərsinə əməliyyat edilir):

| Addım | Servis | Lokal tranzaksiya | Event |
|---|---|---|---|
| 1 | order | sifariş `PENDING` | `OrderPlaced` |
| 2 | payment | ödəniş (uğurlu və ya yox) | `PaymentResult` |
| 3 | order | `CONFIRMED` və ya kompensasiya: `CANCELLED` | — |

Bu **choreography** saga-dır: mərkəzi koordinator yoxdur, hər servis event-lərə reaksiya verir. Alternativ **orchestration**-dır: bir "saga orchestrator" kimə nə edəcəyini deyir. Addımlar çoxaldıqda (anbar, çatdırılma, bonus) orchestration izləmək üçün daha asandır.

API buna görə `201 Created` yox, `202 Accepted` qaytarır: sorğu qəbul olunub, nəticə sonra gələcək. Frontend statusu polling, SSE və ya WebSocket ilə izləyir.

### 5. Sıralama

Mesajın açarı (`key`) `orderId`-dir. Kafka eyni açarlı mesajları eyni partition-a yazır, partition daxilində isə sıra qorunur. Beləliklə, bir sifarişin event-ləri həmişə ardıcıl emal olunur; fərqli sifarişlər isə paralel emal oluna bilər.

## Konfiqurasiya

| Dəyişən / property | Default | Nə üçün |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka ünvanı |
| `outbox.poll-interval` | `300ms` | Relay-in outbox-u yoxlama intervalı |
| `spring.kafka.producer.acks` | `all` | Broker yalnız bütün replikalar yazdıqdan sonra təsdiq edir |
| `enable.idempotence` | `true` | Producer retry-ı broker-də dublikat yaratmır |

## Testlər

Testlər `@EmbeddedKafka` ilə işləyir: Kafka JVM-in içində qalxır, Docker lazım deyil.

```bash
./gradlew :kafka-events:test
```

| Test | Nəyi yoxlayır |
|---|---|
| `orderIsAcceptedAsPendingAndConfirmedAfterPayment` | Tam saga: 202 + `PENDING`, sonra `CONFIRMED` |
| `declinedPaymentCompensatesTheOrder` | Kompensasiya: `CANCELLED` |
| `duplicateEventIsNotChargedTwice` | Eyni `eventId` ikinci dəfə gəldikdə ödəniş təkrarlanmır |
| `transientFailureIsRetriedThenParkedInTheDeadLetterTopic` | retry → DLT → kompensasiya |
| `outboxRefusesToWorkOutsideATransaction` | `MANDATORY` qoruyucusu |
| `invalidOrderIsRejected` | Validasiya (400) |

## Tələlər

- **`@Transactional` + `kafkaTemplate.send`** outbox deyil. Göndərmə tranzaksiyanın içində olsa da, bazanın rollback-i Kafka mesajını geri qaytarmır.
- **Auto-commit və uzun emal.** Consumer `max.poll.interval.ms`-dən (default 5 dəq) uzun işləsə, broker onu qrupdan çıxarır və mesaj başqasına verilir, yəni dublikat yaranır.
- **Topic-ləri avtomatik yaratmaq** dev-də rahatdır; production-da partition sayı və replication factor-u açıq idarə edin.
- **Outbox cədvəli böyüyür.** Göndərilmiş sətirləri periodik silin (məs. 7 gündən köhnə olanları).
- **Poison pill.** Parse olunmayan mesaj retry-la düzəlmir; onu birbaşa DLT-yə göndərin (`exclude` və ya `DltStrategy`).
