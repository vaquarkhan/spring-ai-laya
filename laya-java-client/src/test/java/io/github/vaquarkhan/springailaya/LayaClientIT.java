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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springaicommunity.typesafe.JevBatchOptions;
import org.springaicommunity.typesafe.JevBatchResult;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.exception.TypeSafeUnprocessableEntityException;
import io.github.vaquarkhan.springailaya.exception.LayaPayloadTooLargeException;
import io.github.vaquarkhan.springailaya.response.LayaAbstention;
import io.github.vaquarkhan.springailaya.response.LayaBatchResponse;
import io.github.vaquarkhan.springailaya.response.LayaHealth;
import io.github.vaquarkhan.springailaya.response.LayaResponse;
import io.github.vaquarkhan.springailaya.response.LayaRouting;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Every endpoint and field against a live laya-serve.
 *
 * <pre>
 * LAYA_BASE_URL=http://localhost:8002 mvn -Pintegration-tests verify
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = LayaConstants.BASE_URL_ENV, matches = ".+")
class LayaClientIT {

	private static LayaClient laya;

	private static Map<String, Question> questions() {
		return SystemOneRequest.builder()
			.state("x")
			.question("dept", LayaFixtures.dept())
			.question("urgency", LayaFixtures.urgency())
			.question("churn", LayaFixtures.churn())
			.build()
			.questions();
	}

	@BeforeAll
	static void connect() {
		laya = LayaClient.builder().build();
	}

	@Test
	void health() {
		LayaHealth health = laya.health();
		assertThat(health.isUp()).isTrue();
		assertThat(health.loaded()).isNotEmpty();
	}

	@Test
	void singleDecisionCarriesEveryLayaField() {
		LayaResponse response = laya.systemOne(JsonContent.object("body", "I was billed twice, refund me or I cancel"),
				questions());

		assertThat(response.response().choiceValue("dept")).isEqualTo("billing");
		assertThat(response.detailsOf("dept").answerConfidence()).isNotNull();
		assertThat(response.detailsOf("dept").jevConfidence()).isNotNull();
		assertThat(response.routing()).isNotNull();
		assertThat(response.inferenceTimeMs()).isNotNull();
		// The client-side formula agrees with the server's own x_jev_confidence.
		assertThat(LayaConfidence.jevChoice(response.response().choice("dept").probabilities().values()))
			.isCloseTo(response.detailsOf("dept").jevConfidence(), within(2e-4));
		assertThat(LayaConfidence.jevScore(response.response().score("urgency").probabilities()))
			.isCloseTo(response.detailsOf("urgency").jevConfidence(), within(5e-4));
	}

	@Test
	void abstentionGateIsReported() {
		LayaResponse response = laya.systemOne(
				SystemOneRequest.builder().state("billed twice").questions(questions()).build(),
				LayaOptions.builder().minConfidence(0.99).build());

		assertThat(response.details().values()).allSatisfy(d -> assertThat(d.abstention()).isNotNull());
		assertThat(response.details().values()).anyMatch(d -> d.abstention() == LayaAbstention.ABSTAINED);
		assertThat(response.anyAbstained()).isTrue();
	}

	@Test
	void batchMatchesSingleCallsAnswerForAnswer() {
		List<JsonContent> states = List.of(JsonContent.object("body", "billed twice, refund please"),
				JsonContent.object("body", "cannot login, the app shows error 500"));
		Map<String, Question> dept = Map.of("dept", LayaFixtures.dept());

		LayaBatchResponse batch = laya.systemOneBatch(states, dept);

		assertThat(batch.results()).hasSize(2);
		assertThat(batch.results().get(0).response().choiceValue("dept")).isEqualTo("billing");
		assertThat(batch.results().get(1).response().choiceValue("dept")).isEqualTo("tech");
		for (int i = 0; i < states.size(); i++) {
			LayaResponse single = laya.systemOne(states.get(i), dept);
			assertThat(batch.results().get(i).response().choice("dept").probabilityOf("billing"))
				.isCloseTo(single.response().choice("dept").probabilityOf("billing"), within(0.02));
		}
	}

	@Test
	void adapterBatchesThroughTheBatchEndpoint() {
		LayaTypeSafeClient client = new LayaTypeSafeClient(laya);
		List<SystemOneRequest> requests = new ArrayList<>();
		for (String body : List.of("refund my double charge", "the login page crashes", "invoice is wrong")) {
			requests.add(SystemOneRequest.builder()
				.state(JsonContent.object("body", body))
				.model(client.defaultModel())
				.question("dept", LayaFixtures.dept())
				.build());
		}

		List<JevBatchResult<SystemOneResponse>> results = client.systemOneAll(requests, JevBatchOptions.defaults());

		assertThat(results).allMatch(JevBatchResult::succeeded);
		assertThat(results).extracting(r -> r.orThrow().choiceValue("dept")).containsExactly("billing", "tech", "billing");
	}

	@Test
	void routeSnapshot() {
		LayaRouting routing = laya.route(JsonContent.object("message", "Turn the lights off please"), Map.of(), true);
		assertThat(routing.model()).isNotBlank();
		assertThat(routing.reason()).isNotBlank();
	}

	@Test
	void serverRefusalsMapToTypedExceptions() {
		Choice.Builder wide = Choice.builder().instructions("pick");
		for (int i = 0; i < 101; i++) {
			wide.option("o" + i, "option " + i);
		}
		LayaClient unchecked = LayaClient.builder().limits(new LayaLimits(64, 64, 255, 32, 512, 50_000, 20)).build();
		assertThatThrownBy(() -> unchecked.systemOne(JsonContent.of("x"), Map.of("c", wide.build())))
			.isInstanceOf(LayaPayloadTooLargeException.class);
		assertThatThrownBy(() -> laya.systemOne(
				SystemOneRequest.builder().state("x").model("org/repo").questions(questions()).build()))
			.isInstanceOf(TypeSafeUnprocessableEntityException.class);
	}

	@Test
	void escalationRoutesLowConfidenceToTheFallback() {
		// A second Laya client stands in for Jev so the routing runs end to end without a
		// Jev key; with one, pass a TypeSafeClient instead.
		LayaTypeSafeClient fallback = new LayaTypeSafeClient(LayaClient.builder().defaultModel("english").build());
		LayaEscalatingClient client = LayaEscalatingClient.builder(laya, fallback).minAnswerConfidence(0.999).build();

		client.systemOne(SystemOneRequest.builder().state("hmm").question("dept", LayaFixtures.dept()).build());

		assertThat(client.stats().get(EscalationReason.LOW_CONFIDENCE)).isEqualTo(1L);
	}

}
