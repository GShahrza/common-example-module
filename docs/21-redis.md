# 21. Redis: keşləmə və yaddaşdakı məlumat strukturları

[← 20. Kafka](20-kafka.md) · [Mündəricat](README.md)

**Hissə IV: Əlavə mövzular** · **Kod:** [`CacheConfig`](../redis-cache/src/main/java/io/github/gshahrza/cache/CacheConfig.java), [`ProductService`](../redis-cache/src/main/java/io/github/gshahrza/cache/product/ProductService.java), [`ReportService`](../redis-cache/src/main/java/io/github/gshahrza/cache/report/ReportService.java), [`ProductViews`](../redis-cache/src/main/java/io/github/gshahrza/cache/product/ProductViews.java) · **Demo:** http://localhost:8088

```bash
docker run -d --name redis -p 6379:6379 redis:8-alpine
./gradlew :redis-cache:bootRun
docker exec -it redis redis-cli monitor      # ayrı terminalda: Redis-in aldığı hər əmr
```

---

## Həyatdan analogiya

Kitabxanaçıdan hər gün eyni üç kitabı soruşurlar. Kitablar zirzəmidədir: hər dəfə aşağı enmək beş dəqiqə çəkir. Ağıllı kitabxanaçı bu kitabları **masasının üstündə** saxlamağa başlayır:

- Masadakı kitabı vermək bir saniyə çəkir, zirzəmiyə enmək beş dəqiqə.
- Masa kiçikdir: yeni kitab üçün yer lazım olanda **ən çoxdan istifadə olunmayanı** geri aparır.
- Kitabın yeni nəşri çıxanda masadakı köhnə nüsxəni **götürür**, yoxsa oxucular köhnə məlumatla gedər.
- Qəzetləri isə masada yalnız **bir gün** saxlayır: sabah artıq köhnədir.
- Səhər 50 nəfər eyni anda eyni kitabı istəyir, masada isə yoxdur. Kitabxanaçı 50 dəfə zirzəmiyə enmir: **bir dəfə** enir, qalanlar gözləyir.

Masa **keşdir**. Zirzəmi bazadır. Nüsxəni geri aparmaq **eviction**, qəzetin bir günlük ömrü **TTL**, yeni nəşrdə nüsxəni götürmək **invalidation**, 50 nəfərin bir enişi isə **stampede qorunmasıdır**. Redis sürətli, paylaşılan və ağıllı bir masadır.

## Problem

Məhsul səhifəsi hər açılanda bazaya sorğu gedir. `redis-cache` modulunda "baza" hər sorğuda 300 ms gözləyir:

- Populyar məhsulu saniyədə 1000 nəfər açır, deməli bazaya saniyədə 1000 eyni sorğu gedir və hamısı eyni cavabı alır.
- Baza yüklənir, cavablar yavaşlayır, və bu, **bütün** sorğulara təsir edir, keşlənə bilməyənlərə də.
- Valyuta məzənnəsi xarici API-dən gəlir: hər sorğuda onu çağırmaq həm yavaş, həm də pullu və ya limitlidir.
- Bir neçə tətbiq instance-ı var: hər birinin öz yaddaşındakı keş bir-biri ilə uyğunsuz olur.

## Redis nədir

Redis (**RE**mote **DI**ctionary **S**erver) məlumatı **yaddaşda** saxlayan key-value serveridir. Adi key-value-dan fərqi dəyərlərin **məlumat strukturları** olmasıdır:

| Tip | Nədir | Tipik istifadə | Əmrlər |
|---|---|---|---|
| **String** | Mətn, rəqəm, binary (512 MB-a qədər) | Keş (JSON), sayğac | `SET`, `GET`, `INCR`, `SETEX` |
| **Hash** | Sahə → dəyər xəritəsi | Obyekt, sessiya | `HSET`, `HGETALL`, `HINCRBY` |
| **List** | Sıralı siyahı | Sadə növbə, son N element | `LPUSH`, `RPOP`, `LRANGE` |
| **Set** | Unikal elementlər | Teqlər, "kim bəyəndi" | `SADD`, `SISMEMBER`, `SINTER` |
| **Sorted Set** | Balı olan unikal elementlər | Reytinq, top-N, zaman sırası | `ZINCRBY`, `ZREVRANGE`, `ZRANGEBYSCORE` |
| **Stream** | Yalnız sona yazılan log, consumer group-larla | Event, iş növbəsi | `XADD`, `XREADGROUP`, `XACK` |
| **Bitmap, HyperLogLog, Geo** | Bitlər, təxmini unikal say, koordinatlar | Aktiv istifadəçilər, unikal ziyarətçi, "yaxınlıqdakı" | `SETBIT`, `PFADD`, `GEOSEARCH` |

Redis 8-də JSON, axtarış (Query Engine) və time series da əsas paylamaya daxildir.

Niyə bu qədər sürətlidir:

- Hər şey **RAM**-dadır.
- Əmrlər **bir thread-də**, ardıcıl icra olunur: lock yoxdur, hər əmr **atomardır**.
- Şəbəkə I/O-su event loop ilə işləyir (Redis 6-dan I/O üçün əlavə thread-lər də var).
- Məlumat strukturları sürət üçün optimallaşdırılıb.

