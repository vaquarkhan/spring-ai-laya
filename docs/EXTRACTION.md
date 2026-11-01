# Contributing the Laya backend to spring-ai-typesafe

This project is independent and owns its own coordinates (`io.github.vaquarkhan`) and packages (`io.github.vaquarkhan.springailaya`). If the spring-ai-typesafe maintainers ever ask for Laya support upstream, the code moves with a package rename and no logic changes. Nothing in spring-ai-typesafe itself has to change.

## Package and module map

| This project | Possible upstream target |
|---|---|
| `laya-java-client`, package `io.github.vaquarkhan.springailaya` (and `.api`, `.response`, `.exception`) | `typesafe-java-sdk`, package `org.springaicommunity.typesafe.laya` |
| `spring-ai-laya-starter`, package `io.github.vaquarkhan.springailaya.autoconfigure` | `spring-ai-starter-typesafe`, package `org.springaicommunity.typesafe.autoconfigure.laya` |
| `spring.ai.laya.*` properties | `spring.ai.typesafe.laya.*` properties |
| `spring-ai-laya-spring-ai-tests` (test-only) | `typesafe-spring-ai` tests |
| `laya-benchmarks` | not proposed; stays here as evidence |

## Package layout

```
io.github.vaquarkhan.springailaya                LayaClient, LayaTypeSafeClient, LayaEscalatingClient,
                                                  LayaOptions, LayaLimits, LayaCheckpoints, LayaConstants,
                                                  ConfidenceMetric, LayaConfidence, EscalationReason,
                                                  EscalationListener, LayaBatching (package-private)
io.github.vaquarkhan.springailaya.api            LayaRequestBodies, LayaResponseParser, LayaResponseErrorHandler
io.github.vaquarkhan.springailaya.response       LayaResponse, LayaAnswerDetails, LayaAbstention, LayaUsage,
                                                  LayaRouting, LayaBatchResponse, LayaHealth
io.github.vaquarkhan.springailaya.exception      LayaServerBusyException (503), LayaPayloadTooLargeException (413),
                                                  LayaLimitExceededException (client-side)
io.github.vaquarkhan.springailaya.autoconfigure  LayaProperties, LayaAutoConfiguration
```

## spring-ai-typesafe API this code depends on

All of it is public in 0.4.0, and the code doesn't reflect into or subclass anything else:

- `TypeSafeClient` (subclassed). Overridden: `systemOne(SystemOneRequest)`, `systemOneAll(List, JevBatchOptions)`, `listModels()`. The other `systemOne` overloads delegate to the first one, so they route through Laya too.
- `TypeSafeApi(String, String, RestClient)` gives the superclass a transport it never uses.
- The records `SystemOneRequest`, `Question`/`Noul`/`Choice`/`Score`, `JsonContent`, `SystemOneResponse`, `ChoiceAnswer`, `ScoreAnswer`, `NoulAnswer`, `Usage`, `ModelMetadata`, `JevBatchOptions`, `JevBatchResult`, `RetryPolicy`.
- `TypeSafeResponseErrorHandler` (subclassed for 413 and 503), the `TypeSafeApiException` family, `TypeSafeRateLimitException`, `TypeSafeApiResponseValidationException`.
- Starter: `TypeSafeProperties` and `TypeSafeAutoConfiguration` (as the `before` anchor).

## Changes that would make an upstream version tidier

These are not needed to move the code:

1. **A transport-free constructor for subclasses.** `LayaTypeSafeClient` and `LayaEscalatingClient` pass the superclass a `TypeSafeApi` they never use. A protected constructor without a transport, or a `SystemOneClient` interface, would remove that.
2. **The extra answer fields upstream.** Nullable `answerConfidence` and `extras` on `ChoiceAnswer` and `ScoreAnswer` would let `JevConfidenceGate` pick a metric itself, instead of the adapter rewriting `confidence`.
3. **`docs/client/Laya.md` upstream.** It would point at `LayaTypeSafeClient` and the starter properties instead of the base-URL workaround.
