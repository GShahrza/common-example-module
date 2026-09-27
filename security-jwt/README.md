# security-jwt: Spring Security və JWT

Mobil tətbiq, SPA və digər servislər üçün API-ni **stateless** qorumaq: server sessiya saxlamır, hər sorğu imzalı token gətirir.

| Mövzu | Harada |
|---|---|
| Login → access token (JWT, RS256) + refresh token | `AuthController`, `TokenService` |
| JWT-ni yoxlamaq (resource server): imza, `exp`, `iss` | `JwtKeyConfig`, `SecurityConfig` |
| Rollar (`ROLE_ADMIN`) və scope-lar (`SCOPE_orders:write`) | `SecurityConfig.authorities()` |
| URL qaydaları və `@PreAuthorize` (sahiblik yoxlaması, IDOR) | `SecurityConfig`, `OrderController` |
| Refresh token rotation və oğurluğun aşkarlanması (reuse detection) | `RefreshTokenStore` |
| Logout, "hər yerdən çıx" | `/api/auth/logout`, `/api/admin/users/{u}/revoke` |
| Public açarların paylaşılması (JWKS) | `/.well-known/jwks.json` |
| 401/403 cavabları: `WWW-Authenticate` + JSON | `SecurityConfig` |
| CORS (başqa origin-dəki SPA) | `SecurityConfig.corsConfigurationSource` |
| Test: real token-lərlə və `jwt()` ilə | `SecurityJwtTest`, `MethodSecurityTest` |

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

# 3. Access token bitəndə: refresh
curl -s -X POST localhost:8090/api/auth/refresh -H 'Content-Type: application/json' \
     -d '{"refresh_token":"Qm9..."}'
```

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

Demo-da açar hər start-da yenidən yaradılır, yəni restart-dan sonra köhnə token-lər etibarsız olur. Production-da açarı `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` (PEM) ilə secret store-dan verin ki, bütün instance-lar eyni açardan istifadə etsin.

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

### Refresh token: rotation və reuse detection

Access token qısa (15 dəq) olmalıdır, çünki onu geri almaq olmur. İstifadəçinin hər 15 dəqiqədən bir login olmaması üçün isə uzunömürlü (7 gün) **refresh token** verilir.

- Refresh token JWT deyil, təsadüfi sətirdir və serverdə saxlanılır (ləğv oluna bilməsi üçün). Bazada onun **SHA-256 hash-i** saxlanılır: cədvəl sızsa belə, token-lər istifadə oluna bilməz.
- **Rotation:** hər refresh **yeni** refresh token qaytarır, köhnəsi "istifadə olunub" kimi işarələnir.
- **Reuse detection:** istifadə olunmuş token yenidən gəlirsə, deməli, onu kimsə oğurlayıb. O login-in bütün token-ləri (family) ləğv olunur: həm oğru, həm də əsl istifadəçi çıxır, və əsl istifadəçi yenidən login olur. Demo-da "Köhnə refresh token-i yenidən işlət" düyməsi bunu göstərir.

```
login ──► RT1 ──refresh──► RT2 ──refresh──► RT3
               (RT1: used)      (RT2: used)
RT1 yenidən gəldi ──► family ləğv: RT3 də işləmir
```

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
| SPA (brauzer) | Access token yaddaşda (JS dəyişəni); refresh token `HttpOnly; Secure; SameSite=Strict` cookie-də. `localStorage` XSS ilə oxuna bilər. |
| Servis → servis | Client credentials ilə alınan token, yaddaşda |

### 401 və 403

- **401 Unauthorized:** "kim olduğunu bilmirəm". Token yoxdur, səhvdir və ya vaxtı bitib. Klient refresh edir və ya login ekranına qayıdır.
- **403 Forbidden:** "səni tanıyıram, amma icazən yoxdur". Refresh kömək etməz.

Hər ikisi standart `WWW-Authenticate: Bearer error="..."` header-i (RFC 6750) və mobil/SPA klientlər üçün JSON body qaytarır. Spring Security 7 əlavə olaraq `resource_metadata` (RFC 9728) göndərir və `/.well-known/oauth-protected-resource` endpoint-ini avtomatik açır.

### CSRF niyə söndürülüb?

CSRF hücumu brauzerin cookie-ni **avtomatik** göndərməsinə əsaslanır. Burada token `Authorization` header-indədir və brauzer onu özü əlavə etmir, ona görə CSRF qorunması lazım deyil. Refresh token-i cookie-də saxlasanız, həmin endpoint üçün CSRF qorunmasını (və ya `SameSite=Strict`) geri qaytarın.

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
- **Saat fərqi.** Servislərin saatı fərqli ola bilər; decoder 60 saniyə tolerantlıqla `exp`-i yoxlayır.
