# security-jwt: Spring Security və JWT

Mobil tətbiq, SPA və digər servislər üçün API-ni **stateless** qorumaq: server sessiya saxlamır, hər sorğu imzalı token gətirir.

| Mövzu | Harada |
|---|---|
| Login → access token (JWT, RS256) + refresh token | `AuthController`, `TokenService` |
| JWT-ni yoxlamaq (resource server): imza, `exp`, `iss` | `JwtKeyConfig`, `SecurityConfig` |
| Rollar (`ROLE_ADMIN`) və scope-lar (`SCOPE_orders:write`) | `SecurityConfig.authorities()` |
| URL qaydaları və `@PreAuthorize` (sahiblik yoxlaması, IDOR) | `SecurityConfig`, `OrderController` |
| Refresh token: bazada (hash), rotation, oğurluğun aşkarlanması, maksimum ömür | `RefreshTokenStore`, `schema.sql` |
| Brauzer üçün `HttpOnly` cookie, mobil üçün body | `AuthController` |
| Klientdə avtomatik refresh (401 → refresh → təkrar), bir refresh bir dəfə | `index.html`; Android/iOS nümunələri aşağıda |
| Aktiv sessiyalar (cihazlar), bir cihazı çıxarmaq, logout, "hər yerdən çıx" | `/api/me/sessions`, `/api/auth/logout`, `/api/admin/users/{u}/revoke` |
| Public açarların paylaşılması (JWKS) | `/.well-known/jwks.json` |
| 401/403 cavabları: `WWW-Authenticate` + JSON | `SecurityConfig` |
| CORS (başqa origin-dəki SPA) | `SecurityConfig.corsConfigurationSource` |
| Test: real token-lərlə və `jwt()` ilə | `SecurityJwtTest`, `RefreshTokenTest`, `MethodSecurityTest` |

📖 Kitab üslubunda izah və 30 müsahibə sualı: [23. Spring Security və JWT: API-ni stateless qorumaq](../docs/23-security-jwt.md).

## İşə salma qaydası

Heç bir xarici servis lazım deyil.

```bash
./gradlew :security-jwt:bootRun
```

və ya Docker ilə:

```bash
docker build -f security-jwt/Dockerfile -t security-jwt .
docker run --rm -p 8090:8090 security-jwt
```

**http://localhost:8090**: login olun, token-in içinə baxın, API-ni çağırın və hücumları sınayın. İstifadəçilər:

| İstifadəçi | Parol | Rollar | Scope |
|---|---|---|---|
| `aynur` | `aynur123` | USER | `orders:read orders:write` |
| `rashad` | `rashad123` | USER | `orders:read orders:write` |
| `admin` | `admin123` | USER, ADMIN | `orders:read orders:write users:admin` |

Terminaldan (mobil tətbiq də eyni şeyi edir):

```bash
# 1. Login
curl -s -X POST localhost:8090/api/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"aynur","password":"aynur123"}'
# {"access_token":"eyJraWQi...","token_type":"Bearer","expires_in":900,"refresh_token":"Qm9..."}

# 2. Token ilə sorğu
TOKEN=eyJraWQi...
curl -s localhost:8090/api/me -H "Authorization: Bearer $TOKEN"

# 3. Access token bitəndə: refresh (cavabda YENİ refresh token gəlir, köhnəsini atın)
curl -s -X POST localhost:8090/api/auth/refresh -H 'Content-Type: application/json' \
     -d '{"refresh_token":"Qm9..."}'
```

Avtomatik refresh-i real vaxtla görmək üçün access token-in ömrünü qısaldın. Token `exp`-dən sonra da ~30 saniyə qəbul olunur (`clock-skew`):

```bash
JWT_ACCESS_TTL=30s ./gradlew :security-jwt:bootRun
```

Refresh token-lər `build/security-jwt-db` faylındakı H2 bazasında saxlanılır, ona görə login-lər restart-dan sonra da qalır. Başqa baza üçün: `JWT_DB_URL=jdbc:postgresql://...` (+ driver).

---

## Necə işləyir

### Session, yoxsa token?