Bir əmr adətən mikrosaniyələr çəkir; gecikmənin əsas hissəsi şəbəkədir.

## Həll, addım-addım

### 1. Cache-aside: `@Cacheable`

```java
@Cacheable(cacheNames = "products", key = "#id", unless = "#result == null")
public Optional<Product> find(long id) {
    return repository.findById(id);     // 300 ms
}
```

`redis-cli monitor`-da görünən ardıcıllıq:

```
1-ci çağırış:  EXISTS products~lock                               (lock yoxlaması, 5-ci addım)
               GET shop:products:5            → (nil)          miss
               ... baza, 300 ms ...
               EXISTS products~lock
               SET shop:products:5 "{...}" PX 600000               yaz, TTL 10 dəq
2-ci çağırış:  EXISTS products~lock
               GET shop:products:5            → "{...}"         hit
qiymət dəyişir: SET shop:products:5 "{...}" PX 600000              @CachePut
               DEL shop:productsByCategory:laptops                  @CacheEvict
```

Bu, modulu işlədib `redis-cli monitor` ilə yazılmış real ardıcıllıqdır.

Ölçülən nəticə: birinci oxuma `db · 311 ms`, sonrakılar `cache · 2-5 ms`.

Bu, **cache-aside** (lazy loading) pattern-idir: tətbiq əvvəl keşə baxır, yoxdursa mənbədən alır və keşə yazır. Keşdə yalnız həqiqətən istənilən məlumat olur.

Detallar:

- **`Optional` avtomatik açılır:** keşə `Product` yazılır, `Optional` qabı yox.
- **`unless = "#result == null"`:** "yoxdur" cavabı keşlənmir. Yoxsa sonradan yaradılan məhsul TTL bitənə qədər "tapılmır" kimi qalardı. (Əksinə, hücum ssenarisində "yoxdur"u qısa TTL ilə keşləmək faydalıdır; bax: sual 22.)
- **Annotasiyalar proxy ilə işləyir.** Eyni sinfin içindən `this.find(id)` çağırışı keşdən keçmir (self-invocation).

### 2. Yazanda: `@CachePut` və `@CacheEvict`

```java
@Caching(
        put = @CachePut(cacheNames = "products", key = "#result.id"),                  // yeni dəyəri yaz
        evict = @CacheEvict(cacheNames = "productsByCategory", key = "#product.category"))  // siyahını sil
public Product changePrice(Product product, BigDecimal price) { ... }
```

- `@CachePut` metodu **həmişə** icra edir və nəticəni keşə yazır.
- `@CacheEvict` açarı silir.
- **Siyahılar yenilənmir, silinir.** Bir məhsul dəyişəndə hansı siyahılarda olduğunu tapıb onları "düzəltmək" çətin və səhvə açıqdır. Növbəti oxuma siyahını yenidən qurur.
- Yalnız həmin kateqoriyanın siyahısı silinir. `allEntries = true` bütün kateqoriyaları silərdi.

Yazma zamanı keşlə bağlı üç strategiya var:

| Strategiya | Necə | Riski |
|---|---|---|
| **Invalidate (bu modul, ən çox istifadə olunan)** | Bazaya yaz, keşdən **sil** | Sonrakı oxuma bir dəfə yavaş olur |
| **Write-through** | Bazaya və keşə eyni anda yaz | Heç oxunmayan məlumat da keşə düşür |
| **Write-behind** | Əvvəl keşə yaz, bazaya sonra, asinxron | Redis düşsə, məlumat itir |

### 3. TTL: nə qədər köhnə ola bilər?

```yaml
cache:
  ttl:
    products: 10m
    productsByCategory: 5m
    rates: 10s        # xarici məlumat: biz dəyişmirik, sadəcə köhnəlməsinə icazə veririk
    dailyReport: 1m
```

TTL keşin ən vacib parametridir və texniki deyil, **biznes** sualının cavabıdır: "Bu məlumat nə qədər köhnə ola bilər?" Məzənnə 10 saniyə köhnə ola bilər, məhsulun təsviri 10 dəqiqə, hesab balansı isə heç köhnə ola bilməz, onu keşləmirik.

TTL-siz keş vaxt keçdikcə bazadan fərqlənən ikinci bir bazaya çevrilir. `@CacheEvict` unudulsa və ya iki instance arasında yarış olsa, TTL son sığortadır.

Redis bitmiş açarları iki yolla silir:

- **Passiv:** açar oxunanda vaxtının bitdiyi görülür və silinir.
- **Aktiv:** Redis mütəmadi olaraq TTL-li açarlardan təsadüfi nümunə götürür və bitmişləri silir.

### 4. Serializasiya və açar dizaynı

```java
RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(ttl.ttl().getOrDefault(name, Duration.ofMinutes(5)))
        .computePrefixWith(cache -> "shop:" + cache + ":")     // shop:products:42
        .disableCachingNullValues()
        .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(jsonMapper, type)));
```

