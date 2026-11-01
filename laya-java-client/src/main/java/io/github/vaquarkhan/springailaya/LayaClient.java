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

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.exception.TypeSafeApiConnectionException;
import org.springaicommunity.typesafe.exception.TypeSafeApiResponseValidationException;
import org.springaicommunity.typesafe.exception.TypeSafeApiTimeoutException;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import io.github.vaquarkhan.springailaya.api.LayaRequestBodies;
import io.github.vaquarkhan.springailaya.api.LayaResponseErrorHandler;
import io.github.vaquarkhan.springailaya.api.LayaResponseParser;
import io.github.vaquarkhan.springailaya.exception.LayaLimitExceededException;
import io.github.vaquarkhan.springailaya.response.LayaBatchResponse;
import io.github.vaquarkhan.springailaya.response.LayaHealth;
import io.github.vaquarkhan.springailaya.response.LayaResponse;
import io.github.vaquarkhan.springailaya.response.LayaRouting;
import io.github.vaquarkhan.springailaya.response.LayaUsage;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The native client for a {@code laya-serve} deployment: every endpoint it serves, every
 * field it returns.
 *
 * <ul>
 * <li>{@link #systemOne(SystemOneRequest, LayaOptions)}: one state, the Jev endpoint, with
 * Laya's routing and abstention controls.</li>
 * <li>{@link #systemOneBatch(List, Map, String, LayaOptions)}: one question set over many
 * states in shared forward passes, for "same question, many items" workloads such as the
 * RAG filter and the reranker. Every state is padded to the longest one in the pass, so
 * keep calls small on a CPU host (see {@link LayaTypeSafeClient#systemOneAll}).</li>
 * <li>{@link #route(JsonContent, Map, boolean)}: which checkpoint would answer, without
 * inference.</li>
 * <li>{@link #health()}: liveness, resident checkpoints and the device they really run on.</li>
 * </ul>
 *
 * Requests reuse the upstream question and state types unchanged. To plug Laya into code
 * written against {@code TypeSafeClient}, wrap this client in {@link LayaTypeSafeClient}.
 *
 * @author Vaquar Khan
 */
public class LayaClient {

	private static final Logger logger = LoggerFactory.getLogger(LayaClient.class);

	/** The server refuses a body above 2 MiB with a 413. */
	public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

	private final RestClient restClient;

	private final JsonMapper jsonMapper;

	private final LayaResponseParser parser;

	private final String defaultModel;

	private final LayaOptions defaultOptions;

	private final LayaLimits limits;

	private final RetryPolicy retryPolicy;

	private final @Nullable Duration perAttemptTimeout;

	private final int rowsPerBatchCall;

	/**
	 * Default rows (states times questions) per batch call when the adapter answers a
	 * {@code systemOneAll}. Every row of a pass is padded to the longest one, so small calls
	 * keep the padding cost bounded; raise it on a GPU, where a larger pass is cheap.
	 */
	public static final int DEFAULT_ROWS_PER_BATCH_CALL = 16;

	/**
	 * Default cap on concurrent batch calls in {@code systemOneAll}. One, because on a CPU
	 * host concurrent calls compete for the same cores the forward pass already uses.
	 */
	public static final int DEFAULT_MAX_CONCURRENT_BATCH_CALLS = 1;

	private final int maxConcurrentBatchCalls;

	/**
	 * Creates a client over a pre-built {@link RestClient}. The rest client must already
	 * point at the server and use {@link LayaResponseErrorHandler}; the builder does both.
	 * @param restClient the transport
	 * @param jsonMapper the mapper used for request and response bodies
	 * @param defaultModel the checkpoint for requests that name none;
	 * {@link LayaCheckpoints#AUTO} lets the server route
	 * @param defaultOptions controls applied to every request unless it overrides them
	 * @param limits the limits checked before sending
	 * @param retryPolicy how transient failures are handled
	 * @param perAttemptTimeout the HTTP timeout of one attempt, counted against the retry
	 * budget; {@code null} when unknown
	 */
	public LayaClient(RestClient restClient, JsonMapper jsonMapper, String defaultModel, LayaOptions defaultOptions,
			LayaLimits limits, RetryPolicy retryPolicy, @Nullable Duration perAttemptTimeout) {
		this(restClient, jsonMapper, defaultModel, defaultOptions, limits, retryPolicy, perAttemptTimeout,
				DEFAULT_ROWS_PER_BATCH_CALL, DEFAULT_MAX_CONCURRENT_BATCH_CALLS);
	}

	/**
	 * Creates a client over a pre-built {@link RestClient}, with an explicit batch size.
	 * @param restClient the transport
	 * @param jsonMapper the mapper used for request and response bodies
	 * @param defaultModel the checkpoint for requests that name none
	 * @param defaultOptions controls applied to every request unless it overrides them
	 * @param limits the limits checked before sending
	 * @param retryPolicy how transient failures are handled
	 * @param perAttemptTimeout the HTTP timeout of one attempt; {@code null} when unknown
	 * @param rowsPerBatchCall the most rows (states times questions) one batch call carries
	 * when {@code systemOneAll} is answered through the batch endpoint
	 * @param maxConcurrentBatchCalls the most batch calls {@code systemOneAll} keeps in
	 * flight at once
	 */
	public LayaClient(RestClient restClient, JsonMapper jsonMapper, String defaultModel, LayaOptions defaultOptions,
			LayaLimits limits, RetryPolicy retryPolicy, @Nullable Duration perAttemptTimeout, int rowsPerBatchCall,
			int maxConcurrentBatchCalls) {
		Assert.isTrue(rowsPerBatchCall >= 1, "rowsPerBatchCall must be at least 1");
		Assert.isTrue(maxConcurrentBatchCalls >= 1, "maxConcurrentBatchCalls must be at least 1");
		this.rowsPerBatchCall = rowsPerBatchCall;
		this.maxConcurrentBatchCalls = maxConcurrentBatchCalls;
		Assert.notNull(restClient, "restClient must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.hasText(defaultModel, "defaultModel must not be empty");
		Assert.notNull(defaultOptions, "defaultOptions must not be null");
		Assert.notNull(limits, "limits must not be null");
		Assert.notNull(retryPolicy, "retryPolicy must not be null");
		this.restClient = restClient;
		this.jsonMapper = jsonMapper;
		this.parser = new LayaResponseParser(jsonMapper);
		this.defaultModel = defaultModel;
		this.defaultOptions = defaultOptions;
		this.limits = limits;
		this.retryPolicy = retryPolicy;
		this.perAttemptTimeout = perAttemptTimeout;
	}

	/**
	 * Answers questions about one state with the client's default model and options.
	 * @param state the state
	 * @param questions the questions, in the order they should be asked
	 * @return the decision
	 */
	public LayaResponse systemOne(JsonContent state, Map<String, ? extends Question> questions) {
		return systemOne(SystemOneRequest.builder().state(state).questions(questions).build(), LayaOptions.none());
	}

	/**
	 * Answers a request with the client's default options.
	 * @param request the request; a {@code null} model means the client default
	 * @return the decision
	 */
	public LayaResponse systemOne(SystemOneRequest request) {
		return systemOne(request, LayaOptions.none());
	}

	/**
	 * Answers a request.
	 * @param request the request; a {@code null} model means the client default
	 * @param options controls that override the client defaults for this request
	 * @return the decision
	 */
	public LayaResponse systemOne(SystemOneRequest request, LayaOptions options) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(options, "options must not be null");
		this.limits.validate(request.questions());
		this.limits.validateState(request.state(), this.jsonMapper);
		String body = serialize(LayaRequestBodies.systemOne(request.state(), modelOf(request.model()),
				request.questions(), this.defaultOptions.mergedWith(options)));
		String endpoint = "POST " + LayaConstants.SYSTEM_ONE_PATH;
		return execute(endpoint, () -> {
			ResponseEntity<String> entity = post(LayaConstants.SYSTEM_ONE_PATH, body);
			return this.parser.decision(entity.getBody(), entity.getHeaders(), endpoint);
		});
	}

	/**
	 * Answers one question set over many states with the default model and options.
	 * @param states the states
	 * @param questions the questions shared by every state
	 * @return one result per state, aligned with the input
	 */
	public LayaBatchResponse systemOneBatch(List<JsonContent> states, Map<String, ? extends Question> questions) {
		return systemOneBatch(states, questions, null, LayaOptions.none());
	}

	/**
	 * Answers one question set over many states. Batches above
	 * {@link LayaLimits#maxStatesPerBatch()} are split into consecutive calls; the results
	 * stay aligned with the input and the usage is summed. A call that fails fails the whole
	 * batch: Laya's batch endpoint is all-or-nothing.
	 * @param states the states
	 * @param questions the questions shared by every state
	 * @param model the checkpoint, or {@code null} for the client default
	 * @param options controls that override the client defaults
	 * @return one result per state, aligned with the input
	 */
	public LayaBatchResponse systemOneBatch(List<JsonContent> states, Map<String, ? extends Question> questions,
			@Nullable String model, LayaOptions options) {
		Assert.notNull(states, "states must not be null");
		Assert.noNullElements(states.toArray(), "states must not contain null");
		Assert.notEmpty(questions, "questions must declare at least one question");
		Assert.notNull(options, "options must not be null");
		if (states.isEmpty()) {
			return new LayaBatchResponse(List.of(), LayaUsage.EMPTY, 0);
		}
		Map<String, Question> ordered = new LinkedHashMap<>(questions);
		this.limits.validate(ordered);
		states.forEach(state -> this.limits.validateState(state, this.jsonMapper));
		LayaOptions effective = this.defaultOptions.mergedWith(options);
		String resolvedModel = modelOf(model);
		String endpoint = "POST " + LayaConstants.BATCH_PATH;

		List<LayaResponse> results = new ArrayList<>(states.size());
		LayaUsage total = LayaUsage.EMPTY;
		int calls = 0;
		for (int from = 0; from < states.size(); from += this.limits.maxStatesPerBatch()) {
			List<JsonContent> chunk = states.subList(from, Math.min(states.size(), from + this.limits.maxStatesPerBatch()));
			String body = serialize(LayaRequestBodies.batch(chunk, resolvedModel, ordered, effective));
			int expected = chunk.size();
			LayaBatchResponse part = execute(endpoint, () -> {
				ResponseEntity<String> entity = post(LayaConstants.BATCH_PATH, body);
				return this.parser.batch(entity.getBody(), entity.getHeaders(), endpoint, expected);
			});
			results.addAll(part.results());
			total = total.plus(part.totalUsage());
			calls++;
		}
		return new LayaBatchResponse(results, total, calls);
	}

	/**
	 * Asks which checkpoint would answer, without running inference or loading a checkpoint.
	 * @param state the state
	 * @param questions the questions; may be empty
	 * @param explain whether to include the routing diagnostic
	 * @return the routing decision
	 */
	public LayaRouting route(JsonContent state, Map<String, ? extends Question> questions, boolean explain) {
		Assert.notNull(state, "state must not be null");
		Assert.notNull(questions, "questions must not be null");
		String body = serialize(LayaRequestBodies.route(state, this.defaultModel, new LinkedHashMap<>(questions),
				this.defaultOptions, explain));
		String endpoint = "POST " + LayaConstants.ROUTE_PATH;
		return execute(endpoint, () -> {
			ResponseEntity<String> entity = post(LayaConstants.ROUTE_PATH, body);
			return this.parser.route(entity.getBody(), entity.getHeaders(), endpoint);
		});
	}

	/**
	 * Reads {@code GET /health}. Not retried: a health probe should report what it sees.
	 * @return the health report
	 */
	public LayaHealth health() {
		String endpoint = "GET " + LayaConstants.HEALTH_PATH;
		return translate(endpoint, () -> {
			ResponseEntity<String> entity = this.restClient.get()
				.uri(LayaConstants.HEALTH_PATH)
				.retrieve()
				.toEntity(String.class);
			return this.parser.health(entity.getBody(), entity.getHeaders(), endpoint);
		});
	}

	/**
	 * @return the checkpoint applied to requests that name none
	 */
	public String defaultModel() {
		return this.defaultModel;
	}

	/**
	 * @return the controls applied to every request
	 */
	public LayaOptions defaultOptions() {
		return this.defaultOptions;
	}

	/**
	 * @return the limits checked before sending
	 */
	public LayaLimits limits() {
		return this.limits;
	}

	/**
	 * @return the retry policy in force
	 */
	public RetryPolicy retryPolicy() {
		return this.retryPolicy;
	}

	/**
	 * @return the per-attempt timeout, or {@code null} when unknown
	 */
	public @Nullable Duration timeout() {
		return this.perAttemptTimeout;
	}

	/**
	 * @return the most rows (states times questions) one batch call carries in
	 * {@code systemOneAll}
	 */
	public int rowsPerBatchCall() {
		return this.rowsPerBatchCall;
	}

	/**
	 * @return the most batch calls {@code systemOneAll} keeps in flight at once
	 */
	public int maxConcurrentBatchCalls() {
		return this.maxConcurrentBatchCalls;
	}

	/**
	 * @return the mapper used for bodies
	 */
	public JsonMapper jsonMapper() {
		return this.jsonMapper;
	}

	private @Nullable String modelOf(@Nullable String requested) {
		String model = StringUtils.hasText(requested) ? requested : this.defaultModel;
		return LayaCheckpoints.isAuto(model) ? null : model;
	}

	private ResponseEntity<String> post(String path, String body) {
		return this.restClient.post()
			.uri(path)
			.contentType(MediaType.APPLICATION_JSON)
			.body(body)
			.retrieve()
			.toEntity(String.class);
	}

	private String serialize(Object body) {
		String json = this.jsonMapper.writeValueAsString(body);
		int bytes = json.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > MAX_BODY_BYTES) {
			throw new LayaLimitExceededException("request-body",
					"Laya accepts a request body of at most 2 MiB, this one is %d bytes".formatted(bytes));
		}
		return json;
	}

	/**
	 * Runs one call, retrying while the policy allows it and the budget can absorb another
	 * attempt. The same loop as {@code TypeSafeClient}, so the same {@link RetryPolicy}
	 * behaves the same way against either server.
	 */
	private <T> T execute(String endpoint, Supplier<T> call) {
		long startedAt = System.nanoTime();
		int attempt = 0;
		while (true) {
			attempt++;
			TypeSafeException failure;
			try {
				return translate(endpoint, call);
			}
			catch (LayaLimitExceededException ex) {
				throw ex;
			}
			catch (TypeSafeException ex) {
				failure = ex;
			}
			if (attempt > this.retryPolicy.maxRetries() || !this.retryPolicy.isRetryable(failure)) {
				throw failure;
			}
			Duration backoff = this.retryPolicy.backoffFor(attempt, failure);
			if (exceedsBudget(startedAt, backoff)) {
				logger.debug("Laya {} giving up after attempt {}: the retry budget cannot absorb another {}ms wait",
						endpoint, attempt, backoff.toMillis());
				throw failure;
			}
			logger.debug("Laya {} attempt {} failed ({}), retrying in {}ms", endpoint, attempt, failure.getMessage(),
					backoff.toMillis());
			sleep(backoff, failure);
		}
	}

	private <T> T translate(String endpoint, Supplier<T> call) {
		try {
			return call.get();
		}
		catch (TypeSafeException ex) {
			throw ex;
		}
		catch (ResourceAccessException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof SocketTimeoutException || cause instanceof HttpConnectTimeoutException
					|| cause instanceof HttpTimeoutException) {
				throw new TypeSafeApiTimeoutException("Laya %s timed out".formatted(endpoint), this.perAttemptTimeout,
						ex);
			}
			throw new TypeSafeApiConnectionException("Laya %s failed without an HTTP response".formatted(endpoint), ex);
		}
		catch (RestClientException ex) {
			throw new TypeSafeException("Laya %s response could not be read: %s".formatted(endpoint, ex.getMessage()),
					ex);
		}
	}

	private boolean exceedsBudget(long startedAt, Duration backoff) {
		Duration budget = this.retryPolicy.totalTimeout();
		if (budget == null) {
			return false;
		}
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
		Duration nextAttempt = this.perAttemptTimeout == null ? Duration.ZERO : this.perAttemptTimeout;
		return elapsed.plus(backoff).plus(nextAttempt).compareTo(budget) >= 0;
	}

	private static void sleep(Duration backoff, TypeSafeException failure) {
		if (backoff.isZero() || backoff.isNegative()) {
			return;
		}
		try {
			Thread.sleep(backoff.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw failure;
		}
	}

	/**
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link LayaClient}. Unset values fall back to {@code LAYA_BASE_URL} and
	 * {@code LAYA_API_KEY}, then to {@code http://localhost:8000} and no key.
	 */
	public static final class Builder {

		private @Nullable String baseUrl;

		private @Nullable Supplier<@Nullable String> apiKey;

		private String defaultModel = LayaCheckpoints.AUTO;

		private LayaOptions defaultOptions = LayaOptions.none();

		private LayaLimits limits = LayaLimits.defaults();

		private RetryPolicy retryPolicy = LayaConstants.defaultRetryPolicy();

		private @Nullable Duration timeout;

		private final HttpHeaders headers = new HttpHeaders();

		private RestClient.@Nullable Builder restClientBuilder;

		private @Nullable JsonMapper jsonMapper;

		private int rowsPerBatchCall = DEFAULT_ROWS_PER_BATCH_CALL;

		private int maxConcurrentBatchCalls = DEFAULT_MAX_CONCURRENT_BATCH_CALLS;

		private Builder() {
		}

		/**
		 * Caps the batch calls {@code systemOneAll} keeps in flight; the caller's
		 * {@code JevBatchOptions.concurrency()} can lower it further. Raise it for a GPU or a
		 * deployment with several replicas behind a load balancer.
		 * @param maxConcurrentBatchCalls calls in flight, at least 1
		 * @return this builder
		 */
		public Builder maxConcurrentBatchCalls(int maxConcurrentBatchCalls) {
			Assert.isTrue(maxConcurrentBatchCalls >= 1, "maxConcurrentBatchCalls must be at least 1");
			this.maxConcurrentBatchCalls = maxConcurrentBatchCalls;
			return this;
		}

		/**
		 * Caps the rows (states times questions) one batch call carries when
		 * {@code systemOneAll} goes through the batch endpoint. Chunks run concurrently up to
		 * the batch options' concurrency. Raise it on a GPU, where one larger pass is cheap.
		 * @param rowsPerBatchCall rows per call, at least 1
		 * @return this builder
		 */
		public Builder rowsPerBatchCall(int rowsPerBatchCall) {
			Assert.isTrue(rowsPerBatchCall >= 1, "rowsPerBatchCall must be at least 1");
			this.rowsPerBatchCall = rowsPerBatchCall;
			return this;
		}

		public Builder baseUrl(String baseUrl) {
			Assert.hasText(baseUrl, "baseUrl must not be empty");
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * Sets the bearer key. Optional: a server without {@code LAYA_API_KEY} needs none, and
		 * then no {@code Authorization} header is sent at all.
		 * @param apiKey the key
		 * @return this builder
		 */
		public Builder apiKey(@Nullable String apiKey) {
			return apiKey(() -> apiKey);
		}

		/**
		 * Supplies the bearer key per request, so a rotated key takes effect without
		 * rebuilding. A blank value sends no header.
		 * @param apiKey the key supplier
		 * @return this builder
		 */
		public Builder apiKey(Supplier<@Nullable String> apiKey) {
			Assert.notNull(apiKey, "apiKey must not be null");
			this.apiKey = apiKey;
			return this;
		}

		/**
		 * @param defaultModel a checkpoint name, or {@link LayaCheckpoints#AUTO}
		 * @return this builder
		 */
		public Builder defaultModel(String defaultModel) {
			Assert.hasText(defaultModel, "defaultModel must not be empty");
			this.defaultModel = defaultModel;
			return this;
		}

		public Builder defaultOptions(LayaOptions defaultOptions) {
			Assert.notNull(defaultOptions, "defaultOptions must not be null");
			this.defaultOptions = defaultOptions;
			return this;
		}

		public Builder limits(LayaLimits limits) {
			Assert.notNull(limits, "limits must not be null");
			this.limits = limits;
			return this;
		}

		public Builder retryPolicy(RetryPolicy retryPolicy) {
			Assert.notNull(retryPolicy, "retryPolicy must not be null");
			this.retryPolicy = retryPolicy;
			return this;
		}

		/**
		 * Sets the per-attempt HTTP timeout; {@link LayaConstants#DEFAULT_TIMEOUT} when the
		 * client builds its own transport. With {@link #restClientBuilder}, declare the
		 * transport's real timeout here so the retry budget can count it.
		 * @param timeout the timeout
		 * @return this builder
		 */
		public Builder timeout(Duration timeout) {
			Assert.notNull(timeout, "timeout must not be null");
			this.timeout = timeout;
			return this;
		}

		public Builder headers(HttpHeaders headers) {
			Assert.notNull(headers, "headers must not be null");
			this.headers.addAll(headers);
			return this;
		}

		/**
		 * Supplies the transport; cloned, never mutated. Also the hook a test uses to bind
		 * a {@code MockRestServiceServer}.
		 * @param restClientBuilder the builder
		 * @return this builder
		 */
		public Builder restClientBuilder(RestClient.Builder restClientBuilder) {
			Assert.notNull(restClientBuilder, "restClientBuilder must not be null");
			this.restClientBuilder = restClientBuilder;
			return this;
		}

		public Builder jsonMapper(JsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "jsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		public LayaClient build() {
			String url = firstNonBlank(this.baseUrl, System.getenv(LayaConstants.BASE_URL_ENV),
					LayaConstants.DEFAULT_BASE_URL);
			Supplier<@Nullable String> key = this.apiKey;
			if (key == null) {
				String fromEnv = System.getenv(LayaConstants.API_KEY_ENV);
				key = () -> fromEnv;
			}
			Supplier<@Nullable String> bearer = key;

			Duration perAttemptTimeout = this.timeout;
			RestClient.Builder transport = this.restClientBuilder;
			if (transport == null) {
				perAttemptTimeout = (this.timeout != null) ? this.timeout : LayaConstants.DEFAULT_TIMEOUT;
				JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
				requestFactory.setReadTimeout(perAttemptTimeout);
				transport = RestClient.builder().requestFactory(requestFactory);
			}
			HttpHeaders headersCopy = new HttpHeaders();
			headersCopy.addAll(this.headers);

			RestClient restClient = transport.clone()
				.baseUrl(url)
				.defaultHeaders(h -> {
					h.addAll(HttpHeaders.readOnlyHttpHeaders(headersCopy));
					h.setAccept(List.of(MediaType.APPLICATION_JSON));
				})
				.defaultRequest(request -> request.headers(h -> {
					String value = bearer.get();
					if (StringUtils.hasText(value)) {
						h.setBearerAuth(value);
					}
					else {
						h.remove(HttpHeaders.AUTHORIZATION);
					}
				}))
				.defaultStatusHandler(new LayaResponseErrorHandler())
				.build();

			JsonMapper mapper = (this.jsonMapper != null) ? this.jsonMapper : JsonMapper.builder().build();
			return new LayaClient(restClient, mapper, this.defaultModel, this.defaultOptions, this.limits,
					this.retryPolicy, perAttemptTimeout, this.rowsPerBatchCall, this.maxConcurrentBatchCalls);
		}

		private static String firstNonBlank(@Nullable String... candidates) {
			for (String candidate : candidates) {
				if (StringUtils.hasText(candidate)) {
					return candidate;
				}
			}
			throw new IllegalStateException("No non-blank candidate available");
		}

	}

}
