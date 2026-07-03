/** 参数配置 Tab 字段说明（点击 ? 展示） */
const FIELD_HELP: Record<string, string> = {
  'rag-search:minScore':
    '向量/内积相似度下限，低于此分的 chunk 会被丢弃。归一化向量下近似 cosine；不同主题文档分数差约 0.48。调高可减少误召回，调低可扩大召回面。',
  'rag-search:strategy':
    '默认检索策略。vector：纯向量；hybrid：向量 + 关键词（ES）RRF 融合；hybrid+rerank：混合后再 cross-encoder 精排。Chat 请求可临时覆盖，此处为租户默认值。',
  'rag-search:rrfK':
    '混合检索 RRF（Reciprocal Rank Fusion）常数 k。融合向量路与 BM25 排名时使用，数值越大排名差异越平滑，常用 60。',
  'rag-search:hybridPoolSize':
    '混合检索每路召回池大小：向量路与 ES 路各取 top N 再融合，再进入 rerank。增大可提升召回多样性，但延迟与成本上升。',
  'rag-search:defaultTopK':
    '请求未传 topK 时的默认返回条数。Orchestrator 检索与评测脚本均以此为准，影响最终注入上下文的片段数量。',
  'rag-rerank:enabled':
    '是否启用 Rerank 精排（如 gte-rerank-v2）。关闭后混合检索结果直接返回，延迟更低，但排序质量可能下降。',
  'rag-rerank:minScore':
    'Rerank 模型原始打分下限，低于此分的候选会被过滤。与 minRelevance 配合使用，形成双阈值门禁。',
  'rag-rerank:minRelevance':
    'Rerank relevance 分数下限。与 minScore 共同决定精排后保留哪些片段，避免低相关结果进入回答上下文。',
  'rag-chunk:maxSize':
    '文档入库 Markdown 分段的最大字符数。越小切片越细、定位越准；越大单段上下文越完整。修改后需重新入库文档才生效。',
  'rewrite-rag:enabled':
    '检索前是否启用 Query 改写。开启后先用 LLM 补全制度/流程等领域词并标准化表述，再走向量/混合检索，短问句召回通常更好。',
  'rewrite-rag:model':
    'RAG 改写调用的 LLM 模型 ID，经 llm-gateway 路由。建议使用低延迟 flash 模型。',
  'rewrite-rag:systemPrompt':
    '指导模型如何改写用户问题。应要求保留原意、不编造事实，并约定 JSON 输出格式（如 {"query":"..."}）。发布前建议用检索调试验证改写效果。',
  'rewrite-hyde:enabled':
    '向量检索零命中时是否启用 HyDE：由 LLM 生成一段「可能出现在制度文档中」的假想段落，再用该段落做二次向量检索。',
  'rewrite-hyde:model':
    'HyDE 生成假想文档使用的 LLM 模型，经 llm-gateway 路由。',
  'rewrite-hyde:maxChars':
    '生成的假想文档最大字符数。过长增加 embedding 成本，过短可能丢失关键信息，常用 480 左右。',
  'rewrite-hyde:systemPrompt':
    '指导模型生成假想文档段落。应要求制度条文式正文、禁止问答体与元叙述，并约定 JSON 输出（如 {"document":"..."}）。',
  'rewrite-empty-recall:enabled':
    '首次检索完全无结果时，是否启用零命中改写：生成多个同领域备选 query 做二次检索，避免用户看到「查无资料」。',
  'rewrite-empty-recall:model':
    '零命中改写使用的 LLM 模型，经 llm-gateway 路由。',
  'rewrite-empty-recall:maxAlternatives':
    '生成的备选检索 query 数量（1–5）。越多覆盖越广，但 LLM 调用与检索次数增加。',
  'rewrite-empty-recall:systemPrompt':
    '指导模型生成备选 query。应要求保留原问题业务域与关键名词，禁止改问无关主题，并约定 JSON 输出（如 {"queries":[...]}）。',
}

const SCOPE_HELP: Record<string, string> = {
  'rag-search': '租户级检索默认参数，发布后写入 Nacos sunshine-rag.yaml，全链路检索与 Chat 默认生效。',
  'rag-rerank': '精排阶段参数，控制 cross-encoder 是否启用及分数门禁。',
  'rag-chunk': '文档入库分段策略，影响向量粒度与检索定位精度。',
  'rewrite-rag': '检索前 Query 改写，提升短问句与口语化问题的召回质量。',
  'rewrite-hyde': '向量零命中 fallback：生成假想文档段落再检索。',
  'rewrite-empty-recall': '检索无结果 fallback：生成备选 query 再检索。',
}

export function fieldHelp(scope: string, fieldId: string): string {
  return FIELD_HELP[`${scope}:${fieldId}`] ?? '该参数影响当前模块的默认行为，修改后请保存草稿并发布。'
}

export function scopeHelp(scope: string): string {
  return SCOPE_HELP[scope] ?? '租户级配置，保存草稿后需发布并通过评测门禁才会写入 Nacos。'
}
