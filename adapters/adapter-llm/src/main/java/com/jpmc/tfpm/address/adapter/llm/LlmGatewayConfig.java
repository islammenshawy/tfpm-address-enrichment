package com.jpmc.tfpm.address.adapter.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.tfpm.address.adapter.llm.client.JpmcInternalGatewayLlmClient;
import com.jpmc.tfpm.address.adapter.llm.client.OpenAiCompatibleLlmClient;
import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.LlmModelClient;
import com.jpmc.tfpm.address.domain.LlmModelClient.LlmModelMetadata;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * Wires the LLM adapter beans when {@code enrichment.llm.enabled=true}.
 * Creates the LlmModelClient, LlmAddressStructurer, LlmConfidenceCalibrator,
 * and PromptTemplateLoader.
 */
@Configuration
@ConditionalOnProperty(name = "enrichment.llm.enabled", havingValue = "true")
@EnableConfigurationProperties(LlmProperties.class)
public class LlmGatewayConfig {

    private static final Logger LOG = LoggerFactory.getLogger(LlmGatewayConfig.class);

    @Bean
    public WebClient llmWebClient(LlmProperties props) {
        var provider = ConnectionProvider.builder("llm-pool")
                .maxConnections(30)
                .pendingAcquireMaxCount(50)
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(5))
                .build();
        var httpClient = HttpClient.create(provider);
        var type = props.type() != null ? props.type() : "openai-compatible";
        var builder = WebClient.builder()
                .baseUrl(props.endpoint())
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        // Azure OpenAI uses api-key header; the client adds Bearer auth per-request
        // so we add the api-version query param for Azure here
        if ("azure-openai".equals(type)) {
            var apiVersion = System.getenv("AZURE_OPENAI_API_VERSION");
            if (apiVersion == null) apiVersion = "2024-02-15-preview";
            builder.defaultHeader("api-key", props.apiKey() != null ? props.apiKey() : "");
            builder.defaultUriVariables(java.util.Map.of("api-version", apiVersion));
            LOG.info("WebClient configured for Azure OpenAI (api-version={})", apiVersion);
        }

        return builder.build();
    }

    @Bean
    public LlmModelClient llmModelClient(
            WebClient llmWebClient,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            LlmProperties props) {
        var cb = circuitBreakerRegistry.circuitBreaker("llm-cb");
        var apiKey = props.apiKey() != null ? props.apiKey() : "";
        var type = props.type() != null ? props.type() : "openai-compatible";

        if (apiKey.isEmpty() && props.enabled()) {
            LOG.warn("LLM is enabled but API key is empty — authentication will fail");
        }

        var metadata = new LlmModelMetadata(
                type, "default", false, 0, 0,
                Duration.ofMillis(props.timeoutMs()));

        if ("jpmc-internal-gateway".equals(type)) {
            LOG.info("Creating JPMC Internal Gateway LLM client");
            var retry = retryRegistry.retry("llm-retry");
            return new JpmcInternalGatewayLlmClient(
                    "llm", metadata, llmWebClient, objectMapper,
                    () -> new JpmcInternalGatewayLlmClient.CachedToken(apiKey, java.time.Instant.MAX),
                    cb, retry, Duration.ofSeconds(15));
        }

        if ("azure-openai".equals(type)) {
            LOG.info("Creating Azure OpenAI LLM client");
            // Azure uses api-key header (set on WebClient), not Bearer token
            return new OpenAiCompatibleLlmClient(
                    "llm", metadata, llmWebClient, objectMapper,
                    "", cb, 2);
        }

        LOG.info("Creating OpenAI-compatible LLM client (type={})", type);
        return new OpenAiCompatibleLlmClient(
                "llm", metadata, llmWebClient, objectMapper,
                apiKey, cb, 2);
    }

    @Bean
    public PromptTemplateLoader llmPromptTemplateLoader(LlmProperties props, ObjectMapper objectMapper) {
        var resourceLoader = new DefaultResourceLoader();
        var templateResource = resourceLoader.getResource(props.prompt().templateResource());

        var loader = new PromptTemplateLoader(templateResource, objectMapper);

        // Load country supplements from classpath
        var countryCodes = new String[]{"AE", "CN", "SG", "HK", "GB", "US", "DE", "CH"};
        for (var code : countryCodes) {
            var supplementResource = resourceLoader.getResource(
                    "classpath:prompts/countries/" + code + ".md");
            if (supplementResource.exists()) {
                try {
                    var content = supplementResource.getContentAsString(StandardCharsets.UTF_8);
                    loader.registerCountrySupplement(code, content);
                    LOG.info("Loaded country prompt supplement for {}", code);
                } catch (IOException e) {
                    LOG.warn("Failed to load country supplement for {}: {}", code, e.getMessage());
                }
            }
        }

        return loader;
    }

    @Bean
    public AddressStructurer llmAddressStructurer(
            LlmModelClient llmModelClient,
            PromptTemplateLoader llmPromptTemplateLoader,
            ObjectMapper objectMapper,
            LlmProperties props) {
        Set<AddressField> allowed = EnumSet.noneOf(AddressField.class);
        for (var fieldName : props.fieldsAllowed()) {
            try {
                allowed.add(AddressField.valueOf(fieldName));
            } catch (IllegalArgumentException e) {
                LOG.warn("Unknown field in enrichment.llm.fields-allowed: {}", fieldName);
            }
        }
        if (allowed.isEmpty()) {
            allowed = EnumSet.of(
                    AddressField.CTRY, AddressField.TWN_NM, AddressField.PST_CD,
                    AddressField.CTRY_SUB_DVSN, AddressField.STRT_NM,
                    AddressField.BLDG_NB, AddressField.BLDG_NM);
        }
        return new LlmAddressStructurer(llmModelClient, llmPromptTemplateLoader, objectMapper, allowed);
    }

    @Bean
    public ConfidenceCalibrator llmConfidenceCalibrator() {
        return new LlmConfidenceCalibrator();
    }
}