| | Session cookie | JWT (bu modul) |
|---|---|---|
| Server nə saxlayır | Hər istifadəçinin sessiyası (yaddaş, Redis) | Heç nə (refresh token-lər istisna) |
| Hər sorğuda yoxlama | Sessiya axtarışı | İmzanın yoxlanması (şəbəkəsiz, CPU) |
| Bir neçə servis | Sessiya paylaşılmalıdır | Hər servis public açarla özü yoxlayır |
| Mobil tətbiq | Cookie ilə işləmək narahatdır | `Authorization` header-i, təbii |
| Dərhal logout | Asan (sessiyanı sil) | Çətin: access token bitənə qədər etibarlıdır |
| CSRF | Qorunma lazımdır | Header-lə göndərilirsə, lazım deyil |

Server tərəfdə render olunan klassik veb tətbiq üçün session daha sadə və təhlükəsizdir. JWT isə mobil tətbiqlər, SPA və mikroservislər üçün uyğundur.

### JWT-nin quruluşu

```
eyJraWQiOiI5MGRk...  .  eyJzdWIiOiJheW51ciIs...  .  kT8x2Vb...
      header                   payload                  imza
```

```json
{ "kid": "90dd7864-...", "alg": "RS256" }
{ "sub": "aynur", "roles": ["USER"], "scope": "orders:read orders:write",
  "iss": "http://localhost:8090", "iat": 1790489221, "exp": 1790490121, "jti": "4bc4..." }
```

- **Payload şifrələnmir**, sadəcə Base64-dür. Token-i əldə edən hər kəs onu oxuya bilər (demo səhifədə görünür), ona görə içinə parol, kart nömrəsi və ya şəxsi məlumat qoyulmur.
- **İmza** payload-un dəyişdirilmədiyini sübut edir. Demo-da "Payload-da rolu ADMIN et" düyməsi rolu dəyişdirir, amma imza köhnə qalır: server `401 invalid_token` qaytarır (testdə yoxlanılır).
- `exp`: 15 dəqiqə. `iss`: kim verib. `jti`: token-in unikal id-si (loglar və "qara siyahı" üçün).

### RS256, HS256 deyil

- **HS256:** bir gizli açar həm imzalayır, həm yoxlayır. Token-i yoxlayan hər servis bu açarı bilməlidir, yəni o, token **yarada** da bilər.
- **RS256:** private açar yalnız token verən servisdədir. Digər servislər `/.well-known/jwks.json`-dan **public** açarı götürüb yoxlayır. Başqa bir servisi bu açarla qoşmaq üçün bir sətir kifayətdir:

```yaml
spring.security.oauth2.resourceserver.jwt.jwk-set-uri: http://auth-service/.well-known/jwks.json
```

`kid` (key id) açar rotasiyası üçündür: yeni açar əlavə olunur, köhnə token-lər bitənə qədər köhnə açar da JWKS-də qalır.

Demo-da açar hər start-da yenidən yaradılır, yəni restart-dan sonra köhnə **access** token-lər etibarsız olur. Refresh token-lər isə bazadadır: klient 401 alır, refresh edir və yeni açarla imzalanmış token alır, istifadəçi heç nə hiss etmir. Production-da açarı `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` (PEM) ilə secret store-dan verin ki, bütün instance-lar eyni açardan istifadə etsin.

### Sorğunun yolu

```
Authorization: Bearer eyJ...
      │
BearerTokenAuthenticationFilter
      │  JwtDecoder: imza ✓  exp ✓  iss ✓        ✗ → 401 + WWW-Authenticate: Bearer error="invalid_token"
      ▼
JwtAuthenticationConverter: scope → SCOPE_*, roles → ROLE_*
      ▼
authorizeHttpRequests: /api/admin/** → hasRole("ADMIN")    ✗ → 403 insufficient_scope
      ▼
@PreAuthorize("#username == authentication.name or hasRole('ADMIN')")
      ▼
controller: @AuthenticationPrincipal Jwt jwt
```

`/api/me` bu zəncirin nəticəsini göstərir: token-dən gələn authority-lər. Orada `FACTOR_BEARER` da var: Spring Security 7 hər autentifikasiya üsulunu "factor" kimi qeyd edir (MFA qaydaları üçün).

### Rol, yoxsa scope?

- **Rol** istifadəçinin **kim** olduğudur: `ADMIN`, `USER`.
- **Scope** bu **token-in nəyə icazəsi** olduğudur: `orders:read`, `orders:write`.

Fərq üçüncü tərəf tətbiqlərdə görünür: istifadəçi admin ola bilər, amma bir hesabat tətbiqinə yalnız `orders:read` icazəsi verir. Burada ikisi də istifadə olunur: `/api/admin/**` roldan, `POST /api/orders` isə scope-dan asılıdır.

