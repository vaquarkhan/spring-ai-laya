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

package io.github.vaquarkhan.springailaya.autoconfigure;

import java.time.Duration;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.RetryPolicy;
import io.github.vaquarkhan.springailaya.ConfidenceMetric;
import io.github.vaquarkhan.springailaya.LayaCheckpoints;
import io.github.vaquarkhan.springailaya.LayaConstants;
import io.github.vaquarkhan.springailaya.LayaEscalatingClient;
import io.github.vaquarkhan.springailaya.LayaOptions;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code spring.ai.laya.*}: a Laya backend for spring-ai-typesafe. Setting
 * {@code base-url} switches the {@code TypeSafeClient} bean from Jev to Laya.
 *
 * @author Vaquar Khan
 */
@ConfigurationProperties(LayaProperties.CONFIG_PREFIX)
@Validated
public class LayaProperties {

	/** Configuration prefix. */
	public static final String CONFIG_PREFIX = "spring.ai.laya";

	/** Root of the laya-serve deployment, e.g. http://localhost:8000. Its presence enables Laya. */
	private @Nullable String baseUrl;

	/** Bearer key; only needed when the server sets LAYA_API_KEY. */
	private @Nullable String apiKey;

	/** Checkpoint: auto (let the server route), english, multilingual or typed-decisions. */
	private String model = LayaCheckpoints.AUTO;

	/** Confidence placed in answer records: JEV (default), ANSWER or ENTROPY. */
	private ConfidenceMetric confidenceMetric = ConfidenceMetric.JEV;

	/** Per-attempt HTTP timeout. */
	@NotNull
	private Duration timeout = LayaConstants.DEFAULT_TIMEOUT;

	/** Whether the server runs with LAYA_TOURNAMENT_GROUP, raising the choice option cap to 255. */
	private boolean tournament;

	/** Most rows (states x questions) per batch call in systemOneAll; raise on a GPU. */
	@Min(1)
	private int rowsPerBatchCall = io.github.vaquarkhan.springailaya.LayaClient.DEFAULT_ROWS_PER_BATCH_CALL;

	public int getRowsPerBatchCall() {
		return this.rowsPerBatchCall;
	}

	public void setRowsPerBatchCall(int rowsPerBatchCall) {
		this.rowsPerBatchCall = rowsPerBatchCall;
	}

	/** Most batch calls in flight at once in systemOneAll; raise on a GPU or several replicas. */
	@Min(1)
	private int maxConcurrentBatchCalls = io.github.vaquarkhan.springailaya.LayaClient.DEFAULT_MAX_CONCURRENT_BATCH_CALLS;

	public int getMaxConcurrentBatchCalls() {
		return this.maxConcurrentBatchCalls;
	}

	public void setMaxConcurrentBatchCalls(int maxConcurrentBatchCalls) {
		this.maxConcurrentBatchCalls = maxConcurrentBatchCalls;
	}

	@Valid
	private final Options options = new Options();

	@Valid
	private final Retry retry = new Retry();

	@Valid
	private final Escalation escalation = new Escalation();

	public @Nullable String getBaseUrl() {
		return this.baseUrl;
	}

