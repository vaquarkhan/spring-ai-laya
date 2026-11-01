# What Embedding Studio adds to the positioning

Embedding Studio and Laya solve related decision problems, but they are different products and protocols. This note separates their value clearly so that the Laya proposal stays accurate.

## The relevant positive evidence from Studio

The current [Embedding Studio documentation](https://studio.embedding.in/docs) describes a decision lifecycle that is useful context for the Laya proposal:

1. **Fit from the team's own labelled decisions.** A project accepts a question sheet and a labelled CSV. Studio splits uploaded rows into fit, calibration and held-out measurement partitions.
2. **Use calibration for a real policy.** It temperature-calibrates the decision scores, then calculates a precision band. The band determines which cases route automatically and which route to human review.
3. **Refuse misleading automation.** A fitted head that does not beat the base engine in cross-validation is not served. A precision target the data cannot establish sends all cases to review rather than claiming the target was met.
4. **Keep audit and drift evidence.** A project decision reports its audit block and readout, and the platform can measure answer-mix drift on unlabelled traffic.
5. **Refresh calibration independently.** New labelled traffic can refresh calibration and routing without retraining the fitted head when the decision itself has not changed.

These are strong patterns for a Spring AI decision integration: calibrate on real labels, record a receipt, route uncertain items to review, and monitor when the input distribution changes.

## What Studio does not establish

Studio is backed by `vegaml`. Its hosted API uses `POST /v1/decide`, `X-API-Key`, `boolean` questions, and a different response model. Laya and Jev share `POST /v1/systemone` and the TypeSafe choice, score and noul contract.

As a result, Studio's documentation does **not** establish that Laya outperforms Jev or that either is a replacement for Studio. It is not evidence for a Laya-versus-Jev accuracy claim.

## How it strengthens the Laya proposal

The proposal can adopt the lifecycle, while retaining Laya's different benefit:

| Decision lifecycle practice | Laya backend contribution |
|---|---|
| Validate with held-out labelled data | The included parity harness runs Laya and Jev over the same fixed examples, question payload and evaluator. |
| Calibrate before automation | `LayaResponse.answerConfidence()` exposes the confidence Laya temperature-scales. The application measures a floor on its own data. |
| Route uncertainty deliberately | `LayaEscalatingClient` routes low-confidence, abstained, broad-choice and unavailable-service cases to a configured `TypeSafeClient`, such as Jev. |
| Monitor model operation | `LayaClient.health()` reports liveness, loaded checkpoints and actual device. `LayaUsage` reports state truncation. |
| Keep an audit trail | The parity receipt records SHA-256 hashes of ordered data and question payloads. Applications can attach `EscalationListener` to record policy routing events without logging sensitive request bodies. |

The shared message is not that a deployment should choose one product for every workload. It is that **decision automation earns trust through measured labels, calibrated thresholds, explicit routing and receipts**. Laya gives Spring AI users a local, open-weight implementation path for that discipline.

Content from the Studio documentation was rephrased for compliance with licensing restrictions.
