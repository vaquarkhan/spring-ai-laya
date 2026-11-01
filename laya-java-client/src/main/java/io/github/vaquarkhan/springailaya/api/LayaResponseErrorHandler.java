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

package io.github.vaquarkhan.springailaya.api;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.api.TypeSafeResponseErrorHandler;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import io.github.vaquarkhan.springailaya.exception.LayaPayloadTooLargeException;
import io.github.vaquarkhan.springailaya.exception.LayaServerBusyException;

import org.springframework.http.HttpHeaders;

/**
 * The Jev error mapping plus the two statuses only {@code laya-serve} sends: 413 for a broken
 * size limit and 503 for a full admission queue. Everything else, including the
 * {@code {"detail": "..."}} body Laya shares with FastAPI, is already handled upstream.
 *
 * @author Vaquar Khan
 */
public class LayaResponseErrorHandler extends TypeSafeResponseErrorHandler {

	@Override
	public TypeSafeApiException toException(int status, @Nullable String body, HttpHeaders headers, String endpoint) {
		if (status == 413) {
			TypeSafeApiException generic = super.toException(status, body, headers, endpoint);
			return new LayaPayloadTooLargeException(generic.getMessage(), status, body, headers, endpoint);
		}
		if (status == LayaServerBusyException.BUSY_STATUS) {
			TypeSafeApiException generic = super.toException(status, body, headers, endpoint);
			return new LayaServerBusyException(generic.getMessage(), status, body, headers, endpoint,
					retryAfterMs(headers));
		}
		return super.toException(status, body, headers, endpoint);
	}

}
