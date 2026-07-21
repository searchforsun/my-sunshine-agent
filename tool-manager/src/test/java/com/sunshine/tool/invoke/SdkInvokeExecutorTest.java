package com.sunshine.tool.invoke;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.config.ToolIntegrationProperties;
import com.sunshine.tool.entity.SdkApplicationEntity;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.repo.SdkApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SdkInvokeExecutorTest {

    @Mock
    private DiscoveryClient discoveryClient;
    @Mock
    private SdkApplicationRepository sdkApplicationRepository;

    @Test
    void invoke_forwardsToSdkEndpoint_withIdentityHeaders() {
        AtomicReference<List<String>> capturedUser = new AtomicReference<>(new ArrayList<>());
        AtomicReference<List<String>> capturedTenant = new AtomicReference<>(new ArrayList<>());
        SdkInvokeExecutor executor = buildExecutor(request -> {
            capturedUser.set(request.headers().getOrEmpty("x-user-id"));
            capturedTenant.set(request.headers().getOrEmpty("x-tenant-id"));
            String path = request.url().getPath();
            if (path.contains("/sunshine/tools/invoke/list_my_expenses")) {
                String body = "{\"ok\":true,\"result\":\"2 条待审批\"}";
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
        });
        stubFinanceAppAndInstance();

        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setId("sdk__sunshine-finance__list_my_expenses");
        tool.setSourceRef("sunshine-finance");
        tool.setExternalName("list_my_expenses");
        tool.setEnabled(true);

        String result = executor.invoke(tool, Map.of("status", "pending"), "u-alice", "acme");
        assertThat(result).isEqualTo("2 条待审批");
        assertThat(capturedUser.get()).containsExactly("u-alice");
        assertThat(capturedTenant.get()).containsExactly("acme");
    }

    @Test
    void invoke_defaultsTenantHeaderWhenBlank() {
        AtomicReference<List<String>> capturedTenant = new AtomicReference<>(new ArrayList<>());
        SdkInvokeExecutor executor = buildExecutor(request -> {
            capturedTenant.set(request.headers().getOrEmpty("x-tenant-id"));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"ok\":true,\"result\":\"ok\"}")
                    .build());
        });
        stubFinanceAppAndInstance();

        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setId("sdk__sunshine-finance__list_my_expenses");
        tool.setSourceRef("sunshine-finance");
        tool.setExternalName("list_my_expenses");

        executor.invoke(tool, Map.of(), "u1", null);
        assertThat(capturedTenant.get()).containsExactly("default");
    }

    @Test
    void invoke_noInstanceThrowsOffline() {
        SdkInvokeExecutor executor = buildExecutor(stubExchange());
        SdkApplicationEntity app = new SdkApplicationEntity();
        app.setId("sunshine-finance");
        app.setNacosService("sunshine-finance");
        when(sdkApplicationRepository.findById("sunshine-finance")).thenReturn(Optional.of(app));
        when(discoveryClient.getInstances("sunshine-finance")).thenReturn(List.of());

        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setSourceRef("sunshine-finance");
        tool.setExternalName("list_my_expenses");

        assertThatThrownBy(() -> executor.invoke(tool, Map.of(), null, "default"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ToolErrorCode.SDK_APP_OFFLINE));
    }

    private void stubFinanceAppAndInstance() {
        SdkApplicationEntity app = new SdkApplicationEntity();
        app.setId("sunshine-finance");
        app.setNacosService("sunshine-finance");
        app.setInvokePath("/sunshine/tools/invoke");
        when(sdkApplicationRepository.findById("sunshine-finance")).thenReturn(Optional.of(app));

        ServiceInstance instance = new DefaultServiceInstance(
                "sunshine-finance-1", "sunshine-finance", "127.0.0.1", 8710, false,
                Map.of("sunshine.tool-app", "true"));
        when(discoveryClient.getInstances("sunshine-finance")).thenReturn(List.of(instance));
    }

    private SdkInvokeExecutor buildExecutor(ExchangeFunction exchangeFunction) {
        ToolIntegrationProperties properties = new ToolIntegrationProperties();
        properties.getSdk().setInvokeTimeoutSeconds(5);
        return new SdkInvokeExecutor(
                discoveryClient,
                sdkApplicationRepository,
                WebClient.builder().exchangeFunction(exchangeFunction),
                properties);
    }

    private ExchangeFunction stubExchange() {
        return request -> {
            String path = request.url().getPath();
            if (path.contains("/sunshine/tools/invoke/list_my_expenses")) {
                String body = "{\"ok\":true,\"result\":\"2 条待审批\"}";
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
        };
    }
}
