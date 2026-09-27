# 20. Kafka: event-driven arxitektura və etibarlı mesajlaşma

[← 19. Spring Batch](19-spring-batch.md) · [Mündəricat](README.md) · Növbəti: [21. Redis →](21-redis.md)

**Hissə IV: Əlavə mövzular** · **Kod:** [`OrderService`](../kafka-events/src/main/java/io/github/gshahrza/events/order/OrderService.java), [`Outbox`](../kafka-events/src/main/java/io/github/gshahrza/events/outbox/Outbox.java), [`OutboxRelay`](../kafka-events/src/main/java/io/github/gshahrza/events/outbox/OutboxRelay.java), [`PaymentService`](../kafka-events/src/main/java/io/github/gshahrza/events/payment/PaymentService.java), [`PaymentListener`](../kafka-events/src/main/java/io/github/gshahrza/events/payment/PaymentListener.java) · **Demo:** http://localhost:8084

```bash
docker run -d --name kafka -p 9092:9092 apache/kafka:4.1.0
./gradlew :kafka-events:bootRun
```

---

## Həyatdan analogiya

Böyük anbarın girişində **qeyd jurnalı** var. Gələn hər mal jurnala yeni sətir kimi yazılır, sətirlərin nömrəsi var və heç nə pozulmur, yalnız sona əlavə olunur.

- **Mühasibatlıq** jurnalı öz sürəti ilə oxuyur və əlfəcini 1520-ci sətirdə saxlayıb.
- **Satış şöbəsi** eyni jurnalı oxuyur, amma onun əlfəcini 1700-dədir. Bir-birlərini gözləmirlər.
- Jurnal çox qalın olduğu üçün **bir neçə cildə** bölünüb: bir malın bütün qeydləri həmişə eyni cildə düşür. Mühasibatlığın üç işçisi var və hər biri öz cildini oxuyur.
- Hər cildin **surətləri** başqa binalarda saxlanılır: bir bina yansa da, jurnal itmir.
- Yeni şöbə açılsa, jurnalı **əvvəldən** oxuya bilər: köhnə qeydlər hələ də oradadır.

Kafka budur: jurnal **topic**, cild **partition**, sətir nömrəsi **offset**, əlfəcin **consumer offset**, şöbə **consumer group**, surətlər isə **replikasiya**dır.

## Problem: servislər bir-birini birbaşa çağıranda

Sifariş servisi sifarişi qəbul edəndə ödəniş, anbar, bildiriş və analitika servislərini ardıcıl REST ilə çağırır:

```
POST /orders ──► payment ──► inventory ──► notification ──► analytics
```

- **Sıx bağlılıq.** Yeni servis (bonus proqramı) əlavə etmək üçün sifariş servisinin kodu dəyişir.
- **Zəncirvari çökmə.** Bildiriş servisi düşüb, ona görə sifariş də verilə bilmir, halbuki bildiriş ikinci dərəcəli işdir.
- **Pik yük.** "Qara cümə"də bütün servislər eyni anda eyni yükü götürməli olur.
- **Yavaşlıq.** İstifadəçi beş servisin cəminin cavabını gözləyir.

Event-driven yanaşmada isə sifariş servisi yalnız bir fakt elan edir: **"Sifariş verildi"**. Kimin buna reaksiya verəcəyini bilmir və bilməli də deyil. Kafka bu faktı saxlayır və maraqlanan hər servis onu öz sürəti ilə oxuyur.

## Kafka-nın əsasları

```
Producer ──► topic "orders"
              ├─ partition 0: [0][1][2][3][4][5]...   ← leader broker 1, surətlər: broker 2, 3
              ├─ partition 1: [0][1][2][3]...          ← leader broker 2
              └─ partition 2: [0][1][2][3][4]...       ← leader broker 3
                                    ▲
     consumer group "payment-service":   consumer A ← p0, p1     consumer B ← p2
     consumer group "analytics":         consumer X ← p0, p1, p2 (öz offset-ləri ilə)
```

| Anlayış | Nədir |
|---|---|
| **Broker** | Kafka server-i. Cluster bir neçə broker-dən ibarətdir |
| **Topic** | Mesajların məntiqi kateqoriyası (`orders`, `payments`) |
| **Partition** | Topic-in hissəsi: sıralı, yalnız sona yazılan log. Paralelliyin və sıranın vahidi |
| **Offset** | Mesajın partition daxilindəki sıra nömrəsi |
| **Key** | Mesajın açarı. Eyni açar həmişə eyni partition-a düşür: `hash(key) % partitions` |
| **Consumer group** | Bir "abunəçi" (servis). Hər partition qrupda **yalnız bir** consumer-ə verilir |
| **Consumer offset** | Qrupun hər partition-da harada qaldığı; `__consumer_offsets` topic-ində saxlanılır |
| **Replication factor** | Hər partition-un neçə broker-də surəti var (production-da adətən 3) |
| **Leader / follower / ISR** | Yazma və oxuma leader-ə gedir; follower-lər kopyalayır; ISR "leader-dən geri qalmayan" replikalardır |
| **Retention** | Mesajlar oxunandan sonra silinmir, müddət (default 7 gün) və ya ölçü bitəndə silinir |
| **KRaft** | Kafka-nın öz metadata idarəsi. Kafka 4.0-dan ZooKeeper tamamilə yığışdırılıb |