- **JSON, JDK serializasiyası yox.** `redis-cli`-də oxunur, `Serializable` tələb etmir, başqa dillər də oxuya bilir.
- **Hər keşə dəqiq tip verilir.** Hər dəyərə `@class` yazan "default typing" record-larla (final siniflər) işləmir və təhlükəsizlik riskidir (deserializasiya ilə kod icrası).
- **Açar formatı `servis:keş:id`-dir.** Bir Redis-i çox vaxt bir neçə servis paylaşır. Prefiks toqquşmaların qarşısını alır, `SCAN MATCH shop:*` isə yalnız öz açarlarınızı gəzməyə imkan verir.
- **Versiya:** sinif dəyişəndə köhnə JSON yeni sinfə uyğun gəlməyə bilər. Prefiksə versiya əlavə etmək (`shop:v2:products`) deploy-dan sonra keşi təmiz başladır.

### 5. Cache stampede və `sync = true`

Populyar açarın TTL-i bitir və həmin millisaniyədə 100 sorğu gəlir. 100-ü də miss görür və ağır hesablamanı 100 dəfə başladır. Baza, keşin qorumalı olduğu anda, məhz o an yüklənir.

```java
@Cacheable(cacheNames = "dailyReport", key = "'today'", sync = true)
public Report daily() { ... 1 saniyəlik hesablama ... }
```

`sync = true` ilə yalnız bir çağırış hesablayır, qalanları onun nəticəsini gözləyir.

**Spring Data Redis 4 tələsi.** Bu modulu yazarkən `sync = true`-ya baxmayaraq 30 paralel sorğu **30** hesablama etdi. Səbəb: sinxronlaşdırmanı artıq cache writer edir, default writer isə lock etmir. Həlli `CacheConfig`-dədir:

```java
builder.cacheWriter(RedisCacheWriter.create(connectionFactory, writer -> writer
        .enableLocking()        // sync = true üçün; lock Redis-də açardır, bütün instance-lar üçün işləyir
        .immediateWrites()      // aşağıdakı tələyə bax
        .collectStatistics())); // cache.gets{result=hit|miss} metrikaları
```

Nəticə: 100 paralel sorğu cəmi **1** hesablama etdi. Qiyməti də var:
- gözləyənlər lock-u periodik yoxlayır, ona görə 100 sorğu ~3.5 saniyə çəkdi (hesablama 1 saniyədir);
- `monitor`-da görünür ki, hər keş əməliyyatından əvvəl `EXISTS <keş>~lock` yoxlaması gedir, yəni hər oxuma və yazma üçün əlavə bir round trip.

Locking-i bütün keşlərə yox, yalnız stampede riski olanlara tətbiq etmək üçün ayrıca `RedisCacheManager` qurmaq olar.

Stampede-ə qarşı digər üsullar:

- **TTL jitter:** TTL-ə təsadüfi ±10% əlavə edin, ki min açar eyni anda bitməsin (bu həm də cache avalanche-ın qarşısını alır).
- **Refresh-ahead:** populyar açarı TTL bitməzdən əvvəl arxa fonda yeniləyin.
- **Stale-while-revalidate:** köhnə dəyəri qaytarın və yeniləməni arxa fonda başladın.

### 6. İkinci tələ: asinxron yazılar

Testlərdə `@CacheEvict`-dən **dərhal** sonra açar hələ Redis-də idi, bir an sonra isə yox olurdu. Daha pisi, əvvəlki testin gecikmiş `clear()` əmri növbəti testin təzə yazdığı açarı silirdi.

Səbəb: Lettuce ilə cache writer `SET`/`DEL`-i default olaraq **asinxron** göndərir. Spring-in `Cache.clear()` müqaviləsi də clear-in asinxron olmasına icazə verir. `immediateWrites()` bunu aradan qaldırır. Real sistemdə bir anlıq pəncərə çox vaxt zərərsizdir; amma "yazdım, dərhal oxudum, köhnəsini gördüm" tipli bug-ların mənbəyi məhz budur.

### 7. Redis keşdən artıqdır: sorted set

```java
redis.opsForZSet().incrementScore("shop:product-views", "42", 1);        // ZINCRBY: atomar
redis.opsForZSet().reverseRangeWithScores("shop:product-views", 0, 4);   // ZREVRANGE ... WITHSCORES: top 5
```

Bu, keş deyil, **paylaşılan məlumat strukturudur**. Bütün instance-lar eyni sayğacı artırır: nə cədvəl, nə lock, nə yarış lazımdır, çünki Redis əmri bir thread-də atomar icra edir.

Redis-in tipik "keş olmayan" istifadələri:

| Tapşırıq | Redis həlli |
|---|---|
| Rate limiting | `INCR` + `EXPIRE` (fixed window) və ya sorted set (sliding window) |
| Sessiyalar | Spring Session: bütün instance-lar eyni sessiyanı görür |
| Distributed lock | `SET lock:x <token> NX PX 30000` + token yoxlaması ilə silmək |
| Reytinq, top-N | Sorted set |
| Unikal ziyarətçi sayı | HyperLogLog (12 KB ilə milyardlarla elementi ~0.8% xəta ilə sayır) |
| Instance-lar arası mesaj | Pub/Sub (məs. WebSocket instance-ları arasında) |
| Davamlı iş növbəsi | Streams + consumer group |

