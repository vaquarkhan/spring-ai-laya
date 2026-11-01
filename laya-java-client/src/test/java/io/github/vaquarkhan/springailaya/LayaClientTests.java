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
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.exception.TypeSafeUnprocessableEntityException;
import io.github.vaquarkhan.springailaya.exception.LayaLimitExceededException;
import io.github.vaquarkhan.springailaya.exception.LayaPayloadTooLargeException;
import io.github.vaquarkhan.springailaya.exception.LayaServerBusyException;
import io.github.vaquarkhan.springailaya.response.LayaAbstention;
import io.github.vaquarkhan.springailaya.response.LayaBatchResponse;
import io.github.vaquarkhan.springailaya.response.LayaHealth;
import io.github.vaquarkhan.springailaya.response.LayaResponse;
import io.github.vaquarkhan.springailaya.response.LayaRouting;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.question.SystemOneRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LayaClientTests {

	private static Map<String, Question> threeQuestions() {
		return SystemOneRequest.builder()
			.state("x")
			.question("dept", LayaFixtures.dept())
			.question("urgency", LayaFixtures.urgency())
			.question("churn", LayaFixtures.churn())
			.build()
			.questions();
	}

	@Test
	void sendsNoAuthorizationHeaderWithoutAKeyAndOmitsAutoModel() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
			.andExpect(jsonPath("$.model").doesNotExist())
			.andExpect(jsonPath("$.state.body").value("billed twice"))
			.andExpect(jsonPath("$.questions.dept.type").value("choice"))
			.andRespond(withSuccess(LayaFixtures.SINGLE, MediaType.APPLICATION_JSON));

		laya.client().systemOne(JsonContent.object("body", "billed twice"), threeQuestions());

		laya.server().verify();
	}

	@Test
	void sendsBearerAndPinnedCheckpointWhenConfigured() {
		LayaFixtures.Bound laya = LayaFixtures
			.laya(LayaClient.builder().apiKey("local-test").defaultModel(LayaCheckpoints.ENGLISH));
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer local-test"))
			.andExpect(jsonPath("$.model").value("english"))
			.andRespond(withSuccess(LayaFixtures.SINGLE, MediaType.APPLICATION_JSON));

		laya.client().systemOne(JsonContent.of("billed twice"), threeQuestions());

		laya.server().verify();
	}

	@Test
	void treatsAJevModelNameOnTheRequestAsPassThrough() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(jsonPath("$.model").value("jev-latest"))
			.andRespond(withSuccess(LayaFixtures.SINGLE, MediaType.APPLICATION_JSON));

		laya.client()
			.systemOne(SystemOneRequest.builder().state("x").model("jev-latest").questions(threeQuestions()).build());

		laya.server().verify();
	}

	@Test
	void sendsRoutingAndGatingControls() {
		LayaFixtures.Bound laya = LayaFixtures
			.laya(LayaClient.builder().defaultOptions(LayaOptions.builder().langGuess("en").maxLen(256).build()));
		laya.server()
			.expect(jsonPath("$.lang_guess").value("en"))
			.andExpect(jsonPath("$.max_len").value(256))
			.andExpect(jsonPath("$['min_confidence']['choice:3-5']").value(0.9))
			.andExpect(jsonPath("$.task").doesNotExist())
			.andRespond(withSuccess(LayaFixtures.SINGLE, MediaType.APPLICATION_JSON));

		laya.client()
			.systemOne(SystemOneRequest.builder().state("x").questions(threeQuestions()).build(),
					LayaOptions.builder().minConfidenceBuckets(Map.of("choice:3-5", 0.9, "default", 0.4)).build());

		laya.server().verify();
	}

	@Test
	void readsEveryLayaFieldBesideTheJevAnswers() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-Inference-Time-Ms", "41.5");
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andRespond(withSuccess(LayaFixtures.SINGLE, MediaType.APPLICATION_JSON).headers(headers));

		LayaResponse response = laya.client().systemOne(JsonContent.of("x"), threeQuestions());

		assertThat(response.response().choiceValue("dept")).isEqualTo("billing");
		assertThat(response.response().choice("dept").confidence()).isEqualTo(0.7607);
		assertThat(response.response().scoreValue("urgency")).isEqualTo(1.2318);
		assertThat(response.response().noulValue("churn")).isEqualTo(0.8969);
		assertThat(response.detailsOf("dept").answerConfidence()).isEqualTo(0.9607);
		assertThat(response.detailsOf("dept").jevConfidence()).isEqualTo(0.9214);
		assertThat(response.detailsOf("dept").actProbability()).isEqualTo(1.0);
		assertThat(response.detailsOf("churn").jevConfidence()).isNull();
		assertThat(response.usage().stateTokens()).isEqualTo(13);
		assertThat(response.usage().wasTruncated()).isFalse();
		assertThat(response.routing()).isNotNull();
		assertThat(response.routing().model()).isEqualTo("english");
		assertThat(response.routing().detection()).containsEntry("language", "en");
		assertThat(response.inferenceTimeMs()).isEqualTo(41.5);
		assertThat(response.minAnswerConfidence()).hasValue(0.7149);
	}

	@Test
	void readsAStrictJevPayload() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server().expect(method(HttpMethod.POST)).andRespond(withSuccess(LayaFixtures.STRICT, MediaType.APPLICATION_JSON));

		LayaResponse response = laya.client().systemOne(JsonContent.of("x"), threeQuestions());

		assertThat(response.routing()).isNull();
		assertThat(response.detailsOf("dept").answerConfidence()).isNull();
		assertThat(response.answerConfidence("dept")).hasValue(0.9607);
		assertThat(response.answerConfidence("churn")).hasValue(0.8969);
		assertThat(response.anyAbstained()).isFalse();
	}

	@Test
	void readsTheAbstentionGate() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server().expect(method(HttpMethod.POST)).andRespond(withSuccess(LayaFixtures.GATED, MediaType.APPLICATION_JSON));

		LayaResponse response = laya.client()
			.systemOne(SystemOneRequest.builder()
				.state("x")
				.question("dept", LayaFixtures.dept())
				.question("churn", LayaFixtures.churn())
				.build(), LayaOptions.builder().minConfidence(0.9).build());

		assertThat(response.detailsOf("dept").abstention()).isEqualTo(LayaAbstention.PASSED);
		assertThat(response.detailsOf("churn").abstention()).isEqualTo(LayaAbstention.ABSTAINED);
		assertThat(response.detailsOf("churn").abstentionThreshold()).isEqualTo(0.9);
		assertThat(response.anyAbstained()).isTrue();
	}

	@Test
	void sendsOneQuestionSetOverManyStatesThroughTheBatchEndpoint() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(ExpectedCount.once(), requestTo(LayaFixtures.BASE_URL + "/v1/systemone/batch"))
			.andExpect(jsonPath("$.states.length()").value(3))
			.andExpect(jsonPath("$.states[1].passage").value("b"))
			.andExpect(jsonPath("$.questions.dept.type").value("choice"))
			.andRespond(withSuccess(LayaFixtures.batchOf(LayaFixtures.choiceDecision("billing", 0.9),
					LayaFixtures.choiceDecision("tech", 0.8), LayaFixtures.choiceDecision("billing", 0.7)),
					MediaType.APPLICATION_JSON));

		LayaBatchResponse batch = laya.client()
			.systemOneBatch(List.of(JsonContent.object("passage", "a"), JsonContent.object("passage", "b"),
					JsonContent.object("passage", "c")), Map.of("dept", LayaFixtures.dept()));

		laya.server().verify();
		assertThat(batch.results()).extracting(r -> r.response().choiceValue("dept"))
			.containsExactly("billing", "tech", "billing");
		assertThat(batch.totalUsage().inputTokens()).isEqualTo(30);
		assertThat(batch.calls()).isEqualTo(1);
	}

	@Test
	void splitsBatchesAboveTheStateLimit() {
		LayaFixtures.Bound laya = LayaFixtures
			.laya(LayaClient.builder().limits(new LayaLimits(64, 2, 100, 32, 512, 50_000, 20)));
		String two = LayaFixtures.batchOf(LayaFixtures.choiceDecision("billing", 0.9),
				LayaFixtures.choiceDecision("tech", 0.9));
		String one = LayaFixtures.batchOf(LayaFixtures.choiceDecision("tech", 0.6));
		laya.server().expect(jsonPath("$.states.length()").value(2)).andRespond(withSuccess(two, MediaType.APPLICATION_JSON));
		laya.server().expect(jsonPath("$.states.length()").value(2)).andRespond(withSuccess(two, MediaType.APPLICATION_JSON));
		laya.server().expect(jsonPath("$.states.length()").value(1)).andRespond(withSuccess(one, MediaType.APPLICATION_JSON));

		LayaBatchResponse batch = laya.client()
			.systemOneBatch(List.of(JsonContent.of("1"), JsonContent.of("2"), JsonContent.of("3"), JsonContent.of("4"),
					JsonContent.of("5")), Map.of("dept", LayaFixtures.dept()));

		laya.server().verify();
		assertThat(batch.results()).hasSize(5);
		assertThat(batch.calls()).isEqualTo(3);
		assertThat(batch.totalUsage().inputTokens()).isEqualTo(50);
	}

	@Test
	void retriesAFullAdmissionQueueAndThenSucceeds() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatusCode.valueOf(503)).header(HttpHeaders.RETRY_AFTER, "0")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"detail\":\"server busy\"}"));
		laya.server().expect(method(HttpMethod.POST)).andRespond(withSuccess(LayaFixtures.SINGLE, MediaType.APPLICATION_JSON));

		LayaResponse response = laya.client().systemOne(JsonContent.of("x"), threeQuestions());

		laya.server().verify();
		assertThat(response.response().choiceValue("dept")).isEqualTo("billing");
	}

	@Test
	void reportsABusyServerAsATypedRateLimitOnceRetriesRunOut() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(ExpectedCount.times(3), method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatusCode.valueOf(503)).header(HttpHeaders.RETRY_AFTER, "0")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"detail\":\"server busy\"}"));

		assertThatThrownBy(() -> laya.client().systemOne(JsonContent.of("x"), threeQuestions()))
			.isInstanceOf(LayaServerBusyException.class)
			.hasMessageContaining("server busy");
		laya.server().verify();
	}

	@Test
	void neverRetriesAPayloadTooLarge() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(ExpectedCount.once(), method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatusCode.valueOf(413)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"detail\":\"state too long (60000 > 50000 characters)\"}"));

		assertThatThrownBy(() -> laya.client().systemOne(JsonContent.of("x"), threeQuestions()))
			.isInstanceOf(LayaPayloadTooLargeException.class)
			.hasMessageContaining("state too long");
		laya.server().verify();
	}

	@Test
	void mapsAValidationRefusalOntoTheUpstreamType() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(ExpectedCount.once(), method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatusCode.valueOf(422)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"detail\":\"unknown model 'org/repo'\"}"));

		assertThatThrownBy(() -> laya.client()
			.systemOne(SystemOneRequest.builder().state("x").model("org/repo").questions(threeQuestions()).build()))
			.isInstanceOfSatisfying(TypeSafeUnprocessableEntityException.class,
					ex -> assertThat(ex.errorMessage()).isEqualTo("unknown model 'org/repo'"));
	}

	@Test
	void refusesANoulWithoutInstructionsBeforeSending() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		Noul criteriaOnly = Noul.builder().whenTrue("yes").whenFalse("no").build();

		assertThatThrownBy(() -> laya.client().systemOne(JsonContent.of("x"), Map.of("n", criteriaOnly)))
			.isInstanceOfSatisfying(LayaLimitExceededException.class,
					ex -> assertThat(ex.limit()).isEqualTo("noul-instructions"));
		laya.server().verify();
	}

	@Test
	void refusesTooManyOptionsAndUndescribedScoreLevelsBeforeSending() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		Choice.Builder wide = Choice.builder().instructions("pick");
		for (int i = 0; i < 101; i++) {
			wide.option("o" + i, "option " + i);
		}
		Score blank = new Score(JsonContent.of("how?"), List.of(JsonContent.of("low"), JsonContent.NULL));

		assertThatThrownBy(() -> laya.client().systemOne(JsonContent.of("x"), Map.of("c", wide.build())))
			.isInstanceOfSatisfying(LayaLimitExceededException.class,
					ex -> assertThat(ex.limit()).isEqualTo("options-per-choice"));
		assertThatThrownBy(() -> laya.client().systemOne(JsonContent.of("x"), Map.of("s", blank)))
			.isInstanceOfSatisfying(LayaLimitExceededException.class,
					ex -> assertThat(ex.limit()).isEqualTo("score-level-description"));
		laya.server().verify();
	}

	@Test
	void raisesTheOptionCapForATournamentServer() {
		Choice.Builder wide = Choice.builder().instructions("pick");
		for (int i = 0; i < 150; i++) {
			wide.option("o" + i, "option " + i);
		}
		LayaLimits.withTournament().validate(Map.of("c", wide.build()));
	}

	@Test
	void routeNeverSendsPredictionOnlyControls() {
		LayaFixtures.Bound laya = LayaFixtures.laya(LayaClient.builder()
			.defaultOptions(LayaOptions.builder().langGuess("en").minConfidence(0.8).maxLen(128).build()));
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/route"))
			.andExpect(jsonPath("$.lang_guess").value("en"))
			.andExpect(jsonPath("$.explain").value(true))
			.andExpect(jsonPath("$.min_confidence").doesNotExist())
			.andExpect(jsonPath("$.max_len").doesNotExist())
			.andRespond(withSuccess(LayaFixtures.ROUTE, MediaType.APPLICATION_JSON));

		LayaRouting routing = laya.client().route(JsonContent.object("message", "Turn lights off"), Map.of(), true);

		laya.server().verify();
		assertThat(routing.model()).isEqualTo("english");
		assertThat(routing.detection()).containsEntry("reason", "insufficient_words");
	}

	@Test
	void readsHealth() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/health"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(LayaFixtures.HEALTH, MediaType.APPLICATION_JSON));

		LayaHealth health = laya.client().health();

		assertThat(health.isUp()).isTrue();
		assertThat(health.isDetailed()).isTrue();
		assertThat(health.loaded()).containsExactly("english");
		assertThat(health.device()).isEqualTo("cpu");
		assertThat(health.deviceIsPreference()).isFalse();
		assertThat(health.revisions()).containsEntry("english", "7b928d82");
	}

}
