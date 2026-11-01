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

import java.time.Duration;

import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;

import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Test harness: a {@link LayaClient} bound to a {@link MockRestServiceServer}, and response
 * bodies captured verbatim from a real {@code laya-serve} 0.4.19 (english checkpoint, CPU).
 */
final class LayaFixtures {

	static final String BASE_URL = "http://laya.test";

	static final String JEV_URL = "http://jev.test";

	/**
	 * {@code POST /v1/systemone} with the full Laya payload: choice, score and noul answers,
	 * truncation usage and the routing block.
	 */
	static final String SINGLE = """
			{"model":"laya-rl-agent","answers":{
			 "dept":{"type":"choice","choice":"billing","probabilities":{"billing":0.9607,"tech":0.0393},
			  "confidence":0.7607,"answer_confidence":0.9607,"action":{"act_probability":1.0},"x_jev_confidence":0.9214},
			 "urgency":{"type":"score","score":1.2318,"legend":{"0":"calm","1":"firm","2":"angry"},
			  "probabilities":{"0":0.0267,"1":0.7149,"2":0.2584},"confidence":0.3753,"answer_confidence":0.7149,
			  "action":{"act_probability":1.0},"x_jev_confidence":0.5723},
			 "churn":{"type":"noul","noul":0.8969,"confidence":0.8969,"answer_confidence":0.8969,"action":{"act_probability":1.0}}},
			 "usage":{"input_tokens":114,"output_tokens":0,"state_tokens":13,"state_tokens_dropped":0,"truncated":false,"truncated_questions":[]},
			 "routing":{"model":"english","repo":"convaiinnovations/laya","reason":"English Latin text",
			  "detection":{"script":"latin","language":"en","is_english":true},"workflow":null}}
			""";

	/** The same answers as a server with {@code LAYA_JEV_STRICT=1} sends them. */
	static final String STRICT = """
			{"model":"laya-rl-agent","answers":{
			 "dept":{"type":"choice","choice":"billing","probabilities":{"billing":0.9607,"tech":0.0393},"confidence":0.7607},
			 "urgency":{"type":"score","score":1.2318,"legend":{"0":"calm","1":"firm","2":"angry"},
			  "probabilities":{"0":0.0267,"1":0.7149,"2":0.2584},"confidence":0.3753},
			 "churn":{"type":"noul","noul":0.8969}},
			 "usage":{"input_tokens":114,"output_tokens":0}}
			""";

	/** A gated request ({@code min_confidence: 0.9}): one answer abstained. */
	static final String GATED = """
			{"model":"laya-rl-agent","answers":{
			 "dept":{"type":"choice","choice":"billing","probabilities":{"billing":0.9783,"tech":0.0217},"confidence":0.8489,
			  "answer_confidence":0.9783,"abstention":"passed","abstention_threshold":0.9,"x_jev_confidence":0.9566},
			 "churn":{"type":"noul","noul":0.1021,"confidence":0.8979,"answer_confidence":0.8979,
			  "low_confidence":true,"abstention":"abstained","abstention_threshold":0.9}},
			 "usage":{"input_tokens":99,"output_tokens":0}}
			""";

	static final String HEALTH = """
			{"status":"ok","loaded":["english"],"revisions":{"english":"7b928d82"},"device":"cpu",
			 "device_is_preference":false,"checkpoint_devices":{"english":"cpu"},
			 "cpu_fallbacks":{"english":{"count":0,"last_reason":null}}}
			""";

	static final String ROUTE = """
			{"model":"english","repo":"convaiinnovations/laya","reason":"using default (english)",
			 "diagnostic":{"stage":"whole_state","reason":"insufficient_words","source":null}}
			""";

	static final String JEV_ANSWER = """
			{"model":"jev-1.13.0","answers":{"dept":{"type":"choice","choice":"tech",
			 "probabilities":{"billing":0.1,"tech":0.9},"confidence":0.8}},"usage":{"input_tokens":20,"output_tokens":0}}
			""";

	private LayaFixtures() {
	}

	static Choice dept() {
		return Choice.builder()
			.instructions("Which team?")
			.option("billing", "refunds and invoices")
			.option("tech", "bugs and login")
			.build();
	}

	static Score urgency() {
		return Score.of("How urgent?", "calm", "firm", "angry");
	}

	static Noul churn() {
		return Noul.builder().instructions("Does the user threaten to cancel?").build();
	}

	/** A decision with a single choice answer whose top probability is {@code p}. */
	static String choiceDecision(String label, double p) {
		String other = label.equals("billing") ? "tech" : "billing";
		return """
				{"model":"laya-rl-agent","answers":{"dept":{"type":"choice","choice":"%s",
				 "probabilities":{"%s":%s,"%s":%s},"confidence":0.5,"answer_confidence":%s,"x_jev_confidence":%s}},
				 "usage":{"input_tokens":10,"output_tokens":0}}
				""".formatted(label, label, p, other, 1 - p, p, (p - 0.5) / 0.5);
	}

	static String batchOf(String... decisions) {
		return "{\"results\":[" + String.join(",", decisions)
				+ "],\"total_usage\":{\"input_tokens\":" + (10 * decisions.length) + ",\"output_tokens\":0}}";
	}

	static RetryPolicy fastRetry() {
		return RetryPolicy.builder()
			.maxRetries(2)
			.initialBackoff(Duration.ZERO)
			.maxBackoff(Duration.ZERO)
			.retryableStatuses(LayaConstants.DEFAULT_RETRYABLE_STATUSES)
			.totalTimeout(Duration.ofSeconds(10))
			.build();
	}

	/** A Laya client and the mock server it talks to. */
	record Bound(LayaClient client, MockRestServiceServer server) {
	}

	static Bound laya(LayaClient.Builder builder) {
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).bufferContent().build();
		LayaClient client = builder.baseUrl(BASE_URL).restClientBuilder(rest).retryPolicy(fastRetry()).build();
		return new Bound(client, server);
	}

	static Bound laya() {
		return laya(LayaClient.builder().apiKey((String) null));
	}

	/** A Jev client and its mock server. */
	record BoundJev(TypeSafeClient client, MockRestServiceServer server) {
	}

	static BoundJev jev() {
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).bufferContent().build();
		TypeSafeClient client = TypeSafeClient.builder()
			.apiKey("jev-key")
			.baseUrl(JEV_URL)
			.defaultModel("jev-1.13.0")
			.restClientBuilder(rest)
			.retryPolicy(RetryPolicy.noRetry())
			.build();
		return new BoundJev(client, server);
	}

}
