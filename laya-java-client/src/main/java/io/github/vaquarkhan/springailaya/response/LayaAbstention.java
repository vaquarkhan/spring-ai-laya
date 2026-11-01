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

import org.jspecify.annotations.Nullable;

/**
 * The abstention gate's verdict on one answer, present only when the request set
 * {@code min_confidence}. Its absence is the report that the gate did not run; it is not a
 * fourth state.
 *
 * @author Vaquar Khan
 */
public enum LayaAbstention {

	/** The answer's confidence cleared the threshold. */
	PASSED,

	/** The answer fell below the threshold. It is still returned, marked low confidence. */
	ABSTAINED,

	/** The answer carried no usable confidence, so the gate could not decide. */
	UNEVALUATED;

	/**
	 * @param value the wire value
	 * @return the state, or {@code null} for an absent or unrecognised value
	 */
	public static @Nullable LayaAbstention fromWire(@Nullable String value) {
		if (value == null) {
			return null;
		}
		return switch (value) {
			case "passed" -> PASSED;
			case "abstained" -> ABSTAINED;
			case "unevaluated" -> UNEVALUATED;
			default -> null;
		};
	}

}
