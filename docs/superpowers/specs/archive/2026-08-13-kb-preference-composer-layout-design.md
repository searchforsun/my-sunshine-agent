# 默认知识库迁入对话偏好 · 分支进输入栏

> **日期**：2026-08-13  
> **状态**：✅ 已实现  
> **定位**：输入栏去掉知识库选择；账号级默认知识库进「设置 → 对话偏好」并持久化；task 分支选择挪到原知识库位。

## 拍板

| 项 | 结论 |
|----|------|
| 知识库入口 | 「对话偏好」：当前租户下方「默认知识库」 |
| 保存语义 | 写账号 `defaultKbId`，并覆盖当前会话 `kbId` |
| chat / task UI | 输入栏均不展示知识库；task 仍发送同一默认 `kbId` |
| 分支 | task / newTask / pendingWorkspace：进 `composer-toolbar-left`（「快速」旁） |
| 持久化 | 方案 2：`sys_user.default_kb_id` + `/me` / `login` / `updateProfile` |

## 改动面

- **auth**：`default_kb_id` 列；DTO / `UserService`；单测 `updateProfile_changesDefaultKbId`
- **UI**：`UserSettingsModal` 增加知识库；`ChatView` 去 `KbSelector`、分支进工具条；`useKbPreference` 跟账号同步（旧 localStorage 一次性回落）
- **DDL**：`docker/mysql/init/10-sunshine-auth.sql`；现网需 `ALTER`（已执行）

## 不做

- 会话级独立知识库入口（菜单/更多）
- auth 强校验「kb ∈ 租户」
- 改 orchestrator `DefaultKbResolver` 兜底链
