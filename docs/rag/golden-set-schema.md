# Golden Set 标注规范

> 配套文件：`golden-set.yaml` · 评测脚本：`scripts/rag_eval.py`

## corpus 条目

| 字段 | 必填 | 说明 |
|------|:----:|------|
| `doc_id` | ✅ | 稳定 ID，与 query `relevant_docs` 引用一致 |
| `display_name` | ✅ | 入库 `docName` / Milvus 展示名 |
| `path` | ✅ | 相对仓库根路径的 Markdown 文件 |

## query 条目

| 字段 | 必填 | 说明 |
|------|:----:|------|
| `id` | ✅ | 唯一，如 `q001`、`q_neg_001` |
| `query` | ✅ | 用户自然语言问法 |
| `relevant_docs` | ✅ | 期望命中的 `doc_id` 列表；负例用 `[]` |
| `relevant_keywords` | 推荐 | chunk 正文应含的关键词（弱标注） |
| `category` | ✅ | `leave` / `expense` / `attendance` / `onboarding` / `finance` / `remote` / `security` / `travel` / `multihop` / `negative` |
| `expect_empty` | 负例 | `true` 时期望检索 0 条（minScore 过滤后） |

## 标注原则

1. **一题一意图**：每条 query 只测一个知识点  
2. **口语化**：模拟真实用户问法，非章节标题照搬  
3. **负例**：与语料无关（如「股票期权」），用于观测误召回  
4. **交叉**：财务类 query 可命中 `expense-policy` 或 `finance-approval`，在 `relevant_docs` 列出所有可接受 doc  

## Recall 计算

- **doc 级**：topK 结果中任一 hit 的 `docName` 映射到 `relevant_docs` 即算命中  
- **MRR**：首个命中 doc 的排名倒数  
- **EmptyRate（正例）**：`relevant_docs` 非空但 0 命中比例  
- **EmptyRate（负例）**：`expect_empty: true` 且确实 0 命中比例  

## 版本

- `golden-set.yaml` 顶部的 `version` 递增；`rag_eval` 报告记录 version 便于对比  