### 8. Nə vaxt keşləməmək lazımdır

- **Məlumat hər oxunuşda dəyişirsə:** hit ratio aşağı olur, keş yalnız yük gətirir.
- **Köhnə məlumat qəbuledilməzdirsə:** ödəniş anında hesab balansı, son stok vahidi. Bu halda mənbədən oxuyun.
- **Problem əslində yavaş sorğudursa:** əvvəl indeks və sorğunu düzəldin, keş problemi yalnız gizlədir.

`/actuator/metrics/cache.gets?tag=result:hit` hit sayını, `result:miss` isə miss sayını göstərir. Hit ratio aşağıdırsa, keş işləmir: ya açar səhv seçilib, ya da TTL çox qısadır.

## Production-da Redis

| Mövzu | Qısa |
|---|---|
| **Yaddaş limiti** | `maxmemory` + `maxmemory-policy`. Keş üçün `allkeys-lru` və ya `allkeys-lfu`; default `noeviction` isə yaddaş dolanda yazıları xəta ilə rədd edir |
| **Persistence** | Keş üçün çox vaxt lazım deyil. Sessiya, sayğac kimi məlumat üçün RDB (snapshot) və/və ya AOF (hər əmrin logu) |
| **Yüksək əlçatanlıq** | Replikasiya + **Sentinel** (avtomatik failover) və ya **Cluster** |
| **Miqyas** | Redis Cluster: 16 384 hash slot node-lara bölünür |
| **Client** | Spring Boot default olaraq **Lettuce** (Netty, thread-safe, bir bağlantı çox thread-ə kifayət edir) istifadə edir |
| **Redis düşəndə** | Default olaraq keş xətası sorğunu da uğursuz edir. Keşi "optional" etmək üçün xətaları loglayıb bazaya gedən `CacheErrorHandler` yazın |
| **Lisenziya** | Redis 7.4-də lisenziya dəyişdi və Linux Foundation **Valkey** fork-unu yaratdı; Redis 8-ə isə yenidən AGPLv3 variantı əlavə olundu. Managed servislərdə (AWS, GCP) çox vaxt Valkey təklif olunur; protokol və əmrlər uyğundur |

## Tələlər

- **Self-invocation:** `this.find(id)` proxy-dən keçmir, keş işləmir.
- **`KEYS *` production-da:** Redis tək thread-lidir; `KEYS` milyon açarı gəzərkən bütün digər sorğular gözləyir. `SCAN` istifadə edin.
- **Böyük açarlar (big keys):** 10 MB-lıq dəyər və ya milyon elementli set Redis-i bloklayır və şəbəkəni doldurur. Məlumatı bölün; silmək üçün `DEL` əvəzinə `UNLINK` (arxa fonda silir).
- **Hot key:** bir açarı hamı oxuyur və Cluster-də bir node yüklənir. Lokal (L1) keş və ya açarın replikaları kömək edir.
- **TTL-siz açarlar:** yaddaş yavaş-yavaş dolur, `noeviction` ilə isə bir gün yazılar dayanır.
- **Keş tutarlılığı:** "bazaya yaz, keşi sil" arasında başqa sorğu köhnə dəyəri keşə yaza bilər. Qısa TTL bu pəncərəni məhdudlaşdırır.
- **Deploy-dan sonra sinif dəyişikliyi:** köhnə JSON yeni sinfə oxunmur. Prefiksdə versiya saxlayın və ya naməlum sahələri ignore edin.
- **Redis-i əsas baza kimi istifadə etmək** (persistence-siz): restart hər şeyi silir.

## Yadda saxla

- Redis yaddaşda, tək thread-də işləyən, məlumat strukturları olan key-value serveridir: sürətli və atomar.
- **Cache-aside:** oxu → yoxdursa mənbədən al → keşə yaz. **Yazanda:** bazaya yaz, keşi sil (və ya `@CachePut`).
- **TTL biznes qərarıdır** və keşin son sığortasıdır.
- **Stampede:** `sync = true`, amma Spring Data Redis 4-də locking writer ilə; üstəlik TTL jitter.
- JSON + dəqiq tip + prefiksli açarlar; `KEYS` yox, `SCAN`.
- Redis keşdən artıqdır: sayğac, reytinq, lock, rate limit, sessiya, növbə.

## Tapşırıqlar

