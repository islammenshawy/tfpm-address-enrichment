package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.LlmModelClient.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LlmModelClient types")
class LlmModelClientTest {

    @Nested
    @DisplayName("LlmModelMetadata")
    class MetadataTests {

        @Test
        void valid_construction() {
            var meta = new LlmModelMetadata(
                    "openai-compatible", "gpt-4o", true, 128000, 4096,
                    Duration.ofSeconds(2));
            assertThat(meta.providerType()).isEqualTo("openai-compatible");
            assertThat(meta.modelId()).isEqualTo("gpt-4o");
            assertThat(meta.supportsStreaming()).isTrue();
            assertThat(meta.maxInputTokens()).isEqualTo(128000);
            assertThat(meta.maxOutputTokens()).isEqualTo(4096);
            assertThat(meta.defaultTimeout()).isEqualTo(Duration.ofSeconds(2));
        }

        @Test
        void rejects_null_provider_type() {
            assertThatThrownBy(() -> new LlmModelMetadata(null, "m", false, 0, 0, Duration.ZERO))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejects_null_model_id() {
            assertThatThrownBy(() -> new LlmModelMetadata("p", null, false, 0, 0, Duration.ZERO))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejects_null_timeout() {
            assertThatThrownBy(() -> new LlmModelMetadata("p", "m", false, 0, 0, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("LlmCompletionRequest")
    class RequestTests {

        private LlmCompletionRequest.Message userMsg(String content) {
            return new LlmCompletionRequest.Message(
                    LlmCompletionRequest.Message.Role.USER, content);
        }

        @Test
        void valid_construction() {
            var req = new LlmCompletionRequest(
                    "You are a helper", List.of(userMsg("hello")),
                    100, 0.7, null, "corr-1", Map.of());
            assertThat(req.systemPrompt()).isEqualTo("You are a helper");
            assertThat(req.messages()).hasSize(1);
            assertThat(req.maxTokens()).isEqualTo(100);
            assertThat(req.temperature()).isEqualTo(0.7);
            assertThat(req.correlationId()).isEqualTo("corr-1");
        }

        @Test
        void rejects_empty_messages() {
            assertThatThrownBy(() -> new LlmCompletionRequest(
                    "", List.of(), 0, null, null, "corr", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        void rejects_negative_max_tokens() {
            assertThatThrownBy(() -> new LlmCompletionRequest(
                    "", List.of(userMsg("hi")), -1, null, null, "corr", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTokens");
        }

        @Test
        void rejects_temperature_below_zero() {
            assertThatThrownBy(() -> new LlmCompletionRequest(
                    "", List.of(userMsg("hi")), 0, -0.1, null, "corr", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("temperature");
        }

        @Test
        void rejects_temperature_above_two() {
            assertThatThrownBy(() -> new LlmCompletionRequest(
                    "", List.of(userMsg("hi")), 0, 2.1, null, "corr", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void accepts_null_temperature() {
            var req = new LlmCompletionRequest(
                    "", List.of(userMsg("hi")), 0, null, null, "corr", Map.of());
            assertThat(req.temperature()).isNull();
        }

        @Test
        void accepts_boundary_temperatures() {
            new LlmCompletionRequest("", List.of(userMsg("hi")), 0, 0.0, null, "c", Map.of());
            new LlmCompletionRequest("", List.of(userMsg("hi")), 0, 2.0, null, "c", Map.of());
        }

        @Test
        void messages_are_defensively_copied() {
            var msgs = new java.util.ArrayList<>(List.of(userMsg("hi")));
            var req = new LlmCompletionRequest("", msgs, 0, null, null, "c", Map.of());
            msgs.add(userMsg("added"));
            assertThat(req.messages()).hasSize(1);
        }

        @Test
        void metadata_is_defensively_copied() {
            var meta = new HashMap<String, String>();
            meta.put("key", "val");
            var req = new LlmCompletionRequest(
                    "", List.of(userMsg("hi")), 0, null, null, "c", meta);
            meta.put("added", "val2");
            assertThat(req.metadata()).doesNotContainKey("added");
        }

        @Test
        void rejects_null_system_prompt() {
            assertThatThrownBy(() -> new LlmCompletionRequest(
                    null, List.of(userMsg("hi")), 0, null, null, "c", Map.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejects_null_messages() {
            assertThatThrownBy(() -> new LlmCompletionRequest(
                    "", null, 0, null, null, "c", Map.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejects_null_correlation_id() {
            assertThatThrownBy(() -> new LlmCompletionRequest(
                    "", List.of(userMsg("hi")), 0, null, null, null, Map.of()))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("LlmCompletionResponse")
    class ResponseTests {

        @Test
        void valid_construction() {
            var resp = new LlmCompletionResponse(
                    "content", LlmCompletionResponse.FinishReason.STOP,
                    50, 20, Duration.ofMillis(200), "prov-123");
            assertThat(resp.content()).isEqualTo("content");
            assertThat(resp.finishReason()).isEqualTo(LlmCompletionResponse.FinishReason.STOP);
            assertThat(resp.inputTokens()).isEqualTo(50);
            assertThat(resp.outputTokens()).isEqualTo(20);
        }

        @Test
        void all_finish_reasons_exist() {
            assertThat(LlmCompletionResponse.FinishReason.values()).containsExactly(
                    LlmCompletionResponse.FinishReason.STOP,
                    LlmCompletionResponse.FinishReason.LENGTH,
                    LlmCompletionResponse.FinishReason.CONTENT_FILTER,
                    LlmCompletionResponse.FinishReason.TOOL_CALL,
                    LlmCompletionResponse.FinishReason.ERROR,
                    LlmCompletionResponse.FinishReason.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("LlmStreamChunk")
    class StreamChunkTests {

        @Test
        void non_final_chunk() {
            var chunk = new LlmStreamChunk("hello ", null, 0, 0);
            assertThat(chunk.isFinal()).isFalse();
            assertThat(chunk.deltaContent()).isEqualTo("hello ");
        }

        @Test
        void final_chunk() {
            var chunk = new LlmStreamChunk("",
                    LlmCompletionResponse.FinishReason.STOP, 50, 20);
            assertThat(chunk.isFinal()).isTrue();
        }

        @Test
        void rejects_null_delta_content() {
            assertThatThrownBy(() -> new LlmStreamChunk(null, null, 0, 0))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("OutputFormat")
    class OutputFormatTests {

        @Test
        void text_format() {
            var fmt = new LlmCompletionRequest.OutputFormat.Text();
            assertThat(fmt).isInstanceOf(LlmCompletionRequest.OutputFormat.class);
        }

        @Test
        void json_format() {
            var fmt = new LlmCompletionRequest.OutputFormat.Json("{\"type\":\"object\"}");
            assertThat(fmt.jsonSchema()).isEqualTo("{\"type\":\"object\"}");
        }

        @Test
        void json_format_rejects_null_schema() {
            assertThatThrownBy(() -> new LlmCompletionRequest.OutputFormat.Json(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Message")
    class MessageTests {

        @Test
        void valid_construction() {
            var msg = new LlmCompletionRequest.Message(
                    LlmCompletionRequest.Message.Role.USER, "hello");
            assertThat(msg.role()).isEqualTo(LlmCompletionRequest.Message.Role.USER);
            assertThat(msg.content()).isEqualTo("hello");
        }

        @Test
        void all_roles_exist() {
            assertThat(LlmCompletionRequest.Message.Role.values()).containsExactly(
                    LlmCompletionRequest.Message.Role.SYSTEM,
                    LlmCompletionRequest.Message.Role.USER,
                    LlmCompletionRequest.Message.Role.ASSISTANT);
        }

        @Test
        void rejects_null_role() {
            assertThatThrownBy(() -> new LlmCompletionRequest.Message(null, "hi"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejects_null_content() {
            assertThatThrownBy(() -> new LlmCompletionRequest.Message(
                    LlmCompletionRequest.Message.Role.USER, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
