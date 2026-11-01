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

package io.github.vaquarkhan.springailaya.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.exception.TypeSafeApiResponseValidationException;
import io.github.vaquarkhan.springailaya.response.LayaAbstention;
import io.github.vaquarkhan.springailaya.response.LayaAnswerDetails;
import io.github.vaquarkhan.springailaya.response.LayaBatchResponse;
import io.github.vaquarkhan.springailaya.response.LayaHealth;
import io.github.vaquarkhan.springailaya.response.LayaResponse;
import io.github.vaquarkhan.springailaya.response.LayaRouting;
import io.github.vaquarkhan.springailaya.response.LayaUsage;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;

/**
 * Reads Laya response bodies. The answers go through the upstream {@link SystemOneResponse}
 * binding unchanged, so they decode exactly as they do from Jev; the fields the Jev records
 * ignore are then read from the same tree. Nothing Laya adds is required, which keeps a
 * server in strict Jev mode ({@code LAYA_JEV_STRICT=1}) readable.
 *
 * @author Vaquar Khan
 */
public final class LayaResponseParser {

	/** The server's own inference time, in milliseconds. */
	public static final String INFERENCE_TIME_HEADER = "X-Inference-Time-Ms";

	private final JsonMapper jsonMapper;

	public LayaResponseParser(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	/**
	 * @param body the response body
	 * @param headers the response headers
	 * @param endpoint the method and path, for error messages
	 * @return the decision
	 */
	public LayaResponse decision(@Nullable String body, HttpHeaders headers, String endpoint) {
		JsonNode root = tree(body, headers, endpoint);
		return decision(root, headers, endpoint, inferenceTime(headers));
	}

	/**
	 * @param body the batch response body
	 * @param headers the response headers
	 * @param endpoint the method and path, for error messages
	 * @param expected how many states were sent
	 * @return the results, aligned with the states
	 */
	public LayaBatchResponse batch(@Nullable String body, HttpHeaders headers, String endpoint, int expected) {
		JsonNode root = tree(body, headers, endpoint);
		JsonNode results = root.get("results");
		if (results == null || !results.isArray()) {
			throw invalid("The batch response carried no results array", headers, endpoint, body, "results");
		}
		if (results.size() != expected) {
			throw invalid("The batch response carried %d results for %d states".formatted(results.size(), expected),
					headers, endpoint, body, "results");
		}
		Double inference = inferenceTime(headers);
		List<LayaResponse> decisions = new ArrayList<>(results.size());
		for (JsonNode result : results) {
			decisions.add(decision(result, headers, endpoint, inference));
		}
		LayaUsage total = usage(root.get("total_usage"));
		return new LayaBatchResponse(decisions, total, 1);
	}

	/**
	 * @param body the routing snapshot body
	 * @param headers the response headers
	 * @param endpoint the method and path, for error messages
	 * @return the routing decision; {@code detection} carries the {@code diagnostic}
	 */
	public LayaRouting route(@Nullable String body, HttpHeaders headers, String endpoint) {
		JsonNode root = tree(body, headers, endpoint);
		return new LayaRouting(text(root, "model"), text(root, "repo"), text(root, "reason"), map(root.get("diagnostic")),
				text(root, "workflow"));
	}

	/**
	 * @param body the health body
	 * @param headers the response headers
	 * @param endpoint the method and path, for error messages
	 * @return the health report
	 */
	@SuppressWarnings("unchecked")
	public LayaHealth health(@Nullable String body, HttpHeaders headers, String endpoint) {
		JsonNode root = tree(body, headers, endpoint);
		List<String> loaded = new ArrayList<>();
		JsonNode loadedNode = root.get("loaded");
		if (loadedNode != null && loadedNode.isArray()) {
			loadedNode.forEach(node -> loaded.add(node.asString()));
		}
		Map<String, String> revisions = new LinkedHashMap<>();
		map(root.get("revisions")).forEach((k, v) -> revisions.put(k, String.valueOf(v)));
		Map<String, String> devices = new LinkedHashMap<>();
		map(root.get("checkpoint_devices")).forEach((k, v) -> devices.put(k, String.valueOf(v)));
		JsonNode preference = root.get("device_is_preference");
		return new LayaHealth(String.valueOf(text(root, "status")), loaded, revisions, text(root, "device"),
				(preference != null && preference.isBoolean()) ? preference.booleanValue() : null, devices,
				map(root.get("cpu_fallbacks")));
	}

	private LayaResponse decision(JsonNode node, HttpHeaders headers, String endpoint, @Nullable Double inference) {
		SystemOneResponse response;
		try {
			response = this.jsonMapper.treeToValue(node, SystemOneResponse.class);
		}
		catch (RuntimeException ex) {
			throw invalid("The decision could not be read: " + ex.getMessage(), headers, endpoint, node.toString(),
					null);
		}
		if (response == null || response.answers().isEmpty()) {
			throw invalid("The decision carried no answers", headers, endpoint, node.toString(), "answers");
		}
		Map<String, LayaAnswerDetails> details = new LinkedHashMap<>();
		JsonNode answers = node.get("answers");
		if (answers != null && answers.isObject()) {
			for (Map.Entry<String, JsonNode> entry : answers.properties()) {
				details.put(entry.getKey(), details(entry.getValue()));
			}
		}
		return new LayaResponse(response, details, usage(node.get("usage")), routing(node.get("routing")), inference);
	}

	private LayaAnswerDetails details(JsonNode answer) {
		JsonNode action = answer.get("action");
		JsonNode tournament = answer.get("x_tournament");
		JsonNode low = answer.get("low_confidence");
		return new LayaAnswerDetails(text(answer, "type"), number(answer, "confidence"),
				number(answer, "answer_confidence"), number(answer, "x_jev_confidence"),
				action == null ? null : number(action, "act_probability"),
				LayaAbstention.fromWire(text(answer, "abstention")), number(answer, "abstention_threshold"),
				(low != null && low.isBoolean()) ? low.booleanValue() : null,
				(tournament == null || tournament.isNull()) ? null : map(tournament));
	}

	private LayaUsage usage(@Nullable JsonNode node) {
		if (node == null || !node.isObject()) {
			return LayaUsage.EMPTY;
		}
		List<String> truncated = new ArrayList<>();
		JsonNode questions = node.get("truncated_questions");
		if (questions != null && questions.isArray()) {
			questions.forEach(q -> truncated.add(q.asString()));
		}
		JsonNode flag = node.get("truncated");
		JsonNode options = node.get("options");
		return new LayaUsage(integer(node, "input_tokens"), integer(node, "output_tokens"), integer(node, "state_tokens"),
				integer(node, "state_tokens_dropped"), (flag != null && flag.isBoolean()) ? flag.booleanValue() : null,
				truncated, (options == null || options.isNull()) ? null : map(options));
	}

	private @Nullable LayaRouting routing(@Nullable JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}
		JsonNode detection = node.get("detection");
		return new LayaRouting(text(node, "model"), text(node, "repo"), text(node, "reason"),
				(detection == null || detection.isNull()) ? null : map(detection), text(node, "workflow"));
	}

