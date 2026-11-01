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

package io.github.vaquarkhan.springailaya.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Question;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a receipt proving a Laya/Jev quality comparison used the same labelled examples and
 * the same ordered question payload. It deliberately excludes the model field and transport
 * shape: those belong to the backend, while state, labels, instructions, option descriptions
 * and option order must be identical for a fair decision-quality comparison.
 */
final class ParityReceipt {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private ParityReceipt() {
	}

	static Map<String, Object> forDataset(Datasets.Dataset dataset, Choice question, String layaModel,
			String jevModel) {
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("rule", "Same labelled state, same question id, same type, same instructions, same criteria text, same criteria order; backend-native model selection and batch transport may differ.");
		receipt.put("examples", dataset.examples().size());
		receipt.put("state_label_sha256", hash(dataset.examples()));
		receipt.put("question_sha256", hash(Map.of("label", question)));
		receipt.put("question_id", "label");
		receipt.put("question_type", "choice");
		receipt.put("option_count", dataset.options().size());
		receipt.put("laya_model", layaModel);
		receipt.put("jev_model", jevModel);
		receipt.put("laya_batching", "server-native batch endpoint; preserves input order");
		receipt.put("jev_batching", "TypeSafeClient systemOneAll, concurrency 4; preserves input order");
		return receipt;
	}

	static void requireSameInputCount(String dataset, List<?> laya, List<?> jev, int expected) {
		if (laya.size() != expected || jev.size() != expected) {
			throw new IllegalStateException("Parity run for %s is incomplete: expected %d decisions, Laya answered %d, Jev answered %d"
					.formatted(dataset, expected, laya.size(), jev.size()));
		}
	}

	private static String hash(Object value) {
		try {
			byte[] bytes = JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
