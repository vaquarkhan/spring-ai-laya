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

import java.util.Collection;
import java.util.Map;

/**
 * The confidence formulas Laya documents, computed client-side from an answer's
 * probabilities. Used when a server in strict Jev mode ({@code LAYA_JEV_STRICT=1}) drops the
 * extra fields, and by the benchmarks to compare metrics on the same answers.
 *
 * @author Vaquar Khan
 */
public final class LayaConfidence {

	private LayaConfidence() {
	}

	/**
	 * Jev's choice confidence: {@code (p_max - 1/n) / (1 - 1/n)}, clamped to [0, 1].
	 * @param probabilities the per-option probabilities
	 * @return the confidence; 1 for a single option, 0 for no options
	 */
	public static double jevChoice(Collection<Double> probabilities) {
		int n = probabilities.size();
		if (n == 0) {
			return 0.0d;
		}
		if (n == 1) {
			return 1.0d;
		}
		double max = probabilities.stream().mapToDouble(Double::doubleValue).max().orElse(0.0d);
		double uniform = 1.0d / n;
		return clamp((max - uniform) / (1.0d - uniform));
	}

	/**
	 * Jev's score confidence: one minus the probability-weighted distance from the most
	 * likely level, divided by the same distance under a uniform distribution.
	 * @param probabilities per-level probabilities keyed by level index
	 * @return the confidence in [0, 1]
	 */
	public static double jevScore(Map<Integer, Double> probabilities) {
		int k = probabilities.size();
		if (k == 0) {
			return 0.0d;
		}
		if (k == 1) {
			return 1.0d;
		}
		int mode = probabilities.entrySet()
			.stream()
			.max(Map.Entry.comparingByValue())
			.map(Map.Entry::getKey)
			.orElse(0);
		double weighted = 0.0d;
		double uniform = 0.0d;
		for (Map.Entry<Integer, Double> entry : probabilities.entrySet()) {
			int distance = Math.abs(entry.getKey() - mode);
			weighted += entry.getValue() * distance;
			uniform += distance / (double) k;
		}
		return uniform <= 0.0d ? 1.0d : clamp(1.0d - weighted / uniform);
	}

	/**
	 * Laya's {@code answer_confidence}: the probability mass on the reported answer.
	 * @param probabilities the distribution
	 * @return {@code max(p)}, or 0 when empty
	 */
	public static double answer(Collection<Double> probabilities) {
		return probabilities.stream().mapToDouble(Double::doubleValue).max().orElse(0.0d);
	}

	/**
	 * Laya's native {@code confidence} on choice and score answers: {@code 1 - H(p)/log(n)}.
	 * @param probabilities the distribution
	 * @return the normalised-entropy confidence in [0, 1]
	 */
	public static double entropy(Collection<Double> probabilities) {
		int n = probabilities.size();
		if (n <= 1) {
			return n == 1 ? 1.0d : 0.0d;
		}
		double total = probabilities.stream().mapToDouble(Double::doubleValue).sum();
		if (total <= 0.0d) {
			return 0.0d;
		}
		double entropy = 0.0d;
		for (double p : probabilities) {
			double q = p / total;
			if (q > 0.0d) {
				entropy -= q * Math.log(q);
			}
		}
		return clamp(1.0d - entropy / Math.log(n));
	}

	private static double clamp(double value) {
		return Math.max(0.0d, Math.min(1.0d, value));
	}

}
