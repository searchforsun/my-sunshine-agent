# 沙箱工作区约定

> 平台总览：[docs/sandbox/README.md](../../../sandbox/README.md)

容器挂载：

| 路径 | 权限 | 来源 |
|------|------|------|
| `/skills/{skillId}/` | 只读 | 会话内懒加载的 Skill 包（`scripts/` + `references/` 等） |
| `/workspace` | 可写 | 会话工作区（上传/生成文件，对话级保留） |

## 推荐工具

- 读说明与脚本：`sandbox__read`、`sandbox__glob`
- 改工作区文件：`sandbox__write`、`sandbox__edit`
- 搜内容：`sandbox__grep`
- 跑命令：`sandbox__exec`（cwd 默认 `/workspace`）

## 写与安全

- **write**：目标路径已存在则失败，请用 `edit` 或换路径（拒静默覆盖）。
- **exec**：破坏性命令硬拒；只读白名单（如 `ls`/`pwd`）默认可免确认。
- Chat **工作区**顶栏可调写确认：永不跳过 / 总是跳过 / 智能跳过（仅本会话；用户默认在账号设置）。
- 抽屉：多文件 tab；代码预览横向滚动（不换行）；`.md` 可在面包屑旁切换 **美化 / 原始**；树节点可拖入输入框生成路径芯片。

## 样例命令

```bash
python /skills/sandbox-coding-demo/scripts/hello.py
ls /workspace
python /skills/sandbox-coding-demo/scripts/sum_csv.py /workspace/sample.csv
```
