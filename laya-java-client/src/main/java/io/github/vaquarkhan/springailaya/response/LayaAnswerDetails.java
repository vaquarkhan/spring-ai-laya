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

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The fields Laya adds to one answer beyond the Jev contract. Every field is nullable: a
 * server running with {@code LAYA_JEV_STRICT=1} sends none of them, and the gate fields
 * appear only when the request set {@code min_confidence}.
 *
 * @param type the answer type echoed by the server ({@code noul}, {@code choice},
 * {@code score})
 * @param confidence Laya's native confidence: normalised entropy on choice and score,
 * {@code max(p_yes, p_no)} on noul
 * @param answerConfidence the probability of the reported answer, the calibrated number
 * @param jevConfidence {@code x_jev_confidence}: the confidence Jev would report for the same
 * probabilities; choice and score only
 * @param actProbability {@code action.act_probability}
 * @param abstention the gate verdict, or {@code null} when the gate did not run
 * @param abstentionThreshold the threshold the gate applied
 * @param lowConfidence {@code true} exactly on abstained answers
 * @param tournament {@code x_tournament}, present when a large choice was narrowed by
 * elimination; kept raw because its shape is not part of a stable contract
 * @author Vaquar Khan
 */
public record LayaAnswerDetails(@Nullable String type, @Nullable Double confidence, @Nullable Double answerConfidence,
		@Nullable Double jevConfidence, @Nullable Double actProbability, @Nullable LayaAbstention abstention,
		@Nullable Double abstentionThreshold, @Nullable Boolean lowConfidence,
		@Nullable Map<String, Object> tournament) {

	/** Details of an answer that carried none of the Laya fields. */
	public static final LayaAnswerDetails EMPTY = new LayaAnswerDetails(null, null, null, null, null, null, null, null,
			null);

	/**
	 * @return {@code true} when the abstention gate flagged this answer
	 */
	public boolean abstained() {
		return this.abstention == LayaAbstention.ABSTAINED || Boolean.TRUE.equals(this.lowConfidence);
	}

	/**
	 * @return {@code true} when a large choice was answered by elimination
	 */
	public boolean narrowedByTournament() {
		return this.tournament != null;
	}

}
