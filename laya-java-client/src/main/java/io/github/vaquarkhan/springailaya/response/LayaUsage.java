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

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.response.Usage;

/**
 * Laya's usage block. Beyond the two Jev token counts it reports truncation: how much of the
 * state at least one question did not get to read. That is the only place a silently cut
 * state is visible, since Laya sizes the state by tokens, not characters.
 *
 * @param inputTokens non-pad tokens of the state's rows, one row per question
 * @param outputTokens always 0 on Laya; nothing is generated
 * @param stateTokens tokens the whole serialized state needs
 * @param stateTokensDropped worst-case tokens of the state some question did not see
 * @param truncated {@code true} when that worst case dropped anything
 * @param truncatedQuestions ids of the questions whose own window was cut
 * @param options per-question option-span report, present only when options collapsed
 * @author Vaquar Khan
 */
public record LayaUsage(@Nullable Integer inputTokens, @Nullable Integer outputTokens, @Nullable Integer stateTokens,
		@Nullable Integer stateTokensDropped, @Nullable Boolean truncated, List<String> truncatedQuestions,
		@Nullable Map<String, Object> options) {

	/** A usage block with nothing in it. */
	public static final LayaUsage EMPTY = new LayaUsage(null, null, null, null, null, List.of(), null);

	public LayaUsage {
		truncatedQuestions = truncatedQuestions == null ? List.of() : List.copyOf(truncatedQuestions);
	}

	/**
	 * @return {@code true} when part of the state was not read by at least one question
	 */
	public boolean wasTruncated() {
		return Boolean.TRUE.equals(this.truncated) || !this.truncatedQuestions.isEmpty();
	}

	/**
	 * @return the Jev view of this usage block
	 */
	public Usage toUsage() {
		return new Usage(this.inputTokens, this.outputTokens);
	}

	/**
	 * Adds two usage blocks, as the batch endpoint's {@code total_usage} does.
	 * @param other the block to add
	 * @return the sum; truncation is true when either was truncated
	 */
	public LayaUsage plus(LayaUsage other) {
		List<String> questions = new java.util.ArrayList<>(this.truncatedQuestions);
		other.truncatedQuestions.stream().filter(q -> !questions.contains(q)).forEach(questions::add);
		return new LayaUsage(sum(this.inputTokens, other.inputTokens), sum(this.outputTokens, other.outputTokens),
				sum(this.stateTokens, other.stateTokens), max(this.stateTokensDropped, other.stateTokensDropped),
				(this.truncated == null && other.truncated == null) ? null
						: Boolean.TRUE.equals(this.truncated) || Boolean.TRUE.equals(other.truncated),
				questions, this.options != null ? this.options : other.options);
	}

	private static @Nullable Integer sum(@Nullable Integer a, @Nullable Integer b) {
		if (a == null && b == null) {
			return null;
		}
		return (a == null ? 0 : a) + (b == null ? 0 : b);
	}

	private static @Nullable Integer max(@Nullable Integer a, @Nullable Integer b) {
		if (a == null) {
			return b;
		}
		return b == null ? a : Math.max(a, b);
	}

}
