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

package io.github.vaquarkhan.springailaya.exception;

import org.springaicommunity.typesafe.exception.TypeSafeException;

/**
 * A request that would break a documented {@code laya-serve} limit, caught before it is
 * sent. It saves a round trip and names the question at fault, which the server's 413 or
 * 422 detail does not always do. Not an API exception, so no retry policy retries it.
 *
 * @author Vaquar Khan
 */
public class LayaLimitExceededException extends TypeSafeException {

	private final String limit;

	public LayaLimitExceededException(String limit, String message) {
		super(message);
		this.limit = limit;
	}

	/**
	 * @return the name of the limit, e.g. {@code options-per-choice}
	 */
	public String limit() {
		return this.limit;
	}

}