	private JsonNode tree(@Nullable String body, HttpHeaders headers, String endpoint) {
		if (body == null || body.isBlank()) {
			throw invalid("The response had no body", headers, endpoint, body, null);
		}
		try {
			return this.jsonMapper.readTree(body);
		}
		catch (RuntimeException ex) {
			throw invalid("The response was not JSON", headers, endpoint, body, null);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(@Nullable JsonNode node) {
		if (node == null || !node.isObject()) {
			return Map.of();
		}
		Map<String, Object> converted = this.jsonMapper.treeToValue(node, LinkedHashMap.class);
		return converted == null ? Map.of() : converted;
	}

	private static @Nullable Double inferenceTime(HttpHeaders headers) {
		String value = headers.getFirst(INFERENCE_TIME_HEADER);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Double.parseDouble(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static @Nullable String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value == null || value.isNull()) ? null : value.asString();
	}

	private static @Nullable Double number(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value != null && value.isNumber()) ? value.doubleValue() : null;
	}

	private static @Nullable Integer integer(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value != null && value.isNumber()) ? value.intValue() : null;
	}

	private static TypeSafeApiResponseValidationException invalid(String message, HttpHeaders headers, String endpoint,
			@Nullable String body, @Nullable String field) {
		return new TypeSafeApiResponseValidationException("Laya %s: %s".formatted(endpoint, message), 200, body, headers,
				endpoint, field);
	}

}
