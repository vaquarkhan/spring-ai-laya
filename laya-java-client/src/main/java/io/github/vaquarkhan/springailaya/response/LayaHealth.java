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
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * {@code GET /health}. Liveness is always open; on a server with {@code LAYA_API_KEY} set,
 * a caller without the bearer gets only {@code status}, so every other field is nullable or
 * empty.
 *
 * @param status {@code ok} whenever the process answers; says nothing about checkpoints
 * @param loaded checkpoints resident in memory; empty after an idle unload
 * @param revisions artifact revision per resident checkpoint
 * @param device the device a resident checkpoint really computes on
 * @param deviceIsPreference {@code true} while nothing is resident and {@code device} is
 * only the configured preference
 * @param checkpointDevices measured device per checkpoint
 * @param cpuFallbacks per checkpoint, requests retried on CPU after GPU memory ran out
 * @author Vaquar Khan
 */
public record LayaHealth(String status, List<String> loaded, Map<String, String> revisions, @Nullable String device,
		@Nullable Boolean deviceIsPreference, Map<String, String> checkpointDevices,
		Map<String, Object> cpuFallbacks) {

	public LayaHealth {
		loaded = loaded == null ? List.of() : List.copyOf(loaded);
		revisions = revisions == null ? Map.of() : Map.copyOf(revisions);
		checkpointDevices = checkpointDevices == null ? Map.of() : Map.copyOf(checkpointDevices);
		cpuFallbacks = cpuFallbacks == null ? Map.of() : Map.copyOf(cpuFallbacks);
	}

	/**
	 * @return {@code true} when the process answered {@code ok}
	 */
	public boolean isUp() {
		return "ok".equalsIgnoreCase(this.status);
	}

	/**
	 * @return {@code true} when the server reported detail beyond liveness, i.e. the caller
	 * was authorised or the server has no key
	 */
	public boolean isDetailed() {
		return this.device != null || !this.loaded.isEmpty();
	}

}
