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

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.JevBatchOptions;
import org.springaicommunity.typesafe.JevBatchResult;
import org.springaicommunity.typesafe.JsonContent;
import io.github.vaquarkhan.springailaya.exception.LayaPayloadTooLargeException;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.ModelMetadata;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LayaTypeSafeClientTests {

	private static SystemOneRequest threeQuestionRequest() {
		return SystemOneRequest.builder()
			.state("x")
			.question("dept", LayaFixtures.dept())
			.question("urgency", LayaFixtures.urgency())
			.question("churn", LayaFixtures.churn())
			.build();
	}

	@Test
	void exposesJevFormulaConfidenceByDefault() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server().expect(method(HttpMethod.POST)).andRespond(withSuccess(LayaFixtures.SINGLE, MediaType.APPLICATION_JSON));
		LayaTypeSafeClient client = new LayaTypeSafeClient(laya.client());

		SystemOneResponse response = client.systemOne(threeQuestionRequest());

		assertThat(response.choice("dept").confidence()).isEqualTo(0.9214);
		assertThat(response.score("urgency").confidence()).isEqualTo(0.5723);
		assertThat(response.noulValue("churn")).isEqualTo(0.8969);
		assertThat(response.choice("dept").probabilities()).containsEntry("billing", 0.9607);
	}

	@Test
	void canExposeAnswerConfidenceOrTheRawEntropy() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(ExpectedCount.twice(), method(HttpMethod.POST))
			.andRespond(withSuccess(LayaFixtures.SINGLE, MediaType.APPLICATION_JSON));

		SystemOneResponse answer = new LayaTypeSafeClient(laya.client(), ConfidenceMetric.ANSWER)
			.systemOne(threeQuestionRequest());
		SystemOneResponse entropy = new LayaTypeSafeClient(laya.client(), ConfidenceMetric.ENTROPY)
			.systemOne(threeQuestionRequest());

		assertThat(answer.choice("dept").confidence()).isEqualTo(0.9607);
		assertThat(answer.score("urgency").confidence()).isEqualTo(0.7149);
		assertThat(entropy.choice("dept").confidence()).isEqualTo(0.7607);
		assertThat(entropy.score("urgency").confidence()).isEqualTo(0.3753);
	}

	@Test
	void computesJevConfidenceWhenAStrictServerOmitsIt() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server().expect(method(HttpMethod.POST)).andRespond(withSuccess(LayaFixtures.STRICT, MediaType.APPLICATION_JSON));

		SystemOneResponse response = new LayaTypeSafeClient(laya.client()).systemOne(threeQuestionRequest());

		// The values the full payload carried as x_jev_confidence for the same probabilities.
		assertThat(response.choice("dept").confidence()).isCloseTo(0.9214, within(1e-4));
		assertThat(response.score("urgency").confidence()).isCloseTo(0.5723, within(2e-4));
	}

	@Test
	void sendsRequestsWithTheSameQuestionsAsOneBatchCall() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(ExpectedCount.once(), requestTo(LayaFixtures.BASE_URL + "/v1/systemone/batch"))
			.andExpect(jsonPath("$.states.length()").value(3))
			.andExpect(jsonPath("$.model").doesNotExist())
			.andRespond(withSuccess(LayaFixtures.batchOf(LayaFixtures.choiceDecision("billing", 0.9),
					LayaFixtures.choiceDecision("tech", 0.8), LayaFixtures.choiceDecision("billing", 0.7)),
					MediaType.APPLICATION_JSON));
		LayaTypeSafeClient client = new LayaTypeSafeClient(laya.client());

		List<SystemOneRequest> requests = new ArrayList<>();
		for (String passage : List.of("a", "b", "c")) {
			requests.add(SystemOneRequest.builder()
				.state(JsonContent.object("query", "q", "passage", passage))
				.model(client.defaultModel())
				.question("dept", LayaFixtures.dept())
				.build());
		}
		List<JevBatchResult<SystemOneResponse>> results = client.systemOneAll(requests, JevBatchOptions.defaults());

		laya.server().verify();
		assertThat(results).extracting(JevBatchResult::index).containsExactly(0, 1, 2);
		assertThat(results).allMatch(JevBatchResult::succeeded);
		assertThat(results).extracting(r -> r.orThrow().choiceValue("dept")).containsExactly("billing", "tech", "billing");
		assertThat(results.get(0).orThrow().choice("dept").confidence()).isCloseTo(0.8, within(1e-9));
	}

	@Test
	void keepsDifferentQuestionSetsInSeparateCallsAndAlignsResults() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone/batch"))
			.andRespond(withSuccess(LayaFixtures.batchOf(LayaFixtures.choiceDecision("billing", 0.9),
					LayaFixtures.choiceDecision("tech", 0.9)), MediaType.APPLICATION_JSON));
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andRespond(withSuccess(LayaFixtures.SINGLE, MediaType.APPLICATION_JSON));
		LayaTypeSafeClient client = new LayaTypeSafeClient(laya.client());

		SystemOneRequest a = SystemOneRequest.builder().state("a").question("dept", LayaFixtures.dept()).build();
		SystemOneRequest odd = threeQuestionRequest();
		SystemOneRequest b = SystemOneRequest.builder().state("b").question("dept", LayaFixtures.dept()).build();

		// Concurrency 1 keeps the two calls in a predictable order for the ordered mock.
		List<JevBatchResult<SystemOneResponse>> results = client.systemOneAll(List.of(a, odd, b),
				JevBatchOptions.ofConcurrency(1));

		laya.server().verify();
		assertThat(results.get(0).orThrow().choiceValue("dept")).isEqualTo("billing");
		assertThat(results.get(1).orThrow().answers()).containsKeys("dept", "urgency", "churn");
		assertThat(results.get(2).orThrow().choiceValue("dept")).isEqualTo("tech");
	}

	@Test
	void cutsLargeGroupsIntoConcurrentChunksByRowBudget() {
		org.springframework.web.client.RestClient.Builder rest = org.springframework.web.client.RestClient.builder();
		org.springframework.test.web.client.MockRestServiceServer server = org.springframework.test.web.client.MockRestServiceServer
			.bindTo(rest)
			.ignoreExpectOrder(true)
			.bufferContent()
			.build();
		LayaClient laya = LayaClient.builder()
			.baseUrl(LayaFixtures.BASE_URL)
			.restClientBuilder(rest)
			.retryPolicy(LayaFixtures.fastRetry())
			.rowsPerBatchCall(2)
			.maxConcurrentBatchCalls(2)
			.build();
		server.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone/batch"))
			.andExpect(jsonPath("$.states[0]").value("a"))
			.andExpect(jsonPath("$.states.length()").value(2))
			.andRespond(withSuccess(LayaFixtures.batchOf(LayaFixtures.choiceDecision("billing", 0.9),
					LayaFixtures.choiceDecision("billing", 0.8)), MediaType.APPLICATION_JSON));
		server.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone/batch"))
			.andExpect(jsonPath("$.states[0]").value("c"))
			.andExpect(jsonPath("$.states.length()").value(2))
			.andRespond(withSuccess(LayaFixtures.batchOf(LayaFixtures.choiceDecision("tech", 0.9),
					LayaFixtures.choiceDecision("tech", 0.8)), MediaType.APPLICATION_JSON));
		LayaTypeSafeClient client = new LayaTypeSafeClient(laya);
		List<SystemOneRequest> requests = List.of("a", "b", "c", "d").stream()
			.map(s -> SystemOneRequest.builder().state(s).question("dept", LayaFixtures.dept()).build())
			.toList();

		List<JevBatchResult<SystemOneResponse>> results = client.systemOneAll(requests, JevBatchOptions.ofConcurrency(2));

		server.verify();
		assertThat(results).extracting(r -> r.orThrow().choiceValue("dept"))
			.containsExactly("billing", "billing", "tech", "tech");
	}

	@Test
	void isolatesTheOffendingSlotWhenABatchIsRefused() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone/batch"))
			.andRespond(withStatus(HttpStatusCode.valueOf(413)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"detail\":\"state too long\"}"));
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andRespond(withSuccess(LayaFixtures.choiceDecision("billing", 0.9), MediaType.APPLICATION_JSON));
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andRespond(withStatus(HttpStatusCode.valueOf(413)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"detail\":\"state too long\"}"));
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andRespond(withSuccess(LayaFixtures.choiceDecision("tech", 0.9), MediaType.APPLICATION_JSON));
		LayaTypeSafeClient client = new LayaTypeSafeClient(laya.client());

		List<SystemOneRequest> requests = List.of("short", "huge", "short").stream()
			.map(s -> SystemOneRequest.builder().state(s).question("dept", LayaFixtures.dept()).build())
			.toList();
		List<JevBatchResult<SystemOneResponse>> results = client.systemOneAll(requests, JevBatchOptions.defaults());

		laya.server().verify();
		assertThat(results.get(0).succeeded()).isTrue();
		assertThat(results.get(1).failure()).isInstanceOf(LayaPayloadTooLargeException.class);
		assertThat(results.get(2).orThrow().choiceValue("dept")).isEqualTo("tech");
	}

	@Test
	void failsEverySlotOfACallThatFailedForTransportReasons() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(ExpectedCount.times(3), requestTo(LayaFixtures.BASE_URL + "/v1/systemone/batch"))
			.andRespond(withStatus(HttpStatusCode.valueOf(500)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"detail\":\"prediction failed\"}"));
		LayaTypeSafeClient client = new LayaTypeSafeClient(laya.client());
		List<SystemOneRequest> requests = List.of("a", "b").stream()
			.map(s -> SystemOneRequest.builder().state(s).question("n", Noul.of("Is it urgent?")).build())
			.toList();

		List<JevBatchResult<SystemOneResponse>> results = client.systemOneAll(requests, JevBatchOptions.defaults());

		laya.server().verify();
		assertThat(results).noneMatch(JevBatchResult::succeeded);
		assertThat(results.get(0).failure()).isSameAs(results.get(1).failure());
	}

	@Test
	void listsResidentCheckpointsInsteadOfModels() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/health"))
			.andRespond(withSuccess(LayaFixtures.HEALTH, MediaType.APPLICATION_JSON));

		List<ModelMetadata> models = new LayaTypeSafeClient(laya.client()).listModels();

		assertThat(models).extracting(ModelMetadata::name).containsExactly("english");
		assertThat(models.get(0).description()).contains("7b928d82");
	}

	@Test
	void reportsTheLayaDefaultModelToComponentsThatStampIt() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		assertThat(new LayaTypeSafeClient(laya.client()).defaultModel()).isEqualTo(LayaCheckpoints.AUTO);
	}

}
