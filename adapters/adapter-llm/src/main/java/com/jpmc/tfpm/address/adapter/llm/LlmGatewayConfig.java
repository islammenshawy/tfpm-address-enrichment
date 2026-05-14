package com.jpmc.tfpm.address.adapter.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.tfpm.address.adapter.llm.client.OpenAiCompatibleLlmClient;
import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.LlmModelClient;
import com.jpmc.tfpm.address.domain.LlmModelClient.LlmModelMetadata;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Dynamically creates LLM structurer beans from config.
 * Each entry under {@code enrichment.llm.providers} becomes a separate
 * {@link AddressStructurer} + {@link ConfidenceCalibrator} pair.
 *
 * <p>Config-driven: add/remove YAML entries to change the model set.
 * No code changes needed to add a new LLM provider.
 */
@Configuration
@ConditionalOnProperty(name = "enrichment.llm.enabled", havingValue = "true")
@EnableConfigurationProperties(LlmProperties.class)
public class LlmGatewayConfig {

    private static final Logger LOG = LoggerFactory.getLogger(LlmGatewayConfig.class);

    @Bean
    public List<AddressStructurer> llmStructurers(LlmProperties props, ObjectMapper objectMapper,
                                                   CircuitBreakerRegistry cbRegistry) {
        if (props.providers() == null || props.providers().isEmpty()) {
            LOG.warn("LLM enabled but no providers configured");
            return List.of();
        }

        var structurers = new ArrayList<AddressStructurer>();
        var allowedFields = parseAllowedFields(props);
        var promptLoader = createPromptLoader(props, objectMapper);

        for (var entry : props.providers().entrySet()) {
            var name = entry.getKey();
            var config = entry.getValue();

            if (config.apiKey() == null || config.apiKey().isBlank()) {
                LOG.warn("LLM provider '{}' has no API key — skipping", name);
                continue;
            }

            try {
                var client = createClient(name, config, objectMapper, cbRegistry);
                structurers.add(new LlmAddressStructurer(name, client, promptLoader, objectMapper, allowedFields));
                LOG.info("LLM provider '{}': type={}, model={}, endpoint={}",
                        name, config.type(), config.model(), config.endpoint());
            } catch (Exception e) {
                LOG.error("Failed to create LLM provider '{}': {}", name, e.getMessage());
            }
        }

        LOG.info("{} LLM structurer(s) registered: {}",
                structurers.size(), structurers.stream().map(AddressStructurer::name).toList());
        return structurers;
    }

    @Bean
    public List<ConfidenceCalibrator> llmCalibrators(LlmProperties props) {
        if (props.providers() == null) return List.of();
        return props.providers().keySet().stream()
                .map(name -> (ConfidenceCalibrator) new LlmConfidenceCalibrator(name))
                .toList();
    }

    @Bean
    public PromptTemplateLoader llmPromptTemplateLoader(LlmProperties props, ObjectMapper objectMapper) {
        return createPromptLoader(props, objectMapper);
    }

    private LlmModelClient createClient(String name, LlmProperties.ProviderConfig config,
                                         ObjectMapper objectMapper, CircuitBreakerRegistry cbRegistry) {
        var cb = cbRegistry.circuitBreaker(name + "-cb");
        var model = config.model() != null ? config.model() : "default";
        var metadata = new LlmModelMetadata(config.type(), model, false, 0, 0,
                Duration.ofMillis(config.timeoutMs()));
        var webClient = createWebClient(name, config);

        // Azure uses api-key header (set on WebClient), empty bearer
        var bearer = "azure-openai".equals(config.type()) ? "" : config.apiKey();
        return new OpenAiCompatibleLlmClient(name, metadata, webClient, objectMapper, bearer, cb, 2);
    }

    private WebClient createWebClient(String name, LlmProperties.ProviderConfig config) {
        var provider = ConnectionProvider.builder(name + "-pool")
                .maxConnections(20).pendingAcquireMaxCount(30)
                .maxIdleTime(Duration.ofSeconds(30)).maxLifeTime(Duration.ofMinutes(5))
                .build();
        var builder = WebClient.builder()
                .baseUrl(config.endpoint())
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create(provider)));

        if ("azure-openai".equals(config.type())) {
            builder.defaultHeader("api-key", config.apiKey());
            var apiVersion = config.apiVersion() != null ? config.apiVersion() : "2024-02-15-preview";
            builder.filter((request, next) -> {
                var uri = UriComponentsBuilder.fromUri(request.url())
                        .queryParam("api-version", apiVersion).build().toUri();
                return next.exchange(ClientRequest.from(request).url(uri).build());
            });
        }

        return builder.build();
    }

    private PromptTemplateLoader createPromptLoader(LlmProperties props, ObjectMapper objectMapper) {
        var path = props.prompt() != null && props.prompt().templateResource() != null
                ? props.prompt().templateResource() : "classpath:prompts/address-structuring.json";
        var loader = new PromptTemplateLoader(new DefaultResourceLoader().getResource(path), objectMapper);
        for (var code : new String[]{"AE", "CN", "SG", "HK", "GB", "US", "DE", "CH"}) {
            var res = new DefaultResourceLoader().getResource("classpath:prompts/countries/" + code + ".md");
            if (res.exists()) {
                try { loader.registerCountrySupplement(code, res.getContentAsString(StandardCharsets.UTF_8)); }
                catch (IOException ignored) {}
            }
        }
        return loader;
    }

    private Set<AddressField> parseAllowedFields(LlmProperties props) {
        if (props.fieldsAllowed() == null || props.fieldsAllowed().isEmpty()) {
            return EnumSet.of(AddressField.CTRY, AddressField.TWN_NM, AddressField.PST_CD,
                    AddressField.CTRY_SUB_DVSN, AddressField.STRT_NM, AddressField.BLDG_NB, AddressField.BLDG_NM);
        }
        var fields = EnumSet.noneOf(AddressField.class);
        props.fieldsAllowed().forEach(n -> { try { fields.add(AddressField.valueOf(n)); } catch (Exception ignored) {} });
        return fields.isEmpty() ? EnumSet.allOf(AddressField.class) : fields;
    }
}
