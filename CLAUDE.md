# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Sunshine AI Platform — 企业级 AI 中台。AgentScope-Java + Spring Cloud Alibaba + Vue3/Naive UI。
**阶段一已完成**；**阶段二 2.1 认证与用户体系已实现**（Sa-Token JWT、Gateway 鉴权、登录/注册 UI），Tool Manager / 脱敏等待开发。

## Build & Run Commands

```bash
# 后端（JDK 21）
mvn clean package -DskipTests
mvn compile -pl <module> -am

# 本地服务链（Nacos 配置，需 ecs4c16g 可达 + docs/nacos 已 sync）
powershell -ExecutionPolicy Bypass -File scripts/sync-nacos.ps1
powershell -ExecutionPolicy Bypass -File scripts/start.ps1
# 顺序：llm-gateway:8300 → rag:8400 → orchestrator:8200 → auth-center:8100 → bff:8001 → gateway:8000

# 前端
cd sunshine-ui && npm run dev    # :5173，/api → Gateway :8000

# 认证验收（服务已启）
powershell -ExecutionPolicy Bypass -File scripts/phase2-auth-demo.ps1

# 单测
mvn test -pl auth-center "-Dtest=AuthControllerTest" -q
mvn test -pl orchestrator -am
mvn test -pl llm-gateway -am "-Dtest=QwenAdapterTest,ModelRouterTest" -q
```

**Java**：本机默认可能是 17，需 `switch-java 21` 或 `C:\Program Files\Java\jdk-21`。
**首次 auth**：`CREATE DATABASE sunshine_auth;`，`scripts/sync-nacos.ps1` 上传全部 Nacos 配置。

## Architecture

### Request Flow（当前）

```
Browser (:5173)
  → Gateway (:8000)  [Sa-Token JWT 校验，注入 x-user-id]
       ├─ /api/auth/** → auth-center (:8100)
       └─ /api/**      → BFF (:8001) → Orchestrator (:8200) → LLM GW (:8300)
SSE 流式：浏览器直连 Gateway :8000（避免 Vite 缓冲），Header 带 Authorization: Bearer
```

### Key Modules

| 模块 | 职责 | 状态 |
|------|------|:--:|
| `gateway` | 路由、JWT 鉴权、header 注入 | ✅ 2.1 |
| `auth-center` | 注册/登录、sys_user、Sa-Token JWT | ✅ 2.1 |
| `bff` | SSE/会话 CRUD 透传，信任 Gateway 注入的 x-user-id | ✅ |
| `orchestrator` | ReActAgent + 会话持久化 | ✅ |
| `llm-gateway` | LlmAdapter + ModelRouter + 缓存 | ✅ |
| `rag-service` | Milvus + Embedding | 🔶 |
| `tool-manager` / `desensitize` / 业务模拟 | 阶段二后续 | 🟡 |

### Critical Design Decisions

1. **OpenAIChatModel** 对接自建 LLM Gateway `/v1/chat/completions`（非 DashScope 专有路径）。
2. **Gateway 集中鉴权**：BFF/Orchestrator 不验 Token，只读 `x-user-id`；客户端不得自填该 header。
3. **BFF 只做透传**：SSE 与 CRUD 原样转发。
4. **不做多租户**：`x-tenant-id` 固定 `default`。
5. **ChatCompletionResponse**：`@Builder` 须配合 `@NoArgsConstructor` + `@AllArgsConstructor`。

## Server Infrastructure（ecs4c16g）

| 组件 | 端口 | 凭据 |
|------|------|------|
| Nacos | 8848 | nacos/nacos |
| MySQL | 3306 | root/root123 |
| Redis | 6379 | redis123（auth 用 DB index 1） |
| Milvus | 19530 | — |

各服务 `application.yml` 仅 Nacos 入口；**业务配置唯一维护于 `docs/nacos/`**，用 `scripts/sync-nacos.ps1` 同步线上。

## Version Constraints

- 勿升级 Spring Boot 3.3+、AgentScope 2.0.0。
- Sa-Token **1.45.0**；JWT 需 `sa-token-jwt` 依赖。

## Frontend UI（sunshine-ui）

**风格**：Codex 桌面端 — 中性灰阶、扁平；品牌金色仅 Logo。
**令牌 SSOT**：`src/styles/global.css`（`--sun-*`）；Naive 主题覆盖在 `App.vue`（含 `NMessageProvider` + `NDialogProvider`）。

| 用途 | 变量 |
|------|------|
| 页面底 | `--sun-black` |
| 卡片/输入 | `--sun-surface` |
| 主按钮 | `--sun-accent`（随 Naive primary） |
| 圆角/间距 | `--radius-md`、`--gap-md` |

**路由**

| 路径 | 说明 |
|------|------|
| `/login`, `/register` | 公开页，居中卡片（`.auth-page`） |
| `/chat`, `/knowledge`, `/status` | 需登录，MainLayout 侧栏布局 |

**认证**：`authStore` + `localStorage` key `sunshine-token`；`apiHeaders()` 发 `Authorization: Bearer`；未登录跳转 `/login?redirect=`。
**API**：CRUD 走相对路径 `/api`（Vite → Gateway）；SSE 用 `BFF_STREAM_BASE` 默认 `http://localhost:8000`。
**约定**：Inter + JetBrains Mono；明暗 `useTheme`；弹窗 `.sunshine-dialog`；图标 SVG；可点击加 `cursor-pointer`。

## other

- 禁止保存临时脚本，用完即删
- 上线 auth 前可执行 `scripts/phase2-auth-reset.sql` 清会话表