İki qızıl qayda:

- **Sıra yalnız partition daxilində zəmanətlidir.** Bir sifarişin bütün event-ləri ardıcıl emal olunsun deyə açar `orderId` seçilir.
- **Paralellik partition sayı ilə məhdudlaşır.** 6 partition varsa, qrupda 6-dan çox consumer faydasızdır: artıq olanlar boş dayanır.

### Kafka, yoxsa RabbitMQ?

| | Kafka | RabbitMQ (klassik queue) |
|---|---|---|
| Model | Log: mesaj qalır, hər qrup öz offset-i ilə oxuyur | Növbə: mesaj çatdırılıb təsdiqlənəndə silinir |
| Təkrar oxumaq (replay) | Bəli, offset-i geri çəkmək kifayətdir | Yox (Streams istisna) |
| Bir mesaj, çox abunəçi | Təbii (çox consumer group) | Exchange və bir neçə queue ilə |
| Sıra | Partition daxilində | Queue daxilində (bir consumer ilə) |
| Güclü tərəf | Yüksək throughput, event sourcing, stream processing, audit | Mürəkkəb routing, fərdi mesaj TTL-i, prioritet, RPC |

## Çatdırılma zəmanətləri

| Semantika | Mənası | Necə alınır |
|---|---|---|
| **At-most-once** | Mesaj itə bilər, dublikat olmur | Emaldan **əvvəl** offset-i commit et |
| **At-least-once** | İtmir, amma dublikat ola bilər | Emaldan **sonra** commit et (Spring Kafka-nın default-u) |
| **Exactly-once** | Nə itir, nə təkrarlanır | Kafka daxilində: idempotent producer + transaction + `read_committed` |

Vacib incəlik: Kafka-nın exactly-once zəmanəti **Kafka-dan Kafka-ya** (oxu → emal et → başqa topic-ə yaz) işləyir. Emalın nəticəsi sizin **bazanıza** yazılırsa, zəmanət ora çatmır: consumer bazaya yazıb offset-i commit etməzdən əvvəl çöksə, mesaj yenidən gələcək. Real həyatda buna görə **at-least-once + idempotent consumer** istifadə olunur. Bu modul da məhz bunu edir.

Producer tərəfdə etibarlılıq:

```yaml
spring.kafka.producer:
  acks: all                  # bütün ISR replikaları yazanda təsdiq
  properties:
    enable.idempotence: true # retry dublikat yaratmır (Kafka 3.0-dan default)
```

`acks=all` ilə birlikdə topic-də `min.insync.replicas=2` (replication factor 3 olanda) qoyulur: ən azı iki replika yazmasa, yazı rədd edilir. Beləliklə, bir broker itəndə təsdiqlənmiş mesaj itmir.

## Həll, addım-addım: sifariş saga-sı

`kafka-events` modulunda bir sifarişin yolu:

```
POST /api/orders
   │  TX { INSERT orders (PENDING); INSERT outbox_event (OrderPlaced) }        ← 1. outbox
   ▼
OutboxRelay ──► topic "orders" (key = orderId)                                 ← 5. sıra
                   │
                   ▼
   PaymentListener (group "payment-service")
   TX { eventId emal olunub? ; INSERT payment; INSERT outbox_event (PaymentResult) }   ← 2. idempotency
      │ müvəqqəti xəta → orders-retry-500 → orders-retry-1000 → orders-dlt              ← 3. retry, DLT
      ▼
OutboxRelay ──► topic "payments" ──► PaymentResultListener (group "order-service")
                                       TX { PENDING → CONFIRMED / CANCELLED }            ← 4. saga
```

### 1. Dual write problemi və transactional outbox

Ən təbii görünən kod səhvdir:

```java
@Transactional
public Order place(...) {
    orders.save(order);
    kafka.send("orders", event);   // ❌ iki müxtəlif sistem, ümumi tranzaksiya yoxdur
}
```

- Kafka-ya göndərildi, sonra baza commit-i uğursuz oldu. Nəticədə ödəniş servisi **mövcud olmayan** sifarişdən pul çıxır.
- Baza commit olundu, amma Kafka həmin an əlçatan deyildi. Nəticədə sifariş **əbədi PENDING** qalır.

**Həll:** event Kafka-ya yox, **eyni bazada** `outbox_event` cədvəlinə, **eyni tranzaksiyada** yazılır.

```java
@Transactional
public Order place(String customer, BigDecimal amount) {
    Order order = orders.save(new Order(customer, amount));
    outbox.add(Topics.ORDERS, order.getId(), new OrderPlaced(order.getId(), customer, amount));
    return order;
}

@Transactional(propagation = Propagation.MANDATORY)   // tranzaksiyasız çağırmaq = bug, dərhal exception
public void add(String topic, Object key, Object event) { ... }
```

Ayrıca `OutboxRelay` cədvəli oxuyur və göndərir:

```java
@Scheduled(fixedDelayString = "${outbox.poll-interval}")
public void publishPending() {
    for (OutboxEvent event : repository.findTop100ByPublishedAtIsNullOrderByCreatedAt()) {
        try {
            send(event);                    // broker-in təsdiqini gözləyir
            event.markPublished();
            repository.save(event);
        } catch (Exception e) {
            return;                          // Kafka yoxdur: sətir qalır, növbəti tick-də yenidən; sıra pozulmur
        }
    }
}
```

