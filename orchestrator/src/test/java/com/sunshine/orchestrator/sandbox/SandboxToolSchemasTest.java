package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.config.AgentSandboxProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxToolSchemasTest {

    @Test
    void defaultsCoverAllSandboxToolsWithRequiredFields() {
        AgentSandboxProperties properties = new AgentSandboxProperties();
        for (String toolId : SandboxIds.ALL) {
            AgentSandboxProperties.ToolDef def = properties.resolveTool(toolId);
            assertThat(def).as(toolId).isNotNull();
            assertThat(def.getDisplayName()).as(toolId + " displayName").isNotBlank();
            assertThat(def.getDescription()).as(toolId + " description").isNotBlank();
            assertThat(def.getProperties()).as(toolId + " properties").isNotEmpty();
            assertThat(def.getRequired()).as(toolId + " required").isNotEmpty();
        }
    }

    @Test
    void readSchemaMatchesContract() {
        AgentSandboxProperties properties = new AgentSandboxProperties();
        Map<String, Object> schema = SandboxToolSchemas.toParameters(properties.resolveTool(SandboxIds.READ));
        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKeys("path", "offset", "limit");
        assertThat(schema.get("required")).isEqualTo(List.of("path"));
    }

    @Test
    void displayNameFallsBackToToolIdWhenBlank() {
        AgentSandboxProperties properties = new AgentSandboxProperties();
        AgentSandboxProperties.ToolDef def = new AgentSandboxProperties.ToolDef();
        def.setDisplayName("  ");
        properties.getTools().put(SandboxIds.READ, def);
        assertThat(properties.displayName(SandboxIds.READ)).isEqualTo(SandboxIds.READ);
    }
}
