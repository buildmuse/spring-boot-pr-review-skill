# `spring-boot-pr-review` Review of `SubscriptionReminderService`

## Code Review — `SubscriptionReminderService` + `SubscriptionEmailListener`

### Summary
A scheduled job that finds subscriptions nearing expiry and publishes an
`ApplicationEvent` so an email listener can send reminders asynchronously.
The listener is annotated `@TransactionalEventListener`, but the publisher
runs with no active transaction — so the listener never fires and no
reminder emails are sent. This is a silent functional failure with no error
in the logs.

---

### BLOCKER Issues

**[B-1] Spring Proxy & Transaction Correctness —
`@TransactionalEventListener` fires only after a publisher transaction commits**
File: `SubscriptionEmailListener.java` (`onSubscriptionExpiring`)
Problem: By default, `@TransactionalEventListener` is bound to the
`AFTER_COMMIT` phase of the publishing transaction. If no transaction is
active at `publishEvent(...)` time, Spring silently does not invoke the
listener — and by default (`fallbackExecution = false`) does not log a warning.
In this code, `sweepExpiringSubscriptions` is a `@Scheduled` method with no
`@Transactional` annotation, so events are published outside any transaction.
The result: no email is ever sent. There is no exception, no error log, no
metric — the bug is invisible until a customer complains they did not get
their reminder.

The author's stated rationale — "we do not want a long-running transaction
held open for the entire sweep across all tenants" — is correct and important
(holding a single transaction across thousands of tenants would exhaust the
HikariCP pool and block other request threads). The mistake was choosing
`@TransactionalEventListener` while also wanting no transaction.

Fix: choose one of:

1. **If async-via-events decoupling is the goal**, use `@EventListener` (not
   `@TransactionalEventListener`) and make the listener `@Async` so it runs
   off the scheduler thread:

   ```java
   @EventListener
   @Async
   public void onSubscriptionExpiring(SubscriptionExpiringEvent event) {
       emailSender.sendExpiryReminder(...);
   }
   ```

   (Requires `@EnableAsync` and a properly configured `TaskExecutor`. Note
   the `@Async` proxy rule from the skill checklist §1: the listener must
   be in a different bean from where it is called — already satisfied here.)

2. **If the email must only be sent after a DB change commits**, wrap each
   per-subscription unit of work in its own short transaction (one bean
   method per subscription, called in the loop, so the proxy is engaged per
   iteration). Then `@TransactionalEventListener` works as written.

   ```java
   for (Subscription s : expiring) {
       reminderTransaction.markReminderQueued(s); // @Transactional in its own bean
   }
   ```

   Inside `markReminderQueued`, publish the event. Now each event publishes
   inside an active transaction and the listener fires on commit.

3. **If you want the event to fire whether or not a transaction is active**,
   set `@TransactionalEventListener(fallbackExecution = true)`. This is a
   pragmatic escape hatch but obscures intent and is not recommended as the
   primary fix — use 1 or 2.

---

### MAJOR Issues

**[M-1] Performance & Resource Management — unbounded `findExpiringSoon`**
File: `SubscriptionReminderService.java:30`
Problem: `findExpiringSoon` returns a `List<Subscription>` with no
pagination. In a multi-tenant system, the count of "subscriptions expiring
in the next window" can be arbitrarily large during a renewal cliff (e.g.
many annual subscriptions sold during a launch month all expire together).
Loading them all into a single list holds them in heap for the duration of
the loop and risks OOM under load.
Fix: page through with `Pageable`, or use Spring Data's `Stream<Subscription>
streamExpiringSoon(...)` with `@Transactional(readOnly = true)` and a
`StatelessSession`-style cursor.

**[M-2] Observability — silent failure has no metric**
File: `SubscriptionReminderService.java` (entire sweep method)
Problem: There is no `MeterRegistry` counter for either the size of the
expiring set or the number of emails actually sent. Combined with B-1, the
operational signal is zero: the team will not detect that reminders have
stopped firing. §8.2 of the skill checklist requires a counter on any path
that can silently degrade.
Fix: increment `subscription.reminder.queued` for each event published and
`subscription.reminder.sent` inside the listener. Alert on a sustained drop.

---

### Self-Challenge Notes
Initial pass focused only on B-1. On second pass, I noticed the unbounded
list as a separate MAJOR issue (M-1) that compounds the silent failure: if
the listener were fixed without pagination, a renewal cliff would push the
service into OOM territory. The two findings also chain — see below.

### Attack/Failure Chains
**[C-1] Renewal cliff + silent listener failure**
Components: B-1 + M-1 + M-2
Path: A product launch month produces a large cohort of annual subscribers.
One year later, the hourly sweep loads tens of thousands of subscriptions
into memory (M-1). The events publish, the listener silently does not fire
(B-1), no metric is emitted (M-2). Customers do not receive reminders, do
not renew, and churn before the team realizes anything is wrong.
Impact: revenue loss, customer trust loss, no on-call signal.

---

### Verdict
BLOCKED — the listener never fires; reminders are never sent.
