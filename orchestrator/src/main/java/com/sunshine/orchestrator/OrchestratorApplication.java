package com.sunshine.orchestrator;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.WorkflowProperties;
import com.sunshine.orchestrator.generation.GenerationController;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.generation.GenerationProperties;
import com.sunshine.orchestrator.memory.MemoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.sunshine")
@EnableDiscoveryClient
@EnableAsync
@EnableConfigurationProperties({GenerationProperties.class, AgentPromptProperties.class, MemoryProperties.class, WorkflowProperties.class, AgentExecutionProperties.class})
@ComponentScan(
        basePackages = "com.sunshine.orchestrator",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = GenerationController.class
        )
)
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
