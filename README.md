# spring-boot-pr-review-skill

A Claude Code skill that reviews Spring Boot PRs like a staff engineer.

It is a portable agent skill for Java / Spring Boot / PostgreSQL / JPA code review
and PR review. It encodes the production checklist for the bugs generic AI reviewers
miss: silently bypassed Spring proxies, `@TransactionalEventListener` events that
never fire, N+1 queries, cross-tenant cache leaks, missing optimistic locking, and
external I/O held inside a transaction long enough to exhaust the connection pool.

---

## Before / After: the kind of bug this skill catches

Consider this snippet (full file: [`examples/01-transactional-self-invocation/BuggyService.java`](examples/01-transactional-self-invocation/BuggyService.java)):

```java
@Service
public class OrderService {

    public Order placeOrder(OrderRequest request) {
        Order order = new Order(request);
        persistOrder(order);                          // <- self-invocation
        auditLogRepository.record("order.placed", order.getId());
        return order;
    }

    @Transactional
    protected void persistOrder(Order order) {
        orderRepository.save(order);
    }
}
```

### What a generic AI review says

> Consider extracting the order construction into a factory. Use
> `@RequiredArgsConstructor` to reduce boilerplate. Add input validation. Consider
> inlining `persistOrder` since it's short. Overall the design is clean.

### What `spring-boot-pr-review` says

> **[B-1] BLOCKER — `@Transactional` self-invocation.** `placeOrder` calls
> `persistOrder` on `this`. Spring's transaction proxy intercepts external method
> calls; a `this.method(...)` call bypasses the proxy and the `@Transactional`
> annotation is silently ignored. `orderRepository.save(order)` runs with no
> managed transaction, so partial failure of the broader operation leaves
> inconsistent state. Fix: extract `persistOrder` to a separate Spring bean so the
> proxy boundary is crossed. `protected` visibility also couples the design to
> CGLIB proxies — a JDK dynamic proxy cannot intercept `protected` methods.

The generic review approves the code. This skill blocks it, names the JVM mechanism,
and gives the canonical two-bean fix.

See [`examples/02-transactional-event-listener/`](examples/02-transactional-event-listener/)
and [`examples/03-async-cacheable-proxy-boundary/`](examples/03-async-cacheable-proxy-boundary/)
for two more bugs no generic review catches — both are real production patterns.

---

## Greatest hits — a few of the non-obvious rules

If you are a senior Java engineer, you should nod at each of these.

1. **`@TransactionalEventListener` is silent when there is no active publisher
   transaction.** Publish events outside a transaction and the listener never
   fires. No log, no error. Default phase is `AFTER_COMMIT`; without a commit,
   nothing happens.

2. **`@Async`, `@Cacheable`, `@Retryable` all follow the same proxy rule as
   `@Transactional`.** Self-invocation defeats every one of them. The skill flags
   each independently because each one fails differently in production.

3. **PostgreSQL does not auto-index foreign keys.** A common silent perf cliff —
   especially under `ON DELETE CASCADE`. The skill flags FK columns lacking an
   explicit index.

4. **External I/O inside a `@Transactional` boundary holds a DB connection for
   the duration of the call.** Under load, HikariCP exhausts before thread count
   does. The fix is the two-bean orchestrator/executor pattern: do the I/O first,
   open the transaction with the payload already in hand.

5. **`@Cacheable` without `tenantId` in the key is a cross-tenant data leak.**
   Two tenants requesting the same logical key see each other's data. Even when
   the cached value is "the same for everyone" today, encode tenancy in the key
   so the assumption cannot regress later.

There are about sixty more rules across nine categories in `SKILL.md`.

---

## Install

### 1. As a Claude Code plugin (one command)

```
/plugin marketplace add buildmuse/spring-boot-pr-review-skill
/plugin install spring-boot-pr-review@spring-boot-pr-review-skill
```

The first command registers this repository as a marketplace; the second installs
the skill from that marketplace. Updates flow through `/plugin marketplace update`.

### 2. As a raw skill (copy into your skills directory)

For Claude Code (user scope):

```bash
mkdir -p ~/.claude/skills/spring-boot-pr-review
curl -L https://raw.githubusercontent.com/buildmuse/spring-boot-pr-review-skill/main/SKILL.md \
  -o ~/.claude/skills/spring-boot-pr-review/SKILL.md
```

For Claude Code (project scope), use `.claude/skills/spring-boot-pr-review/` inside
your repo instead.

### 3. Other agents (Cursor, Copilot, Codex, and others)

This skill follows the open agent-skills standard
([agentskills.io](https://agentskills.io)), so any agent that implements the
standard can load it. Place `SKILL.md` in the skills directory that agent expects
— see your agent's documentation for the exact path. The frontmatter and content
are portable; no agent-specific extensions are used beyond the optional
`metadata` block.

---

## How to use it

Once installed, the skill activates automatically on requests like:

- "Review this PR: `https://github.com/org/repo/pull/123`"
- "Take a look at this service — does this look right?"
- "Sanity-check this migration before I merge it"
- "Audit this controller for tenant isolation"

Or invoke it explicitly:

- Claude Code: `/spring-boot-pr-review:spring-boot-pr-review`
- Other agents: per their skill-invocation syntax.

The skill fetches GitHub PRs on its own using `gh` when available, otherwise the
GitHub REST API (with a PAT for private repos). It reads every modified service,
repository, and entity in full before writing a single finding.

---

## Built from production

This checklist comes from reviewing code on a real multi-tenant SaaS, written by
a backend Java developer with 13 years of experience. Every rule has a
corresponding production incident or near-incident behind it. No employer
specifics are encoded in the skill — only the patterns.

---

## Contributing

Two issue templates are provided:

- **Bug report** — the skill produced a wrong or low-quality review on a specific
  input. Include the smallest reproducer you can.
- **Rule suggestion** — a new rule to add (or sharpen). Include the mechanism, a
  minimal failing example, and a canonical fix. See
  [`.github/ISSUE_TEMPLATE/rule_suggestion.md`](.github/ISSUE_TEMPLATE/rule_suggestion.md).

Pull requests against `SKILL.md` should preserve the review philosophy: lead with
mechanism, calibrate severity, no "fine for now" language.

---

## License

[MIT](LICENSE).
