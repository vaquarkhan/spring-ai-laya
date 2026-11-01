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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.JevBatchOptions;
import org.springaicommunity.typesafe.JevBatchResult;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import io.github.vaquarkhan.springailaya.exception.LayaLimitExceededException;
import io.github.vaquarkhan.springailaya.response.LayaBatchResponse;
import io.github.vaquarkhan.springailaya.response.LayaResponse;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.SystemOneRequest;

/**
 * Turns the upstream {@code systemOneAll} contract (independent requests, results aligned
 * with the input, per-slot failures) into Laya batch calls.
 *
 * <p>
 * Requests that share a model and an identical question set are grouped. Each group is cut
 * into chunks of at most {@link LayaClient#rowsPerBatchCall()} rows (states times
 * questions), and each chunk goes through {@code POST /v1/systemone/batch}, where the server
 * shares forward passes across its states. Chunks run up to
 * {@code min(JevBatchOptions.concurrency(), LayaClient.maxConcurrentBatchCalls())} at a
 * time. The client cap defaults to 1: on a CPU host the forward passes already use the
 * server's threads, and in two of three measurements concurrent calls were slower than
 * sequential ones. Raise it for a GPU or a multi-replica deployment. A chunk of one state
 * takes the single endpoint.
 *
 * <p>
 * That is the common shape: {@code JevDocumentFilter} and {@code JevDocumentReranker} ask the
 * same questions of every retrieved passage, and {@code JevConsistency} asks the same
 * questions of every sample.
 *
 * <p>
 * Laya's batch is all-or-nothing, which the upstream contract is not. So when a chunk is
 * refused for something one state could have caused (a 4xx other than 401, 403, 408 or 429,
 * or a client-side limit), its requests are re-sent one by one and only the offending slot
 * fails. A transport failure or a 5xx after retries fails every slot of that chunk with the
 * same exception, as it would have failed each of them individually.
 *
 * <p>
 * {@link JevBatchOptions#failFast()} is best effort. After a chunk fails, chunks that have not
 * started are marked aborted and never sent. Chunks already in flight run to completion,
 * because an HTTP request that has reached the server cannot be recalled. With the default of
 * one call in flight, nothing runs after the first failure.
 *
 * @author Vaquar Khan
 */
final class LayaBatching {

	private static final Logger logger = LoggerFactory.getLogger(LayaBatching.class);

	private LayaBatching() {
	}

	/** A slice of one group, answerable by one call. */
	private record Chunk(GroupKey key, List<Integer> indices) {
	}

	static List<JevBatchResult<LayaResponse>> run(LayaClient laya, List<SystemOneRequest> requests,
			JevBatchOptions options) {
		@SuppressWarnings("unchecked")
		JevBatchResult<LayaResponse>[] slots = new JevBatchResult[requests.size()];
		List<Chunk> chunks = chunks(laya, requests);

		int width = Math.min(Math.min(options.concurrency(), laya.maxConcurrentBatchCalls()), chunks.size());
		ExecutorService owned = (options.executor() == null && width > 1)
				? Executors.newFixedThreadPool(width, threadFactory()) : null;
		Executor executor = (owned != null) ? owned : (options.executor() != null ? options.executor() : Runnable::run);
		java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(Math.max(1, width));
		AtomicBoolean aborted = new AtomicBoolean(false);
		try {
			List<CompletableFuture<Void>> futures = new ArrayList<>(chunks.size());
			for (Chunk chunk : chunks) {
				try {
					futures.add(CompletableFuture.runAsync(() -> {
						permits.acquireUninterruptibly();
						try {
							if (options.failFast() && aborted.get()) {
								chunk.indices().forEach(i -> slots[i] = abortedResult(i));
								return;
							}
							boolean failed = (chunk.indices().size() == 1)
									? runSingle(laya, requests, chunk.indices().get(0), slots)
									: runChunk(laya, requests, chunk, slots);
							if (failed) {
								aborted.set(true);
							}
						}
						finally {
							permits.release();
						}
					}, executor));
				}
				catch (RejectedExecutionException ex) {
					aborted.set(true);
					chunk.indices()
						.forEach(i -> slots[i] = new JevBatchResult<>(i, null,
								new TypeSafeException("Request %d was rejected by the batch executor".formatted(i), ex)));
				}
			}
			futures.forEach(CompletableFuture::join);
		}
		finally {
			if (owned != null) {
				owned.shutdown();
			}
		}
		return Arrays.asList(slots);
	}

