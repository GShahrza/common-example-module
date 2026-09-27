# redis-cache: Redis ilə keşləmə

Keş yavaş, amma tez-tez eyni cavabı verən mənbənin (baza, xarici API, ağır hesablama) önünə qoyulan sürətli yaddaşdır. Bu modulda "baza" hər sorğuda 300 ms gözləyir, Redis-dən isə cavab 2-5 ms-də gəlir.

| Mövzu | Harada |
|---|---|
| Cache-aside: `@Cacheable`, `@CachePut`, `@CacheEvict` | `ProductService` |
| Hər keşin öz TTL-i | `CacheConfig`, `cache.ttl.*` |
| JSON serializasiya, açar dizaynı (`shop:products:42`) | `CacheConfig` |
| `null` / boş nəticəni keşləməmək | `unless = "#result == null"` |
| Cache stampede və `sync = true` | `ReportService` |
| Keşdən başqa Redis: sorted set ilə "ən çox baxılanlar" | `ProductViews` |
| Hit/miss metrikaları | `spring.cache.redis.enable-statistics` |
| Testcontainers ilə real Redis-də test | `RedisCacheTest` |

## İşə salma qaydası

Ardıcıllıqla:

### 1. Redis-i işə salın

```bash
docker run -d --name redis -p 6379:6379 redis:8-alpine
```

### 2. Tətbiqi işə salın

```bash
./gradlew :redis-cache:bootRun
```

və ya Docker ilə:

```bash
docker build -f redis-cache/Dockerfile -t redis-cache .
docker run --rm -p 8088:8088 --add-host=host.docker.internal:host-gateway redis-cache
```

### 3. Baxın

- **http://localhost:8088**: demo səhifə. Hər cavabda `db` və ya `cache` yazılır; Redis-dəki açarlar da TTL-ləri ilə görünür.
- Ayrı terminalda **Redis-in gördüyü əmrlər** canlı axır:

  ```bash
  docker exec -it redis redis-cli monitor
  ```

  Məhsulu iki dəfə oxuyun: birinci dəfə `GET` (boş), sonra `SET ... PX 600000`; ikinci dəfə isə yalnız `GET`.

Terminaldan:

```bash
curl localhost:8088/api/products/5                  # "source":"db",    ~310 ms
curl localhost:8088/api/products/5                  # "source":"cache", ~3 ms
curl -X PUT localhost:8088/api/products/5/price -H 'Content-Type: application/json' -d '{"price":99}'
curl -X POST 'localhost:8088/api/reports/daily/stampede?n=100'   # {"computations":1, ...}
curl localhost:8088/api/cache/keys
curl 'localhost:8088/actuator/metrics/cache.gets?tag=result:hit'
```

---

## Necə işləyir

### Cache-aside

```
oxu:   tətbiq ──GET shop:products:5──► Redis
                  └─ yoxdur (miss) ──► baza ──► SET shop:products:5 (TTL 10 dəq) ──► cavab
yaz:   tətbiq ──► baza ──► SET shop:products:5 (yeni dəyər) + DEL shop:productsByCategory:laptops
```

Spring-in cache abstraksiyası bunu annotasiyalarla edir:

```java
@Cacheable(cacheNames = "products", key = "#id", unless = "#result == null")
public Optional<Product> find(long id)

@Caching(put   = @CachePut(cacheNames = "products", key = "#result.id"),
         evict = @CacheEvict(cacheNames = "productsByCategory", key = "#product.category"))
public Product changePrice(Product product, BigDecimal price)
```

- `@Cacheable` metodu yalnız keşdə dəyər olmayanda çağırır.
- `@CachePut` metodu **həmişə** çağırır və nəticəni keşə yazır.
- `@CacheEvict` açarı silir.

Siyahılar (`productsByCategory`) yenilənmir, silinir: bir məhsul dəyişəndə hansı siyahılarda olduğunu tapıb onları "düzəltmək" həm çətin, həm də səhvə açıqdır. Növbəti oxuma siyahını bazadan yenidən qurur.