1. `redis-cli monitor`-u açın və demo-da məhsulu iki dəfə oxuyun, sonra qiymətini dəyişin. Hansı əmrləri görürsünüz (`GET`, `SET ... PX`, `DEL`)?
2. `CacheConfig`-dən `.enableLocking()`-i silin və "Cache stampede" düyməsini basın. Neçə hesablama oldu?
3. `redis-cli`-də `TTL shop:rates:AZN` əmrini bir neçə dəfə icra edin və azalmasını izləyin. 0-a çatandan sonra məzənnə sorğusunun `source` dəyərinə baxın.
4. `ProductService.find`-dən `unless`-i silin, mövcud olmayan məhsulu (`9999`) soruşun. Redis-ə nə yazıldı? Bu, hansı hücumda faydalı, hansı ssenaridə zərərli olardı?
5. (Çətin) `INCR` + `EXPIRE` ilə IP üzrə "dəqiqədə 10 sorğu" rate limiter-i yazın. Niyə `INCR` və `EXPIRE` bir Lua skriptində və ya `SET ... NX EX` ilə birlikdə olmalıdır?

---

## Müsahibə sualları

Ən çox verilən Redis sualları. Cavabı açmazdan əvvəl özünüz cavab verməyə çalışın.

### Əsaslar

<details>
<summary><b>1. Redis nədir və niyə bu qədər sürətlidir?</b></summary>

Yaddaşda işləyən key-value serveridir; dəyərlər məlumat strukturlarıdır (string, hash, list, set, sorted set, stream...). Sürətinin səbəbləri:

- məlumat RAM-dadır;
- əmrlər bir thread-də icra olunur (lock və context switch yoxdur);
- şəbəkə I/O-su non-blocking event loop ilə işləyir;
- məlumat strukturları sürət üçün optimallaşdırılıb;
- protokol (RESP) sadədir.
</details>

<details>
<summary><b>2. Redis single-threaded-dirmi? Bu, çatışmazlıq deyilmi?</b></summary>

Əmrlərin **icrası** bir thread-dədir, ona görə hər əmr atomardır və lock lazım deyil. Redis 6-dan şəbəkə oxuma və yazması üçün əlavə I/O thread-ləri istifadə oluna bilər; persistence (RDB/AOF rewrite) isə ayrıca prosesdə və ya thread-də gedir. Darboğaz adətən CPU yox, yaddaş və şəbəkədir. Bir thread-in çatışmazlığı budur ki, uzun əmr (`KEYS *`, böyük `DEL`, ağır Lua skripti) **bütün** sorğuları bloklayır. Daha çox CPU lazımdırsa, Cluster ilə horizontal miqyaslanır.
</details>

<details>
<summary><b>3. Redis-in məlumat tipləri və hər birinin istifadəsi</b></summary>

- **String:** keş, sayğac (`INCR`).
- **Hash:** obyekt, sessiya.
- **List:** sadə növbə, son N element.
- **Set:** unikal elementlər, kəsişmə və birləşmə.
- **Sorted set:** reytinq, top-N, vaxta görə sıralama.
- **Stream:** event log, consumer group-lu növbə.
- **Bitmap:** gündəlik aktivlik.
- **HyperLogLog:** unikal elementlərin təxmini sayı.
- **Geo:** məsafə və radius axtarışı.
</details>

<details>
<summary><b>4. Sorted set daxildə necə qurulub?</b></summary>

İki strukturun birləşməsidir:

- **skip list** balla sıralı saxlayır (range sorğuları və sıra: O(log N));
- **hash table** element → bal xəritəsidir (`ZSCORE`: O(1)).

Kiçik sorted set-lər yaddaşa qənaət üçün kompakt formada (listpack) saxlanılır.
</details>

<details>
<summary><b>5. Redis ilə Memcached arasında fərq nədir?</b></summary>

**Memcached** sadə, multi-threaded, yalnız string saxlayan keşdir: persistence, replikasiya və məlumat strukturları yoxdur. **Redis**-də isə məlumat strukturları, atomar əmrlər, persistence, replikasiya, Cluster, Lua, Pub/Sub və Streams var. Sadə keşdə ikisi də işləyir; Redis daha çox tapşırığı (sayğac, reytinq, lock, növbə) bir alətlə həll edir.
</details>

### Keşləmə

<details>
<summary><b>6. Keşləmə strategiyaları: cache-aside, read-through, write-through, write-behind</b></summary>

- **Cache-aside (lazy loading):** tətbiq əvvəl keşə baxır, yoxdursa bazadan alıb keşə yazır. Ən çox istifadə olunur (`@Cacheable`).
- **Read-through:** keş qatı özü bazadan yükləyir (tətbiq yalnız keşlə danışır).
- **Write-through:** yazı eyni anda keşə və bazaya gedir.
- **Write-behind (write-back):** yazı keşə gedir, bazaya sonra, asinxron yazılır. Sürətlidir, amma məlumat itkisi riski var.
</details>

<details>
<summary><b>7. Keş invalidasiyası niyə çətindir? Update zamanı keşi yeniləmək, yoxsa silmək?</b></summary>

Çünki məlumat iki yerdədir və onları atomar dəyişmək olmur. Adətən **silmək** (invalidate) seçilir. Yeniləmək iki paralel yazıda yarış yaradır: A bazaya yazır, B bazaya yazır, B keşə yazır, A keşə yazır, və keşdə köhnə dəyər (A) qalır. Silmədə isə növbəti oxuma təzə dəyəri gətirir. Tövsiyə olunan sıra: əvvəl bazaya yaz, **sonra** keşi sil. Qalan kiçik yarış pəncərəsini qısa TTL (və ya "delayed double delete") bağlayır.
</details>

