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

package io.github.vaquarkhan.springailaya;

import java.util.List;

import org.springaicommunity.typesafe.JevBatchOptions;
import org.springaicommunity.typesafe.JevBatchResult;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.api.TypeSafeApi;
import io.github.vaquarkhan.springailaya.response.LayaHealth;
import io.github.vaquarkhan.springailaya.response.LayaResponse;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.ModelMetadata;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/**
 * A {@link TypeSafeClient} backed by Laya. Pass it wherever spring-ai-typesafe expects a
 * client ({@code JevJudge}, {@code JevGuardrailAdvisor}, {@code JevDocumentFilter},
 * {@code JevDocumentReranker}, {@code JevToolIndex}, {@code JevConfidenceGate} callers...)
 * and those components run on Laya unchanged, with three corrections over pointing a plain
 * {@code TypeSafeClient} at the same server:
 *
 * <ol>
 * <li><strong>Confidence keeps its meaning.</strong> The {@code confidence} of every choice
 * and score answer is replaced by the chosen {@link ConfidenceMetric}, by default Jev's own
 * formula ({@code x_jev_confidence}). A plain client hands Jev-tuned thresholds Laya's
 * normalised entropy, a different number.</li>
 * <li><strong>Batches use the batch endpoint.</strong> {@link #systemOneAll} groups requests
 * with the same questions into {@code POST /v1/systemone/batch} calls of at most
 * {@link LayaClient#rowsPerBatchCall()} rows, sent concurrently. A plain client sends one
 * HTTP request per item, so every item pays its own forward-pass overhead.</li>
 * <li><strong>The protocol differences are handled.</strong> No API key is required, the
 * {@code model} is omitted unless a checkpoint is named, limits are checked before sending,
 * 503 and 413 map to typed exceptions, and {@link #listModels()} reports the resident
 * checkpoints instead of failing with a 404.</li>
 * </ol>
 *
 * Everything Laya reports beyond the Jev records stays reachable through {@link #laya()}.
 *
 * @author Vaquar Khan
 */
public class LayaTypeSafeClient extends TypeSafeClient {

	private final LayaClient laya;

	private final ConfidenceMetric confidenceMetric;

	/**
	 * Wraps a Laya client, exposing Jev-formula confidence.
	 * @param laya the Laya client
	 */
	public LayaTypeSafeClient(LayaClient laya) {
		this(laya, ConfidenceMetric.JEV);
	}

	/**
	 * Wraps a Laya client.
	 * @param laya the Laya client
	 * @param confidenceMetric which confidence the answer records carry
	 */
	public LayaTypeSafeClient(LayaClient laya, ConfidenceMetric confidenceMetric) {
		super(unusedTransport(), laya.defaultModel(), laya.retryPolicy(), laya.timeout());
		Assert.notNull(confidenceMetric, "confidenceMetric must not be null");
		this.laya = laya;
		this.confidenceMetric = confidenceMetric;
	}

	/**
	 * The superclass requires a transport; every method that would use it is overridden, so
	 * it is never called.
	 */
	private static TypeSafeApi unusedTransport() {
		return new TypeSafeApi(TypeSafeApi.DEFAULT_SYSTEM_ONE_PATH, TypeSafeApi.DEFAULT_MODELS_PATH,
				RestClient.builder().build());
	}

	@Override
	public SystemOneResponse systemOne(SystemOneRequest request) {
		Assert.notNull(request, "request must not be null");
		return this.laya.systemOne(request).toTypeSafe(this.confidenceMetric);
	}

	@Override
	public List<JevBatchResult<SystemOneResponse>> systemOneAll(List<SystemOneRequest> requests,
			JevBatchOptions options) {
		Assert.notNull(requests, "requests must not be null");
		Assert.noNullElements(requests.toArray(), "requests must not contain a null request");
		Assert.notNull(options, "options must not be null");
		if (requests.isEmpty()) {
			return List.of();
		}
		return LayaBatching.run(this.laya, requests, options)
			.stream()
			.map(result -> new JevBatchResult<>(result.index(),
					result.value() == null ? null : result.value().toTypeSafe(this.confidenceMetric),
					result.failure()))
			.toList();
	}

	/**
	 * Laya has no {@code GET /v1/models}. The resident checkpoints from {@code /health} are
	 * the closest equivalent, with their revision as the description.
	 * @return the resident checkpoints; empty after an idle unload
	 */
	@Override
	public List<ModelMetadata> listModels() {
		LayaHealth health = this.laya.health();
		return health.loaded()
			.stream()
			.map(name -> new ModelMetadata(name, "Laya checkpoint, revision " + health.revisions().getOrDefault(name,
					"unknown"), null))
			.toList();
	}

	/**
	 * @return the Laya client, for the endpoints and fields the Jev contract has no place for
	 */
	public LayaClient laya() {
		return this.laya;
	}

	/**
	 * @return the confidence metric placed in answer records
	 */
	public ConfidenceMetric confidenceMetric() {
		return this.confidenceMetric;
	}

	/**
	 * Answers a request and keeps the Laya fields.
	 * @param request the request
	 * @return the full Laya decision
	 */
	public LayaResponse systemOneDetailed(SystemOneRequest request) {
		return this.laya.systemOne(request);
	}

}
