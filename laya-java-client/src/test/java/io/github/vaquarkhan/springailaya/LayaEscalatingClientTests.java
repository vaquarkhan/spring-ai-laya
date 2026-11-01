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
import org.springaicommunity.typesafe.exception.TypeSafeUnprocessableEntityException;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LayaEscalatingClientTests {

	private static SystemOneRequest deptRequest(String state) {
		return SystemOneRequest.builder().state(state).question("dept", LayaFixtures.dept()).build();
	}

	@Test
	void servesAConfidentLayaAnswer() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		LayaFixtures.BoundJev jev = LayaFixtures.jev();
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andRespond(withSuccess(LayaFixtures.choiceDecision("billing", 0.95), MediaType.APPLICATION_JSON));
		List<EscalationReason> seen = new ArrayList<>();
		LayaEscalatingClient client = LayaEscalatingClient.builder(laya.client(), jev.client())
			.listener((request, reason, answer, failure) -> seen.add(reason))
			.build();

		SystemOneResponse response = client.systemOne(deptRequest("billed twice"));

		laya.server().verify();
		jev.server().verify();
		assertThat(response.model()).isEqualTo("laya-rl-agent");
		assertThat(seen).containsExactly(EscalationReason.NONE);
		assertThat(client.stats()).containsEntry(EscalationReason.NONE, 1L);
	}

	@Test
	void escalatesALowConfidenceAnswerWithTheFallbacksOwnModel() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		LayaFixtures.BoundJev jev = LayaFixtures.jev();
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andRespond(withSuccess(LayaFixtures.choiceDecision("billing", 0.55), MediaType.APPLICATION_JSON));
		jev.server()
			.expect(requestTo(LayaFixtures.JEV_URL + "/v1/systemone"))
			.andExpect(jsonPath("$.model").value("jev-1.13.0"))
			.andRespond(withSuccess(LayaFixtures.JEV_ANSWER, MediaType.APPLICATION_JSON));
		LayaEscalatingClient client = LayaEscalatingClient.builder(laya.client(), jev.client())
			.minAnswerConfidence(0.7)
			.build();

		SystemOneResponse response = client.systemOne(deptRequest("hmm"));

		laya.server().verify();
		jev.server().verify();
		assertThat(response.model()).isEqualTo("jev-1.13.0");
		assertThat(client.stats()).containsEntry(EscalationReason.LOW_CONFIDENCE, 1L);
	}

	@Test
	void escalatesWhenLayasOwnGateAbstains() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		LayaFixtures.BoundJev jev = LayaFixtures.jev();
		laya.server().expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andRespond(withSuccess(LayaFixtures.GATED, MediaType.APPLICATION_JSON));
		jev.server().expect(requestTo(LayaFixtures.JEV_URL + "/v1/systemone"))
			.andRespond(withSuccess(LayaFixtures.JEV_ANSWER, MediaType.APPLICATION_JSON));
		LayaEscalatingClient client = LayaEscalatingClient.builder(laya.client(), jev.client())
			.minAnswerConfidence(0.0)
			.build();

		client.systemOne(SystemOneRequest.builder()
			.state("x")
			.question("dept", LayaFixtures.dept())
			.question("churn", LayaFixtures.churn())
			.build());

		jev.server().verify();
		assertThat(client.stats()).containsEntry(EscalationReason.ABSTAINED, 1L);
	}

	@Test
	void sendsAWideChoiceStraightToTheFallback() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		LayaFixtures.BoundJev jev = LayaFixtures.jev();
		jev.server().expect(requestTo(LayaFixtures.JEV_URL + "/v1/systemone"))
			.andRespond(withSuccess(LayaFixtures.JEV_ANSWER, MediaType.APPLICATION_JSON));
		Choice.Builder wide = Choice.builder().instructions("Which intent?");
		for (int i = 0; i < 77; i++) {
			wide.option("intent_" + i, "intent number " + i);
		}
		LayaEscalatingClient client = LayaEscalatingClient.builder(laya.client(), jev.client()).build();

		client.systemOne(SystemOneRequest.builder().state("x").question("dept", wide.build()).build());

		laya.server().verify();
		jev.server().verify();
		assertThat(client.stats()).containsEntry(EscalationReason.TOO_MANY_OPTIONS, 1L);
	}

	@Test
	void escalatesWhenLayaIsDownAfterItsRetries() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		LayaFixtures.BoundJev jev = LayaFixtures.jev();
		laya.server()
			.expect(ExpectedCount.times(3), requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andRespond(withStatus(HttpStatusCode.valueOf(503)).header("Retry-After", "0"));
		jev.server().expect(requestTo(LayaFixtures.JEV_URL + "/v1/systemone"))
			.andRespond(withSuccess(LayaFixtures.JEV_ANSWER, MediaType.APPLICATION_JSON));
		LayaEscalatingClient client = LayaEscalatingClient.builder(laya.client(), jev.client()).build();

		SystemOneResponse response = client.systemOne(deptRequest("x"));

		laya.server().verify();
		assertThat(response.model()).isEqualTo("jev-1.13.0");
		assertThat(client.stats()).containsEntry(EscalationReason.LAYA_FAILED, 1L);
	}

	@Test
	void doesNotHideAClientMistakeBehindTheFallback() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		LayaFixtures.BoundJev jev = LayaFixtures.jev();
		laya.server()
			.expect(requestTo(LayaFixtures.BASE_URL + "/v1/systemone"))
			.andRespond(withStatus(HttpStatusCode.valueOf(422)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"detail\":\"unknown model 'org/repo'\"}"));
		LayaEscalatingClient client = LayaEscalatingClient.builder(laya.client(), jev.client()).build();

		assertThatThrownBy(() -> client.systemOne(deptRequest("x")))
			.isInstanceOf(TypeSafeUnprocessableEntityException.class);
		jev.server().verify();
	}

	@Test
	void batchesOnLayaAndEscalatesOnlyTheUnsureSlots() {
		LayaFixtures.Bound laya = LayaFixtures.laya();
		LayaFixtures.BoundJev jev = LayaFixtures.jev();
		laya.server()
			.expect(ExpectedCount.once(), requestTo(LayaFixtures.BASE_URL + "/v1/systemone/batch"))
			.andExpect(jsonPath("$.states.length()").value(4))
			.andRespond(withSuccess(LayaFixtures.batchOf(LayaFixtures.choiceDecision("billing", 0.95),
					LayaFixtures.choiceDecision("tech", 0.52), LayaFixtures.choiceDecision("billing", 0.9),
					LayaFixtures.choiceDecision("tech", 0.6)), MediaType.APPLICATION_JSON));
		jev.server()
			.expect(ExpectedCount.times(2), requestTo(LayaFixtures.JEV_URL + "/v1/systemone"))
			.andRespond(withSuccess(LayaFixtures.JEV_ANSWER, MediaType.APPLICATION_JSON));
		LayaEscalatingClient client = LayaEscalatingClient.builder(laya.client(), jev.client())
			.minAnswerConfidence(0.75)
			.build();

		List<JevBatchResult<SystemOneResponse>> results = client.systemOneAll(
				List.of(deptRequest("a"), deptRequest("b"), deptRequest("c"), deptRequest("d")),
				JevBatchOptions.ofConcurrency(1));

		laya.server().verify();
		jev.server().verify();
		assertThat(results).extracting(r -> r.orThrow().model())
			.containsExactly("laya-rl-agent", "jev-1.13.0", "laya-rl-agent", "jev-1.13.0");
		assertThat(client.stats()).containsEntry(EscalationReason.NONE, 2L)
			.containsEntry(EscalationReason.LOW_CONFIDENCE, 2L);
	}

}
