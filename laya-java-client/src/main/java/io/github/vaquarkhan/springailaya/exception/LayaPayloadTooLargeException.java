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

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;

import org.springframework.http.HttpHeaders;

/**
 * A 413 from {@code laya-serve}: the request broke one of its size limits (body, state
 * length, questions, states per batch, options per choice, score levels or total options).
 * The {@code detail} names the limit. Never retried: the same request fails the same way.
 *
 * @author Vaquar Khan
 */
public class LayaPayloadTooLargeException extends TypeSafeApiException {

	public LayaPayloadTooLargeException(String message, int status, @Nullable String body, HttpHeaders headers,
			String endpoint) {
		super(message, status, body, headers, endpoint);
	}

}
