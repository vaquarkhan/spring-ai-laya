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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * The per-request routing and gating controls {@code laya-serve} accepts beside
 * {@code state}, {@code questions} and {@code model}. Every field is optional; an absent
 * one leaves the deployment's own setting in charge, which is how the server itself treats
 * a missing key.
 *
 * @param task force a checkpoint by typed-decisions workflow name
 * @param lang a language code that skips detection, e.g. {@code de}
 * @param langGuess a softer hint consulted after {@code lang} and before detection
 * @param maxLen total token window for the request, capped by the server
 * @param headMaxLen token window the option prompt shares; raise it for many-option choices
 * @param minConfidence abstention threshold in [0, 1]; answers below it come back marked
 * {@code low_confidence}, never dropped
 * @param minConfidenceBuckets per option-count thresholds such as
 * {@code {"choice:3-5": 0.9, "default": 0.4}}; takes precedence over {@code minConfidence}
 * @author Vaquar Khan
 */
public record LayaOptions(@Nullable String task, @Nullable String lang, @Nullable String langGuess,
		@Nullable Integer maxLen, @Nullable Integer headMaxLen, @Nullable Double minConfidence,
		@Nullable Map<String, Double> minConfidenceBuckets) {

	private static final LayaOptions NONE = new LayaOptions(null, null, null, null, null, null, null);

	public LayaOptions {
		Assert.isTrue(maxLen == null || maxLen > 0, "maxLen must be positive");
		Assert.isTrue(headMaxLen == null || headMaxLen > 0, "headMaxLen must be positive");
		Assert.isTrue(minConfidence == null || (minConfidence >= 0.0d && minConfidence <= 1.0d),
				"minConfidence must be between 0 and 1");
		if (minConfidenceBuckets != null) {
			minConfidenceBuckets.forEach((bucket, threshold) -> Assert.isTrue(
					threshold != null && threshold >= 0.0d && threshold <= 1.0d,
					() -> "minConfidenceBuckets[" + bucket + "] must be between 0 and 1"));
			minConfidenceBuckets = Collections.unmodifiableMap(new LinkedHashMap<>(minConfidenceBuckets));
		}
	}

	/**
	 * @return options that send nothing extra
	 */
	public static LayaOptions none() {
		return NONE;
	}

	/**
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Overlays another set of options on this one, field by field.
	 * @param override the options that win where they are set
	 * @return the merged options
	 */
	public LayaOptions mergedWith(@Nullable LayaOptions override) {
		if (override == null || override == NONE) {
			return this;
		}
		return new LayaOptions(pick(override.task, this.task), pick(override.lang, this.lang),
				pick(override.langGuess, this.langGuess), pick(override.maxLen, this.maxLen),
				pick(override.headMaxLen, this.headMaxLen), pick(override.minConfidence, this.minConfidence),
				pick(override.minConfidenceBuckets, this.minConfidenceBuckets));
	}

	/**
	 * @return the value to send as {@code min_confidence}: the bucket map, the number, or
	 * {@code null} to send nothing
	 */
	public @Nullable Object minConfidenceWireValue() {
		if (this.minConfidenceBuckets != null && !this.minConfidenceBuckets.isEmpty()) {
			return this.minConfidenceBuckets;
		}
		return this.minConfidence;
	}

	private static <T> @Nullable T pick(@Nullable T preferred, @Nullable T fallback) {
		if (preferred instanceof String text) {
			return StringUtils.hasText(text) ? preferred : fallback;
		}
		return preferred != null ? preferred : fallback;
	}

	/**
	 * Builder for {@link LayaOptions}.
	 */
	public static final class Builder {

		private @Nullable String task;

		private @Nullable String lang;

		private @Nullable String langGuess;

		private @Nullable Integer maxLen;

		private @Nullable Integer headMaxLen;

		private @Nullable Double minConfidence;

		private @Nullable Map<String, Double> minConfidenceBuckets;

		private Builder() {
		}

		public Builder task(@Nullable String task) {
			this.task = task;
			return this;
		}

		public Builder lang(@Nullable String lang) {
			this.lang = lang;
			return this;
		}

		public Builder langGuess(@Nullable String langGuess) {
			this.langGuess = langGuess;
			return this;
		}

		public Builder maxLen(@Nullable Integer maxLen) {
			this.maxLen = maxLen;
			return this;
		}

		public Builder headMaxLen(@Nullable Integer headMaxLen) {
			this.headMaxLen = headMaxLen;
			return this;
		}

		public Builder minConfidence(@Nullable Double minConfidence) {
			this.minConfidence = minConfidence;
			return this;
		}

		public Builder minConfidenceBuckets(@Nullable Map<String, Double> minConfidenceBuckets) {
			this.minConfidenceBuckets = minConfidenceBuckets;
			return this;
		}

		public LayaOptions build() {
			return new LayaOptions(this.task, this.lang, this.langGuess, this.maxLen, this.headMaxLen,
					this.minConfidence, this.minConfidenceBuckets);
		}

	}

}
