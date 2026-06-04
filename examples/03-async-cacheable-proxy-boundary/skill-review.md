# `spring-boot-pr-review` Review of `PricingService`

## Code Review — `PricingService`

### Summary
A pricing service intended to run an expensive third-party quote
asynchronously and cache the per-SKU base price. As written, neither
`@Async` nor `@Cacheable` has any effect. Both annotations are bypassed by
self-invocation, so every request runs synchronously on the caller's
thread and refetches the base price from the upstream every time. The
service also lacks tenant scoping on the cache key, which is a
multi-tenant isolation defect once the annotations are actually wired up.

---

### BLOCKER Issues

**[B-1] Spring Proxy & Transaction Correctness — `@Async` self-invocation**
File: `PricingService.java:23` (`quoteAsync` calls `computeQuote(...)`)
Problem: `quoteAsync` invokes `computeQuote` on the same instance via an
implicit `this` reference. Spring's `@Async` support is implemented through
a proxy: the proxy intercepts external calls to `@Async` methods and
dispatches them onto a `TaskExecutor`. A `this.computeQuote(...)` call
bypasses the proxy and runs `computeQuote` synchronously on the calling
thread. The returned `CompletableFuture` is `completedFuture(...)` —
already completed — so the caller never even sees an unfilled future. From
the outside it looks like async-with-immediate-result. In reality, the
expensive upstream call is blocking the request thread.

Fix: extract `computeQuote` to a different bean so the proxy boundary is
crossed:

```java
@Service
public class PricingService {
    private final QuoteComputer quoteComputer;
    public PricingService(QuoteComputer quoteComputer) {
        this.quoteComputer = quoteComputer;
    }

    public CompletableFuture<BigDecimal> quoteAsync(String tenantId, String sku) {
        return quoteComputer.computeQuote(tenantId, sku); // proxy engaged
    }
}

@Service
public class QuoteComputer {
    private final PricingClient pricingClient;
    private final BasePriceCache basePriceCache;
    // ...

    @Async
    public CompletableFuture<BigDecimal> computeQuote(String tenantId, String sku) {
        BigDecimal base = basePriceCache.basePrice(sku);
        BigDecimal adjusted = pricingClient.applyTenantAdjustment(tenantId, base);
        return CompletableFuture.completedFuture(adjusted);
    }
}
```

Also: returning `CompletableFuture.completedFuture(...)` inside an `@Async`
method that has been correctly proxied is still wrong-shaped — the executor
runs the method, builds the future, and returns it. Prefer returning the
raw value and letting `@Async` wrap it. Confirm `@EnableAsync` is present on
the application config; without it, every `@Async` is a silent no-op
regardless of proxy boundaries.

**[B-2] Spring Proxy & Transaction Correctness — `@Cacheable` self-invocation**
File: `PricingService.java:28` (`computeQuote` calls `basePrice(...)`)
Problem: Same root cause as B-1: `computeQuote` invokes `basePrice` on the
same instance. The cache proxy is bypassed; `pricingClient.fetchBasePrice`
runs on every request. Beyond the latency hit, this also amplifies the
outbound request volume to the pricing upstream — a free DoS amplifier under
load and likely a billing surprise if the upstream meters by call.

Fix: Move `basePrice` to its own bean (e.g. `BasePriceCache` above) so the
caching proxy is engaged. After the fix, also see B-3 below.

**[B-3] Authorization & Multi-Tenant Isolation — cache key omits tenant**
File: `PricingService.java:33` (`@Cacheable("base-price")`)
Problem: The cache key defaults to all method parameters, here just `sku`.
The comment ("it's the same for everyone") is the load-bearing assumption,
and it is wrong in any multi-tenant pricing system: base prices are
typically governed by tenant-level contracts, currency, or region. If two
tenants on different price tiers query the same SKU, the second tenant
receives the first tenant's cached price — a cross-tenant data leak with
direct revenue impact. This is BLOCKER under the §4.2 multi-tenant cache
leak rule.

Fix: include `tenantId` in the cache key explicitly:

```java
@Cacheable(value = "base-price", key = "#tenantId + ':' + #sku")
public BigDecimal basePrice(String tenantId, String sku) { ... }
```

Even if today the price is genuinely tenant-independent, encode tenancy in
the key so the assumption cannot silently regress when a per-tenant
adjustment is added later.

---

### MAJOR Issues

**[M-1] Performance & Resource Management — no TTL on `@Cacheable`**
File: `PricingService.java:33`
Problem: `@Cacheable("base-price")` inherits the cache manager's default
TTL. If the cache manager is `ConcurrentMapCacheManager` (Spring's default
when no other cache provider is configured), there is no TTL at all and the
cache grows unbounded for the life of the JVM. Stale prices then persist
indefinitely.
Fix: configure an explicit TTL on the cache manager (Caffeine: `expireAfterWrite`)
and bound the max size.

**[M-2] Error Handling & Resilience — no timeout / circuit breaker on `pricingClient`**
File: `PricingService.java:28-30`
Problem: Synchronous calls to an external pricing service with no visible
timeout or circuit breaker. A hung upstream will pin the task executor
threads (after B-1 is fixed) and ripple back into request latency.
Fix: configure `WebClient` `responseTimeout` and `ChannelOption.CONNECT_TIMEOUT_MILLIS`,
and wrap in a Resilience4j circuit breaker scoped per tenant or per SKU
prefix.

---

### Self-Challenge Notes
First pass had only B-1 and B-2. On second pass I caught the multi-tenant
cache-key leak (B-3) — this is the kind of finding that is easy to miss
because the code "looks fine once the proxies work." The chain matters: as
soon as B-1 and B-2 are fixed, B-3 becomes a live cross-tenant data leak.
Surfacing all three together prevents the team from "fixing" the first two
and shipping the leak.

---

### Verdict
BLOCKED — two no-op annotations and a cross-tenant data leak waiting in the
cache key. Cannot ship in current form.
