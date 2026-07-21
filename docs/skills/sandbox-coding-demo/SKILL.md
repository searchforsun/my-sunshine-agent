---
name: sandbox-coding-demo
description: 企业工作区沙箱编程（读 /skills/{id}、写 /workspace、exec）
---

# 工作区沙箱编程

面向隔离 Docker 沙箱：使用 `sandbox__read` / `write` / `edit` / `glob` / `grep` / `exec` 完成文件与命令操作。Skill 物料只读挂载；可写区为对话级 `/workspace`。

## 适用场景

- 在隔离容器内读写工作区、运行脚本做数据处理或校验
- 读取本包 `scripts/`、`references/`（容器内 `/skills/sandbox-coding-demo/`）
- 同一会话可再 `@` 其他 docker Skill，物料并存于 `/skills/{skillId}/`，`/workspace` 保留

## 操作步骤（Agent）

1. 用 `sandbox__glob` 或 `sandbox__read` 查看 `/skills/sandbox-coding-demo/scripts`、`references`
2. 改文件只写 `/workspace/...`（禁止写 `/skills`）
3. 精确修改优先 `sandbox__edit`；搜索用 `sandbox__grep`
4. 运行：`sandbox__exec`，例如 `python /skills/sandbox-coding-demo/scripts/hello.py`
5. 只读命令（`ls` / `pwd` / `python -m pytest *`）一般免 HITL；写文件与其它 exec 需用户确认

## 试跑提示词

```text
@sandbox-coding-demo 请用沙箱工具：读取 /skills/sandbox-coding-demo 下脚本，在 /workspace 写 test.txt，再 ls
```

## 约束

- 仅使用 `sandbox__*` 完成文件与命令操作；勿用 `sandbox__exec` 代替 read/grep/glob/edit
- 禁止越狱路径（`/tmp`、`..` 逃逸等）
- 默认无外网；需外连时由管理员配置会话级 `network-allow`
