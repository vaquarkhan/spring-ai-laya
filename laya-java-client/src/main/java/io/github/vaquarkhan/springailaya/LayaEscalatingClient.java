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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.JevBatchOptions;
import org.springaicommunity.typesafe.JevBatchResult;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.api.TypeSafeApi;
import org.springaicommunity.typesafe.exception.TypeSafeApiConnectionException;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import io.github.vaquarkhan.springailaya.exception.LayaLimitExceededException;
import io.github.vaquarkhan.springailaya.response.LayaResponse;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.ModelMetadata;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/**
 * Laya first, Jev (or any other {@link TypeSafeClient}) when Laya is not good enough for a
 * particular request. A {@link TypeSafeClient}, so every spring-ai-typesafe component accepts
 * it unchanged.
 *
 * <p>
 * A request goes to the fallback when:
 * <ul>
 * <li>a choice has more than {@code maxLayaChoiceOptions} options (Laya's documented weak
 * spot), without asking Laya first;</li>
 * <li>Laya fails with a transport error, a timeout, a 5xx, a 408 or a 413, or the request
 * breaks a Laya limit the fallback may not have;</li>
 * <li>Laya's abstention gate flags an answer (when the request set {@code min_confidence});
 * or</li>
 * <li>the lowest calibrated confidence ({@code answer_confidence}) among the answers is
 * below {@code minAnswerConfidence}.</li>
 * </ul>
 * Everything else is served by Laya. Where an answer came from is visible in
 * {@link SystemOneResponse#model()} (Laya reports its decision head, Jev its model id), in
 * the {@link EscalationListener} and in {@link #stats()}.
 *
 * <p>
 * In {@link #systemOneAll} Laya answers the whole batch through its batch endpoint first;
 * only the requests that need it are then sent to the fallback, through the fallback's own
 * {@code systemOneAll}.
 *
 * @author Vaquar Khan
 */
public class LayaEscalatingClient extends TypeSafeClient {

	private static final Logger logger = LoggerFactory.getLogger(LayaEscalatingClient.class);

	/** Default floor on {@code answer_confidence}; tune it on your own labelled data. */
	public static final double DEFAULT_MIN_ANSWER_CONFIDENCE = 0.75d;

	/** Default option count above which a choice skips Laya. */
	public static final int DEFAULT_MAX_LAYA_CHOICE_OPTIONS = 20;

	private final LayaClient laya;

	private final TypeSafeClient fallback;

	private final ConfidenceMetric confidenceMetric;

	private final double minAnswerConfidence;

	private final boolean escalateOnAbstention;

	private final boolean escalateOnFailure;

	private final int maxLayaChoiceOptions;

	private final EscalationListener listener;

	private final Map<EscalationReason, AtomicLong> counters = new EnumMap<>(EscalationReason.class);

	private LayaEscalatingClient(Builder builder) {
		super(new TypeSafeApi(TypeSafeApi.DEFAULT_SYSTEM_ONE_PATH, TypeSafeApi.DEFAULT_MODELS_PATH,
				RestClient.builder().build()), builder.laya.defaultModel(), builder.laya.retryPolicy(),
				builder.laya.timeout());
		this.laya = builder.laya;
		this.fallback = builder.fallback;
		this.confidenceMetric = builder.confidenceMetric;
		this.minAnswerConfidence = builder.minAnswerConfidence;
		this.escalateOnAbstention = builder.escalateOnAbstention;
		this.escalateOnFailure = builder.escalateOnFailure;
		this.maxLayaChoiceOptions = builder.maxLayaChoiceOptions;
		this.listener = builder.listener;
		for (EscalationReason reason : EscalationReason.values()) {
			this.counters.put(reason, new AtomicLong());
		}
	}