	private static List<Chunk> chunks(LayaClient laya, List<SystemOneRequest> requests) {
		Map<GroupKey, List<Integer>> groups = new LinkedHashMap<>();
		for (int i = 0; i < requests.size(); i++) {
			groups.computeIfAbsent(GroupKey.of(requests.get(i), laya.defaultModel()), key -> new ArrayList<>()).add(i);
		}
		List<Chunk> chunks = new ArrayList<>();
		groups.forEach((key, unsorted) -> {
			// Every row of a pass is padded to the longest one, so chunk states of similar
			// length together. Results are written back by index, so order is unaffected.
			List<Integer> indices = new ArrayList<>(unsorted);
			indices.sort(java.util.Comparator.comparingInt(i -> approximateLength(requests.get(i).state())));
			int statesPerCall = Math.max(1, laya.rowsPerBatchCall() / Math.max(1, key.questions().size()));
			statesPerCall = Math.min(statesPerCall, laya.limits().maxStatesPerBatch());
			for (int from = 0; from < indices.size(); from += statesPerCall) {
				chunks.add(new Chunk(key, List.copyOf(indices.subList(from, Math.min(indices.size(), from + statesPerCall)))));
			}
		});
		return chunks;
	}

	private static int approximateLength(JsonContent state) {
		Object value = state.value();
		return value == null ? 0 : value instanceof String text ? text.length() : String.valueOf(value).length();
	}

	/** @return {@code true} when the slot failed */
	private static boolean runSingle(LayaClient laya, List<SystemOneRequest> requests, int index,
			JevBatchResult<LayaResponse>[] slots) {
		try {
			slots[index] = new JevBatchResult<>(index, laya.systemOne(requests.get(index)), null);
			return false;
		}
		catch (TypeSafeException ex) {
			slots[index] = new JevBatchResult<>(index, null, ex);
			return true;
		}
		catch (RuntimeException ex) {
			slots[index] = new JevBatchResult<>(index, null,
					new TypeSafeException("Request %d failed: %s".formatted(index, ex.getMessage()), ex));
			return true;
		}
	}

	/** @return {@code true} when any slot failed */
	private static boolean runChunk(LayaClient laya, List<SystemOneRequest> requests, Chunk chunk,
			JevBatchResult<LayaResponse>[] slots) {
		List<Integer> indices = chunk.indices();
		List<JsonContent> states = indices.stream().map(i -> requests.get(i).state()).toList();
		try {
			LayaBatchResponse batch = laya.systemOneBatch(states, chunk.key().questions(), chunk.key().model(),
					LayaOptions.none());
			for (int j = 0; j < indices.size(); j++) {
				int index = indices.get(j);
				slots[index] = new JevBatchResult<>(index, batch.results().get(j), null);
			}
			return false;
		}
		catch (TypeSafeException ex) {
			if (isolatable(ex)) {
				logger.debug("Laya batch of {} refused ({}); re-sending one by one to isolate the failure",
						indices.size(), ex.getMessage());
				boolean failed = false;
				for (int index : indices.stream().sorted().toList()) {
					failed |= runSingle(laya, requests, index, slots);
				}
				return failed;
			}
			indices.forEach(index -> slots[index] = new JevBatchResult<>(index, null, ex));
			return true;
		}
		catch (RuntimeException ex) {
			indices.forEach(index -> slots[index] = new JevBatchResult<>(index, null,
					new TypeSafeException("Request %d failed: %s".formatted(index, ex.getMessage()), ex)));
			return true;
		}
	}

	private static boolean isolatable(TypeSafeException ex) {
		if (ex instanceof LayaLimitExceededException) {
			return true;
		}
		if (ex instanceof TypeSafeApiException api) {
			int status = api.status();
			return status >= 400 && status < 500 && status != 408 && status != 429 && status != 401
					&& status != 403;
		}
		return false;
	}

	private static JevBatchResult<LayaResponse> abortedResult(int index) {
		return new JevBatchResult<>(index, null,
				new TypeSafeException("Batch aborted before this request ran because failFast is set"));
	}

	private static java.util.concurrent.ThreadFactory threadFactory() {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, "laya-batch-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	/**
	 * Requests in one group must be answerable by one batch call: same model, same
	 * questions in the same order.
	 */
	record GroupKey(@Nullable String model, Map<String, Question> questions, List<String> order) {

		static GroupKey of(SystemOneRequest request, String defaultModel) {
			String model = (request.model() == null || request.model().isBlank()) ? defaultModel : request.model();
			String normalized = LayaCheckpoints.isAuto(model) ? null : model.trim();
			return new GroupKey(normalized, request.questions(), List.copyOf(request.questions().keySet()));
		}

	}

}
