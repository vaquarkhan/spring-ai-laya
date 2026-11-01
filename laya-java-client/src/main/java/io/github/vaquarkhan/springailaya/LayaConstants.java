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

import java.time.Duration;
import java.util.Set;

import org.springaicommunity.typesafe.RetryPolicy;

/**
 * Defaults and environment variable names for a {@code laya-serve} deployment.
 *
 * <p>
 * The environment variable names deliberately match the server's own ({@code LAYA_API_KEY}
 * is the bearer the server checks), so one {@code .env} file configures both sides.
 *
 * @author Vaquar Khan
 */
public final class LayaConstants {

	/** Environment variable holding the server root, e.g. {@code http://localhost:8000}. */
	public static final String BASE_URL_ENV = "LAYA_BASE_URL";

	/** Environment variable holding the optional bearer key, the same one the server reads. */
	public static final String API_KEY_ENV = "LAYA_API_KEY";

	/** {@code laya-serve} binds port 8000 unless {@code LAYA_PORT} says otherwise. */
	public static final String DEFAULT_BASE_URL = "http://localhost:8000";

	/** Single-state decision endpoint, the one Jev also serves. */
	public static final String SYSTEM_ONE_PATH = "/v1/systemone";

	/** One question set over many states, with forward passes shared. Laya only. */
	public static final String BATCH_PATH = "/v1/systemone/batch";

	/** Routing snapshot: which checkpoint would answer, without inference. Laya only. */
	public static final String ROUTE_PATH = "/v1/route";

	/** Liveness and resident checkpoints. Laya only. */
	public static final String HEALTH_PATH = "/health";

	/**
	 * Per-attempt HTTP timeout. Much higher than the Jev default: on a CPU host a 16-row
	 * batch of long passages was measured at 30 to 60 seconds, and a timeout makes the RAG
	 * components fail open, passing every passage through unscreened.
	 */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);

	/** Retry budget: two attempts at {@link #DEFAULT_TIMEOUT} plus backoff. */
	public static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(200);

	/**
	 * Laya answers 503 with {@code Retry-After: 1} when its admission cap is full and 408
	 * when a body arrives too slowly. It never sends 429 or 529.
	 */
	public static final Set<Integer> DEFAULT_RETRYABLE_STATUSES = Set.of(408, 503);

	private LayaConstants() {
	}

	/**
	 * The retry policy suited to a server on the same network: short backoff, one retry, and a
	 * budget that fits two full attempts at {@link #DEFAULT_TIMEOUT} plus backoff. A second
	 * retry could never start after slow attempts, because the client reserves a whole attempt
	 * timeout before each retry, so it is not configured.
	 * @return the default policy
	 */
	public static RetryPolicy defaultRetryPolicy() {
		return RetryPolicy.builder()
			.maxRetries(1)
			.initialBackoff(Duration.ofMillis(200))
			.maxBackoff(Duration.ofSeconds(2))
			.retryableStatuses(DEFAULT_RETRYABLE_STATUSES)
			.totalTimeout(DEFAULT_TOTAL_TIMEOUT)
			.build();
	}

}