### Ən çox rast gəlinən boşluq: IDOR

```java
@GetMapping("/api/users/{username}/orders")
@PreAuthorize("#username == authentication.name or hasRole('ADMIN')")
```

Login olmaq **başqasının** məlumatına baxmaq icazəsi demək deyil. `aynur` URL-də `rashad` yazsa, `403` almalıdır. Bu yoxlamanı unutmaq OWASP API Security Top 10-da **1-ci** yerdədir (Broken Object Level Authorization). Qayda: sahibi URL-dən və ya body-dən yox, **token-dən** götürün (`/api/orders` belə edir). URL-də id varsa, sahibliyi yoxlayın.

Default qayda `anyRequest().authenticated()`-dir: yeni endpoint unudulsa belə, public olmur.

### Refresh token

Access token qısa (15 dəq) olmalıdır, çünki onu geri almaq olmur. İstifadəçinin hər 15 dəqiqədən bir login olmaması üçün uzunömürlü **refresh token** verilir. Onun yeganə işi yeni access token almaqdır.

| | Access token | Refresh token |
|---|---|---|
| Format | JWT (imzalı, oxuna bilən) | Təsadüfi 256 bit (heç nə daşımır) |
| Ömür | 15 dəq | 7 gün istifadəsiz; aktiv login ən çox 30 gün |
| Harada yoxlanılır | Hər API sorğusunda, bazasız | Yalnız `/api/auth/refresh`-də, bazada |
| Geri almaq olur? | Yox (bitənə qədər) | Bəli, sətri silmək kifayətdir |
| Hara göndərilir | Hər API-yə (`Authorization` header) | Yalnız auth server-ə |

#### Serverdə: `refresh_token` cədvəli

```sql
token_hash      CHAR(64) PRIMARY KEY   -- SHA-256, token-in özü yox
username, family_id, device            -- family = bir login (bir cihaz)
created_at, expires_at, family_expires -- 7 gün idle, 30 gün absolut
used_at                                -- rotation zamanı doldurulur
```

- **Hash:** baza sızsa belə, oradakı dəyərlərlə refresh etmək olmur (testdə yoxlanılır).
- **Rotation:** hər refresh **yeni** token qaytarır, köhnəsi `used_at` ilə işarələnir.
- **Reuse detection:** istifadə olunmuş token yenidən gəlirsə, onu kimsə kopyalayıb. O login-in bütün token-ləri (family) silinir: həm oğru, həm əsl istifadəçi çıxır, və əsl istifadəçi yenidən login olur.
- **Atomarlıq:** yoxlama və işarələmə bir SQL-dədir: `UPDATE ... SET used_at = now WHERE token_hash = ? AND used_at IS NULL AND expires_at > now`. İki paralel sorğudan yalnız biri `1` sətir dəyişdirir (testdə 8 paralel sorğudan yalnız biri uğurlu olur).
- **`@Transactional(noRollbackFor = ...)`:** reuse aşkarlananda family silinir **və** exception atılır. Adi `@Transactional` exception-da `DELETE`-i geri qaytarardı və oğurlanmış login işləməyə davam edərdi.
- **İki limit:**
  - 7 gün istifadəsiz → yenidən login;
  - aktiv istifadədə belə 30 gündən sonra → yenidən login (`session-max-age`), ki oğurlanmış, amma aktiv istifadə olunan token əbədi yaşamasın.
- **Təmizlik:** vaxtı keçmiş sətirlər saatda bir silinir (`@Scheduled`).
- Refresh zamanı istifadəçinin rolları **bazadan yenidən oxunur**: rolu alınmış istifadəçi onu 15 dəqiqədən artıq saxlamır.

```
login ──► RT1 ──refresh──► RT2 ──refresh──► RT3
               (RT1: used)      (RT2: used)
RT1 yenidən gəldi ──► family ləğv: RT3 də işləmir
```

#### Brauzer: `HttpOnly` cookie

Refresh token JavaScript-in əli çata bilən yerdə (`localStorage`, JS dəyişəni) olarsa, bir XSS boşluğu onu oğurlayır və oğru 30 gün login qalır. Ona görə brauzer login zamanı `"cookie": true` göndərir:

```http
POST /api/auth/login   {"username":"aynur","password":"...","cookie":true}

200 {"access_token":"eyJ...","token_type":"Bearer","expires_in":900}       ← body-də refresh token yoxdur
Set-Cookie: refresh_token=Qm9...; Path=/api/auth; Max-Age=604800; Secure; HttpOnly; SameSite=Strict
```

