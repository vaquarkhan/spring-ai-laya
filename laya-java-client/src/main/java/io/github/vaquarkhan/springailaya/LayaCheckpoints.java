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

/**
 * Checkpoint names {@code laya-serve} honours in a request's {@code model} field.
 *
 * <p>
 * Any other value that is not a path or a Hub id, such as {@code jev-latest}, already means
 * "let the router choose" on the server. The SDK goes one step further for {@link #AUTO}: it
 * never puts it on the wire, so the deployment's own routing and {@code LAYA_DEFAULT_MODEL}
 * stay in charge and the server never has to guess what the client meant.
 *
 * @author Vaquar Khan
 */
public final class LayaCheckpoints {

	/** Let the server's router pick per request. Never sent; the field is omitted. */
	public static final String AUTO = "auto";

	/** ModernBERT-large, 421M parameters, 512-token context. English text. */
	public static final String ENGLISH = "english";

	/** mmBERT-base, 322M parameters. 100+ languages. */
	public static final String MULTILINGUAL = "multilingual";

	/** ModernBERT-large fine-tuned for the typed-decisions workflows. */
	public static final String TYPED_DECISIONS = "typed-decisions";

	private LayaCheckpoints() {
	}

	/**
	 * @param model a model name from a request or a configuration property
	 * @return {@code true} when the name means "let the server route", so it must be omitted
	 */
	public static boolean isAuto(@Nullable String model) {
		return model == null || model.isBlank() || AUTO.equalsIgnoreCase(model.trim());
	}

}
