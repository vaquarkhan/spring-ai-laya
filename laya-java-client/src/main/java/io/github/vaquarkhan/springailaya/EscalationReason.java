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
 * Why {@link LayaEscalatingClient} sent a request where it did.
 *
 * @author Vaquar Khan
 */
public enum EscalationReason {

	/** Laya answered and every answer cleared the confidence floor. Served by Laya. */
	NONE,

	/**
	 * A choice had more options than Laya handles well; sent straight to the fallback
	 * without asking Laya.
	 */
	TOO_MANY_OPTIONS,

	/** Laya was unreachable, overloaded, timed out, or refused the request's size. */
	LAYA_FAILED,

	/** Laya answered, but some answer's calibrated confidence was below the floor. */
	LOW_CONFIDENCE,

	/** Laya's own abstention gate ({@code min_confidence}) flagged an answer. */
	ABSTAINED;

	/**
	 * @return {@code true} when the fallback answered
	 */
	public boolean escalated() {
		return this != NONE;
	}

}
