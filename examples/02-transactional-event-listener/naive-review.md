# Generic AI Review of `SubscriptionReminderService`

A typical generic-AI code review would say something like:

> The implementation looks reasonable. A few thoughts:
>
> - Consider paginating `findExpiringSoon` so the sweep doesn't load all
>   subscriptions into memory at once.
> - The cron expression `0 0 * * * *` runs once per hour — that's fine, just
>   confirm it matches the intended SLA for reminders.
> - You could log the size of `expiring` for observability.
> - `SubscriptionEmailListener` is a sensible separation of concerns.
> - Consider adding retry logic in `EmailSender` for transient SMTP failures.
>
> Overall the event-driven design is clean and the asynchronous decoupling
> via `ApplicationEventPublisher` is a good pattern here.

This review misses the most important fact about this code: **the email is
never sent.** The author chose `@TransactionalEventListener` for asynchronous
decoupling but did not realize that this listener only fires after a publisher
transaction commits — and there is no publisher transaction here.