`Optional` avtomatik açılır: keşə `Product` yazılır, boş nəticə isə yazılmır (`unless`). Yoxsa "yoxdur" cavabı TTL bitənə qədər keşdə qalardı; məhsul sonradan yaradılsa belə, görünməzdi.

### TTL: nə qədər köhnə ola bilər?

TTL keşin ən vacib parametridir və biznes sualına cavabdır:

| Keş | TTL | Niyə |
|---|---|---|
| `products` | 10 dəq | Dəyişəndə `@CachePut` onsuz da yeniləyir; TTL sığortadır |
| `productsByCategory` | 5 dəq | Dəyişiklikdə silinir |
| `rates` | 10 san | Xarici məlumat: biz onu dəyişmirik, yalnız köhnəlməsinə icazə veririk |
| `dailyReport` | 1 dəq | Ağır hesablama, bir az köhnə olması problem deyil |

TTL-siz keş, vaxt keçdikcə bazadan fərqlənən ikinci bir bazaya çevrilir.

### Serializasiya və açarlar

- **JSON**, JDK serializasiyası yox. `redis-cli`-də oxunur, `Serializable` tələb etmir, digər dillər də oxuya bilir. Hər keşə dəqiq tip verilir (`JacksonJsonRedisSerializer<>(jsonMapper, type)`). Hər dəyərə `@class` yazan "default typing" isə record-larla (final siniflər) işləmir və təhlükəsizlik riskidir.
- **Açarlar** `servis:keş:id` formasındadır (`shop:products:5`). Bir Redis-i çox vaxt bir neçə servis paylaşır; prefiks toqquşmaların qarşısını alır və `SCAN shop:*` ilə yalnız öz açarlarınıza baxmağa imkan verir.
- `KEYS *` production-da istifadə olunmur: Redis tək thread-lidir və `KEYS` işləyərkən bütün digər sorğular gözləyir. `SCAN` isə hissə-hissə gəzir.

### Cache stampede və `sync = true`

Populyar açarın TTL-i bitir və həmin anda 100 sorğu gəlir: 100-ü də miss görür və ağır hesablamanı 100 dəfə başladır. Baza tam keşin qorumalı olduğu anda yüklənir.

```java
@Cacheable(cacheNames = "dailyReport", key = "'today'", sync = true)
public Report daily()
```

`sync = true` ilə yalnız bir çağırış hesablayır, qalanları onun nəticəsini gözləyir. Demo-da 100 paralel sorğu **1** hesablama edir; locking olmadan hər sorğu ayrıca hesablayırdı.

**Spring Data Redis 4 tələsi:** `sync = true` təkbaşına kifayət deyil. Sinxronlaşdırmanı cache writer edir, default writer isə lock etmir. Ona görə `CacheConfig`-də locking writer qurulub:

```java
RedisCacheWriter.create(connectionFactory, writer -> writer
        .enableLocking()       // sync = true üçün; lock Redis-dədir, bütün instance-lar üçün işləyir
        .immediateWrites()     // put/evict dərhal (Lettuce ilə default asinxrondur)
        .collectStatistics()); // cache.gets{result=hit|miss}
```

Lock Redis-də açar kimi saxlanılır, ona görə bir neçə tətbiq instance-ı arasında da işləyir. Qiyməti: hər keş yazısı lock alır (əlavə bir round trip), gözləyənlər isə lock-u periodik yoxlayır (demo-da 100 sorğu ~3.5 s çəkdi, amma hesablama 1 dəfə oldu). Başqa variantlar:

- TTL-ə təsadüfi "jitter" əlavə etmək, ki açarlar eyni anda bitməsin;
- dəyəri TTL bitməzdən əvvəl arxa fonda yeniləmək (refresh-ahead).

