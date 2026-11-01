/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vaquarkhan.springailaya.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springaicommunity.typesafe.JevBatchOptions;
import org.springaicommunity.typesafe.JevBatchResult;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.judge.JevConfidenceGate;
import io.github.vaquarkhan.springailaya.ConfidenceMetric;
import io.github.vaquarkhan.springailaya.LayaClient;
import io.github.vaquarkhan.springailaya.LayaConfidence;
import io.github.vaquarkhan.springailaya.LayaTypeSafeClient;
import io.github.vaquarkhan.springailaya.response.LayaBatchResponse;
import io.github.vaquarkhan.springailaya.response.LayaHealth;
import io.github.vaquarkhan.springailaya.response.LayaResponse;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.rag.JevDocumentFilter;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Measures what the proposal claims, on public data, against a live laya-serve, and against
 * Jev as well when {@code TYPESAFE_API_KEY} is set. Writes {@code results/RESULTS.md} and
 * {@code results/results.json}.
 *
 * <p>
 * System properties: {@code bench.sst2} (default 200), {@code bench.ag} (200),
 * {@code bench.banking.perLabel} (2), {@code bench.latency} (20), {@code bench.throughput}
 * (32), {@code bench.rag} (16), {@code bench.out} (results), {@code bench.laya.model}
 * (english), {@code bench.jev.model} (jev-latest), {@code bench.trials} (2), and
 * {@code bench.requireBoth}. Setting {@code bench.requireBoth=true} requires a Jev key and
 * fails the entire comparison if either backend does not answer every fixed input.
 */
public final class LayaBenchmark {

	private static final double[] THRESHOLDS = { 0.5, 0.6, 0.7, 0.75, 0.8, 0.9 };

	private static final JevConfidenceGate GATE = JevConfidenceGate.withDefaultFloor();

	private LayaBenchmark() {
	}

	/** One answered example. */
	record Prediction(boolean correct, double answerConfidence, double jevConfidence, double entropyConfidence,
			double nativeConfidence) {
	}