	@Override
	public SystemOneResponse systemOne(SystemOneRequest request) {
		Assert.notNull(request, "request must not be null");
		if (tooManyOptions(request)) {
			return escalate(request, EscalationReason.TOO_MANY_OPTIONS, null, null);
		}
		LayaResponse answer;
		try {
			answer = this.laya.systemOne(request);
		}
		catch (TypeSafeException ex) {
			if (this.escalateOnFailure && escalatable(ex)) {
				return escalate(request, EscalationReason.LAYA_FAILED, null, ex);
			}
			throw ex;
		}
		EscalationReason reason = judge(answer);
		if (reason.escalated()) {
			return escalate(request, reason, answer, null);
		}
		record(request, EscalationReason.NONE, answer, null);
		return answer.toTypeSafe(this.confidenceMetric);
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

		@SuppressWarnings("unchecked")
		JevBatchResult<SystemOneResponse>[] slots = new JevBatchResult[requests.size()];
		EscalationReason[] reasons = new EscalationReason[requests.size()];
		List<Integer> forLaya = new ArrayList<>();
		for (int i = 0; i < requests.size(); i++) {
			if (tooManyOptions(requests.get(i))) {
				reasons[i] = EscalationReason.TOO_MANY_OPTIONS;
			}
			else {
				forLaya.add(i);
			}
		}

		LayaResponse[] layaAnswers = new LayaResponse[requests.size()];
		Throwable[] layaFailures = new Throwable[requests.size()];
		if (!forLaya.isEmpty()) {
			List<SystemOneRequest> layaRequests = forLaya.stream().map(requests::get).toList();
			List<JevBatchResult<LayaResponse>> layaResults = LayaBatching.run(this.laya, layaRequests, options);
			for (int j = 0; j < forLaya.size(); j++) {
				int index = forLaya.get(j);
				JevBatchResult<LayaResponse> result = layaResults.get(j);
				if (result.succeeded() && result.value() != null) {
					layaAnswers[index] = result.value();
					reasons[index] = judge(result.value());
				}
				else if (this.escalateOnFailure && escalatable(result.failure())) {
					layaFailures[index] = result.failure();
					reasons[index] = EscalationReason.LAYA_FAILED;
				}
				else {
					slots[index] = new JevBatchResult<>(index, null, result.failure());
					reasons[index] = null;
				}
			}
		}

		List<Integer> escalated = new ArrayList<>();
		for (int i = 0; i < requests.size(); i++) {
			if (reasons[i] == null) {
				continue;
			}
			if (reasons[i] == EscalationReason.NONE) {
				record(requests.get(i), EscalationReason.NONE, layaAnswers[i], null);
				slots[i] = new JevBatchResult<>(i, layaAnswers[i].toTypeSafe(this.confidenceMetric), null);
			}
			else {
				record(requests.get(i), reasons[i], layaAnswers[i], layaFailures[i]);
				escalated.add(i);
			}
		}

		if (!escalated.isEmpty()) {
			List<SystemOneRequest> fallbackRequests = escalated.stream()
				.map(i -> forFallback(requests.get(i)))
				.toList();
			List<JevBatchResult<SystemOneResponse>> fallbackResults = this.fallback.systemOneAll(fallbackRequests,
					options);
			for (int j = 0; j < escalated.size(); j++) {
				int index = escalated.get(j);
				JevBatchResult<SystemOneResponse> result = fallbackResults.get(j);
				slots[index] = new JevBatchResult<>(index, result.value(), result.failure());
			}
		}
		return Arrays.asList(slots);
	}

	@Override
	public List<ModelMetadata> listModels() {
		return this.fallback.listModels();
	}

	/**
	 * @return how many requests took each route since this client was built
	 */
	public Map<EscalationReason, Long> stats() {
		Map<EscalationReason, Long> snapshot = new EnumMap<>(EscalationReason.class);
		this.counters.forEach((reason, count) -> snapshot.put(reason, count.get()));
		return snapshot;
	}

	/**
	 * @return the Laya client
	 */
	public LayaClient laya() {
		return this.laya;
	}

	/**
	 * @return the client escalated to
	 */
	public TypeSafeClient fallback() {
		return this.fallback;
	}

	/**
	 * @return the floor on {@code answer_confidence}
	 */
	public double minAnswerConfidence() {
		return this.minAnswerConfidence;
	}

	private EscalationReason judge(LayaResponse answer) {
		if (this.escalateOnAbstention && answer.anyAbstained()) {
			return EscalationReason.ABSTAINED;
		}
		OptionalDouble lowest = answer.minAnswerConfidence();
		if (lowest.isPresent() && lowest.getAsDouble() < this.minAnswerConfidence) {
			return EscalationReason.LOW_CONFIDENCE;
		}
		return EscalationReason.NONE;
	}

	private boolean tooManyOptions(SystemOneRequest request) {
		return request.questions()
			.values()
			.stream()
			.anyMatch(q -> q instanceof Choice choice && choice.criteria().size() > this.maxLayaChoiceOptions);
	}