Demo-da yoxlanılıb: Kafka dayandırıldı, sifariş verildi. API `202` qaytardı, event outbox-da "⏳" ilə gözlədi. `docker start kafka`-dan 6 saniyə sonra sifariş özü `CONFIRMED` oldu.

Relay göndərib `publishedAt` yazmazdan əvvəl çöksə, mesaj **yenidən** göndəriləcək. Yəni outbox at-least-once verir, və bu, növbəti addımı məcburi edir. Production-da polling əvəzinə çox vaxt **CDC** (Debezium bazanın WAL-ını oxuyur) istifadə olunur. Prinsip eynidir.

### 2. Idempotent consumer

Dublikat Kafka-da istisna deyil, normal haldır. Onun mənbələri:

- producer retry;
- outbox relay-in təkrarı;
- consumer rebalance (offset commit olunmamış mesajlar başqa consumer-ə verilir).

```java
@Transactional
public void charge(String eventId, OrderPlaced order) {
    if (processed.existsById(eventId)) {
        return;                                        // artıq emal olunub
    }
    processed.save(new ProcessedMessage(eventId));     // PK = eventId
    ...
    payments.save(new Payment(...));
    outbox.add(Topics.PAYMENTS, order.orderId(), new PaymentResult(...));
}
```

- `eventId` header-də gəlir (outbox sətrinin UUID-si), yəni mesajın özünün **sabit** id-sidir, Kafka offset-i deyil.
- Dedup sətri, biznes dəyişikliyi və növbəti event **bir tranzaksiyadadır**: ya hamısı, ya heç biri.
- İki paralel çatdırılma eyni anda `existsById`-dən keçsə belə, primary key ikincinin commit-ini buraxmır.

Demo-dakı "Təkrar göndər" düyməsi eyni event-i (eyni `eventId` ilə) Kafka-ya bir daha yazır: ödəniş ikinci dəfə çıxılmır, yalnız "atlanan dublikat" sayğacı artır.

Bəzi əməliyyatlar **təbiətən idempotentdir**: `Order.complete` yalnız `PENDING` sifarişi dəyişir, ona görə eyni `PaymentResult`-un iki dəfə gəlməsi zərərsizdir. Mümkün olanda belə dizayn ən ucuz həlldir.

### 3. Retry və dead letter topic

```java
@RetryableTopic(
        attempts = "3",
        backOff = @BackOff(delay = 500, multiplier = 2),
        include = PaymentGatewayException.class)
@KafkaListener(topics = Topics.ORDERS, groupId = "payment-service")
void on(String payload, @Header(Topics.HEADER_EVENT_ID) String eventId) { ... }

@DltHandler
void dlt(ConsumerRecord<String, String> record) { ... }   // son dayanacaq: saxla və kompensasiya et
```

Spring Kafka özü `orders-retry-500`, `orders-retry-1000` və `orders-dlt` topic-lərini yaradır. Uğursuz mesaj növbəti topic-ə köçürülür və gecikmə ilə yenidən emal olunur.

İki növ retry var:

| | Blocking retry (`DefaultErrorHandler`) | Non-blocking (`@RetryableTopic`) |
|---|---|---|
| Harada gözləyir | Consumer eyni mesajın üstündə dayanır | Mesaj retry topic-ə köçür |
| Partition-un qalanı | Gözləyir (bir "xəstə" mesaj hamını saxlayır) | Axmağa davam edir |
| Sıra | Qorunur | Pozulur: retry olunan mesaj sonra gəlir |
| Nə vaxt | Qısa, nadir xətalar; sıra vacibdirsə | Xarici servis dəqiqələrlə düşə bilərsə |

**Nəyi retry etməli:** yalnız müvəqqəti xətaları (timeout, 503). Biznes "yox" cavabı (limit aşılıb) exception deyil, adi nəticədir. Parse olunmayan mesaj (poison pill) isə 100 dəfə yoxlasanız da düzəlməyəcək: onu birbaşa DLT-yə göndərin.

DLT "zibil qutusu" deyil. Oraya düşən hər mesaj monitorinqdə görünməli, kimsə ona baxmalı və düzəliş olunandan sonra yenidən göndərilməlidir. Bu modulda `DltHandler` mesajı `dead_letter` cədvəlinə yazır və saga-nı kompensasiya edir, ki sifariş `PENDING`-də asılı qalmasın.

Spring Kafka 4 + `@RetryableTopic` incəliyi: DLT mesajında orijinal topic və xəta `kafka_original-topic`, `kafka_exception-message` header-lərindədir (`KafkaHeaders.ORIGINAL_TOPIC`, `EXCEPTION_MESSAGE`). Bir çox nümunədə istifadə olunan `DLT_ORIGINAL_TOPIC` / `DLT_EXCEPTION_MESSAGE` isə bu qurğuda boş gəldi; bu modulu yazarkən bununla qarşılaşdım. Şübhə olanda gələn header-ləri loglayın.

### 4. Saga: distributed transaction olmadan

Sifariş və ödəniş ayrı bazalardadır. Onları bir ACID tranzaksiyasına salmaq (2PC/XA) mikroservislərdə praktik deyil. **Saga** əməliyyatı lokal tranzaksiyalar zəncirinə bölür. Uğursuzluqda əvvəlki addımlar **kompensasiya** olunur: geri qaytarılmır, tərsinə əməliyyat edilir.