	public static void main(String[] args) throws Exception {
		Path out = Path.of(System.getProperty("bench.out", "laya-benchmarks/results"));
		Path cache = Path.of("target", "benchmark-data");
		Files.createDirectories(out);

		String layaModel = System.getProperty("bench.laya.model", "english");
		String jevModel = System.getProperty("bench.jev.model", "jev-latest");
		boolean requireBoth = Boolean.getBoolean("bench.requireBoth");
		LayaClient laya = LayaClient.builder()
			.defaultModel(layaModel)
			.timeout(Duration.ofMinutes(5)).retryPolicy(RetryPolicy.builder()
			.maxRetries(1)
			.totalTimeout(Duration.ofMinutes(12))
			.retryableStatuses(Set.of(408, 503))
			.build()).build();
		LayaHealth health = laya.health();
		String jevKey = System.getenv("TYPESAFE_API_KEY");
		TypeSafeClient jev = (jevKey == null || jevKey.isBlank()) ? null
				: TypeSafeClient.builder().apiKey(jevKey).defaultModel(jevModel).timeout(Duration.ofSeconds(60)).build();
		if (requireBoth && jev == null) {
			throw new IllegalStateException("bench.requireBoth=true needs TYPESAFE_API_KEY; no comparison was run");
		}

		Map<String, Object> report = new LinkedHashMap<>();
		report.put("date", OffsetDateTime.now().toString());
		report.put("comparison_mode", jev == null ? "Laya-only: no TYPESAFE_API_KEY" : "side-by-side parity");
		report.put("laya_model", layaModel);
		report.put("jev_model", jevModel);
		report.put("laya", Map.of("loaded", health.loaded(), "revisions", health.revisions(), "device",
				String.valueOf(health.device())));
		report.put("host", Map.of("os", System.getProperty("os.name"), "cores", Runtime.getRuntime().availableProcessors(),
				"java", System.getProperty("java.version")));
		report.put("jev", jev == null ? "not measured: TYPESAFE_API_KEY not set" : "measured");

		List<Datasets.Dataset> datasets = List.of(
				Datasets.sst2(cache, Integer.getInteger("bench.sst2", 200)),
				Datasets.agNews(cache, Integer.getInteger("bench.ag", 200)),
				Datasets.banking77(cache, Integer.getInteger("bench.banking.perLabel", 2)));

		// 1. Accuracy and calibration. -Dbench.reuseAccuracy=<results.json> keeps a previous run's
		// section, so the timing sections can be re-measured without the 40-minute accuracy pass.
		Map<String, Object> accuracy = new LinkedHashMap<>();
		String reuse = System.getProperty("bench.reuseAccuracy");
		if (reuse != null) {
			@SuppressWarnings("unchecked")
			Map<String, Object> previous = JsonMapper.builder().build().readValue(Files.readString(Path.of(reuse)),
					LinkedHashMap.class);
			@SuppressWarnings("unchecked")
			Map<String, Object> kept = (Map<String, Object>) previous.get("accuracy");
			accuracy.putAll(kept);
			report.put("accuracy_measured", previous.get("date"));
		}
		for (Datasets.Dataset dataset : reuse != null ? List.<Datasets.Dataset>of() : datasets) {
			Choice question = choice(dataset);
			int chunk = dataset.options().size() > 20 ? 8 : 32;
			log("accuracy %s: %d examples, %d options, Laya batches of %d", dataset.name(), dataset.examples().size(),
					dataset.options().size(), chunk);
			laya.systemOne(JsonContent.of(dataset.examples().get(0).text()), Map.of("label", question));
			long started = System.nanoTime();
			List<Prediction> predictions = runLaya(laya, dataset, question, chunk);
			double seconds = (System.nanoTime() - started) / 1e9;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("source", dataset.source());
			entry.put("examples", dataset.examples().size());
			entry.put("options", dataset.options().size());
			entry.put("parity_receipt", ParityReceipt.forDataset(dataset, question, layaModel, jevModel));
			entry.put("laya", summarize(predictions, seconds));
			if (jev != null) {
				started = System.nanoTime();
				List<Prediction> jevPredictions = runJev(jev, dataset, question);
				ParityReceipt.requireSameInputCount(dataset.name(), predictions, jevPredictions, dataset.examples().size());
				entry.put("jev", summarize(jevPredictions, (System.nanoTime() - started) / 1e9));
			}
			accuracy.put(dataset.name(), entry);
			log("  %s", entry.get("laya"));
		}
		report.put("accuracy", accuracy);

		// 2. Single-request latency.
		Datasets.Dataset sst2 = datasets.get(0);
		Choice sentiment = choice(sst2);
		int latencyN = Integer.getInteger("bench.latency", 20);
		List<Double> clientMs = new ArrayList<>();
		List<Double> serverMs = new ArrayList<>();
		for (int i = 0; i < latencyN; i++) {
			long t = System.nanoTime();
			LayaResponse r = laya.systemOne(JsonContent.of(sst2.examples().get(i).text()), Map.of("label", sentiment));
			clientMs.add((System.nanoTime() - t) / 1e6);
			if (r.inferenceTimeMs() != null) {
				serverMs.add(r.inferenceTimeMs());
			}
		}
		report.put("latency", Map.of("requests", latencyN, "questions_per_request", 1, "client_ms",
				percentiles(clientMs), "server_inference_ms", percentiles(serverMs)));
		log("latency %s", report.get("latency"));

		// 3. systemOneAll: the upstream fan-out against Laya vs the batch endpoint.
		int n = Integer.getInteger("bench.throughput", 32);
		List<SystemOneRequest> requests = sst2.examples()
			.subList(0, n)
			.stream()
			.map(e -> SystemOneRequest.builder().state(e.text()).model("auto").question("label", sentiment).build())
			.toList();
		TypeSafeClient plain = plainClientAgainstLaya();
		LayaTypeSafeClient adapter = new LayaTypeSafeClient(laya);
		int trials = Integer.getInteger("bench.trials", 2);
		List<Double> plainRuns = new ArrayList<>();
		List<Double> adapterRuns = new ArrayList<>();
		List<JevBatchResult<SystemOneResponse>> viaPlain = null;
		List<JevBatchResult<SystemOneResponse>> viaAdapter = null;
		// Interleaved, so drift in the host's speed hits both clients alike.
		for (int trial = 0; trial < trials; trial++) {
			long t0 = System.nanoTime();
			viaPlain = plain.systemOneAll(requests, JevBatchOptions.defaults());
			plainRuns.add(round((System.nanoTime() - t0) / 1e6));
			t0 = System.nanoTime();
			viaAdapter = adapter.systemOneAll(requests, JevBatchOptions.defaults());
			adapterRuns.add(round((System.nanoTime() - t0) / 1e6));
			log("systemOneAll trial %d: plain %.0f ms, adapter %.0f ms", trial + 1, plainRuns.get(trial),
					adapterRuns.get(trial));
		}
		double plainMs = plainRuns.stream().mapToDouble(Double::doubleValue).min().orElse(0);
		double adapterMs = adapterRuns.stream().mapToDouble(Double::doubleValue).min().orElse(0);
		long agree = 0;
		long plainOk = viaPlain.stream().filter(JevBatchResult::succeeded).count();
		long adapterOk = viaAdapter.stream().filter(JevBatchResult::succeeded).count();
		for (int i = 0; i < n; i++) {
			if (viaPlain.get(i).succeeded() && viaAdapter.get(i).succeeded() && viaPlain.get(i)
				.orThrow()
				.choiceValue("label")
				.equals(viaAdapter.get(i).orThrow().choiceValue("label"))) {
				agree++;
			}
		}
		Map<String, Object> throughput = new LinkedHashMap<>();
		throughput.put("requests", n);
		throughput.put("adapter_settings", "rowsPerBatchCall=" + laya.rowsPerBatchCall() + ", maxConcurrentBatchCalls="
				+ laya.maxConcurrentBatchCalls());
		throughput.put("plain_typesafe_client_fanout4_ms_runs", plainRuns);
		throughput.put("laya_adapter_ms_runs", adapterRuns);
		throughput.put("plain_succeeded", plainOk);
		throughput.put("adapter_succeeded", adapterOk);
		throughput.put("speedup_best_of_runs", round(plainMs / adapterMs));
		throughput.put("same_label", agree + "/" + n);
		report.put("systemOneAll", throughput);
		log("systemOneAll %s", throughput);

		// 4. The upstream RAG filter, end to end.
		Datasets.Dataset ag = datasets.get(1);
		int ragN = Integer.getInteger("bench.rag", 16);
		List<Document> passages = ag.examples().subList(0, ragN).stream().map(e -> new Document(e.text())).toList();
		Query query = new Query("What is the latest news about company earnings and stock markets?");
		List<Double> ragPlainRuns = new ArrayList<>();
		List<Double> ragAdapterRuns = new ArrayList<>();
		List<Document> keptPlain = List.of();
		List<Document> keptAdapter = List.of();
		for (int trial = 0; trial < trials; trial++) {
			long t0 = System.nanoTime();
			keptPlain = JevDocumentFilter.builder(plain).build().process(query, passages);
			ragPlainRuns.add(round((System.nanoTime() - t0) / 1e6));
			t0 = System.nanoTime();
			keptAdapter = JevDocumentFilter.builder(adapter).build().process(query, passages);
			ragAdapterRuns.add(round((System.nanoTime() - t0) / 1e6));
			log("rag trial %d: plain %.0f ms, adapter %.0f ms", trial + 1, ragPlainRuns.get(trial),
					ragAdapterRuns.get(trial));
		}
		double ragPlainMs = ragPlainRuns.stream().mapToDouble(Double::doubleValue).min().orElse(0);
		double ragAdapterMs = ragAdapterRuns.stream().mapToDouble(Double::doubleValue).min().orElse(0);
		Map<String, Object> rag = new LinkedHashMap<>();
		rag.put("passages", ragN);
		rag.put("questions_per_passage", 4);
		rag.put("plain_typesafe_client_ms_runs", ragPlainRuns);
		rag.put("laya_adapter_ms_runs", ragAdapterRuns);
		rag.put("speedup_best_of_runs", round(ragPlainMs / ragAdapterMs));
		rag.put("kept_plain", labels(keptPlain));
		rag.put("kept_adapter", labels(keptAdapter));
		report.put("ragFilter", rag);
		log("rag %s", rag);

		JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
		Files.writeString(out.resolve("results.json"), json.writeValueAsString(report));
		Files.writeString(out.resolve("RESULTS.md"), Report.markdown(report));
		log("wrote %s", out.toAbsolutePath());
	}

