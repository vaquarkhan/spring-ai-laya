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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Public labelled datasets, fetched from the Hugging Face datasets-server and cached under
 * {@code target/benchmark-data}. Samples are drawn with a fixed seed so every run, and
 * every backend, sees the same examples.
 */
final class Datasets {

	record Example(String text, String label) {
	}

	/**
	 * @param name short name used in the report
	 * @param source the Hugging Face dataset id and split
	 * @param instructions the choice instructions
	 * @param options label to description, in label order
	 * @param examples the sampled examples
	 */
	record Dataset(String name, String source, String instructions, Map<String, String> options,
			List<Example> examples) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

	private Datasets() {
	}

	static Dataset sst2(Path cache, int n) throws IOException, InterruptedException {
		List<JsonNode> rows = rows(cache, "stanfordnlp/sst2", "validation", 872);
		Map<String, String> options = new LinkedHashMap<>();
		options.put("negative", "the reviewer dislikes the film or criticises it");
		options.put("positive", "the reviewer likes the film or praises it");
		List<String> labels = List.copyOf(options.keySet());
		List<Example> all = rows.stream()
			.map(r -> new Example(r.get("sentence").asString().trim(), labels.get(r.get("label").asInt())))
			.toList();
		return new Dataset("SST-2", "stanfordnlp/sst2 validation", "What is the sentiment of this movie review?",
				options, sample(all, n, 7));
	}

	static Dataset agNews(Path cache, int n) throws IOException, InterruptedException {
		List<JsonNode> rows = new ArrayList<>();
		// The test split is 7,600 rows; three spread-out pages give a mixed pool to sample.
		for (int offset : new int[] { 0, 2500, 5000 }) {
			rows.addAll(page(cache, "fancyzhx/ag_news", "test", offset, 100));
		}
		Map<String, String> options = new LinkedHashMap<>();
		options.put("World", "international news, politics, conflicts, governments");
		options.put("Sports", "sport, teams, athletes, matches and competitions");
		options.put("Business", "companies, markets, the economy, finance");
		options.put("Sci/Tech", "science, technology, computing, the internet, space");
		List<String> labels = List.copyOf(options.keySet());
		List<Example> all = rows.stream()
			.map(r -> new Example(r.get("text").asString().trim(), labels.get(r.get("label").asInt())))
			.toList();
		return new Dataset("AG News", "fancyzhx/ag_news test", "Which section of a news site does this article belong to?",
				options, sample(all, n, 11));
	}

	static Dataset banking77(Path cache, int perLabel) throws IOException, InterruptedException {
		List<JsonNode> rows = rows(cache, "PolyAI/banking77", "test", 3080);
		List<String> names = labelNames(cache, "PolyAI/banking77", "test");
		Map<String, String> options = new LinkedHashMap<>();
		names.forEach(name -> options.put(name, name.replace('_', ' ')));
		Map<String, List<Example>> byLabel = new LinkedHashMap<>();
		for (JsonNode r : rows) {
			String label = names.get(r.get("label").asInt());
			byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(new Example(r.get("text").asString().trim(), label));
		}
		List<Example> picked = new ArrayList<>();
		byLabel.values().forEach(examples -> picked.addAll(sample(examples, perLabel, 13)));
		Collections.shuffle(picked, new Random(13));
		return new Dataset("Banking77", "PolyAI/banking77 test, " + perLabel + " per intent",
				"Which banking intent does this customer message express?", options, picked);
	}

	private static List<Example> sample(List<Example> all, int n, long seed) {
		List<Example> copy = new ArrayList<>(all);
		Collections.shuffle(copy, new Random(seed));
		return List.copyOf(copy.subList(0, Math.min(n, copy.size())));
	}

	private static List<JsonNode> rows(Path cache, String dataset, String split, int total)
			throws IOException, InterruptedException {
		List<JsonNode> rows = new ArrayList<>();
		for (int offset = 0; offset < total; offset += 100) {
			rows.addAll(page(cache, dataset, split, offset, 100));
		}
		return rows;
	}

	private static List<String> labelNames(Path cache, String dataset, String split)
			throws IOException, InterruptedException {
		JsonNode root = JSON.readTree(fetch(cache, dataset, split, 0, 100));
		for (JsonNode feature : root.get("features")) {
			if ("label".equals(feature.get("name").asString())) {
				List<String> names = new ArrayList<>();
				feature.get("type").get("names").forEach(n -> names.add(n.asString()));
				return names;
			}
		}
		throw new IllegalStateException("No label feature in " + dataset);
	}

	private static List<JsonNode> page(Path cache, String dataset, String split, int offset, int length)
			throws IOException, InterruptedException {
		JsonNode root = JSON.readTree(fetch(cache, dataset, split, offset, length));
		List<JsonNode> rows = new ArrayList<>();
		root.get("rows").forEach(r -> rows.add(r.get("row")));
		return rows;
	}

	private static String fetch(Path cache, String dataset, String split, int offset, int length)
			throws IOException, InterruptedException {
		Path file = cache.resolve(dataset.replace('/', '_') + "_" + split + "_" + offset + "_" + length + ".json");
		if (Files.exists(file)) {
			return Files.readString(file);
		}
		Files.createDirectories(cache);
		URI uri = URI.create("https://datasets-server.huggingface.co/rows?dataset=" + dataset
				+ "&config=default&split=" + split + "&offset=" + offset + "&length=" + length);
		IOException last = null;
		for (int attempt = 0; attempt < 4; attempt++) {
			try {
				HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).build(),
						HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					Files.writeString(file, response.body());
					return response.body();
				}
				last = new IOException("HTTP " + response.statusCode() + " for " + uri);
			}
			catch (IOException ex) {
				last = ex;
			}
			Thread.sleep(2000L * (attempt + 1));
		}
		throw last;
	}

}
