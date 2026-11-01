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

package io.github.vaquarkhan.springailaya.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import io.github.vaquarkhan.springailaya.ConfidenceMetric;
import io.github.vaquarkhan.springailaya.LayaCheckpoints;
import io.github.vaquarkhan.springailaya.LayaClient;
import io.github.vaquarkhan.springailaya.LayaEscalatingClient;
import io.github.vaquarkhan.springailaya.LayaTypeSafeClient;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class LayaAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(LayaAutoConfiguration.class, TypeSafeAutoConfiguration.class));

	@Test
	void staysOffWithoutABaseUrlSoTheJevStarterKeepsTheClient() {
		this.runner.withPropertyValues("spring.ai.typesafe.api-key=jev-key").run(context -> {
			assertThat(context).doesNotHaveBean(LayaClient.class);
			assertThat(context).hasSingleBean(TypeSafeClient.class);
			assertThat(context.getBean(TypeSafeClient.class)).isNotInstanceOf(LayaTypeSafeClient.class);
		});
	}

	@Test
	void replacesTheJevClientWhenABaseUrlIsSetAndNeedsNoKey() {
		this.runner.withPropertyValues("spring.ai.laya.base-url=http://localhost:8002").run(context -> {
			assertThat(context).hasSingleBean(LayaClient.class);
			assertThat(context).hasSingleBean(TypeSafeClient.class);
			TypeSafeClient client = context.getBean(TypeSafeClient.class);
			assertThat(client).isInstanceOf(LayaTypeSafeClient.class);
			assertThat(((LayaTypeSafeClient) client).confidenceMetric()).isEqualTo(ConfidenceMetric.JEV);
			assertThat(client.defaultModel()).isEqualTo(LayaCheckpoints.AUTO);
		});
	}

	@Test
	void winsOverTheJevStarterEvenWhenAJevKeyIsPresent() {
		this.runner
			.withPropertyValues("spring.ai.laya.base-url=http://localhost:8002",
					"spring.ai.typesafe.api-key=jev-key")
			.run(context -> assertThat(context.getBean(TypeSafeClient.class)).isInstanceOf(LayaTypeSafeClient.class));
	}

	@Test
	void bindsModelMetricOptionsAndRetry() {
		this.runner
			.withPropertyValues("spring.ai.laya.base-url=http://localhost:8002",
					"spring.ai.laya.model=english", "spring.ai.laya.confidence-metric=answer",
					"spring.ai.laya.options.lang-guess=en", "spring.ai.laya.options.min-confidence=0.8",
					"spring.ai.laya.retry.max-retries=5", "spring.ai.laya.tournament=true")
			.run(context -> {
				LayaClient laya = context.getBean(LayaClient.class);
				assertThat(laya.defaultModel()).isEqualTo("english");
				assertThat(laya.defaultOptions().langGuess()).isEqualTo("en");
				assertThat(laya.defaultOptions().minConfidence()).isEqualTo(0.8);
				assertThat(laya.retryPolicy().maxRetries()).isEqualTo(5);
				assertThat(laya.limits().maxChoiceOptions()).isEqualTo(255);
				assertThat(((LayaTypeSafeClient) context.getBean(TypeSafeClient.class)).confidenceMetric())
					.isEqualTo(ConfidenceMetric.ANSWER);
			});
	}

	@Test
	void rejectsBatchAndConfidenceValuesOutsideTheirRange() {
		this.runner
			.withPropertyValues("spring.ai.laya.base-url=http://localhost:8002",
					"spring.ai.laya.rows-per-batch-call=0", "spring.ai.laya.escalation.min-answer-confidence=1.5")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(failureText(context.getStartupFailure())).contains("rowsPerBatchCall", "minAnswerConfidence");
			});
	}

	@Test
	void failsFastOnABlankBaseUrl() {
		this.runner.withPropertyValues("spring.ai.laya.base-url=").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("spring.ai.laya.base-url");
		});
	}

	@Test
	void buildsAnEscalatingClientOverJevWhenAsked() {
		this.runner
			.withPropertyValues("spring.ai.laya.base-url=http://localhost:8002",
					"spring.ai.laya.escalation.enabled=true",
					"spring.ai.laya.escalation.allow-external-data-export=true",
					"spring.ai.laya.escalation.min-answer-confidence=0.8", "spring.ai.typesafe.api-key=jev-key")
			.run(context -> {
				TypeSafeClient client = context.getBean(TypeSafeClient.class);
				assertThat(client).isInstanceOf(LayaEscalatingClient.class);
				LayaEscalatingClient escalating = (LayaEscalatingClient) client;
				assertThat(escalating.minAnswerConfidence()).isEqualTo(0.8);
				assertThat(escalating.fallback().defaultModel()).isEqualTo("jev-latest");
			});
	}

	@Test
	void refusesEscalationUntilExternalDataExportIsAcknowledged() {
		this.runner
			.withPropertyValues("spring.ai.laya.base-url=http://localhost:8002",
					"spring.ai.laya.escalation.enabled=true", "spring.ai.typesafe.api-key=jev-key")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("spring.ai.laya.escalation.allow-external-data-export=true");
			});
	}

	@Test
	void refusesEscalationWithoutAJevKey() {
		this.runner
			.withPropertyValues("spring.ai.laya.base-url=http://localhost:8002",
					"spring.ai.laya.escalation.enabled=true",
					"spring.ai.laya.escalation.allow-external-data-export=true")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("spring.ai.typesafe.api-key");
			});
	}

	@Test
	void backsOffForAClientOfYourOwn() {
		this.runner.withPropertyValues("spring.ai.laya.base-url=http://localhost:8002")
			.withUserConfiguration(OwnClient.class)
			.run(context -> {
				assertThat(context).hasSingleBean(TypeSafeClient.class);
				assertThat(context.getBean(TypeSafeClient.class)).isSameAs(context.getBean(OwnClient.class).client);
			});
	}

	@Test
	void isRegisteredForAutoConfiguration() throws IOException {
		try (InputStream imports = getClass().getResourceAsStream(
				"/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
			assertThat(imports).isNotNull();
			assertThat(new String(imports.readAllBytes(), StandardCharsets.UTF_8))
				.contains(LayaAutoConfiguration.class.getName());
		}
	}

	private static String failureText(Throwable failure) {
		StringBuilder text = new StringBuilder();
		while (failure != null && text.length() < 20_000) {
			text.append(failure.getClass().getName()).append(": ").append(failure.getMessage()).append('\n');
			if (failure instanceof org.springframework.validation.BindException bind) {
				text.append(bind.getAllErrors()).append('\n');
			}
			failure = failure.getCause();
		}
		return text.toString();
	}

	@Configuration(proxyBeanMethods = false)
	static class OwnClient {

		final TypeSafeClient client = TypeSafeClient.builder().apiKey("mine").build();

		@Bean
		TypeSafeClient myClient() {
			return this.client;
		}

	}

}
