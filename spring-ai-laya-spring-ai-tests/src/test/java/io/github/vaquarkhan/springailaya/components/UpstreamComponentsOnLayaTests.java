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

package io.github.vaquarkhan.springailaya.components;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.judge.JevConfidenceGate;
import io.github.vaquarkhan.springailaya.LayaClient;
import io.github.vaquarkhan.springailaya.LayaTypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.rag.JevDocumentFilter;
import org.springaicommunity.typesafe.rag.JevDocumentReranker;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The released spring-ai-typesafe 0.4.0 components, unmodified, driven by a
 * {@link LayaTypeSafeClient} against a mock Laya server.
 */
class UpstreamComponentsOnLayaTests {

	private static final String URL = "http://laya.test";

	/** Probabilities captured from laya-serve 0.4.19 for a two-option choice. */
	private static final String CAPTURED_CHOICE = """
			{"model":"laya-rl-agent","answers":{"d":{"type":"choice","choice":"billing",
			 "probabilities":{"billing":0.8834,"tech":0.1166},"confidence":0.4805,"answer_confidence":0.8834,
			 "x_jev_confidence":0.7668}},"usage":{"input_tokens":17,"output_tokens":0}}
			""";

	private static String screening(double injection, double contradiction, double relevant, double evidence) {
		return """
				{"model":"laya-rl-agent","answers":{
				 "is_relevant":{"type":"noul","noul":%s},
				 "contains_answer_evidence":{"type":"noul","noul":%s},
				 "contradicts_query_premise":{"type":"noul","noul":%s},
				 "contains_prompt_injection":{"type":"noul","noul":%s}},"usage":{"input_tokens":40,"output_tokens":0}}
				""".formatted(relevant, evidence, contradiction, injection);
	}

	private static String rerankScore(double p) {
		return """
				{"model":"laya-rl-agent","answers":{"answers_query":{"type":"noul","noul":%s}},
				 "usage":{"input_tokens":20,"output_tokens":0}}
				""".formatted(p);
	}

	private static String batch(List<String> results) {
		return "{\"results\":[" + String.join(",", results) + "],\"total_usage\":{\"input_tokens\":1,\"output_tokens\":0}}";
	}

	private record Bound(LayaClient laya, MockRestServiceServer server) {
	}

	private static Bound laya() {
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).bufferContent().build();
		LayaClient laya = LayaClient.builder()
			.baseUrl(URL)
			.apiKey((String) null)
			.rowsPerBatchCall(64)
			.restClientBuilder(rest)
			.retryPolicy(RetryPolicy.builder().maxRetries(0).totalTimeout(Duration.ofSeconds(5)).build())
			.build();
		return new Bound(laya, server);
	}

	private static List<Document> passages(int n) {
		return IntStream.range(0, n).mapToObj(i -> new Document("passage " + i)).collect(Collectors.toList());
	}

	@Test
	void documentFilterScreensFivePassagesInOneBatchCall() {
		Bound bound = laya();
		bound.server()
			.expect(ExpectedCount.once(), requestTo(URL + "/v1/systemone/batch"))
			.andExpect(jsonPath("$.states.length()").value(5))
			.andExpect(jsonPath("$.questions.length()").value(4))
			.andRespond(withSuccess(batch(List.of(screening(0.1, 0.1, 0.9, 0.9), screening(0.9, 0.1, 0.9, 0.9),
					screening(0.1, 0.9, 0.9, 0.9), screening(0.1, 0.1, 0.2, 0.9), screening(0.1, 0.1, 0.9, 0.9))),
					MediaType.APPLICATION_JSON));
		JevDocumentFilter filter = JevDocumentFilter.builder(new LayaTypeSafeClient(bound.laya())).build();

		List<Document> kept = filter.process(new Query("what is the refund window?"), passages(5));

		bound.server().verify();
		assertThat(kept).extracting(Document::getText).containsExactly("passage 0", "passage 2", "passage 4");
		assertThat(kept).extracting(d -> d.getMetadata().get(JevDocumentFilter.CLASSIFICATION_METADATA_KEY))
			.containsExactly("INCLUDED", "CONFLICTING", "INCLUDED");
	}

	@Test
	void aPlainTypeSafeClientSendsOneRequestPerPassageToTheSameServer() {
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).bufferContent().build();
		server.expect(ExpectedCount.times(5), requestTo(URL + "/v1/systemone"))
			.andRespond(withSuccess(screening(0.1, 0.1, 0.9, 0.9), MediaType.APPLICATION_JSON));
		TypeSafeClient plain = TypeSafeClient.builder()
			.baseUrl(URL)
			.apiKey("unused")
			.restClientBuilder(rest)
			.retryPolicy(RetryPolicy.noRetry())
			.build();

		JevDocumentFilter.builder(plain).build().process(new Query("q"), passages(5));

		server.verify();
	}

	@Test
	void rerankerScoresAllPassagesInOneBatchCall() {
		Bound bound = laya();
		bound.server()
			.expect(ExpectedCount.once(), requestTo(URL + "/v1/systemone/batch"))
			.andExpect(jsonPath("$.states.length()").value(4))
			.andRespond(withSuccess(
					batch(List.of(rerankScore(0.2), rerankScore(0.9), rerankScore(0.6), rerankScore(0.05))),
					MediaType.APPLICATION_JSON));
		JevDocumentReranker reranker = JevDocumentReranker.builder(new LayaTypeSafeClient(bound.laya()))
			.topK(2)
			.build();

		List<Document> top = reranker.process(new Query("q"), passages(4));

		bound.server().verify();
		assertThat(top).extracting(Document::getText).containsExactly("passage 1", "passage 2");
	}

	@Test
	void theConfidenceGateKeepsItsJevMeaningOnLaya() {
		// One real Laya answer: billing at p=0.8834. JevConfidenceGate's 0.6 floor was tuned
		// on Jev's confidence formula. A plain client hands it Laya's entropy (0.4805), so a
		// clear-cut answer escalates; the adapter hands it Jev's formula (0.7668).
		Choice question = Choice.builder()
			.instructions("Which team?")
			.option("billing", "refunds")
			.option("tech", "bugs")
			.build();
		JevConfidenceGate gate = JevConfidenceGate.withDefaultFloor();

		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).bufferContent().build();
		server.expect(requestTo(URL + "/v1/systemone"))
			.andRespond(withSuccess(CAPTURED_CHOICE, MediaType.APPLICATION_JSON));
		TypeSafeClient plain = TypeSafeClient.builder()
			.baseUrl(URL)
			.apiKey("unused")
			.restClientBuilder(rest)
			.retryPolicy(RetryPolicy.noRetry())
			.build();
		SystemOneResponse viaPlain = plain.systemOne("billed twice", java.util.Map.of("d", question));

		Bound bound = laya();
		bound.server()
			.expect(requestTo(URL + "/v1/systemone"))
			.andRespond(withSuccess(CAPTURED_CHOICE, MediaType.APPLICATION_JSON));
		SystemOneResponse viaAdapter = new LayaTypeSafeClient(bound.laya())
			.systemOne("billed twice", java.util.Map.of("d", question));

		assertThat(gate.decide(viaPlain.choice("d"))).isEqualTo(JevConfidenceGate.Decision.ESCALATE);
		assertThat(gate.decide(viaAdapter.choice("d"))).isEqualTo(JevConfidenceGate.Decision.EXECUTE);
	}

}
