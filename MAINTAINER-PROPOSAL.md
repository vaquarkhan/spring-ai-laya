# Proposal: first-class Laya support in spring-ai-typesafe

*This text is meant to be posted as a GitHub issue on spring-ai-community/spring-ai-typesafe. Nothing has been posted. The implementation is in this repository.*

---

## Summary

spring-ai-typesafe already documents running against Laya by changing the base URL (`docs/client/Laya.md`). I'd like to propose making Laya a supported backend, added in new `org.springaicommunity.typesafe.laya` packages, with no change to existing classes. I've built and tested it against the released 0.4.0 and a live `laya-serve` 0.4.19, and I'd value your view on the approach before opening a PR.

## The problem

A plain `TypeSafeClient` pointed at Laya works on the wire, but three things go wrong.

1. **`confidence` means a different number.** Laya puts normalised entropy in the field. Jev's value is `(p_max - 1/n)/(1 - 1/n)`. `JevConfidenceGate` (floor 0.6) and every threshold tuned on Jev therefore gate a different quantity. Measured on 400 SST-2 and AG News examples, 15 to 20% of gate decisions change, and 93 to 97% of the answers that get needlessly escalated are correct. Laya does send Jev's formula as `x_jev_confidence`, but `ChoiceAnswer` drops it.
2. **`systemOneAll` doesn't use Laya's batch endpoint.** `JevDocumentFilter`, `JevDocumentReranker` and `JevConsistency` ask one question set about many states. That is exactly what `POST /v1/systemone/batch` serves. On 32 one-question requests it was 1.9x to 5.2x faster across three CPU runs, with identical labels. For long four-question passages, the CPU timings here were within noise.
3. **Laya's own signals are lost**: `answer_confidence` (the calibrated number), the abstention gate, truncation, routing, `/health`, and typed 413/503 errors. In addition, the key is required although Laya doesn't need one, and `listModels()` gets a 404.

## What I propose

Additions only. Every class sits in a new package and uses only the public 0.4.0 API.

- **`typesafe-java-sdk`, package `org.springaicommunity.typesafe.laya`:**
  - `LayaClient`: every Laya endpoint and field; options `lang`, `lang_guess`, `task`, `max_len`, `head_max_len`, `min_confidence`; client-side limit checks; the upstream `RetryPolicy` and retry loop.
  - `LayaTypeSafeClient extends TypeSafeClient`: a drop-in that sets `confidence` from a `ConfidenceMetric` (default `JEV`) and batches `systemOneAll`.
  - `LayaEscalatingClient extends TypeSafeClient`: Laya first, then Jev on low `answer_confidence`, on abstention, on choices with more than 20 options, or when Laya is down. Batches on Laya, escalates only the slots that need it, and reports what it did through `stats()` and a listener.
- **`spring-ai-starter-typesafe`, package `org.springaicommunity.typesafe.autoconfigure.laya`:**
  - `spring.ai.typesafe.laya.base-url` makes the `TypeSafeClient` bean Laya.
  - `spring.ai.typesafe.laya.escalation.enabled=true` builds the escalating client over the Jev client from `spring.ai.typesafe.*`.
  - The configuration runs before `TypeSafeAutoConfiguration`, and a user-defined bean still wins.
- **`typesafe-spring-ai`:** tests only. The existing components run on the adapter unchanged.
- **Docs:** update `docs/client/Laya.md`.

## Evidence

All measured on public data, zero-shot, Laya 0.4.19 english checkpoint on CPU. Details and caveats are in [ANALYSIS.md](ANALYSIS.md), and raw output in `typesafe-laya-benchmarks/results/`.

| | SST-2 (2 labels) | AG News (4) | Banking77 (77) |
|---|---|---|---|
| Laya accuracy (n) | 0.910 (200) | 0.935 (200) | 0.370 (154) |
| ECE `answer_confidence` / native `confidence` | 0.050 / 0.375 | 0.067 / 0.221 | 0.527 / 0.549 |
| Gate decisions changed by the confidence fix | 20.5% | 15.0% | 5.8% |
| Served by Laya at a 0.75 floor / accuracy | 80% / 0.956 | 87% / 0.971 | 83% / 0.402 |

The SST-2 and Banking77 accuracies match laya-ai.com's published numbers (0.920 and 0.363). Banking77 shows why the escalation client routes wide choices by option count rather than by confidence.

Tests:
- Unit tests: MockRestServiceServer driven by responses captured from the live server.
- Live integration tests, gated on `LAYA_BASE_URL` the same way the existing ITs are gated on `TYPESAFE_API_KEY`.

## What I have not been able to verify

- **Jev side by side.** I don't have a Jev key. The harness now enforces the [same-data, same-question protocol](../typesafe-laya-benchmarks/BENCHMARK-PROTOCOL.md), records SHA-256 receipts, and fails on partial results when `-Dbench.requireBoth=true`. Would one of you be willing to run it?
- **GPU timings.** My CPU host drifted up to 5x in speed during the day, so I make no speed claims beyond the interleaved CPU comparisons in the analysis. The batch defaults (16 rows per call, one call in flight) are CPU-tuned and configurable.

## Questions for the maintainers

1. Is this something you'd take into the existing modules, or would you rather keep it as a separate community module?
2. Is subclassing `TypeSafeClient` acceptable? The alternative is a small upstream change: a protected no-transport constructor, or a `SystemOneClient` interface. Either would remove the unused transport the subclasses pass up today.
3. Is `JEV` the right default `ConfidenceMetric`? It keeps existing thresholds meaningful. `ANSWER` is better calibrated (section 5.2 of the analysis) but changes what thresholds mean.
4. Is the escalation client in scope, or should it be an example?

## Proposed PR plan

Once we agree on the approach:

1. SDK: `laya` packages and tests.
2. Starter: properties, auto-configuration and tests.
3. Component tests in `typesafe-spring-ai`, plus the `Laya.md` update.