| Addım | Servis | Lokal tranzaksiya | Event |
|---|---|---|---|
| 1 | order | Sifariş `PENDING` | `OrderPlaced` |
| 2 | payment | Ödəniş (uğurlu və ya yox) | `PaymentResult` |
| 3 | order | `CONFIRMED`, ya da kompensasiya: `CANCELLED` | — |

- **Choreography** (bu modul): mərkəzi koordinator yoxdur, hər servis event-lərə reaksiya verir. Sadədir, amma addımlar çoxaldıqca axını izləmək çətinləşir.
- **Orchestration:** saga orchestrator hər servisə nə edəcəyini deyir. Addımlar çox olanda daha şəffafdır.

API buna görə `201` yox, `202 Accepted` qaytarır: nəticə **sonra** gələcək. Frontend statusu polling, SSE və ya WebSocket ilə izləyir. Bu, **eventual consistency**-dir: bir anlıq sifariş "gözləmədə"dir, və bu, biznesə izah olunmalı olan qərardır.

### 5. Sıra və açar

```java
new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());   // key = orderId
```

Eyni sifarişin bütün event-ləri eyni partition-a düşür və ardıcıl emal olunur. Fərqli sifarişlər isə paralel emal olunur.

Açar seçimi dizayn qərarıdır:

- Açar çox "qızğın"dırsa (məs. bütün event-lər bir `tenantId` ilə), bir partition yüklənir və qalanları boş qalır (**hot partition**).
- Açarsız (`null`) mesajlar partition-lara paylanır, amma sıra zəmanəti yoxdur.

## Consumer group, rebalance və lag

- Qrupa consumer qoşulanda və ya ayrılanda partition-lar yenidən paylanır: bu, **rebalance**-dir. Köhnə (eager) protokolda bu müddətdə bütün qrup dayanırdı. `CooperativeStickyAssignor` və Kafka 4.0-da GA olan yeni consumer protokolu (KIP-848) yalnız köçən partition-ları dayandırır.
- Consumer `max.poll.interval.ms` (default 5 dəqiqə) ərzində növbəti `poll()`-u etməsə, broker onu "ölü" sayır və partition-larını başqasına verir. Commit olunmamış mesajlar yenidən emal olunur: bu, dublikatların klassik mənbəyidir. Uzun emalda batch ölçüsünü (`max.poll.records`) azaldın.
- **Consumer lag** son offset ilə qrupun offset-i arasındakı fərqdir. Bu, Kafka monitorinqinin ən vacib metrikasıdır: lag böyüyürsə, consumer-lər yükün öhdəsindən gəlmir.

```bash
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group payment-service
```

## Spring Kafka qısa arayış

| Nə | Necə |
|---|---|
| Göndərmək | `KafkaTemplate.send(topic, key, value)`: `CompletableFuture` qaytarır; təsdiq lazımdırsa `.get()` |
| Qəbul etmək | `@KafkaListener(topics, groupId)` |
| Paralellik | `concurrency = "3"`: bir tətbiqdə 3 consumer thread (partition sayından çox olmamalı) |
| Blocking retry | `DefaultErrorHandler` + `BackOff` |
| Non-blocking retry | `@RetryableTopic` + `@DltHandler` |
| Offset commit | Default: listener uğurla bitəndən sonra (`AckMode.BATCH`); `enable.auto.commit` Spring-də söndürülüb |
| JSON | `JsonSerializer` və ya bu modulda olduğu kimi `String` + öz `JsonMapper`-iniz (tip və versiya nəzarəti sizdə olur) |
| Test | `@EmbeddedKafka` (Docker-siz) və ya Testcontainers (`KafkaContainer`) |
| Trace | `spring.kafka.template.observation-enabled`, `spring.kafka.listener.observation-enabled` |

## Tələlər

- **`@Transactional` + `kafkaTemplate.send`** outbox deyil. Baza rollback olsa belə, mesaj artıq getmiş olur.
- **Idempotent olmayan consumer.** "Dublikat bizdə olmur" yanaşması ilk rebalance-a qədər yaşayır.
- **Partition sayını sonra artırmaq.** Açar → partition xəritəsi dəyişir və eyni açarın yeni mesajları başqa partition-a düşür: sıra pozulur. Partition sayını əvvəlcədən ehtiyatla seçin; azaltmaq isə ümumiyyətlə mümkün deyil.
- **Poison pill.** Parse olunmayan mesaj sonsuz retry-da partition-u bloklayır. Deserialization xətaları üçün `ErrorHandlingDeserializer` və DLT istifadə edin.
- **Böyük mesajlar.** Default limit ~1 MB-dır. Faylları Kafka-ya qoymayın: faylı S3-ə yazın, mesajda linkini göndərin (**claim check** pattern).
- **Topic-ləri avtomatik yaratmaq.** Dev-də rahatdır, production-da partition sayı, replication factor və retention açıq idarə olunmalıdır.
- **Schema dəyişikliyi.** Producer sahənin adını dəyişdi, bütün consumer-lər çökdü. Schema Registry (Avro, Protobuf, JSON Schema) və geriyə uyğun dəyişikliklər (yalnız sahə əlavə etmək) bundan qoruyur.
- **Outbox cədvəli böyüyür.** Göndərilmiş sətirləri periodik silin.

## Yadda saxla

