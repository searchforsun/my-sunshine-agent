# Sentinel Dashboard — Gateway 租户 QPS（3.5.3）

## 访问

| 项 | 值 |
|----|-----|
| URL | http://ecs4c16g:8858 |
| 账号 | `sentinel` / `sentinel123` |
| 应用 | `sunshine-gateway`（**appType=11** 网关类型） |
| 规则 SSOT | `docs/nacos/sunshine-gateway-gw-flow-rules.json` → Nacos `sunshine-gateway-gw-flow-rules.json` |

## Dashboard 查看路径

1. 登录 → **菜单「网关流控」**（仅 `appType=网关` 时出现）
2. **流控规则** → 选择应用 `sunshine-gateway`、机器 IP:8720
3. 应看到 route `sunshine-bff-api`，参数位置 **Header `x-tenant-id`**，阈值 30 QPS/秒
4. **实时监控** → 资源 `sunshine-bff-api` → 热点参数值 `tenant-a` / `tenant-b` / `default` 的通过 QPS、拒绝 QPS

## 配置要点（`sunshine-gateway.yaml`）

```yaml
spring.cloud.sentinel:
  eager: true                    # 启动即注册，Dashboard 可见机器
  transport:
    dashboard: ecs4c16g:8858
    port: 8720                   # Gateway 暴露 Sentinel 命令端口
    client-ip: <Dashboard 能访问的 IP>  # 跨机部署必填
  datasource.gw-flow.nacos:      # gw-flow 规则，Dashboard 可拉取
    rule-type: gw-flow
```

**跨机联调**：Gateway 在开发机、Dashboard 在 `ecs4c16g` 时，Dashboard 无法轮询开发机 `8720`，流控规则列表可能超时。处理方式：

- 生产/联调：Gateway 与 Dashboard 同网段，设置 `spring.cloud.sentinel.transport.client-ip` 为 Dashboard 可达 IP
- 本地：直接访问 `http://127.0.0.1:8720/gateway/getRules` 校验规则
- 指标：本机 `~/logs/csp/sentinel-block.log` 可见 `ParamFlowException,tenant-a`

## 运维命令

```bash
# 同步 gw-flow 规则 + gateway 配置
python scripts/sync_nacos.py --data-id sunshine-gateway.yaml
python scripts/sync_nacos.py --data-id sunshine-gateway-gw-flow-rules.json

# 重启 gateway
# Live 验收
python scripts/verify_sentinel_dashboard.py
python scripts/verify_tenant_qps_live.py --burst 20
```

## 与 Grafana 分工

| 能力 | Sentinel Dashboard | Grafana (`docs/grafana/`) |
|------|-------------------|---------------------------|
| 租户 QPS / 429 | ✅ 网关热点参数 | — |
| RAG Recall / 检索延迟 | — | ✅ rag-dashboard |
