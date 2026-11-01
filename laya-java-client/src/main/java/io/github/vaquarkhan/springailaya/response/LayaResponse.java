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

package io.github.vaquarkhan.springailaya.response;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;
import io.github.vaquarkhan.springailaya.ConfidenceMetric;
import io.github.vaquarkhan.springailaya.LayaConfidence;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

/**
 * One Laya decision: the answers in the Jev records the rest of spring-ai-typesafe already
 * reads, plus everything Laya reports that the Jev contract has no field for.
 *
 * <p>
 * {@link #response()} carries the answers exactly as Laya sent them, so its
 * {@code confidence} fields hold Laya's normalised-entropy value. Use
 * {@link #toTypeSafe(ConfidenceMetric)} for a view whose {@code confidence} means what a
 * Jev-tuned threshold expects.
 *
 * @param response the answers as Jev records, confidence untouched
 * @param details the Laya-only fields per answer, keyed like the answers
 * @param usage the Laya usage block, including truncation
 * @param routing which checkpoint answered and why; {@code null} under strict Jev mode
 * @param inferenceTimeMs the server's own inference time from {@code X-Inference-Time-Ms}
 * @author Vaquar Khan
 */
public record LayaResponse(SystemOneResponse response, Map<String, LayaAnswerDetails> details, LayaUsage usage,
		@Nullable LayaRouting routing, @Nullable Double inferenceTimeMs) {

	public LayaResponse {
		details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
		usage = usage == null ? LayaUsage.EMPTY : usage;
	}

	/**
	 * @param name the question name
	 * @return the Laya fields of that answer, {@link LayaAnswerDetails#EMPTY} when none
	 */
	public LayaAnswerDetails detailsOf(String name) {
		return this.details.getOrDefault(name, LayaAnswerDetails.EMPTY);
	}

	/**
	 * The answers with {@code confidence} replaced by the chosen metric, as a
	 * {@link SystemOneResponse} any spring-ai-typesafe component accepts. Noul answers carry
	 * no confidence in the Jev records and pass through unchanged.
	 * @param metric which of Laya's confidence numbers to expose
	 * @return the Jev view
	 */
	public SystemOneResponse toTypeSafe(ConfidenceMetric metric) {
		if (metric == ConfidenceMetric.ENTROPY) {
			return this.response;
		}
		Map<String, Answer> answers = new LinkedHashMap<>();
		this.response.answers().forEach((name, answer) -> answers.put(name, remap(answer, detailsOf(name), metric)));
		return new SystemOneResponse(this.response.model(), answers, this.response.usage(),
				this.response.requestId());
	}

	/**
	 * The calibrated confidence of one answer: {@code answer_confidence} when the server sent
	 * it, otherwise computed from the probabilities the same way.
	 * @param name the question name
	 * @return the confidence, or empty when the answer has no usable probabilities
	 */
	public OptionalDouble answerConfidence(String name) {
		LayaAnswerDetails detail = detailsOf(name);
		if (detail.answerConfidence() != null) {
			return OptionalDouble.of(detail.answerConfidence());
		}
		Answer answer = this.response.answers().get(name);
		if (answer instanceof NoulAnswer noul) {
			return OptionalDouble.of(Math.max(noul.value(), 1.0d - noul.value()));
		}
		if (answer instanceof ChoiceAnswer choice && !choice.probabilities().isEmpty()) {
			return OptionalDouble.of(LayaConfidence.answer(choice.probabilities().values()));
		}
		if (answer instanceof ScoreAnswer score && !score.probabilities().isEmpty()) {
			return OptionalDouble.of(LayaConfidence.answer(score.probabilities().values()));
		}
		return OptionalDouble.empty();
	}

	/**
	 * @return the lowest calibrated confidence across all answers; empty when none has one
	 */
	public OptionalDouble minAnswerConfidence() {
		return this.response.answers()
			.keySet()
			.stream()
			.map(this::answerConfidence)
			.filter(OptionalDouble::isPresent)
			.mapToDouble(OptionalDouble::getAsDouble)
			.min();
	}

	/**
	 * @return {@code true} when the abstention gate flagged at least one answer
	 */
	public boolean anyAbstained() {
		return this.details.values().stream().anyMatch(LayaAnswerDetails::abstained);
	}

	private static Answer remap(Answer answer, LayaAnswerDetails detail, ConfidenceMetric metric) {
		if (answer instanceof ChoiceAnswer choice) {
			double confidence = (metric == ConfidenceMetric.JEV)
					? orElse(detail.jevConfidence(), LayaConfidence.jevChoice(choice.probabilities().values()))
					: orElse(detail.answerConfidence(), LayaConfidence.answer(choice.probabilities().values()));
			return new ChoiceAnswer(choice.value(), choice.probabilities(), confidence);
		}
		if (answer instanceof ScoreAnswer score) {
			double confidence = (metric == ConfidenceMetric.JEV)
					? orElse(detail.jevConfidence(), LayaConfidence.jevScore(score.probabilities()))
					: orElse(detail.answerConfidence(), LayaConfidence.answer(score.probabilities().values()));
			return new ScoreAnswer(score.value(), score.legend(), score.probabilities(), confidence);
		}
		return answer;
	}

	private static double orElse(@Nullable Double value, double fallback) {
		return value != null ? value : fallback;
	}

}
