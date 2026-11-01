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

/**
 * Which of Laya's three confidence numbers to place in the {@code confidence} field of the
 * {@code ChoiceAnswer} and {@code ScoreAnswer} records that the rest of spring-ai-typesafe
 * reads.
 *
 * <p>
 * This matters because the same field name means different things on the two servers.
 * Jev's {@code confidence} is {@code (p_max - 1/n) / (1 - 1/n)} for a choice. Laya's is
 * {@code 1 - H(p)/log(n)}, a measure of how concentrated the whole distribution is. A
 * threshold tuned on Jev, such as {@code JevConfidenceGate}'s 0.6 floor, therefore gates a
 * different quantity when the same record comes from Laya. The upstream Laya docs say so
 * directly: read a ported Jev threshold against {@code x_jev_confidence}.
 *
 * @author Vaquar Khan
 */
public enum ConfidenceMetric {

	/**
	 * Jev's own formula, from Laya's {@code x_jev_confidence}, or computed from the
	 * probabilities when the server runs with {@code LAYA_JEV_STRICT=1} and omits it. The
	 * default: thresholds already written for Jev keep their meaning.
	 */
	JEV,

	/**
	 * {@code answer_confidence}, the probability of the reported answer ({@code max(p)}).
	 * The quantity Laya's temperature scaling fits, so the one to gate on when thresholds
	 * are tuned on Laya itself.
	 */
	ANSWER,

	/**
	 * Laya's native {@code confidence} (normalised entropy), passed through unchanged. The
	 * behaviour of the plain {@code TypeSafeClient} pointed at Laya today.
	 */
	ENTROPY

}
