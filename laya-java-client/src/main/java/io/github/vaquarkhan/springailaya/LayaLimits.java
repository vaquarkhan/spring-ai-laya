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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.JsonContent;
import io.github.vaquarkhan.springailaya.exception.LayaLimitExceededException;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.Score;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.util.Assert;

/**
 * The request limits {@code laya-serve} enforces, checked on the client before a request is
 * sent. Defaults are the server's documented values; raise {@code maxChoiceOptions} to 255
 * when the deployment sets {@code LAYA_TOURNAMENT_GROUP}.
 *
 * <p>
 * Two rules here are stricter than the Jev SDK's own builders, because Laya refuses what Jev
 * accepts: a noul must carry {@code instructions} (Laya answers 422 otherwise, even with
 * criteria), and every score level needs a description.
 *
 * @param maxQuestions questions per request
 * @param maxStatesPerBatch states per batch call; larger batches are split by the client
 * @param maxChoiceOptions options per choice question
 * @param maxScoreLevels levels per score question
 * @param maxTotalOptions options summed across all questions of a request
 * @param maxStateChars characters of the state the server accepts
 * @param accuracyWarningOptions above this many options a choice is logged once as likely
 * to lose accuracy: they stop fitting Laya's option token budget
 * @author Vaquar Khan
 */
public record LayaLimits(int maxQuestions, int maxStatesPerBatch, int maxChoiceOptions, int maxScoreLevels,
		int maxTotalOptions, int maxStateChars, int accuracyWarningOptions) {

	private static final Logger logger = LoggerFactory.getLogger(LayaLimits.class);

	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	public LayaLimits {
		Assert.isTrue(maxQuestions >= 1, "maxQuestions must be at least 1");
		Assert.isTrue(maxStatesPerBatch >= 1, "maxStatesPerBatch must be at least 1");
		Assert.isTrue(maxChoiceOptions >= 2, "maxChoiceOptions must be at least 2");
		Assert.isTrue(maxScoreLevels >= 2, "maxScoreLevels must be at least 2");
		Assert.isTrue(maxTotalOptions >= 2, "maxTotalOptions must be at least 2");
		Assert.isTrue(maxStateChars >= 1, "maxStateChars must be at least 1");
		Assert.isTrue(accuracyWarningOptions >= 2, "accuracyWarningOptions must be at least 2");
	}

	/**
	 * @return the limits of a default {@code laya-serve} 0.4.x
	 */
	public static LayaLimits defaults() {
		return new LayaLimits(64, 64, 100, 32, 512, 50_000, 20);
	}

	/**
	 * @return the limits of a server started with {@code LAYA_TOURNAMENT_GROUP} set
	 */
	public static LayaLimits withTournament() {
		return new LayaLimits(64, 64, 255, 32, 512, 50_000, 20);
	}

	/**
	 * Checks a question set.
	 * @param questions the questions of one request
	 * @throws LayaLimitExceededException when a limit is broken
	 */
	public void validate(Map<String, ? extends Question> questions) {
		if (questions.size() > this.maxQuestions) {
			throw new LayaLimitExceededException("questions-per-request",
					"Laya accepts at most %d questions per request, got %d".formatted(this.maxQuestions,
							questions.size()));
		}
		int totalOptions = 0;
		for (Map.Entry<String, ? extends Question> entry : questions.entrySet()) {
			String name = entry.getKey();
			Question question = entry.getValue();
			if (question instanceof Noul noul && (noul.instructions() == null || noul.instructions().isNull())) {
				throw new LayaLimitExceededException("noul-instructions",
						"Laya requires instructions on every noul; question '%s' has only criteria".formatted(name));
			}
			if (question instanceof Choice choice) {
				int options = choice.criteria().size();
				totalOptions += options;
				if (options > this.maxChoiceOptions) {
					throw new LayaLimitExceededException("options-per-choice",
							"Laya accepts at most %d options per choice; '%s' has %d. Narrow the candidates, or "
									+ "start the server with LAYA_TOURNAMENT_GROUP and use LayaLimits.withTournament()"
								.formatted(this.maxChoiceOptions, name, options));
				}
				if (options > this.accuracyWarningOptions && WARNED.add(name + "#" + options)) {
					logger.warn("Choice '{}' has {} options. Above about {} short options Laya's option budget "
							+ "is shared so thinly that accuracy drops; consider splitting the label space, "
							+ "raising head_max_len, or escalating such questions to Jev.", name, options,
							this.accuracyWarningOptions);
				}
			}
			if (question instanceof Score score) {
				int levels = score.criteria().size();
				totalOptions += levels;
				if (levels > this.maxScoreLevels) {
					throw new LayaLimitExceededException("levels-per-score",
							"Laya accepts at most %d score levels; '%s' has %d".formatted(this.maxScoreLevels, name,
									levels));
				}
				for (JsonContent level : score.criteria()) {
					if (level == null || level.isNull()) {
						throw new LayaLimitExceededException("score-level-description",
								"Laya requires a description for every score level; '%s' has a null level"
									.formatted(name));
					}
				}
			}
		}
		if (totalOptions > this.maxTotalOptions) {
			throw new LayaLimitExceededException("options-per-request",
					"Laya accepts at most %d options across all questions, got %d".formatted(this.maxTotalOptions,
							totalOptions));
		}
	}

	/**
	 * Checks a state's size. The server measures Python's spaced JSON, which is never
	 * shorter than the compact JSON measured here, so this only rejects states that are
	 * certainly too large.
	 * @param state the state
	 * @param jsonMapper the mapper used to serialise structured states
	 * @throws LayaLimitExceededException when the state is too large
	 */
	public void validateState(JsonContent state, JsonMapper jsonMapper) {
		Object value = state.value();
		if (value == null) {
			return;
		}
		int length = (value instanceof String text) ? text.length() : jsonMapper.writeValueAsString(value).length();
		if (length > this.maxStateChars) {
			throw new LayaLimitExceededException("state-characters",
					"Laya accepts at most %d characters of state, got at least %d".formatted(this.maxStateChars,
							length));
		}
	}

}
