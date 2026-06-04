# Generic AI Review of `OrderService`

A typical generic-AI code review of the snippet would say something like:

> The code looks reasonable. A few suggestions:
>
> - Consider extracting the order construction into a dedicated factory.
> - You could use constructor injection with `@RequiredArgsConstructor` from Lombok
>   to reduce boilerplate.
> - Add input validation on `OrderRequest`.
> - The `persistOrder` method is `protected` and short — consider inlining it.
> - You may want to add logging around the audit-log call.
>
> Overall the design is clean and the separation between persistence and auditing
> is a sensible pattern.

Notice what is missing: the entire reason `persistOrder` was written this way —
to run inside its own transaction — does not work. The reviewer treated
`@Transactional` as if it always applies, and proposed cosmetic improvements
on top of a silently broken design.
