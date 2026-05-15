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
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
 * Dynamically registers individual {@link AddressStructurer} and
 * {@link ConfidenceCalibrator} beans for each LLM provider configured
 * under {@code enrichment.llm.providers}.
 *
 * <p>Uses {@link BeanDefinitionRegistryPostProcessor} so each provider
 * becomes a first-class Spring bean that Spring can collect into
 * {@code List<AddressStructurer>} for the cascade orchestrator.
 */
@Configuration
@ConditionalOnProperty(name = "enrichment.llm.enabled", havingValue = "true")
@EnableConfigurationProperties(LlmProperties.class)
public class LlmGatewayConfig implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private static final Logger LOG = LoggerFactory.getLogger(LlmGatewayConfig.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        var props = Binder.get(environment)
                .bind("enrichment.llm", LlmProperties.class)
                .orElse(null);
        if (props == null || props.providers() == null || props.providers().isEmpty()) {
            LOG.warn("LLM enabled but no providers configured");
            return;
        }

        var registered = new ArrayList<String>();
        for (var entry : props.providers().entrySet()) {
            var name = entry.getKey();
            var config = entry.getValue();

            if (config.apiKey() == null || config.apiKey().isBlank()) {
                LOG.warn("LLM provider '{}' has no API key — skipping", name);
                continue;
            }

            // Register AddressStructurer bean
            var structurerDef = new RootBeanDefinition();
            structurerDef.setBeanClass(LlmAddressStructurer.class);
            structurerDef.setInstanceSupplier(() -> {
                try {
                    return createStructurer(name, config, props);
                } catch (Exception e) {
                    LOG.error("Failed to create LLM provider '{}': {}", name, e.getMessage(), e);
                    throw e;
                }
            });
            registry.registerBeanDefinition("llmStructurer-" + name, structurerDef);

            // Register ConfidenceCalibrator bean
            var calibratorDef = new RootBeanDefinition();
            calibratorDef.setBeanClass(LlmConfidenceCalibrator.class);
            calibratorDef.setInstanceSupplier(() -> new LlmConfidenceCalibrator(name));
            registry.registerBeanDefinition("llmCalibrator-" + name, calibratorDef);

            LOG.info("LLM provider '{}': type={}, model={}, endpoint={}",
                    name, config.type(), config.model(), config.endpoint());
            registered.add(name);
        }

        LOG.info("{} LLM structurer(s) registered: {}", registered.size(), registered);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // no-op
    }

    private AddressStructurer createStructurer(String name, LlmProperties.ProviderConfig config,
                                                LlmProperties props) {
        var objectMapper = new ObjectMapper();
        var cb = io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults(name + "-cb");
        var client = createClient(name, config, objectMapper, cb);
        var allowedFields = parseAllowedFields(props);
        var promptLoader = createPromptLoader(props, objectMapper);
        return new LlmAddressStructurer(name, client, promptLoader, objectMapper, allowedFields);
    }

    @Bean
    public PromptTemplateLoader llmPromptTemplateLoader(LlmProperties props, ObjectMapper objectMapper) {
        return createPromptLoader(props, objectMapper);
    }

    private LlmModelClient createClient(String name, LlmProperties.ProviderConfig config,
                                         ObjectMapper objectMapper,
                                         io.github.resilience4j.circuitbreaker.CircuitBreaker cb) {
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
                catch (IOException e) { LOG.debug("Failed to load country supplement for {}: {}", code, e.getMessage(), e); }
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
        props.fieldsAllowed().forEach(n -> { try { fields.add(AddressField.valueOf(n)); } catch (Exception e) { LOG.warn("Unknown LLM field name '{}': {}", n, e.getMessage(), e); } });
        return fields.isEmpty() ? EnumSet.allOf(AddressField.class) : fields;
    }
}