<details>
<summary><b>8. Cache stampede (thundering herd) nədir və necə qarşısı alınır?</b></summary>

Populyar açarın vaxtı bitəndə çoxlu sorğu eyni anda miss görür və mənbəyə hücum edir. Qarşısını almağın yolları:

- bir sorğunun yenidən hesablaması, qalanlarının gözləməsi (`@Cacheable(sync = true)`, distributed lock);
- TTL jitter;
- refresh-ahead (vaxt bitməzdən əvvəl arxa fonda yeniləmə);
- stale-while-revalidate (köhnə dəyəri qaytarıb arxa fonda yeniləmə).
</details>

<details>
<summary><b>9. Cache penetration nədir?</b></summary>

**Mövcud olmayan** açarlar üçün sorğular (çox vaxt hücum: `/products/-1`, təsadüfi id-lər). Onlar heç vaxt keşə düşmür və hər dəfə bazaya gedir. Qarşısını almaq üçün:

- "yoxdur" cavabını qısa TTL ilə keşləmək;
- **Bloom filter** ilə mövcud id-ləri əvvəlcədən yoxlamaq;
- girişi validasiya etmək.
</details>

<details>
<summary><b>10. Cache avalanche nədir?</b></summary>

Çoxlu açarın **eyni anda** vaxtının bitməsi (məs. hamısı deploy-da eyni TTL ilə yazılıb) və ya Redis-in tamamilə düşməsi. Nəticədə bütün yük birdən bazaya keçir. Qarşısını almaq üçün:

- TTL-ə təsadüfi əlavə (jitter);
- Redis-in yüksək əlçatanlığı (replika, Sentinel, Cluster);
- circuit breaker və rate limit ilə bazanı qorumaq;
- keşi əvvəlcədən doldurmaq (warm-up).
</details>

<details>
<summary><b>11. Hot key problemi nədir?</b></summary>

Bir açara qeyri-adi dərəcədə çox sorğu gəlir (viral məhsul, qlobal konfiqurasiya). Cluster-də o açar bir node-dadır və həmin node yüklənir, digərləri isə boş qalır. Həlləri:

- tətbiqin daxilində lokal keş (Caffeine) ilə iki səviyyəli keş;
- açarın bir neçə surəti (`key:1`...`key:N`, təsadüfi oxuma);
- replikalardan oxumaq.
</details>

<details>
<summary><b>12. Spring-də <code>@Cacheable</code> niyə bəzən işləmir?</b></summary>

- **Self-invocation:** eyni sinfin daxilindən çağırış proxy-dən keçmir.
- Metod `private` və ya `final`-dır.
- `@EnableCaching` yoxdur.
- Açar (`key`) hər çağırışda fərqlidir: məsələn, parametr `equals`/`hashCode`-suz obyektdir.
- `unless` və ya `condition` keşləməni bloklayır.
- Spring Data Redis 4-də `sync = true` locking writer olmadan sinxronlaşdırmır.
</details>

### Yaddaş, TTL və persistence

<details>
<summary><b>13. Redis vaxtı bitmiş açarları necə silir?</b></summary>

İki mexanizmlə:

- **Passiv:** açara müraciət olunanda vaxtının bitdiyi görülür və silinir.
- **Aktiv:** Redis saniyədə bir neçə dəfə TTL-li açarlardan təsadüfi nümunə götürür və bitmişləri silir; bitmişlərin payı yüksəkdirsə, dövrü təkrarlayır.

Ona görə vaxtı bitmiş açar yaddaşda bir müddət qala bilər, amma heç vaxt oxunmaz.
</details>

<details>
<summary><b>14. Eviction policy-lər hansılardır?</b></summary>

`maxmemory` dolanda nə ediləcəyini müəyyən edir:

- `noeviction` (default): yazılar xəta ilə rədd edilir;
- `allkeys-lru` / `allkeys-lfu`: bütün açarlardan ən az son istifadə olunan / ən az tez-tez istifadə olunan silinir;
- `volatile-lru` / `volatile-lfu` / `volatile-ttl` / `volatile-random`: yalnız TTL-li açarlardan;
- `allkeys-random`.

Təmiz keş üçün adətən `allkeys-lru` və ya `allkeys-lfu` seçilir. LRU və LFU təxminidir: Redis nümunə götürərək seçir.
</details>

<details>
<summary><b>15. RDB və AOF persistence arasında fərq nədir?</b></summary>

- **RDB:** müəyyən intervallarla bütün məlumatın snapshot-u (fork + copy-on-write). Kompaktdır və tez bərpa olunur, amma son snapshot-dan sonrakı dəyişikliklər itə bilər.
- **AOF:** hər yazı əmri log-a əlavə olunur; `appendfsync always` / `everysec` (default, ən çox 1 saniyəlik itki) / `no`. Daha etibarlıdır, fayl böyükdür, periodik rewrite olunur.

