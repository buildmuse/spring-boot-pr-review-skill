# `spring-boot-pr-review` Review of `OrderService`

## Code Review — `OrderService`

### Summary
A new order placement service that intends to persist the order in its own
transaction and then record an audit log entry. The transactional boundary
does not exist as written: `placeOrder` calls `persistOrder` on `this`, so
Spring's transaction proxy is bypassed entirely. The audit log is also written
outside any transaction.

---

### BLOCKER Issues

**[B-1] Spring Proxy & Transaction Correctness — `@Transactional` self-invocation**
File: `OrderService.java:23` (call to `persistOrder(order)`)
Problem: `placeOrder` calls `persistOrder` directly on `this`. Spring's
transaction support is implemented by wrapping the bean in a proxy at injection
time; the proxy intercepts external method calls and starts/commits the
transaction. When one method on the bean invokes another method on the same
bean using `this.method(...)`, the JVM dispatches directly to the local
bytecode — the proxy is not involved, and the `@Transactional` annotation on
`persistOrder` is silently ignored. The result: `orderRepository.save(order)`
runs with no managed transaction. Depending on the Spring Data configuration,
this either fails at runtime (`TransactionRequiredException` on flush), or
opens a short-lived transaction per repository call and leaves the wider
operation un-rolled-back on partial failure.

In addition, `protected` visibility on `persistOrder` is a code smell here —
CGLIB proxies can intercept `protected` methods but JDK dynamic proxies
(used when the bean implements an interface) cannot. Even if the
self-invocation problem were fixed, `protected` introduces accidental
proxy-mode coupling.

Fix: Move the transactional method to a separate Spring bean so that the
proxy boundary is crossed:

```java
@Service
public class OrderService {
    private final OrderPersister orderPersister; // separate bean
    private final AuditLogRepository auditLogRepository;
    // ... constructor ...

    public Order placeOrder(OrderRequest request) {
        Order order = new Order(request);
        orderPersister.persist(order);          // proxy is engaged
        auditLogRepository.record("order.placed", order.getId());
        return order;
    }
}

@Service
public class OrderPersister {
    private final OrderRepository orderRepository;
    public OrderPersister(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public void persist(Order order) {
        orderRepository.save(order);
    }
}
```

If splitting the class is undesirable, the alternative is to inject the
service into itself (`@Autowired private OrderService self;` or expose the
proxy via `AopContext.currentProxy()` after enabling
`@EnableAspectJAutoProxy(exposeProxy = true)`) — but the two-bean split is
the canonical fix and the one to recommend by default.

---

### MAJOR Issues

**[M-1] Error Handling & Resilience — audit log runs outside any transaction**
File: `OrderService.java:26`
Problem: Even after fixing B-1, the audit log insert sits outside the
order-persist transaction. If the audit insert fails, the order has already
committed, and the system silently loses the audit record for a privileged
action. Audit logging for privileged actions is a §4.10 requirement.
Fix: Either include the audit insert inside the same transactional executor
(simplest, atomic), or publish a domain event from inside the transaction
and consume it with a `@TransactionalEventListener` on `AFTER_COMMIT` that
writes the audit row with its own retry policy.

---

### Self-Challenge Notes
On second pass: verified the bug applies regardless of whether
`OrderRepository` extends `JpaRepository` (Spring Data opens its own
short-lived transaction per call) or a custom repository (no transaction at
all). In both cases, the developer's intent — "persistOrder runs in its own
transaction so the audit log can still be written if the audit insert fails" —
is not realized. Also confirmed that no `@EnableTransactionManagement` is
visible in the snippet; if it is missing from the application config, the
`@Transactional` is even more clearly a no-op.

---

### Verdict
BLOCKED — the transactional boundary the author depends on does not exist;
this will silently corrupt state under partial-failure scenarios in
production.