- Kafka növbə deyil, **log**-dur: mesaj oxunandan sonra silinmir, hər consumer group öz offset-i ilə oxuyur.
- Sıra yalnız **partition daxilində**dir; açarı buna görə seçin. Paralellik partition sayı ilə məhdudlaşır.
- Bazaya yazmaq və mesaj göndərmək bir tranzaksiyada olmur: **transactional outbox**.
- Real sistemlərdə **at-least-once + idempotent consumer** standartdır.
- Retry yalnız müvəqqəti xətalar üçündür; qalanı **DLT**-yə, DLT isə monitorinqə.
- Paylanmış əməliyyat **saga** və kompensasiya ilə qurulur: nəticə eventual consistency-dir, API isə `202` qaytarır.

## Tapşırıqlar

1. Demo-da sifariş verin, sonra onun üçün "Təkrar göndər" basın. Ödənişlər cədvəlində və "atlanan dublikat" sayğacında nə dəyişdi? `PaymentService.charge`-dan `existsById` yoxlamasını silib təkrarlayın.
2. `docker stop kafka` edin, 3 sifariş verin, outbox cədvəlinə baxın. `docker start kafka`-dan sonra nə qədər vaxta hamısı tamamlandı?
3. `13` məbləğli sifariş verin və `kafka-events` loglarında retry topic-lərinin adlarını tapın. Gecikmələr `BackOff` konfiqurasiyasına uyğundurmu?
4. Kafka-nı sıfırdan qaldırın və tətbiqi başlatmazdan **əvvəl** `orders` topic-ini 3 partition ilə yaradın: `docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic orders --partitions 3` (tətbiq topic-i özü yaratsa, 1 partition ilə yaradır). `@KafkaListener`-ə `concurrency = "3"` əlavə edin və loglarda hansı thread-in hansı sifarişi emal etdiyinə baxın. Bir sifarişin event-ləri həmişə eyni thread-dədirmi?
5. (Çətin) Choreography saga-ya üçüncü addım əlavə edin: `inventory` servisi stoku ayırır; ödəniş uğursuz olarsa, stok geri qaytarılır (kompensasiya). Event-ləri və topic-ləri çəkin.

---

## Müsahibə sualları

Ən çox verilən Kafka sualları, mövzulara görə. Cavabı açmazdan əvvəl özünüz cavab verməyə çalışın.

### Əsaslar

<details>
<summary><b>1. Kafka nədir və nə üçün istifadə olunur?</b></summary>

Paylanmış, dayanıqlı (disk-də saxlanan, replikasiya olunan), yüksək throughput-lu **event streaming platformasıdır**: mesajları sıralı log kimi saxlayır və çoxlu producer və consumer arasında ötürür. İstifadə sahələri:

- servislər arası asinxron əlaqə (event-driven mikroservislər);
- log və metrika toplama;
- CDC (bazadakı dəyişiklikləri axın kimi paylamaq);
- stream processing (Kafka Streams, Flink);
- event sourcing və audit.
</details>

<details>
<summary><b>2. Topic, partition və offset nədir?</b></summary>

- **Topic:** mesajların məntiqi kateqoriyası.
- **Partition:** topic-in hissəsi, yalnız sona yazılan sıralı log. Paralelliyin və sıranın vahidi, və müxtəlif broker-lərə paylanır.
- **Offset:** mesajın partition daxilindəki artan sıra nömrəsi. Consumer harada qaldığını offset ilə yadda saxlayır.
</details>

<details>
<summary><b>3. Kafka mesajların sırasını qoruyurmu?</b></summary>

Yalnız **bir partition daxilində**. Topic səviyyəsində (partition-lar arasında) sıra zəmanəti yoxdur. Sıra lazım olan mesajlara eyni **key** verilir (məs. `orderId`), onda hamısı eyni partition-a düşür. Producer tərəfdə retry-lar sıranı pozmasın deyə idempotence açıq olmalıdır (`enable.idempotence=true`, bu halda `max.in.flight.requests.per.connection ≤ 5` sıranı qoruyur).
</details>

<details>
<summary><b>4. Consumer group nədir? Consumer sayı partition sayından çox olsa nə olar?</b></summary>

Consumer group bir "məntiqi abunəçidir": qrupdakı consumer-lər partition-ları öz aralarında bölüşür, hər partition qrupda **yalnız bir** consumer-ə verilir. Beləliklə, qrup daxilində hər mesaj bir dəfə emal olunur, fərqli qruplar isə eyni mesajları müstəqil oxuyur. Consumer sayı partition sayından çoxdursa, artıq consumer-lər **boş dayanır**: paralellik partition sayı ilə məhdudlaşır.
</details>

<details>
<summary><b>5. Kafka niyə bu qədər sürətlidir?</b></summary>

- **Ardıcıl disk I/O:** yalnız sona yazılır, ardıcıl yazma təsadüfi yazmadan qat-qat sürətlidir.
- **OS page cache:** məlumat JVM heap-də deyil, əməliyyat sisteminin keşindədir.
- **Zero-copy (`sendfile`):** diskdən şəbəkəyə user-space-ə kopyalamadan göndərilir.
- **Batching və sıxılma:** mesajlar dəstə-dəstə göndərilir və sıxılır.
- **Partition-lar ilə horizontal miqyaslanma.**
</details>

