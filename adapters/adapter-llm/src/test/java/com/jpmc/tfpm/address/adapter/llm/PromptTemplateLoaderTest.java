package com.jpmc.tfpm.address.adapter.llm;

import com.jpmc.tfpm.address.domain.RawAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PromptTemplateLoader")
class PromptTemplateLoaderTest {

    @Test
    void loads_template_and_renders() {
        var loader = new PromptTemplateLoader(
                new ClassPathResource("prompts/address-structuring.json"), null);

        var rendered = loader.render(new RawAddress("123 Main St, NYC", "US", "en"));

        assertThat(rendered.systemPrompt()).contains("address parsing engine");
        assertThat(rendered.userMessage()).contains("123 Main St, NYC");
        assertThat(rendered.userMessage()).contains("US");
        assertThat(rendered.maxTokens()).isGreaterThan(0);
    }

    @Test
    void country_supplement_appended_to_system_prompt() {
        var loader = new PromptTemplateLoader(
                new ClassPathResource("prompts/address-structuring.json"), null);
        loader.registerCountrySupplement("AE", "Emirates have specific formats.");

        var rendered = loader.render(new RawAddress("addr", "AE", "ar"));

        assertThat(rendered.systemPrompt()).contains("Emirates have specific formats.");
        assertThat(rendered.systemPrompt()).contains("Country-specific guidance for AE");
    }

    @Test
    void unknown_country_has_no_supplement() {
        var loader = new PromptTemplateLoader(
                new ClassPathResource("prompts/address-structuring.json"), null);

        var rendered = loader.render(new RawAddress("addr", "ZZ", "en"));

        assertThat(rendered.systemPrompt()).doesNotContain("Country-specific guidance");
    }
}
