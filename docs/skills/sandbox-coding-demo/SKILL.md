---
name: sandbox-coding-demo
description: 4.5 Docker 沙箱 Coding Agent 示例（读 /skills/{id}、写 /workspace、exec）
---

# 沙箱编码演示 Skill

面向 **Skills Docker 沙箱（4.5）** 联调：在隔离容器内使用 `sandbox__read` / `write` / `edit` / `glob` / `grep` / `exec`。

## 适用场景

- 验证沙箱工具注入与 HITL（写操作需确认）
- 读取本包 `scripts/`、`references/`（容器内挂载为只读 `/skills/sandbox-coding-demo/`）
- 在可写 `/workspace` 生成或修改文件后执行命令
- 同一会话可再 `@` 其他 docker Skill，物料并存于 `/skills/{skillId}/`，`/workspace` 保留

## 操作步骤（Agent）

1. 用 `sandbox__glob` 或 `sandbox__read` 查看 `/skills/sandbox-coding-demo/scripts`、`/skills/sandbox-coding-demo/references`
2. 需要改文件时：只写 `/workspace/...`（禁止写 `/skills`）
3. 优先用 `sandbox__edit` 做精确修改；搜索用 `sandbox__grep`
4. 运行脚本：`sandbox__exec`，例如  
   `python /skills/sandbox-coding-demo/scripts/hello.py`  
   或先把脚本拷到 workspace 再跑
5. 只读命令（`ls` / `pwd` / `python -m pytest *`）一般免 HITL；写文件与其它 exec 需用户确认

## 试跑提示词

```text
@sandbox-coding-demo 请用沙箱工具：读取 /skills/sandbox-coding-demo 下脚本，在 /workspace 写 test.txt，再 ls
```

## 约束

- 仅使用 `sandbox__*` 完成文件与命令操作；勿用 `sandbox__exec` 代替 read/grep/glob/edit
- 禁止越狱路径（`/tmp`、`..` 逃逸等）
- 默认无外网；需要 pip 等外连时由管理员配置会话级 `agent.sandbox.runtime.network-allow`