Adətən ikisi birlikdə istifadə olunur (hybrid: AOF RDB preamble ilə). Təmiz keş üçün persistence-i söndürmək olar.
</details>

<details>
<summary><b>16. <code>KEYS</code> ilə <code>SCAN</code> arasında fərq nədir?</b></summary>

`KEYS pattern` bütün açarları bir əmrdə gəzir: O(N), və Redis tək thread-li olduğu üçün bu müddətdə hər şey bloklanır. `SCAN` isə cursor ilə hissə-hissə gəzir (`COUNT` ipucu ilə), hər çağırış qısadır. İcra zamanı dəyişən açarlar üçün zəmanəti zəifdir (element iki dəfə qayıda bilər). Production-da yalnız `SCAN` (`HSCAN`, `SSCAN`, `ZSCAN`) istifadə olunur.
</details>

<details>
<summary><b>17. <code>DEL</code> ilə <code>UNLINK</code> arasında fərq nədir?</b></summary>

`DEL` yaddaşı sinxron azad edir: milyon elementli açarda Redis saniyələrlə bloklana bilər. `UNLINK` isə açarı keyspace-dən dərhal çıxarır, yaddaşı isə arxa fon thread-ində azad edir. Böyük açarlar üçün `UNLINK` istifadə olunur. `lazyfree-*` parametrləri eviction və expire üçün də arxa fonda silməni yandırır.
</details>

### Atomarlıq, tranzaksiya və lock

<details>
<summary><b>18. Redis-də tranzaksiyalar necə işləyir? Rollback varmı?</b></summary>

`MULTI` əmrləri növbəyə yığır, `EXEC` onları ardıcıl və araya başqa əmr girmədən icra edir. Rollback **yoxdur**: bir əmr runtime-da xəta versə də qalanları icra olunur. `WATCH key` optimistic lock verir: izlənən açar `EXEC`-dən əvvəl dəyişibsə, tranzaksiya ləğv olunur (`nil`) və client yenidən cəhd edir. Şərti məntiq lazımdırsa, Lua skripti daha rahatdır.
</details>

<details>
<summary><b>19. Lua skriptləri nə üçündür?</b></summary>

Skript (`EVAL`, Redis 7+ `FUNCTION`) server tərəfdə **atomar** icra olunur: skript işləyərkən başqa əmr icra olunmur. Bu, "oxu, yoxla, yaz" məntiqini bir round trip-də və yarışsız etməyə imkan verir: rate limiter, token yoxlaması ilə lock-un buraxılması, şərti yeniləmə. Uzun skript isə bütün Redis-i bloklayır.
</details>

<details>
<summary><b>20. Pipelining nədir və tranzaksiyadan nə ilə fərqlənir?</b></summary>

Pipelining bir neçə əmri cavab gözləmədən birlikdə göndərməkdir: round trip sayını azaldır, throughput-u kəskin artırır. Amma **atomar deyil**: başqa client-lərin əmrləri araya girə bilər. `MULTI`/`EXEC` isə atomar icradır. İkisi birlikdə də istifadə oluna bilər.
</details>

<details>
<summary><b>21. Redis ilə distributed lock necə qurulur?</b></summary>

- **Götürmək:** `SET lock:order:42 <unikal token> NX PX 30000`. `NX` açar yoxdursa yazır, `PX` isə sahib çöksə lock-un əbədi qalmamasını təmin edir.
- **Buraxmaq:** yalnız token uyğundursa silmək, və bu, Lua skripti ilə atomar edilir (yoxsa başqasının lock-unu silmək olar).

Tələlər:

- iş TTL-dən uzun çəksə, lock bitir və ikinci proses girir. Buna görə resursa **fencing token** göndərilir və ya lock uzadılır (watchdog, Redisson-da olduğu kimi);
- tək node-lu lock node düşəndə itir. Redlock çoxlu node alqoritmidir, amma onun etibarlılığı mübahisəlidir.

Ciddi korrektlik tələb edən hallarda baza səviyyəsində lock (`SELECT ... FOR UPDATE`, unique constraint) və ya ZooKeeper/etcd daha etibarlıdır.
</details>

<details>
<summary><b>22. Redis ilə rate limiter necə yazılır?</b></summary>

- **Fixed window:** `INCR rate:{ip}:{dəqiqə}`, ilk artımda `EXPIRE 60`; limitdən çoxdursa, rədd et. Sadədir, amma pəncərə sərhədində 2× partlayışa icazə verir.
- **Sliding window:** sorted set-ə zaman damğası əlavə edilir (`ZADD`), köhnələr silinir (`ZREMRANGEBYSCORE`), sonra sayılır (`ZCARD`); hamısı bir Lua skriptində.
- **Token bucket:** vəziyyət hash-də saxlanılır, doldurma və götürmə Lua ilə atomar edilir.

`INCR` və `EXPIRE` ayrı əmrlərdirsə, arada proses çökəndə TTL-siz sayğac qalır. Buna görə ya Lua, ya da `SET key 0 EX 60 NX` + `INCR` istifadə edilir.
</details>

### Yüksək əlçatanlıq və miqyas