	private static Choice choice(Datasets.Dataset dataset) {
		Choice.Builder builder = Choice.builder().instructions(dataset.instructions());
		dataset.options().forEach(builder::option);
		return builder.build();
	}

	private static List<Prediction> runLaya(LayaClient laya, Datasets.Dataset dataset, Choice question, int chunk) {
		List<Prediction> predictions = new ArrayList<>();
		Map<String, Question> questions = Map.of("label", question);
		List<Datasets.Example> examples = dataset.examples();
		for (int from = 0; from < examples.size(); from += chunk) {
			List<Datasets.Example> part = examples.subList(from, Math.min(examples.size(), from + chunk));
			long t = System.nanoTime();
			LayaBatchResponse batch = laya.systemOneBatch(part.stream().map(e -> JsonContent.of(e.text())).toList(),
					questions);
			log("  %s %d/%d (%.1fs)", dataset.name(), from + part.size(), examples.size(), (System.nanoTime() - t) / 1e9);
			for (int i = 0; i < part.size(); i++) {
				LayaResponse r = batch.results().get(i);
				ChoiceAnswer answer = r.response().choice("label");
				double answerConfidence = r.answerConfidence("label").orElse(0.0d);
				double jevConfidence = r.toTypeSafe(ConfidenceMetric.JEV).choice("label").confidence();
				predictions.add(new Prediction(answer.value().equals(part.get(i).label()), answerConfidence,
						jevConfidence, answer.confidence(), answer.confidence()));
			}
		}
		return predictions;
	}