**İkinci tələ, asinxron yazılar:** Lettuce ilə writer default olaraq `SET`/`DEL`-i asinxron göndərir. `@CacheEvict`-dən dərhal sonra köhnə dəyər hələ bir an oxuna bilir; testlərdə bu, "təsadüfi" uğursuzluq kimi görünür. `immediateWrites()` bunu aradan qaldırır.

### Redis keşdən artıqdır

`ProductViews` keş deyil, **paylaşılan məlumat strukturudur**:

```java
redis.opsForZSet().incrementScore("shop:product-views", "42", 1);   // ZINCRBY: atomar
redis.opsForZSet().reverseRangeWithScores("shop:product-views", 0, 4); // top 5
```

Bütün instance-lar eyni sayğacı artırır, lock və cədvəl lazım deyil. Redis-in digər tipik istifadələri:

- rate limiting (`INCR` + `EXPIRE`);
- sessiyalar (Spring Session);
- distributed lock;
- növbələr (Streams);
- pub/sub (məs. WebSocket instance-ları arasında mesaj paylaşmaq).

### Keşi nə vaxt istifadə etməməli

- Məlumat hər oxunuşda dəyişirsə (hit ratio aşağı olur, keş yalnız yük gətirir).
- Köhnə məlumat qəbuledilməzdirsə (hesab balansı, stok sayı ödəniş anında). Bu halda mənbədən oxuyun.
- Problem əslində yavaş sorğudursa: əvvəl indeks və sorğunu düzəldin, keş problemi gizlədir.

`/actuator/metrics/cache.gets` hit və miss sayını göstərir. Hit ratio aşağıdırsa, keş işləmir: ya açar səhv seçilib, ya TTL çox qısadır.

## Konfiqurasiya

| Dəyişən / property | Default | Nə üçün |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis ünvanı |
| `cache.ttl.<keş>` | yuxarıdakı cədvəl | Hər keşin TTL-i |
| `spring.cache.redis.enable-statistics` | `true` | hit/miss metrikaları |

## Testlər

Testlər **Testcontainers** ilə real Redis-i Docker-də qaldırır (`@ServiceConnection` Spring-i ona avtomatik qoşur), ona görə **Docker lazımdır**:

```bash
./gradlew :redis-cache:test
```

| Test | Nəyi yoxlayır |
|---|---|
| `secondReadComesFromRedisAsJsonWithTtl` | İkinci oxuma bazaya getmir; Redis-də JSON və TTL var |
| `priceChangeUpdatesTheEntryAndEvictsLists` | `@CachePut` yeni dəyəri yazır; yalnız həmin kateqoriyanın siyahısı silinir |
| `missingProductIsNotCached` | Boş nəticə keşlənmir |
| `deleteEvicts` | Silinmə keşdən də silir |
| `entriesExpireAfterTheirTtl` | TTL bitəndən sonra məlumat yenidən alınır |
| `syncCacheableComputesOnceUnderConcurrentMisses` | 30 paralel miss → 1 hesablama |
| `sortedSetKeepsTheMostViewedProducts` | Sorted set ilə top siyahı |

## Tələlər

- **Self-invocation.** Eyni sinifdən `this.find(id)` çağırışı proxy-dən keçmir və keş işləmir.
- **Keşlənmiş obyekti dəyişmək.** Lokal keşlərdə (Caffeine) qaytarılan obyekt keşdəki obyektin özüdür. Immutable record-lar bu problemi aradan qaldırır.
- **Sinif dəyişikliyi.** Deploy-dan sonra köhnə JSON yeni sinfə uyğun gəlməyə bilər. Ya prefiksə versiya əlavə edin (`shop:v2:products`), ya da naməlum sahələri ignore edin.
- **Redis düşəndə.** Default olaraq keş xətası sorğunu da uğursuz edir. Keşi "optional" etmək üçün xətaları loglayıb bazaya gedən `CacheErrorHandler` yazın.
- **Keş tutarlılığı.** "Bazaya yaz, keşi sil" arasında başqa sorğu köhnə dəyəri keşə yaza bilər. Qısa TTL bu pəncərəni məhdudlaşdırır.
