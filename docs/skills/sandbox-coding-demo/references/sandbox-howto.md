# 沙箱工作区约定

容器挂载：

| 路径 | 权限 | 来源 |
|------|------|------|
| `/skill` | 只读 | 本 Skill 包的 `scripts/` + `references/` |
| `/workspace` | 可写 | 会话工作区（上传/生成文件） |

## 推荐工具

- 读说明与脚本：`sandbox__read`、`sandbox__glob`
- 改工作区文件：`sandbox__write`、`sandbox__edit`
- 搜内容：`sandbox__grep`
- 跑命令：`sandbox__exec`（cwd 默认 `/workspace`）

## 样例命令

```bash
python /skill/scripts/hello.py
ls /workspace
python /skill/scripts/sum_csv.py /workspace/sample.csv
```