<details>
<summary><b>6. ZooKeeper nə idi və KRaft nədir?</b></summary>

ZooKeeper əvvəllər cluster metadata-sını (broker-lər, topic-lər, leader seçimi, controller) saxlayırdı. **KRaft** (Kafka Raft) bu funksiyanı Kafka-nın özünə köçürür: metadata Raft konsensusu ilə idarə olunan daxili log-da saxlanılır. Bu, arxitekturanı sadələşdirir, daha çox partition dəstəkləyir və failover-i sürətləndirir. Kafka 4.0-dan ZooKeeper tamamilə çıxarılıb.
</details>

<details>
<summary><b>7. Kafka ilə RabbitMQ arasındakı fərq nədir?</b></summary>

- **Kafka** log-dur: mesaj oxunandan sonra retention müddətinə qədər qalır, çoxlu qrup onu müstəqil və təkrar (replay) oxuya bilər. Yüksək throughput üçündür, sıra partition daxilindədir.
- **RabbitMQ** message broker-dir: mesaj təsdiqlənəndən sonra növbədən silinir. Zəngin routing (exchange-lər), mesaj səviyyəsində TTL, prioritet və RPC pattern-ləri var.

Seçim: event streaming, replay və böyük həcm lazımdırsa Kafka; mürəkkəb routing və klassik iş növbələri lazımdırsa RabbitMQ.
</details>

### Producer

<details>
<summary><b>8. <code>acks</code> parametrinin dəyərləri nədir?</b></summary>

- `acks=0`: təsdiq gözlənilmir. Ən sürətlisidir, mesaj itə bilər.
- `acks=1`: yalnız leader yazanda təsdiq. Leader follower-lər kopyalamadan çöksə, mesaj itir.
- `acks=all` (`-1`): bütün ISR replikaları yazanda təsdiq. Ən etibarlısıdır; `min.insync.replicas=2` ilə birlikdə istifadə olunur. Kafka 3.0-dan default-dur.
</details>

<details>
<summary><b>9. Idempotent producer nədir?</b></summary>

Şəbəkə xətasından sonra producer mesajı yenidən göndərəndə broker-də dublikat yaranmasının qarşısını alır. Hər producer-ə **producer id**, hər mesaja partition üzrə **sequence number** verilir; broker artıq gördüyü nömrəni ikinci dəfə yazmır. `enable.idempotence=true` (Kafka 3.0-dan default). Zəmanət bir producer sessiyası və bir partition daxilindədir.
</details>

<details>
<summary><b>10. Mesaj hansı partition-a düşür?</b></summary>

- **Key varsa:** `hash(key) % partition sayı` (default partitioner murmur2 hash istifadə edir); eyni key həmişə eyni partition-a düşür.
- **Key yoxdursa:** sticky partitioner mesajları bir batch dolana qədər bir partition-a, sonra başqasına yazır (bərabər paylanma + effektiv batching).
- **Öz partitioner-iniz də yazıla bilər.**
</details>

<details>
<summary><b>11. <code>linger.ms</code> və <code>batch.size</code> nəyə təsir edir?</b></summary>

Producer mesajları partition üzrə batch-lərə yığır. `batch.size` batch-in maksimum ölçüsüdür (bayt), `linger.ms` isə batch dolmasa belə göndərməzdən əvvəl nə qədər gözləyəcəyidir. Böyük dəyərlər throughput-u və sıxılmanın effektivliyini artırır, amma gecikməni (latency) də artırır.
</details>

<details>
<summary><b>12. Kafka-da exactly-once necə əldə olunur?</b></summary>

Üç hissədən:

1. **Idempotent producer:** retry dublikat yaratmır.
2. **Transactions:** `transactional.id` ilə producer bir neçə partition-a yazmağı və consumer offset-lərini **atomar** commit edir.
3. **`isolation.level=read_committed`:** consumer yalnız commit olunmuş transaction-ların mesajlarını görür.

Bu, Kafka daxilində "oxu → emal et → yaz" üçün işləyir (Kafka Streams-də `processing.guarantee=exactly_once_v2`). Xarici sistemə (bazaya, API-yə) yan təsir varsa, zəmanət ora çatmır: orada idempotent consumer lazımdır.
</details>

### Consumer

<details>
<summary><b>13. Offset commit nədir? Auto commit-in təhlükəsi nədir?</b></summary>

Commit, consumer group-un "bu partition-da X offset-ə qədər emal etdim" qeydidir (`__consumer_offsets` topic-inə yazılır). **Auto commit** (`enable.auto.commit=true`) offset-i hər `auto.commit.interval.ms`-də (default 5 s), emaldan asılı olmayaraq commit edir:

- mesaj emal olunmadan commit olunsa və consumer çöksə, mesaj **itir**;
- emal olunub commit-dən əvvəl çöksə, **dublikat** yaranır.

Spring Kafka auto commit-i söndürür və offset-i listener uğurla bitəndən sonra commit edir (at-least-once).
</details>

<details>
<summary><b>14. Rebalance nədir və nə vaxt baş verir?</b></summary>

Partition-ların qrupdakı consumer-lər arasında yenidən paylanmasıdır. Nə vaxt baş verir:

- consumer qoşulur və ya ayrılır;
- consumer `session.timeout.ms` ərzində heartbeat göndərmir və ya `max.poll.interval.ms` ərzində `poll()` etmir;
- topic-ə partition əlavə olunur.

