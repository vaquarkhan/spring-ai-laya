# Benchmark outputs

All runs used `laya-serve` 0.4.19 (english checkpoint, revision `7b928d82`, CPU, `LAYA_THREADS=8`) on a Windows 11 laptop with 14 logical cores, between 2026-10-31 and 2026-11-01. **All results are Laya-only.** Jev was not measured, because no `TYPESAFE_API_KEY` was available. The benchmark uses no generative LLM: dataset labels are the ground truth.

| File | What it is |
|---|---|
| `RESULTS.md`, `results.json` | **Run 3, the current reference.** A fresh, complete run with the final code: accuracy and calibration on SST-2 (200), AG News (200) and Banking77 (154), latency, `systemOneAll` and the RAG filter with two interleaved trials. Includes SHA-256 input receipts for every dataset. A paired Jev run must report the same hashes. |
| `run2-RESULTS.md`, `run2-results.json` | Run 2. Timing sections re-measured with the final code; accuracy reused from run 1. |
| `run1-RESULTS.md`, `run1-results.json` | Run 1. The first full run, with the first batching version (whole groups in one call). |
| `raw-probes/probe.json` | Raw responses captured from every endpoint, including the 413 and 422 refusals. The unit-test fixtures come from these. |
| `raw-probes/probe2-4.json` and `.py` | Direct timings of the RAG filter's four questions in different batch and concurrency shapes, used to choose the defaults. The scripts expect the server at `127.0.0.1:8002`. |

Runs 1 and 3 measured accuracy independently and agree exactly on every accuracy, calibration and confidence-floor number. Laya is deterministic for the same input.

The host's speed drifted by up to 5x: single-request latency p50 was 984 ms in run 1 and about 5,300 ms in runs 2 and 3. Compare only interleaved trials within one run, and treat every CPU timing here as host-specific.

To produce the side-by-side comparison, set `TYPESAFE_API_KEY` and run with `-Dbench.requireBoth=true`. See [BENCHMARK-PROTOCOL.md](../BENCHMARK-PROTOCOL.md).
