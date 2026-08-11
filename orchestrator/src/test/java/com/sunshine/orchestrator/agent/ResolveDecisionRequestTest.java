package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ResolveDecisionRequest JSON 契约：POST body 使用 answers[]，非旧 choice/customInput */
class ResolveDecisionRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("answers[] JSON 反序列化为 DecisionAnswer 列表")
    void deserialize_answersArray() throws Exception {
        String json = """
                {
                  "answers": [
                    {"questionId": "q1", "selectedOptionIds": ["agent"], "customInput": null},
                    {"questionId": "q2", "selectedOptionIds": ["__custom__"], "customInput": "补充说明"}
                  ]
                }
                """;

        ResolveDecisionRequest body = objectMapper.readValue(json, ResolveDecisionRequest.class);

        assertThat(body.answers()).hasSize(2);
        assertThat(body.answers().get(0).questionId()).isEqualTo("q1");
        assertThat(body.answers().get(0).selectedOptionIds()).containsExactly("agent");
        assertThat(body.answers().get(1).customInput()).isEqualTo("补充说明");
    }

    @Test
    @DisplayName("空 answers 数组合法")
    void deserialize_emptyAnswersArray() throws Exception {
        ResolveDecisionRequest body = objectMapper.readValue(
                "{\"answers\": []}", ResolveDecisionRequest.class);
        assertThat(body.answers()).isEmpty();
        assertThat(body.skip()).isNull();
    }

    @Test
    @DisplayName("skip=true 合法")
    void deserialize_skipTrue() throws Exception {
        ResolveDecisionRequest body = objectMapper.readValue(
                "{\"skip\": true}", ResolveDecisionRequest.class);
        assertThat(body.skip()).isTrue();
        assertThat(body.answers()).isNull();
    }
}