Köhnə (eager) protokolda rebalance zamanı bütün qrup dayanırdı ("stop the world"). `CooperativeStickyAssignor` və yeni consumer protokolu (KIP-848, Kafka 4.0-da GA) yalnız köçən partition-ları dayandırır. Static membership (`group.instance.id`) isə restart zamanı lazımsız rebalance-ların qarşısını alır.
</details>

<details>
<summary><b>15. <code>session.timeout.ms</code> ilə <code>max.poll.interval.ms</code> arasında fərq nədir?</b></summary>

- `session.timeout.ms` (default 45 s): arxa fon thread-inin heartbeat-ləri bu müddətdə gəlməsə, consumer "ölü" sayılır (proses çöküb, şəbəkə kəsilib).
- `max.poll.interval.ms` (default 5 dəq): iki `poll()` arasındakı maksimum vaxt. Consumer canlıdır, amma emal çox uzun çəkirsə, qrupdan çıxarılır.

Uzun emalda ikincisi problem yaradır: həll `max.poll.records`-u azaltmaq və ya emalı sürətləndirməkdir.
</details>

<details>
<summary><b>16. <code>auto.offset.reset</code> nədir?</b></summary>

Qrupun həmin partition üçün commit olunmuş offset-i yoxdursa (yeni qrup) və ya offset artıq mövcud deyilsə (retention silib), haradan başlamaq lazım olduğunu deyir:

- `earliest`: ən əvvəldən;
- `latest` (default): yalnız yeni mesajlar;
- `none`: exception.

Yeni servis köhnə event-ləri də emal etməlidirsə, `earliest` seçilir.
</details>

<details>
<summary><b>17. Consumer lag nədir və necə azaldılır?</b></summary>

Partition-dakı son offset ilə qrupun commit etdiyi offset arasındakı fərq, yəni "emal olunmağı gözləyən mesaj sayı". Lag daim böyüyürsə:

- consumer sayını artırın (partition sayına qədər);
- partition sayını artırın;
- emalı sürətləndirin (batch emal, xarici çağırışları azaltmaq);
- `max.poll.records` / `fetch.min.bytes`-i tənzimləyin.

Lag monitorinqin əsas metrikasıdır (Burrow, Kafka exporter, Grafana).
</details>

<details>
<summary><b>18. Consumer dublikat mesajları necə idarə etməlidir?</b></summary>

At-least-once sistemdə dublikat normaldır. Consumer **idempotent** olmalıdır:

- mesajın unikal id-sini emal olunmuş mesajlar cədvəlində, biznes dəyişikliyi ilə **eyni tranzaksiyada** saxlamaq (bu modul);
- təbiətən idempotent əməliyyatlar (`UPSERT`, `status = PAID WHERE status = PENDING`);
- biznes açarı üzrə unikal constraint.
</details>

### Etibarlılıq və cluster

<details>
<summary><b>19. Replication, ISR və <code>min.insync.replicas</code> nədir?</b></summary>

- Hər partition-un `replication.factor` qədər surəti var: biri **leader** (yazma və oxuma), qalanları **follower**.
- **ISR** (in-sync replicas) leader-dən müəyyən vaxtdan (`replica.lag.time.max.ms`) çox geri qalmayan replikalardır.
- `min.insync.replicas` isə `acks=all` ilə yazının uğurlu sayılması üçün minimum ISR sayıdır.

Tipik production: RF=3, `min.insync.replicas=2`, `acks=all`. Bir broker itəndə yazmaq davam edir, təsdiqlənmiş heç bir mesaj itmir.
</details>

<details>
<summary><b>20. Leader broker çöksə nə olur?</b></summary>

Controller ISR-dəki follower-lərdən birini yeni leader seçir, producer və consumer-lər metadata-nı yeniləyib yeni leader-ə keçir. `acks=all` ilə təsdiqlənmiş mesajlar ISR-in hamısında olduğu üçün itmir. `unclean.leader.election.enable=false` (default) ISR-dən kənar, geri qalmış replikanın leader olmasına icazə vermir: məlumat itkisi əvəzinə müvəqqəti əlçatmazlıq seçilir.
</details>

<details>
<summary><b>21. Retention və log compaction nədir?</b></summary>

- **Retention** (`retention.ms`, default 7 gün, və ya `retention.bytes`): köhnə seqmentlər vaxt və ya ölçü limitinə görə silinir, oxunub-oxunmamasından asılı olmayaraq.
- **Log compaction** (`cleanup.policy=compact`): hər **key** üçün yalnız son dəyər saxlanılır. Dəyəri `null` olan mesaj (tombstone) açarı silir.

Compaction "hər müştərinin son vəziyyəti", konfiqurasiya, CDC cədvəlləri üçün uyğundur: topic cədvəlin snapshot-u kimi işləyir.
</details>

<details>
<summary><b>22. Partition sayını necə seçmək lazımdır? Sonra dəyişmək olarmı?</b></summary>

Partition sayı maksimum consumer paralelliyini müəyyən edir. Hesab: tələb olunan throughput ÷ bir consumer-in throughput-u, üstəgəl gələcək üçün ehtiyat. Çox partition da zərərlidir: daha çox fayl, daha uzun leader seçimi, daha çox yaddaş. Sayı **artırmaq** olur, amma key → partition xəritəsi dəyişir və eyni key-in sırası pozulur; **azaltmaq** mümkün deyil (yalnız yeni topic yaradıb köçürməklə).
</details>

