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

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Which checkpoint answered and why. Sent on predictions (as {@code routing}) and by the
 * routing snapshot endpoint. Absent under {@code LAYA_JEV_STRICT=1}.
 *
 * @param model the checkpoint that answered or would answer: {@code english},
 * {@code multilingual}, {@code typed-decisions} or a registered extra
 * @param repo where it was loaded from, as {@code repo} or {@code repo/subfolder}
 * @param reason the routing sentence, naming the evidence it acted on. May quote input text
 * @param detection the language analysis, or {@code null} when routing decided without
 * reading the state; the diagnostic on a routing snapshot
 * @param workflow the typed-decisions workflow the question ids matched, or {@code null}
 * @author Vaquar Khan
 */
public record LayaRouting(@Nullable String model, @Nullable String repo, @Nullable String reason,
		@Nullable Map<String, Object> detection, @Nullable String workflow) {

}