- `HttpOnly`: `document.cookie` onu göstərmir (demo səhifədə yoxlaya bilərsiniz).
- `Path=/api/auth`: cookie yalnız auth endpoint-lərinə gedir, hər API sorğusuna yox.
- `SameSite=Strict`: başqa saytdan başlanan sorğuya əlavə olunmur. Bu, `/refresh`-i CSRF-dən qoruyur.
- `Secure`: yalnız HTTPS ilə. Brauzerlər `http://localhost`-u təhlükəsiz sayır, ona görə lokalda da işləyir.

Access token isə yalnız JS yaddaşındadır. Səhifə yenilənəndə o itir, və səhifə açılan kimi `/api/auth/refresh` çağırılır: cookie avtomatik getdiyi üçün sessiya bərpa olunur.

Mobil tətbiq cookie ilə işləmir: `"cookie"` göndərmir, refresh token-i body-də alır və Keychain / Keystore-da saxlayır. Server hər iki yolu dəstəkləyir: cavab sorğunun gəldiyi kanalla qayıdır (cookie ilə gələnə cookie, body ilə gələnə body).

#### Klientdə: avtomatik refresh

Qayda sadədir: **401 → refresh → sorğunu bir dəfə təkrarla; refresh də 401 verirsə → login ekranı.** Tələ isə paralel sorğulardadır: ekran açılanda 5 sorğu eyni anda 401 alır. Hər biri ayrıca refresh etsə, ikinci refresh artıq istifadə olunmuş token göndərir, reuse detection işə düşür və istifadəçi çıxarılır. Ona görə **refresh bir dəfə edilir, qalanları onun nəticəsini gözləyir** (single flight). Demo-da "Access token-i korla + 5 paralel sorğu" düyməsi bunu göstərir: 5 × 401, 1 refresh, 5 × 200.

**Brauzer (JavaScript)**, `index.html`-dən:

```js
let refreshing = null;
function refresh() {
  refreshing ??= fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' })   // cookie özü gedir
      .then(r => r.ok ? r.json() : Promise.reject())
      .then(json => { access = json.access_token; return true; }, () => false)
      .finally(() => refreshing = null);
  return refreshing;
}
async function api(url, options = {}) {
  const call = () => fetch(url, { ...options, headers: { ...options.headers, Authorization: `Bearer ${access}` } });
  let r = await call();
  if (r.status === 401 && await refresh()) r = await call();   // bir dəfə təkrar
  return r;
}
```

**Android (Kotlin, OkHttp):** OkHttp-in `Authenticator`-u məhz 401 üçündür:

```kotlin
class TokenAuthenticator(private val store: TokenStore, private val authApi: AuthApi) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.header("X-Retried") != null) return null            // təkrar da 401: login ekranı
        val newAccess = synchronized(this) {                                      // single flight
            val current = store.accessToken
            if (response.request.header("Authorization") != "Bearer $current") current   // başqa thread artıq yenilədi
            else authApi.refresh(RefreshRequest(store.refreshToken)).execute().body()
                ?.also { store.save(it.accessToken, it.refreshToken) }?.accessToken      // YENİ refresh token-i saxla
        } ?: return null.also { store.clear() }
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newAccess").header("X-Retried", "1").build()
    }
}

val client = OkHttpClient.Builder()
    .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
        .header("Authorization", "Bearer ${store.accessToken}").build()) }
    .authenticator(TokenAuthenticator(store, authApi))   // authApi ayrı OkHttpClient ilə (authenticator-suz)
    .build()
```

`TokenStore` refresh token-i `EncryptedSharedPreferences` / Android Keystore-da saxlayır.

**iOS (Swift):** `actor` single flight-ı təbii edir:

```swift
actor TokenManager {
    private var accessToken: String?
    private var refreshTask: Task<String, Error>?

    func validToken() async throws -> String { if let t = accessToken { return t }; return try await refresh() }

    func refresh() async throws -> String {
        if let task = refreshTask { return try await task.value }         // artıq gedir: gözlə
        let task = Task { () throws -> String in
            defer { refreshTask = nil }
            var request = URLRequest(url: URL(string: "https://api.example.az/api/auth/refresh")!)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(["refresh_token": try Keychain.refreshToken()])
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { try Keychain.clear(); throw AuthError.loggedOut }
            let tokens = try JSONDecoder().decode(TokenResponse.self, from: data)
            try Keychain.save(refreshToken: tokens.refresh_token)          // YENİ refresh token
            accessToken = tokens.access_token
            return tokens.access_token
        }
        refreshTask = task
        return try await task.value
    }
}

func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
    var req = request
    req.setValue("Bearer \(try await tokens.validToken())", forHTTPHeaderField: "Authorization")
    var (data, response) = try await URLSession.shared.data(for: req)
    if (response as? HTTPURLResponse)?.statusCode == 401 {
        req.setValue("Bearer \(try await tokens.refresh())", forHTTPHeaderField: "Authorization")
        (data, response) = try await URLSession.shared.data(for: req)                // bir dəfə təkrar
    }
    return (data, response)
}
```