<details>
<summary><b>23. Redis replikasiyası necə işləyir?</b></summary>

Master yazıları qəbul edir və onları replikalara **asinxron** ötürür. Replikalar oxuma üçün istifadə oluna bilər. Asinxron olduğu üçün master çökəndə replikaya hələ çatmamış son yazılar itə bilər. `WAIT numreplicas timeout` yazının N replikaya çatmasını gözləyir, amma tam sinxron zəmanət vermir.
</details>

<details>
<summary><b>24. Redis Sentinel nədir?</b></summary>

Master/replika qurğusu üçün monitorinq və **avtomatik failover** sistemidir. Bir neçə Sentinel prosesi master-i izləyir. Kvorum master-in düşdüyünə razılaşanda replikalardan birini master edir və client-lərə yeni ünvanı bildirir. Sharding etmir: bütün məlumat bir master-dədir.
</details>

<details>
<summary><b>25. Redis Cluster necə işləyir?</b></summary>

- Məlumat **16 384 hash slot**-a bölünür: `slot = CRC16(key) mod 16384`. Slot-lar master node-lar arasında paylanır, hər master-in replikaları olur.
- Client açarın slot-unu hesablayıb düzgün node-a gedir. Səhv node `MOVED` (və ya köçürmə zamanı `ASK`) cavabı ilə yönləndirir.
- Failover daxilidir, Sentinel lazım deyil.
- Master-lər arasında məlumat bölüşdürülür, ona görə həm yaddaş, həm də yazma throughput-u miqyaslanır.
</details>

<details>
<summary><b>26. Cluster-də çox açarlı əmrlər və hash tag-lar</b></summary>

`MGET`, `MULTI`, Lua skriptləri və `SINTER` kimi çox açarlı əmrlər yalnız bütün açarlar **eyni slot**-dadırsa işləyir; yoxsa `CROSSSLOT` xətası alınır. **Hash tag** ilə açarın yalnız `{...}` içindəki hissəsi hash-lənir: `cart:{user42}` və `orders:{user42}` eyni slot-a düşür. Həddindən artıq istifadəsi isə bir slot-u "qızğın" edir.
</details>

### Mesajlaşma və digər

<details>
<summary><b>27. Pub/Sub ilə Streams arasında fərq nədir?</b></summary>

- **Pub/Sub** "at və unut"dur: mesaj yalnız həmin an qoşulu olan abunəçilərə çatır, saxlanılmır, təsdiq yoxdur. Canlı bildirişlər və instance-lar arası siqnallar üçündür.
- **Streams** isə saxlanılan log-dur: ID-lər, consumer group-lar, `XACK` ilə təsdiq, pending siyahısı (`XPENDING`) və başqa consumer-ə vermək (`XCLAIM`/`XAUTOCLAIM`). Kafka-ya bənzər, amma daha kiçik miqyaslı növbədir.
</details>

<details>
<summary><b>28. Redis əsas baza kimi istifadə oluna bilərmi?</b></summary>

Bəzi hallarda: AOF (`everysec` və ya `always`), replikasiya və HA ilə Redis sessiyalar, reytinqlər, sayğaclar və real-time vəziyyət üçün əsas saxlama yeri ola bilər. Amma:

- bütün məlumat RAM-a sığmalıdır;
- mürəkkəb sorğular, join və əlaqələr yoxdur;
- asinxron replikasiyada son yazılar itə bilər;
- ACID tranzaksiyaları məhduddur.

Pul, sifariş və hüquqi əhəmiyyəti olan məlumat üçün relyasiyalı baza "həqiqət mənbəyi" olaraq qalmalıdır, Redis isə onun önündə durur.
</details>

<details>
<summary><b>29. Redis-də böyük açarları (big keys) necə tapmaq və idarə etmək olar?</b></summary>

- **Tapmaq:** `redis-cli --bigkeys`, `--memkeys` və ya `MEMORY USAGE key`.
- **Nə üçün problemdir:** böyük dəyərləri oxumaq və silmək Redis-i bloklayır, şəbəkəni doldurur, Cluster-də bir node-u şişirir, replikasiyanı yavaşladır.
- **Həlli:** məlumatı kiçik açarlara bölmək (məs. hash-i `user:{id}:part:N`-ə), kolleksiyaları hissə-hissə oxumaq (`HSCAN`, `LRANGE` diapazonla), silmək üçün `UNLINK`.
</details>

<details>
<summary><b>30. Lettuce ilə Jedis arasında fərq nədir?</b></summary>

- **Lettuce** (Spring Boot-un default-u): Netty üzərində, non-blocking, **thread-safe**; bir bağlantını çox thread paylaşa bilər. Sinxron, asinxron və reaktiv API-si var, Cluster və Sentinel-i dəstəkləyir.
- **Jedis:** sadə, bloklayan client-dir; bir bağlantı thread-safe deyil, ona görə pool (`JedisPool`) lazımdır.

Reaktiv (WebFlux) tətbiqdə yalnız Lettuce uyğundur.
</details>

---

[← 20. Kafka](20-kafka.md) · [Mündəricat](README.md)
