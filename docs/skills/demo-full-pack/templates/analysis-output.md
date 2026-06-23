# 内部分析输出模板

## 摘要

{{summary}}

## 逐条结论

| 单据 ID | 结论 | 依据 |
|---------|------|------|
{{#each items}}
| {{id}} | {{verdict}} | {{reason}} |
{{/each}}

## 风险点

{{#each risks}}
- {{this}}
{{/each}}