	private static boolean escalatable(@Nullable Throwable failure) {
		if (failure instanceof LayaLimitExceededException || failure instanceof TypeSafeApiConnectionException) {
			return true;
		}
		if (failure instanceof TypeSafeApiException api) {
			int status = api.status();
			return status >= 500 || status == 408 || status == 413;
		}
		return false;
	}

	private SystemOneResponse escalate(SystemOneRequest request, EscalationReason reason, @Nullable LayaResponse laya,
			@Nullable Throwable failure) {
		record(request, reason, laya, failure);
		return this.fallback.systemOne(forFallback(request));
	}

	/** Laya checkpoint names mean nothing to the fallback; it gets its own default model. */
	private SystemOneRequest forFallback(SystemOneRequest request) {
		return request.withModel(this.fallback.defaultModel());
	}

	private void record(SystemOneRequest request, EscalationReason reason, @Nullable LayaResponse laya,
			@Nullable Throwable failure) {
		this.counters.get(reason).incrementAndGet();
		try {
			this.listener.onDecision(request, reason, laya, failure);
		}
		catch (RuntimeException ex) {
			// A listener is for observation; it must not change the answer. The warning omits
			// the request so a failing listener cannot write decision state to the log.
			logger.warn("Laya escalation listener failed for {} ({})", reason, ex.getClass().getName());
			logger.debug("Laya escalation listener failure", ex);
		}
	}

	/**
	 * @param laya the primary client
	 * @param fallback the client to escalate to, typically a Jev {@link TypeSafeClient}
	 * @return a new builder
	 */
	public static Builder builder(LayaClient laya, TypeSafeClient fallback) {
		return new Builder(laya, fallback);
	}

	/**
	 * Builder for {@link LayaEscalatingClient}.
	 */
	public static final class Builder {

		private final LayaClient laya;

		private final TypeSafeClient fallback;

		private ConfidenceMetric confidenceMetric = ConfidenceMetric.JEV;

		private double minAnswerConfidence = DEFAULT_MIN_ANSWER_CONFIDENCE;

		private boolean escalateOnAbstention = true;

		private boolean escalateOnFailure = true;

		private int maxLayaChoiceOptions = DEFAULT_MAX_LAYA_CHOICE_OPTIONS;

		private EscalationListener listener = EscalationListener.NONE;

		private Builder(LayaClient laya, TypeSafeClient fallback) {
			Assert.notNull(laya, "laya must not be null");
			Assert.notNull(fallback, "fallback must not be null");
			Assert.isTrue(!(fallback instanceof LayaEscalatingClient), "fallback must not itself escalate");
			this.laya = laya;
			this.fallback = fallback;
		}

		/**
		 * @param confidenceMetric the confidence Laya's answer records carry when Laya serves
		 * @return this builder
		 */
		public Builder confidenceMetric(ConfidenceMetric confidenceMetric) {
			Assert.notNull(confidenceMetric, "confidenceMetric must not be null");
			this.confidenceMetric = confidenceMetric;
			return this;
		}

		/**
		 * @param minAnswerConfidence escalate when any answer's {@code answer_confidence} is
		 * below this; 0 disables confidence escalation
		 * @return this builder
		 */
		public Builder minAnswerConfidence(double minAnswerConfidence) {
			Assert.isTrue(minAnswerConfidence >= 0.0d && minAnswerConfidence <= 1.0d,
					"minAnswerConfidence must be between 0 and 1");
			this.minAnswerConfidence = minAnswerConfidence;
			return this;
		}

		public Builder escalateOnAbstention(boolean escalateOnAbstention) {
			this.escalateOnAbstention = escalateOnAbstention;
			return this;
		}

		public Builder escalateOnFailure(boolean escalateOnFailure) {
			this.escalateOnFailure = escalateOnFailure;
			return this;
		}

		/**
		 * @param maxLayaChoiceOptions choices with more options than this skip Laya
		 * @return this builder
		 */
		public Builder maxLayaChoiceOptions(int maxLayaChoiceOptions) {
			Assert.isTrue(maxLayaChoiceOptions >= 2, "maxLayaChoiceOptions must be at least 2");
			this.maxLayaChoiceOptions = maxLayaChoiceOptions;
			return this;
		}

		public Builder listener(EscalationListener listener) {
			Assert.notNull(listener, "listener must not be null");
			this.listener = listener;
			return this;
		}

		public LayaEscalatingClient build() {
			return new LayaEscalatingClient(this);
		}

	}

}
