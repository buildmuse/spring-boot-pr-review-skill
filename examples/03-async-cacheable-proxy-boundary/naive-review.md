# Generic AI Review of `PricingService`

A typical generic-AI code review would say something like:

> The structure looks clean. A few small suggestions:
>
> - Consider using `@Value("${pricing.cache.ttl}")` to make the cache TTL
>   configurable.
> - You may want to add a circuit breaker around `pricingClient` calls.
> - `BigDecimal` is the right choice for monetary values.
> - The async + cache pattern is a nice combination for an expensive
>   third-party call.
> - Consider adding `@Transactional(readOnly = true)` if any DB reads happen
>   in `pricingClient`.
>
> Overall the separation of concerns is good and the async pattern looks
> appropriate.

This review compliments the very thing that is broken: the `@Async` and
`@Cacheable` annotations are both no-ops because they are invoked through
`this`. Neither asynchrony nor caching is happening in production, and the
"nice combination" silently degrades to a synchronous, un-cached, latency-
amplifying request path.