### Arxitektura və pattern-lər

<details>
<summary><b>23. Dual write problemi nədir və transactional outbox onu necə həll edir?</b></summary>

Bazaya yazmaq və Kafka-ya göndərmək iki ayrı sistemdir və ümumi tranzaksiyaları yoxdur: biri uğurlu, digəri uğursuz ola bilər. Outbox-da event **eyni bazadakı** cədvələ biznes dəyişikliyi ilə eyni tranzaksiyada yazılır, ayrıca proses (polling relay və ya Debezium CDC) isə onu Kafka-ya göndərir. Nəticə at-least-once çatdırılmadır, ona görə consumer idempotent olmalıdır.
</details>

<details>
<summary><b>24. Saga pattern nədir? Choreography ilə orchestration arasında fərq nədir?</b></summary>

Saga paylanmış əməliyyatı lokal tranzaksiyalar zəncirinə bölür. Uğursuzluqda əvvəlki addımlar **kompensasiya** əməliyyatları ilə tərsinə çevrilir.

- **Choreography:** hər servis event-lərə reaksiya verir, mərkəz yoxdur. Sadədir, amma axını izləmək çətindir.
- **Orchestration:** orchestrator hər addımı idarə edir. Mürəkkəb axınlarda şəffafdır, amma əlavə komponentdir.
</details>

<details>
<summary><b>25. Dead letter topic nədir və nə vaxt istifadə olunur?</b></summary>

Bütün retry-lardan sonra emal oluna bilməyən mesajların göndərildiyi ayrıca topic-dir. Partition-u "xəstə" mesajın bloklamasının qarşısını alır. DLT-dəki mesajlar monitorinqdə görünməli, analiz edilməli və düzəlişdən sonra yenidən emal olunmalıdır (replay). Spring Kafka-da `DeadLetterPublishingRecoverer` və ya `@RetryableTopic` + `@DltHandler` ilə qurulur.
</details>

<details>
<summary><b>26. Poison pill nədir?</b></summary>

Consumer-in heç vaxt emal edə bilməyəcəyi mesajdır: səhv format, deserializasiya xətası, gözlənilməz schema. Sonsuz retry-da partition-u tamamilə bloklayır. Həlli:

- `ErrorHandlingDeserializer` (deserializasiya xətası exception-a çevrilir, consumer dayanmır);
- belə xətaları retry etməmək;
- mesajı birbaşa DLT-yə göndərmək.
</details>

<details>
<summary><b>27. Schema Registry nə üçündür?</b></summary>

Producer və consumer-lər arasında mesajın strukturu (schema) mərkəzi yerdə saxlanılır: Avro, Protobuf və ya JSON Schema. Registry yeni versiyanın uyğunluq qaydalarına (backward, forward, full) cavab verdiyini yoxlayır və producer consumer-ləri sındıran dəyişiklik edə bilmir. Mesajda tam schema yox, yalnız onun id-si gedir.
</details>

<details>
<summary><b>28. Böyük faylı (məs. 50 MB) Kafka ilə necə ötürmək olar?</b></summary>

Kafka böyük mesajlar üçün deyil (default limit ~1 MB). **Claim check** pattern istifadə olunur: fayl obyekt anbarına (S3, MinIO) yazılır, Kafka mesajında isə yalnız onun linki və metadata-sı göndərilir. Limitləri artırmaq (`message.max.bytes`, `max.request.size`) mümkündür, amma broker-lərin yaddaşına və replikasiyaya pis təsir edir.
</details>

<details>
<summary><b>29. Kafka Streams, Kafka Connect və Debezium nədir?</b></summary>

- **Kafka Streams:** Java kitabxanası; topic-lərdən oxuyub filter, join, aggregate, pəncərə (window) əməliyyatları edir və nəticəni yenidən topic-ə yazır. Ayrıca cluster lazım deyil.
- **Kafka Connect:** xarici sistemləri kodsuz qoşmaq üçün framework: source connector-lar (baza → Kafka) və sink connector-lar (Kafka → Elasticsearch, S3...).
- **Debezium:** Kafka Connect üzərində CDC connector-u; bazanın transaction log-unu (PostgreSQL WAL, MySQL binlog) oxuyub hər dəyişikliyi event kimi yayımlayır. Outbox pattern-in ən etibarlı relay-idir.
</details>

<details>
<summary><b>30. Kafka-da mesajı gecikmə ilə (məs. 30 dəqiqə sonra) necə emal etmək olar?</b></summary>

Kafka-da daxili "delayed delivery" yoxdur. Variantlar:

- ayrıca "delay" topic-ləri (Spring Kafka-nın retry topic-ləri kimi): consumer mesajın vaxtını yoxlayır və vaxtı çatmayıbsa gözləyir və ya partition-u pause edir;
- gecikməli işləri bazada və ya scheduler-də saxlamaq, vaxtı çatanda Kafka-ya yazmaq;
- bu tip tələb çoxdursa, gecikməli çatdırılmanı dəstəkləyən broker istifadə etmək (RabbitMQ delayed plugin).
</details>

---

[← 19. Spring Batch](19-spring-batch.md) · [Mündəricat](README.md) · Növbəti: [21. Redis →](21-redis.md)
