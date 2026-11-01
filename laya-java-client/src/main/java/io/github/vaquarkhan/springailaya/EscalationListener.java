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

import org.jspecify.annotations.Nullable;
import io.github.vaquarkhan.springailaya.response.LayaResponse;
import org.springaicommunity.typesafe.question.SystemOneRequest;

/**
 * Observes every routing decision {@link LayaEscalatingClient} makes, for metrics or audit.
 * Called on the calling thread; keep it cheap and do not throw.
 *
 * @author Vaquar Khan
 */
@FunctionalInterface
public interface EscalationListener {

	/** A listener that does nothing. */
	EscalationListener NONE = (request, reason, laya, failure) -> {
	};

	/**
	 * @param request the request as the caller sent it
	 * @param reason where it went and why
	 * @param laya Laya's answer when Laya answered, even if it was then escalated
	 * @param failure Laya's failure when it failed
	 */
	void onDecision(SystemOneRequest request, EscalationReason reason, @Nullable LayaResponse laya,
			@Nullable Throwable failure);

}