Hər iki platformada vacib olan iki şey var: **yeni refresh token-i mütləq saxlayın** (köhnəsi artıq işləməyəcək), və refresh sorğusunu elə client ilə göndərin ki, o da 401 alanda yenidən refresh etməyə çalışmasın (sonsuz dövr).

#### Aktiv sessiyalar

`GET /api/me/sessions` istifadəçinin bütün login-lərini (cihazlarını) qaytarır: Google/Facebook-dakı "harada login olmusunuz" siyahısı kimi. `DELETE /api/me/sessions/{id}` bir cihazı çıxarır (məs. itmiş telefon). Admin `POST /api/admin/users/{u}/revoke` ilə istifadəçini hər yerdən çıxara bilər (parol dəyişdikdən sonra da belə edilməlidir).

### Logout və token-in geri alınması

Logout refresh token-i ləğv edir. **Access token isə bitənə qədər (maks. 15 dəq) etibarlı qalır**: bu, stateless token-in qiymətidir. Bu pəncərə qəbuledilməzdirsə, variantlar:

- daha qısa access token (5 dəq);
- `jti` qara siyahısı (Redis-də, TTL = token-in qalan ömrü): hər sorğuda bir lookup;
- opaque token + introspection (hər sorğuda auth server-ə sual, yəni stateful).

### Token-i harada saxlamaq?

| Klient | Tövsiyə |
|---|---|
| Android | `EncryptedSharedPreferences` / Keystore |
| iOS | Keychain |
| SPA (brauzer) | Access token yaddaşda (JS dəyişəni); refresh token `HttpOnly; Secure; SameSite=Strict` cookie-də (bu modulda `"cookie": true`). `localStorage` XSS ilə oxuna bilər. |
| Servis → servis | Client credentials ilə alınan token, yaddaşda |

### 401 və 403

- **401 Unauthorized:** "kim olduğunu bilmirəm". Token yoxdur, səhvdir və ya vaxtı bitib. Klient refresh edir və ya login ekranına qayıdır.
- **403 Forbidden:** "səni tanıyıram, amma icazən yoxdur". Refresh kömək etməz.

Hər ikisi standart `WWW-Authenticate: Bearer error="..."` header-i (RFC 6750) və mobil/SPA klientlər üçün JSON body qaytarır. Spring Security 7 əlavə olaraq `resource_metadata` (RFC 9728) göndərir və `/.well-known/oauth-protected-resource` endpoint-ini avtomatik açır.

### CSRF niyə söndürülüb?

CSRF hücumu brauzerin cookie-ni **avtomatik** göndərməsinə əsaslanır. API sorğularında token `Authorization` header-indədir və brauzer onu özü əlavə etmir, ona görə CSRF qorunması lazım deyil. Cookie yalnız `/api/auth/*` endpoint-lərinə gedir və `SameSite=Strict` başqa saytdan başlanan sorğuya onu əlavə etmir, ona görə `/refresh` də CSRF-dən qorunur. Köhnə brauzerlər və ya `SameSite=Lax` tələb olunan hallar üçün isə refresh endpoint-ində CSRF token və ya xüsusi header yoxlaması əlavə edin.

## Production-da: öz auth server-inizi yazmayın

Bu modul token verməni **öyrənmək** üçün özü edir. Real sistemdə token-ləri ayrıca **authorization server** verir:

- Keycloak, Auth0, Okta, Azure AD, Cognito;
- və ya Spring Authorization Server (`spring-boot-starter-oauth2-authorization-server`).

Onlar login səhifəsini, MFA-nı, sosial login-i, parol sıfırlamanı, açar rotasiyasını, mobil tətbiqlər üçün **Authorization Code + PKCE** axınını və audit-i hazır təqdim edir. API servisləriniz isə yalnız **resource server** olur, yəni bu modulun `SecurityConfig` hissəsi olur:

