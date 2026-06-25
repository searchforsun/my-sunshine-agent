package com.sunshine.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Gateway 按租户 QPS 限流（Sentinel gw-flow + x-tenant-id 热点参数）。 */
@Data
@Component
@ConfigurationProperties(prefix = "sunshine.gateway.tenant-qps")
public class GatewayTenantQpsProperties {

    /** 是否启用（false 时请在 Nacos 清空 gw-flow 规则） */
    private boolean enabled = true;
}
