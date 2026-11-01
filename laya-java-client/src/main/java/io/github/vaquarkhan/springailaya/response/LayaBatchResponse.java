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

/**
 * The answer to {@code POST /v1/systemone/batch}: one result per state, in the order the
 * states were sent, and the usage summed over every forward pass. When the client split a
 * large batch into several calls, the results are concatenated and the usage summed.
 *
 * @param results one decision per state, aligned with the input
 * @param totalUsage the usage summed over the batch
 * @param calls how many HTTP calls produced this batch
 * @author Vaquar Khan
 */
public record LayaBatchResponse(List<LayaResponse> results, LayaUsage totalUsage, int calls) {

	public LayaBatchResponse {
		results = results == null ? List.of() : List.copyOf(results);
		totalUsage = totalUsage == null ? LayaUsage.EMPTY : totalUsage;
	}

}
