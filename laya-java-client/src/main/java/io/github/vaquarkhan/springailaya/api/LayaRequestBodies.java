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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.JsonContent;
import io.github.vaquarkhan.springailaya.LayaCheckpoints;
import io.github.vaquarkhan.springailaya.LayaOptions;
import org.springaicommunity.typesafe.question.Question;

/**
 * Assembles the JSON bodies of the Laya endpoints as ordered maps. The order is the one the
 * server documents ({@code state} first, then {@code model}, {@code questions} and the
 * controls), and an absent control is omitted rather than sent as {@code null}: the server
 * refuses some controls even when their value is {@code null}.
 *
 * @author Vaquar Khan
 */
public final class LayaRequestBodies {

	private LayaRequestBodies() {
	}

	/**
	 * @param state the state
	 * @param model the checkpoint, or {@code null} / {@link LayaCheckpoints#AUTO} to omit it
	 * @param questions the questions
	 * @param options the controls
	 * @return the {@code POST /v1/systemone} body
	 */
	public static Map<String, Object> systemOne(JsonContent state, @Nullable String model,
			Map<String, Question> questions, LayaOptions options) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("state", state);
		putModel(body, model);
		body.put("questions", questions);
		putOptions(body, options, true);
		return body;
	}

	/**
	 * @param states the states, one result each
	 * @param model the checkpoint, or {@code null} / {@link LayaCheckpoints#AUTO} to omit it
	 * @param questions the questions shared by every state
	 * @param options the controls
	 * @return the {@code POST /v1/systemone/batch} body
	 */
	public static Map<String, Object> batch(List<JsonContent> states, @Nullable String model,
			Map<String, Question> questions, LayaOptions options) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("states", states);
		putModel(body, model);
		body.put("questions", questions);
		putOptions(body, options, true);
		return body;
	}

	/**
	 * The routing snapshot refuses the prediction-only controls ({@code max_len},
	 * {@code head_max_len}, {@code min_confidence}) with a 422, so they are never sent here.
	 * @param state the state
	 * @param model the checkpoint, or {@code null} / {@link LayaCheckpoints#AUTO} to omit it
	 * @param questions the questions; may be empty
	 * @param options the routing controls
	 * @param explain whether to ask for the routing diagnostic
	 * @return the {@code POST /v1/route} body
	 */
	public static Map<String, Object> route(JsonContent state, @Nullable String model, Map<String, Question> questions,
			LayaOptions options, boolean explain) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("state", state);
		putModel(body, model);
		body.put("questions", questions);
		putOptions(body, options, false);
		body.put("explain", explain);
		return body;
	}

	private static void putModel(Map<String, Object> body, @Nullable String model) {
		if (!LayaCheckpoints.isAuto(model)) {
			body.put("model", model.trim());
		}
	}

	private static void putOptions(Map<String, Object> body, LayaOptions options, boolean prediction) {
		putIfSet(body, "task", options.task());
		putIfSet(body, "lang", options.lang());
		putIfSet(body, "lang_guess", options.langGuess());
		if (prediction) {
			putIfSet(body, "max_len", options.maxLen());
			putIfSet(body, "head_max_len", options.headMaxLen());
			putIfSet(body, "min_confidence", options.minConfidenceWireValue());
		}
	}

	private static void putIfSet(Map<String, Object> body, String key, @Nullable Object value) {
		if (value instanceof String text && text.isBlank()) {
			return;
		}
		if (value != null) {
			body.put(key, value);
		}
	}

}