```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri: https://auth.example.az/realms/shop
```

## Testlər

```bash
./gradlew :security-jwt:test
```

**`SecurityJwtTest`**: real HTTP, real token-lər.

| Test | Nəyi yoxlayır |
|---|---|
| `withoutTokenTheApiAnswers401WithBearerChallenge` | 401 + `WWW-Authenticate: Bearer` |
| `wrongPasswordAndUnknownUserGetTheSameAnswer` | İstifadəçi adlarının mövcudluğu sızmır |
| `loginReturnsOAuthStyleTokensAndTheApiReadsTheClaims` | `access_token`, `expires_in: 900`, claim-lər → authority-lər |
| `usersSeeOnlyTheirOwnDataAdminsSeeEverything` | IDOR qorunması |
| `adminEndpointsRequireTheAdminRole` | URL qaydası |
| `changingThePayloadBreaksTheSignature` | Rolu `ADMIN` etmək → 401 |
| `expiredTokenIsRejected` | Vaxtı bitmiş token → 401 `expired` |
| `tokenSignedWithAnotherKeyIsRejected` | Başqa açarla imzalanmış "admin" token-i → 401 |
| `refreshRotatesAndReusingAnOldTokenRevokesTheWholeLogin` | Rotation + reuse detection |
| `logoutEndsTheRefreshToken` | Logout |
| `jwksPublishesOnlyThePublicKey` | JWKS-də private açar hissələri (`d`, `p`, `q`) yoxdur |

**`RefreshTokenTest`**: refresh token-in saxlanması və limitləri.

| Test | Nəyi yoxlayır |
|---|---|
| `onlyTheHashOfTheTokenIsStored` | Bazada token-in özü yox, SHA-256 hash-i var |
| `browserGetsTheRefreshTokenOnlyAsHttpOnlyCookie` | Body-də yoxdur; `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`; cookie ilə rotation; köhnə cookie → 401 və cookie silinir |
| `twoRefreshesWithTheSameTokenAtOnceSucceedOnlyOnce` | 8 paralel refresh → yalnız 1 uğurlu (atomar `UPDATE`) |
| `userSeesAndEndsTheirOwnSessions` | Cihazların siyahısı; biri çıxarılır, digəri işləyir |
| `expiredRefreshTokenIsRefused` | 7 günlük limit |
| `activeLoginStillEndsAtItsMaximumAge` | 30 günlük absolut limit |

**`MethodSecurityTest`**: `jwt()` ilə MockMvc; login və imza olmadan yalnız qaydalar yoxlanılır.

| Test | Nəyi yoxlayır |
|---|---|
| `creatingAnOrderNeedsTheWriteScope` | `SCOPE_orders:read` → 403, `SCOPE_orders:write` → 201 |
| `ownershipRuleUsesTheTokenSubject` | `@PreAuthorize` sahiblik qaydası |
| `rolesClaimBecomesRoleAuthorities` | `roles` claim-i → `ROLE_*` çevirməsi |

## Tələlər

- **`alg: none` və alqoritm qarışıqlığı.** Token-in header-inə etibar edib alqoritmi oradan seçməyin. `NimbusJwtDecoder.withPublicKey(...)` yalnız RS256 qəbul edir.
- **Uzun access token.** 24 saatlıq access token oğurlansa, 24 saat işləyir. Qısa access token üstəgəl refresh token istifadə edin.
- **Rolları yalnız token-dən oxumaq.** Rol dəyişəndə köhnə token 15 dəqiqə köhnə rolla işləyir. Refresh zamanı rollar bazadan yenidən oxunur (`AuthController.refresh`).
- **Token-i loglamaq.** `Authorization` header-ini loglara, trace-lərə və URL-lərə (`?token=`) yazmayın.
- **Saat fərqi.** Servislərin saatı fərqli ola bilər; decoder `exp`-i 30 saniyə tolerantlıqla yoxlayır (`jwt.clock-skew`).
- **Paralel refresh.** Klient bir anda iki refresh göndərsə, ikincisi reuse kimi görünür və istifadəçi çıxarılır. Klientdə single flight məcburidir (yuxarıdakı nümunələr).
- **Yeni refresh token-i saxlamamaq.** Rotation ilə köhnə token artıq işləmir. Tətbiq cavabdakı yeni token-i saxlamasa, növbəti refresh-də istifadəçi çıxarılır.
