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

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeProperties;
import io.github.vaquarkhan.springailaya.LayaClient;
import io.github.vaquarkhan.springailaya.LayaEscalatingClient;
import io.github.vaquarkhan.springailaya.LayaLimits;
import io.github.vaquarkhan.springailaya.LayaTypeSafeClient;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Switches spring-ai-typesafe to a Laya backend when {@code spring.ai.laya.base-url}
 * is set. It runs before {@link TypeSafeAutoConfiguration} and contributes the
 * {@link TypeSafeClient} bean, so that one's {@code @ConditionalOnMissingBean} backs off and
 * every component built from the injected client runs on Laya.
 *
 * <ul>
 * <li>Default: a {@link LayaTypeSafeClient}.</li>
 * <li>{@code spring.ai.laya.escalation.enabled=true}: a
 * {@link LayaEscalatingClient} over Laya, escalating to a Jev client built from
 * {@code spring.ai.typesafe.*}.</li>
 * <li>A {@link TypeSafeClient} or {@link LayaClient} bean of your own wins over either.</li>
 * </ul>
 *
 * @author Vaquar Khan
 */
@AutoConfiguration(before = TypeSafeAutoConfiguration.class)
@ConditionalOnClass({ LayaClient.class, RestClient.class })
@ConditionalOnProperty(prefix = LayaProperties.CONFIG_PREFIX, name = "base-url")
@EnableConfigurationProperties({ LayaProperties.class, TypeSafeProperties.class })
public class LayaAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public LayaClient layaClient(LayaProperties properties, ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
		// @ConditionalOnProperty matches a property that merely exists, so
		// `base-url=${LAYA_BASE_URL:}` with the variable unset gets here blank. Fail fast.
		Assert.state(StringUtils.hasText(properties.getBaseUrl()),
				() -> "No Laya server configured. Set " + LayaProperties.CONFIG_PREFIX + ".base-url.");
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		requestFactory.setReadTimeout(properties.getTimeout());
		RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable(RestClient::builder)
			.clone()
			.requestFactory(requestFactory);
		String apiKey = properties.getApiKey();
		return LayaClient.builder()
			.baseUrl(properties.getBaseUrl())
			.apiKey(() -> apiKey)
			.defaultModel(StringUtils.hasText(properties.getModel()) ? properties.getModel() : "auto")
			.defaultOptions(properties.getOptions().toLayaOptions())
			.limits(properties.isTournament() ? LayaLimits.withTournament() : LayaLimits.defaults())
			.timeout(properties.getTimeout())
			.retryPolicy(properties.toRetryPolicy())
			.rowsPerBatchCall(properties.getRowsPerBatchCall())
			.maxConcurrentBatchCalls(properties.getMaxConcurrentBatchCalls())
			.restClientBuilder(restClientBuilder)
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(TypeSafeClient.class)
	public TypeSafeClient typeSafeClient(LayaClient layaClient, LayaProperties properties,
			TypeSafeProperties jevProperties, ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
		LayaProperties.Escalation escalation = properties.getEscalation();
		if (!escalation.isEnabled()) {
			return new LayaTypeSafeClient(layaClient, properties.getConfidenceMetric());
		}
		// Escalation sends the whole request, state included, to an external service. That
		// contradicts the data-local default, so it must be acknowledged explicitly.
		Assert.state(escalation.isAllowExternalDataExport(),
				() -> LayaProperties.CONFIG_PREFIX + ".escalation.enabled=true sends escalated requests, including "
						+ "their full state, to the Jev service. Set " + LayaProperties.CONFIG_PREFIX
						+ ".escalation.allow-external-data-export=true to confirm that this export is approved.");
		Assert.state(StringUtils.hasText(jevProperties.getApiKey()),
				() -> LayaProperties.CONFIG_PREFIX + ".escalation.enabled=true needs a Jev key: set "
						+ TypeSafeProperties.CONFIG_PREFIX + ".api-key.");
		return LayaEscalatingClient.builder(layaClient, jevClient(jevProperties, restClientBuilderProvider))
			.confidenceMetric(properties.getConfidenceMetric())
			.minAnswerConfidence(escalation.getMinAnswerConfidence())
			.maxLayaChoiceOptions(escalation.getMaxLayaChoiceOptions())
			.escalateOnAbstention(escalation.isOnAbstention())
			.escalateOnFailure(escalation.isOnFailure())
			.build();
	}

	/** The same Jev client {@link TypeSafeAutoConfiguration} would have built. */
	private static TypeSafeClient jevClient(TypeSafeProperties properties,
			ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		requestFactory.setReadTimeout(properties.getTimeout());
		RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable(RestClient::builder)
			.clone()
			.requestFactory(requestFactory);
		return TypeSafeClient.builder()
			.apiKey(properties.getApiKey())
			.baseUrl(properties.getBaseUrl())
			.defaultModel(properties.getModel())
			.timeout(properties.getTimeout())
			.retryPolicy(properties.toRetryPolicy())
			.restClientBuilder(restClientBuilder)
			.build();
	}

}