	private static List<Prediction> runJev(TypeSafeClient jev, Datasets.Dataset dataset, Choice question) {
		List<SystemOneRequest> requests = dataset.examples()
			.stream()
			.map(e -> SystemOneRequest.builder().state(e.text()).question("label", question).build())
			.toList();
		List<JevBatchResult<SystemOneResponse>> results = jev.systemOneAll(requests, JevBatchOptions.ofConcurrency(4));
		List<Prediction> predictions = new ArrayList<>();
		for (int i = 0; i < results.size(); i++) {
			if (!results.get(i).succeeded()) {
				continue;
			}
			ChoiceAnswer answer = results.get(i).orThrow().choice("label");
			double max = LayaConfidence.answer(answer.probabilities().values());
			predictions.add(new Prediction(answer.value().equals(dataset.examples().get(i).label()), max,
					answer.confidence(), LayaConfidence.entropy(answer.probabilities().values()), answer.confidence()));
		}
		return predictions;
	}

	private static Map<String, Object> summarize(List<Prediction> predictions, double seconds) {
		Map<String, Object> s = new LinkedHashMap<>();
		int n = predictions.size();
		long correct = predictions.stream().filter(Prediction::correct).count();
		s.put("answered", n);
		s.put("accuracy", round((double) correct / Math.max(1, n)));
		s.put("wall_seconds", round(seconds));
		s.put("ms_per_example", round(seconds * 1000 / Math.max(1, n)));
		s.put("ece_answer_confidence", round(ece(predictions, ConfidenceMetric.ANSWER)));
		s.put("ece_jev_formula", round(ece(predictions, ConfidenceMetric.JEV)));
		s.put("ece_entropy", round(ece(predictions, ConfidenceMetric.ENTROPY)));

		List<Map<String, Object>> selective = new ArrayList<>();
		for (double t : THRESHOLDS) {
			List<Prediction> kept = predictions.stream().filter(p -> p.answerConfidence() >= t).toList();
			long keptCorrect = kept.stream().filter(Prediction::correct).count();
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("min_answer_confidence", t);
			row.put("served_share", round((double) kept.size() / Math.max(1, n)));
			row.put("accuracy_when_served", kept.isEmpty() ? null : round((double) keptCorrect / kept.size()));
			selective.add(row);
		}
		s.put("selective", selective);

		// JevConfidenceGate (floor 0.6), fed the native confidence vs Jev's formula.
		long escalateNative = predictions.stream()
			.filter(p -> GATE.decide("x", p.nativeConfidence()) == JevConfidenceGate.Decision.ESCALATE)
			.count();
		long escalateJev = predictions.stream()
			.filter(p -> GATE.decide("x", p.jevConfidence()) == JevConfidenceGate.Decision.ESCALATE)
			.count();
		long flipped = predictions.stream()
			.filter(p -> GATE.decide("x", p.nativeConfidence()) != GATE.decide("x", p.jevConfidence()))
			.count();
		long flippedCorrect = predictions.stream()
			.filter(p -> GATE.decide("x", p.nativeConfidence()) != GATE.decide("x", p.jevConfidence()))
			.filter(Prediction::correct)
			.count();
		Map<String, Object> gate = new LinkedHashMap<>();
		gate.put("escalated_with_native_confidence", round((double) escalateNative / Math.max(1, n)));
		gate.put("escalated_with_jev_formula", round((double) escalateJev / Math.max(1, n)));
		gate.put("decisions_that_differ", round((double) flipped / Math.max(1, n)));
		gate.put("accuracy_of_differing", flipped == 0 ? null : round((double) flippedCorrect / flipped));
		s.put("gate_floor_0_6", gate);
		return s;
	}

