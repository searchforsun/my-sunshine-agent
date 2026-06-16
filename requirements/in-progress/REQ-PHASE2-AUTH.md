# REQ-PHASE2-AUTH — 阶段二 2.1 认证与用户体系

> **状态**：in-progress（设计已批准）  
> **创建**：2026-06-16  
> **关联文档**：[`docs/implementation-plan.md`](../../docs/implementation-plan.md) 任务卡 2.1

## 背景

阶段一 / 1.5 使用前端随机 `x-user-id` + localStorage 做轻量用户隔离，无真实登录。阶段二入口需建立 Sa-Token JWT 认证，替换匿名身份，满足检查门「无效 Token → 401」。

## 目标

1. 开放注册（用户名 + 密码）与登录 / 登出。
2. Gateway 集中 JWT 校验，解析 `userId` 注入 `x-user-id`。
3. 前端登录 / 注册页、路由守卫、Bearer Token 请求。
4. 上线前强制清库，会话从零开始。

## 已确认决策

| 项 | 决策 |
|----|------|
| 设计范围 | 仅 2.1 认证与用户体系 |
| 架构方案 | Gateway 集中鉴权（方案 1） |
| 多租户 | **不做**（`x-tenant-id` 固定 `default`） |
| 注册 | 开放注册 |
| 注册后 | 跳转登录页（不自动登录） |
| Token | 单 Token Sa-Token JWT，`Authorization: Bearer` |
| 历史会话 | 强制清库 + 清 localStorage（不迁移） |

## 范围

**包含：**

- `auth-center` 用户表、注册 / 登录 / 登出 / me API
- `gateway` Sa-Token Reactor 鉴权 + `/api/auth/**` 路由
- `bff` / `orchestrator` 移除 `x-user-id` 默认值
- `sunshine-ui` 登录 / 注册页、`authStore`、路由守卫
- Nacos 配置、`scripts/start.ps1` 纳入 auth-center
- Vite 代理改指向 Gateway `:8000`

**不包含：**

- RBAC 细粒度权限、OAuth / SSO
- Refresh Token、Cookie HttpOnly 方案
- 多租户管理
- `/v1/**` LLM Gateway 鉴权（后续独立）
- 登录限流（可阶段三 Sentinel 补充）

## 验收标准（需求级）

- [ ] 开放注册；重复用户名 409
- [ ] 登录返回 Token；错误密码 401
- [ ] 无 Token / 无效 Token 访问 `/api/**` → 401（Gateway）
- [ ] 有效 Token 下会话 CRUD 与 SSE 对话正常，userId 隔离有效
- [ ] 登出后 Token 失效
- [ ] 前端未登录访问 `/chat` 跳转 `/login`
- [ ] 客户端伪造 `x-user-id` 无效（以 Token 解析为准）
- [ ] `docs/implementation-plan.md` 阶段二检查门 JWT 项 `[x]`

## 设计文档

[`REQ-PHASE2-AUTH-design.md`](./REQ-PHASE2-AUTH-design.md)

## 实施计划

[`REQ-PHASE2-AUTH-task.md`](./REQ-PHASE2-AUTH-task.md)
