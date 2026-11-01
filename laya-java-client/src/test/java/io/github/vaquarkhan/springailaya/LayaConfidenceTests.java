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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The client-side formulas reproduce what laya-serve 0.4.19 sent for the same
 * probabilities (values from a captured response).
 */
class LayaConfidenceTests {

	@Test
	void choiceFormulasMatchTheServer() {
		List<Double> p = List.of(0.9607, 0.0393);
		assertThat(LayaConfidence.jevChoice(p)).isCloseTo(0.9214, within(1e-4));
		assertThat(LayaConfidence.answer(p)).isEqualTo(0.9607);
		assertThat(LayaConfidence.entropy(p)).isCloseTo(0.7607, within(5e-4));
	}

	@Test
	void scoreFormulasMatchTheServer() {
		Map<Integer, Double> p = Map.of(0, 0.0267, 1, 0.7149, 2, 0.2584);
		assertThat(LayaConfidence.jevScore(p)).isCloseTo(0.5723, within(2e-4));
		assertThat(LayaConfidence.entropy(p.values())).isCloseTo(0.3753, within(5e-4));
	}

	@Test
	void theSameDistributionGetsVeryDifferentNumbers() {
		// Why a Jev threshold cannot be applied to Laya's native confidence: a 0.6 floor
		// passes this answer on Jev's formula and rejects it on Laya's entropy.
		List<Double> p = List.of(0.85, 0.05, 0.05, 0.05);
		assertThat(LayaConfidence.jevChoice(p)).isGreaterThan(0.6);
		assertThat(LayaConfidence.entropy(p)).isLessThan(0.6);
	}

	@Test
	void degenerateDistributions() {
		assertThat(LayaConfidence.jevChoice(List.of())).isZero();
		assertThat(LayaConfidence.jevChoice(List.of(1.0))).isEqualTo(1.0);
		assertThat(LayaConfidence.jevChoice(List.of(0.5, 0.5))).isZero();
		assertThat(LayaConfidence.jevScore(Map.of(0, 1.0))).isEqualTo(1.0);
		assertThat(LayaConfidence.entropy(List.of(0.25, 0.25, 0.25, 0.25))).isCloseTo(0.0, within(1e-12));
	}

}