	/** Expected calibration error, 10 equal-width bins. */
	private static double ece(List<Prediction> predictions, ConfidenceMetric metric) {
		int bins = 10;
		double[] sumConf = new double[bins];
		double[] sumCorrect = new double[bins];
		int[] count = new int[bins];
		for (Prediction p : predictions) {
			double c = switch (metric) {
				case ANSWER -> p.answerConfidence();
				case JEV -> p.jevConfidence();
				case ENTROPY -> p.entropyConfidence();
			};
			int b = Math.min(bins - 1, (int) (c * bins));
			sumConf[b] += c;
			sumCorrect[b] += p.correct() ? 1 : 0;
			count[b]++;
		}
		double ece = 0;
		for (int b = 0; b < bins; b++) {
			if (count[b] > 0) {
				ece += (count[b] / (double) predictions.size())
						* Math.abs(sumConf[b] / count[b] - sumCorrect[b] / count[b]);
			}
		}
		return ece;
	}

	private static Map<String, Object> percentiles(List<Double> values) {
		List<Double> sorted = values.stream().sorted().toList();
		Map<String, Object> m = new LinkedHashMap<>();
		if (sorted.isEmpty()) {
			return m;
		}
		m.put("p50", round(sorted.get(sorted.size() / 2)));
		m.put("p95", round(sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1))));
		m.put("min", round(sorted.get(0)));
		m.put("max", round(sorted.get(sorted.size() - 1)));
		return m;
	}

	private static TypeSafeClient plainClientAgainstLaya() {
		String base = System.getenv().getOrDefault("LAYA_BASE_URL", "http://localhost:8000");
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
		factory.setReadTimeout(Duration.ofMinutes(5));
		return TypeSafeClient.builder()
			.baseUrl(base)
			.apiKey("unused-by-laya")
			.defaultModel("auto")
			.timeout(Duration.ofMinutes(5))
			.retryPolicy(RetryPolicy.builder().maxRetries(1).totalTimeout(Duration.ofMinutes(12)).build())
			.restClientBuilder(RestClient.builder().requestFactory(factory))
			.build();
	}

	private static List<String> labels(List<Document> kept) {
		return kept.stream()
			.map(d -> d.getMetadata().getOrDefault(JevDocumentFilter.CLASSIFICATION_METADATA_KEY, "UNSCREENED") + ": "
					+ abbreviate(d.getText()))
			.collect(Collectors.toList());
	}

	private static String abbreviate(String text) {
		return text == null ? "" : text.length() <= 70 ? text : text.substring(0, 67) + "...";
	}

	static double round(double v) {
		return Math.round(v * 1000.0d) / 1000.0d;
	}

	private static void log(String format, Object... args) {
		System.out.printf("[bench] " + format + "%n", args);
		System.out.flush();
	}

}