	public void setBaseUrl(@Nullable String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public @Nullable String getApiKey() {
		return this.apiKey;
	}

	public void setApiKey(@Nullable String apiKey) {
		this.apiKey = apiKey;
	}

	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public ConfidenceMetric getConfidenceMetric() {
		return this.confidenceMetric;
	}

	public void setConfidenceMetric(ConfidenceMetric confidenceMetric) {
		this.confidenceMetric = confidenceMetric;
	}

	public Duration getTimeout() {
		return this.timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public boolean isTournament() {
		return this.tournament;
	}

	public void setTournament(boolean tournament) {
		this.tournament = tournament;
	}

	public Options getOptions() {
		return this.options;
	}

	public Retry getRetry() {
		return this.retry;
	}

	public Escalation getEscalation() {
		return this.escalation;
	}

	/**
	 * @return the retry policy these properties describe
	 */
	public RetryPolicy toRetryPolicy() {
		return RetryPolicy.builder()
			.maxRetries(this.retry.maxRetries)
			.initialBackoff(this.retry.initialBackoff)
			.maxBackoff(this.retry.maxBackoff)
			.jitter(this.retry.jitter)
			.retryableStatuses(this.retry.statuses)
			.respectRetryAfter(this.retry.respectRetryAfter)
			.retryConnectionErrors(this.retry.retryConnectionErrors)
			.totalTimeout(this.retry.totalTimeout)
			.build();
	}

	/**
	 * Per-request controls applied to every request.
	 */
	public static class Options {

		/** Force a checkpoint by typed-decisions workflow name. */
		private @Nullable String task;

		/** Language code that skips detection. */
		private @Nullable String lang;

		/** Soft language hint consulted before detection. */
		private @Nullable String langGuess;

		/** Token window for the request. */
		@Min(1)
		private @Nullable Integer maxLen;

		/** Token window of the option prompt; raise for many-option choices. */
		@Min(1)
		private @Nullable Integer headMaxLen;

		/** Server-side abstention threshold; answers below it are marked low confidence. */
		@DecimalMin("0.0")
		@DecimalMax("1.0")
		private @Nullable Double minConfidence;

		public @Nullable String getTask() {
			return this.task;
		}

		public void setTask(@Nullable String task) {
			this.task = task;
		}

		public @Nullable String getLang() {
			return this.lang;
		}

		public void setLang(@Nullable String lang) {
			this.lang = lang;
		}

		public @Nullable String getLangGuess() {
			return this.langGuess;
		}

		public void setLangGuess(@Nullable String langGuess) {
			this.langGuess = langGuess;
		}

		public @Nullable Integer getMaxLen() {
			return this.maxLen;
		}

		public void setMaxLen(@Nullable Integer maxLen) {
			this.maxLen = maxLen;
		}

		public @Nullable Integer getHeadMaxLen() {
			return this.headMaxLen;
		}

		public void setHeadMaxLen(@Nullable Integer headMaxLen) {
			this.headMaxLen = headMaxLen;
		}

		public @Nullable Double getMinConfidence() {
			return this.minConfidence;
		}

		public void setMinConfidence(@Nullable Double minConfidence) {
			this.minConfidence = minConfidence;
		}

		/**
		 * @return the options as an SDK value
		 */
		public LayaOptions toLayaOptions() {
			return LayaOptions.builder()
				.task(this.task)
				.lang(this.lang)
				.langGuess(this.langGuess)
				.maxLen(this.maxLen)
				.headMaxLen(this.headMaxLen)
				.minConfidence(this.minConfidence)
				.build();
		}

	}

	/**
	 * Retry settings, defaulting to a server on the same network.
	 */
	public static class Retry {

		/** One retry: with 90 s attempts and a 200 s budget, a second retry cannot fit. */
		@Min(0)
		private int maxRetries = 1;

		@NotNull
		private Duration initialBackoff = Duration.ofMillis(200);

		@NotNull
		private Duration maxBackoff = Duration.ofSeconds(2);

		@DecimalMin("0.0")
		@DecimalMax("1.0")
		private double jitter = 0.25d;

		private Set<Integer> statuses = LayaConstants.DEFAULT_RETRYABLE_STATUSES;

		private boolean respectRetryAfter = true;

		private boolean retryConnectionErrors = true;

		private @Nullable Duration totalTimeout = LayaConstants.DEFAULT_TOTAL_TIMEOUT;

		public int getMaxRetries() {
			return this.maxRetries;
		}

		public void setMaxRetries(int maxRetries) {
			this.maxRetries = maxRetries;
		}

		public Duration getInitialBackoff() {
			return this.initialBackoff;
		}

		public void setInitialBackoff(Duration initialBackoff) {
			this.initialBackoff = initialBackoff;
		}

		public Duration getMaxBackoff() {
			return this.maxBackoff;
		}

		public void setMaxBackoff(Duration maxBackoff) {
			this.maxBackoff = maxBackoff;
		}

		public double getJitter() {
			return this.jitter;
		}

		public void setJitter(double jitter) {
			this.jitter = jitter;
		}

		public Set<Integer> getStatuses() {
			return this.statuses;
		}

		public void setStatuses(Set<Integer> statuses) {
			this.statuses = statuses;
		}

		public boolean isRespectRetryAfter() {
			return this.respectRetryAfter;
		}

		public void setRespectRetryAfter(boolean respectRetryAfter) {
			this.respectRetryAfter = respectRetryAfter;
		}

		public boolean isRetryConnectionErrors() {
			return this.retryConnectionErrors;
		}

		public void setRetryConnectionErrors(boolean retryConnectionErrors) {
			this.retryConnectionErrors = retryConnectionErrors;
		}

		public @Nullable Duration getTotalTimeout() {
			return this.totalTimeout;
		}

		public void setTotalTimeout(@Nullable Duration totalTimeout) {
			this.totalTimeout = totalTimeout;
		}

	}

	/**
	 * Laya first, Jev on low confidence or failure. Needs spring.ai.typesafe.api-key.
	 */
	public static class Escalation {

		/** Whether to escalate to Jev; requires spring.ai.typesafe.api-key. */
		private boolean enabled;

		/** Escalate when any answer's answer_confidence is below this. */
		@DecimalMin("0.0")
		@DecimalMax("1.0")
		private double minAnswerConfidence = LayaEscalatingClient.DEFAULT_MIN_ANSWER_CONFIDENCE;

		/** Choices with more options than this go straight to Jev. */
		@Min(2)
		private int maxLayaChoiceOptions = LayaEscalatingClient.DEFAULT_MAX_LAYA_CHOICE_OPTIONS;

		/** Escalate when Laya's abstention gate flags an answer. */
		private boolean onAbstention = true;

		/** Escalate when Laya is unreachable, overloaded or refuses the size. */
		private boolean onFailure = true;

		/**
		 * Acknowledges that an escalated request, including its full state, is sent to the Jev
		 * service configured under spring.ai.typesafe.*. Required when escalation is enabled.
		 */
		private boolean allowExternalDataExport;

		public boolean isAllowExternalDataExport() {
			return this.allowExternalDataExport;
		}

		public void setAllowExternalDataExport(boolean allowExternalDataExport) {
			this.allowExternalDataExport = allowExternalDataExport;
		}

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public double getMinAnswerConfidence() {
			return this.minAnswerConfidence;
		}

		public void setMinAnswerConfidence(double minAnswerConfidence) {
			this.minAnswerConfidence = minAnswerConfidence;
		}

		public int getMaxLayaChoiceOptions() {
			return this.maxLayaChoiceOptions;
		}

		public void setMaxLayaChoiceOptions(int maxLayaChoiceOptions) {
			this.maxLayaChoiceOptions = maxLayaChoiceOptions;
		}

		public boolean isOnAbstention() {
			return this.onAbstention;
		}

		public void setOnAbstention(boolean onAbstention) {
			this.onAbstention = onAbstention;
		}

		public boolean isOnFailure() {
			return this.onFailure;
		}

		public void setOnFailure(boolean onFailure) {
			this.onFailure = onFailure;
		}

	}

}
