---
name: Rule suggestion
about: Propose a new review rule (or sharpen an existing one)
title: "[rule] "
labels: rule-suggestion
---

## The rule

One sentence. The shorter, the better. Example: "Flag any `@Async` method
called from within the same class — the annotation is bypassed by the
Spring proxy."

## Category

Which checklist section in `SKILL.md` would this live under?

- [ ] §1 Spring Proxy & Transaction Correctness
- [ ] §2 JPA / Hibernate
- [ ] §3 PostgreSQL & Query Safety
- [ ] §4 Security
- [ ] §5 Architecture & Design
- [ ] §6 Error Handling & Resilience
- [ ] §7 Performance & Resource Management
- [ ] §8 Observability & Operability
- [ ] §9 Test Coverage Gaps
- [ ] New category — describe:

## Severity

Suggested severity (`BLOCKER` / `MAJOR` / `MINOR`) and why.

## Minimal failing example

A small Java/Spring Boot snippet that demonstrates the bug the rule should
catch. Include enough context that a reviewer can confirm the bug is real.

```java
// minimal reproducer here
```

## Mechanism

Explain *why* the code is wrong at the JVM / Spring / JDBC / PostgreSQL
level. The skill's reviews lead with mechanism, not rule citation.

## How to detect

What pattern should the skill look for? (Class structure, annotation
placement, repository signature, etc.) The more concrete the pattern, the
easier it is to encode.

## How to fix

A canonical fix or pattern. Code snippet welcome.

## References

Spring docs, Hibernate JIRA tickets, PostgreSQL docs, CVE numbers,
blog posts, anything that backs up the rule.
