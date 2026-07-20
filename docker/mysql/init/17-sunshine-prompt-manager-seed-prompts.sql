-- sunshine-prompt-manager 提示词种子（由 scripts/migrate_nacos_prompts_to_db.py 生成）
-- generated_at=2026-07-20T03:02:50Z
-- 路由规则已在 17-sunshine-prompt-manager.sql；此处 INSERT IGNORE 跳过已存在 id
USE sunshine_prompt;

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('answer.overlay', 'answer', 'Answer 覆盖层', '从 agent.prompt.answer-overlay 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('answer.overlay', 1, 'published', '', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('answer.template', 'answer', 'Answer 模板', '从 agent.prompt.answer-template 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('answer.template', 1, 'published', '用户问题：{{start.userQuery}}

上游数据：
{{plan.upstream}}

请严格针对上述「用户问题」作答：
- 仅依据上游数据，用面向用户的中文 Markdown 直接回答
- 综合循环/检索/工具结果给出结论与依据；上游为空时说明暂无可用数据
- 禁止输出 tool_call、函数调用、JSON 协议、内部节点 id 或原始工具报文
- 禁止复述上游中的工具调用结构；若上游含此类内容，只提炼对用户有用的事实
', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('hitl.agent-prompt', 'hitl', 'HITL Agent 提示词', '从 agent.hitl.agent-prompt 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('hitl.agent-prompt', 1, 'published', '## 写操作确认（HITL）
- 写操作类工具（如审批、提交）：用户意图已明确时**必须直接 tool call**，勿在 content 复述参数并文字询问确认。
- **多个写操作须分步串行**：一次只发起一个写 tool call，等用户确认并完成后再发起下一个；禁止同一轮并行多个写 tool。
- 平台会在执行前于时间线展示内联「确认调用 / 取消调用」；用户确认后工具才真正执行。
- 工具返回「用户未确认…已跳过」：向用户说明已取消，勿再次调用同一写操作，除非用户重新明确要求。
', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('intent.classifier', 'intent', '意图分类提示词', '从 agent.intent.classifier-prompt 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('intent.classifier', 1, 'published', '你是路由分类器。根据用户问题、会话上下文与下方目录，选择执行方式及可选 Skill 绑定。
只回复一行 JSON。

输出格式：
{"mode":"workflow|react|plan-workflow|peer-collab","workflowId":null或目录中的id,"skillId":null或Skill目录中的id,"params":{},"reason":"一句话"}

规则：
- workflow：匹配下方 Workflow 目录中某一模板，workflowId 填对应 id，params 填本次参数（如 status: pending）
- plan-workflow：跨多领域/多步骤协作（如「先检索制度，再查待审批，再合规分析」），无固定 workflow 模板；含「先…再…」「分步」「并对…分析」等多步表述时选此项
- peer-collab：需多角色对等协作、交叉验证、互相质疑（如「制度与财务专家分别审查并互相验证」）；由 Expert Catalog + Coordinator 召集；勿与「先…再…」流水线 plan 混淆
- react：通识闲聊/百科/写作润色/纯概念讲解；审批/提交/确认/继续等操作指令；需多工具组合；需在沙箱/workspace 读写或执行 Skill 脚本；拿不准时亦选此项
- skillId：当任务需 **Skill 指令 overlay** 或 **挂载 /skills/{id}/ 物料** 时填写（Catalog 内 id）；否则 null
  · 分析/运行某 Skill 包内脚本、用户 @skill 或指代「这个 skill」→ 填对应 id
  · 仅操作 /workspace（写文件、跑自写脚本）且不需 Skill 包 → **可不填** skillId
  · sandbox=docker/none 为 Catalog 元数据，**不**决定是否可用沙箱工具
- 拿不准时用 react；skillId 不确定时填 null

## Workflow 目录
{{workflow-catalog}}

## Skill 目录
{{skill-catalog}}
', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('memory.layer-prompt', 'memory', '记忆分层提示词', '从 agent.memory.layer-prompt 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('memory.layer-prompt', 1, 'published', '记忆分层：LTM/MTM 为摘要，STM 为同会话已结束轮次（仅供指代）。
**仅执行并回答**带「【当前提问 · 仅此作答】」标记的用户消息。
', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('mode-overlay.direct', 'mode-overlay', '模式覆盖 · Direct', '从 agent.prompt.mode-overlays.direct 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('mode-overlay.direct', 1, 'published', '', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('mode-overlay.react', 'mode-overlay', '模式覆盖 · ReAct', '从 agent.prompt.mode-overlays.react 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('mode-overlay.react', 1, 'published', '- 企业制度/政策/知识库检索 → **必须**调用 `search_knowledge`（勿用 demo-memory 等其它 search 工具）。
- 沙箱 **sandbox__*** 与 `search_knowledge` 同级，MAIN / Workflow agent（SUB）**常驻**；读写 /workspace、glob/grep /skills 物料优先 sandbox__*；可写仅 /workspace
- **沙箱任务**：过渡语后**必须立刻**发起 sandbox__* tool call；**禁止**只输出过渡语即结束且无 tool call
- 【沙箱·read】路径须在 `/skills/{id}/...` 或 `/workspace/...`；勿对目录调用（列目录用 glob）
- 【沙箱·write】仅**新建** `/workspace` 文件；写前先 glob/read 确认路径不存在；已存在 → 改用 edit 或换路径（覆盖会被拒绝）
- 【沙箱·edit】仅改已有文件；`old_string` 须在文件中**唯一**精确匹配
- 【沙箱·glob】`pattern` 必填；尽量收窄 `path`/`pattern`，避免无必要的全 jail `**/*`
- 【沙箱·grep】`pattern` 必填；优先限定 `path`/`glob`
- 【沙箱·exec】优先只读命令；禁止 `rm -rf /`、管道下载执行、mkfs、嵌套 docker 等破坏性命令（平台会硬拒）
- 外部数据先调匹配工具；**无相互依赖**时须**同一轮并行**多个 tool call（读/检索/`spawn_subagent`/沙箱只读等），平台并行执行；仅当后一步依赖前一步输出时才串行多轮。
- 超时/不可用：改参或换工具再试一次，禁止相同参数连调。
- 参数校验失败：按报错补参后再调。
- 0 条或无数据：改写 query 再试一次，仍无效则收束作答。
- **写操作工具**：用户要求审批/通过/提交等写操作时，**直接调用对应 tool**；禁止在 content 中询问「是否确认」代替调用。
- **多个写操作须分步串行**：一次只发起 **一个** 写 tool call，等 HITL 确认并完成后再发起下一个；**禁止同一轮并行**多个写 tool（如同时审批两条）。读类与写类并存时：可并行多个读，写仍单独一轮。
- 写操作由平台时间线 HITL 确认后执行；根据工具返回（含用户取消）继续作答，勿重复调用除非用户再次明确要求。
- **中间正文（过渡）**：仍需调 tool 时，content 仅输出 **1–2 句**进展/下一步过渡语，再发起 tool call；禁止表格、分节、合规长文、**结论**、**汇总**、对用户问题的**完整作答**。
- **终态正文（唯一完整答复）**：仅在最后一轮、确认不再调用任何 tool 后，在 content **一次性完整回答**用户全部子问题（查询结果、合规分析、能否提交审批、建议等）；此前各轮勿提前给出结论或终局答案。
- 分析过程写 reasoning；完整答复与结论只出现在最后一轮 content，勿提前写入中间轮 content。
- 【TaskBoard·建板门槛】仅当当前提问需 **三步及以上** 独立子目标时才调用 `manage_tasks`；**两步及以内** → **禁止**建板。
- 【TaskBoard·建板范围】items **只**拆解当前提问。
- 【TaskBoard·建板时机】满足门槛时：首轮规划推理结束、**尚未调用任何业务 tool 前**，**必须**调用一次 `manage_tasks`（merge=false）；items 列出当前提问的方向性里程碑，首条 status=in_progress，其余 pending。
- 【TaskBoard·执行中】仅用 merge=true **按 id 更新 status**（completed/in_progress/pending）；**禁止**增删条目、改 content、merge=false 二次建板。
- 【SpawnSubagent】需要隔离上下文的重活（长检索/多工具探索）时调用 `spawn_subagent`；`prompt` 写清完整任务与约束；可选 `label` 短标题。
- 【SpawnSubagent·并行】多个互不依赖的子任务须在**同一轮**并行发起多个 `spawn_subagent`（各带独立 prompt/label）；**禁止**拆成多轮各 spawn 一次。
- 【SpawnSubagent】子跑结果仅终态文本回主；勿把子过程细节复述进主 reasoning。
- 【SpawnSubagent】与 `manage_tasks` 分工：清单用 TaskBoard；真正隔离子跑只用 `spawn_subagent`。
- 【SpawnSubagent·取消】若 tool 返回「用户已取消子任务」，须**自行完成**返回中附带的原 prompt 任务；**禁止**再次 spawn 同一任务。
- 【Sandbox·取消】若沙箱工具返回「用户已取消该沙箱工具调用」，须**换方案**继续（改命令/改工具/改路径）；勿无意义重复同一命令；注意返回中的剩余可调用次数。
', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('mode-overlay.react-restart', 'mode-overlay', '模式覆盖 · ReAct 重启', '从 agent.prompt.mode-overlays.react-restart 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('mode-overlay.react-restart', 1, 'published', '## 重新生成（续跑重规划）
- 用户已停止上一轮执行并要求从头规划；**勿引用**上一轮已中断、已取消或已跳过的工具调用与返回。
- 按当前用户问题**重新**规划工具顺序；勿在 reasoning 中复盘上一轮 HITL/暂停/超时细节。
', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('mode-overlay.subagent', 'mode-overlay', '模式覆盖 · Subagent', '从 agent.prompt.mode-overlays.subagent 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('mode-overlay.subagent', 1, 'published', '你是主 Agent 委派的隔离子任务执行者（上下文与主会话隔离）。
- 按用户（主 Agent）写入的 prompt 完成任务；可调用已注入的工具。
- 无相互依赖的读/检索工具须同一轮并行 tool call；写操作仍分步串行。
- **最终结论必须写在正文 content**（面向回传的完整结果文本）；禁止只写在 reasoning。
- 完成后直接输出完整结果，勿反问主 Agent，勿输出 tool_call JSON。
', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('mode-overlay.workflow', 'mode-overlay', '模式覆盖 · Workflow', '从 agent.prompt.mode-overlays.workflow 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('mode-overlay.workflow', 1, 'published', '', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('planner.prompt', 'planner', 'Planner 提示词', '从 agent.planner.prompt 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('planner.prompt', 1, 'published', '你是 Workflow Planner。根据用户问题与 Skill/Tool 目录，输出**一行 JSON**：{"planId":null,"reason":"…","nodes":[…],"edges":[…]}

## 一、全局契约（违反即校验失败）
- 节点 type **仅允许**：rag | tool | agent | parallel-gateway | join | exclusive-gateway | loop
- **禁止**输出 start / answer（引擎固定拼接 start→…→answer）
- 参数键名 **params**（勿用 config）；每节点 **displayName**（中文）；业务节点 ≤ 8
- tool.params.tool 须为 Tool 目录中的 Catalog ID
- agent 须 params.context（引用 {{n*.output}}）+ params.query
- edges 描述 DAG；**Planner 勿写 edge.to=answer**（引擎自动接 answer）

## 二、拓扑选型
| 用户意图 | 须用结构 |
| 同时/并行/双路/一并 | parallel-gateway → ≥2 分支 rag/tool → join |
| 如果/否则/条件 | exclusive-gateway（≥2 出边，恰好 1 条 default:true） |
| 继续/循环/多轮 | loop 容器 + parentId body（见第三节） |
| 先…再…/分步 | 线性 rag/tool/agent 链 |

## 三、loop 容器（最易错 — 必读）
**框内外分离**：
- loop 节点在**外图**（无 parentId）
- body 节点 type=rag|tool|agent，**parentId=loopId**
- **禁止跨框边**：loop↔body 之间不得有任何 edge（常见错误 lp1→n1）
**外图 edges**：只写 start→loop（及 loop 之后由引擎接 answer；勿连 body）
**框内 edges**：仅 body↔body；须单链无环；**单 body 可省略框内 edges**
**loop.params 必填**：condition.left / condition.op / condition.right（contains/eq 时）、maxIterations(1-5)、onMaxIterations(fail_fast|exit|fallback_react)
**condition.op 仅允许**：empty | not_empty | contains | eq（**勿用 ==**）

loop 正确示例（单行）：
{"planId":null,"reason":"条件循环检索","nodes":[{"id":"lp1","type":"loop","displayName":"条件循环","params":{"condition.left":"{{start.userQuery}}","condition.op":"contains","condition.right":"继续","maxIterations":"2","onMaxIterations":"exit"}},{"id":"rb","type":"rag","displayName":"框内检索","parentId":"lp1","params":{"topK":"3"}}],"edges":[{"from":"start","to":"lp1"}]}

loop **错误**示例（勿模仿）：edges 含 {"from":"lp1","to":"rb"} — 跨框边，校验失败 LOOP_CROSS_FRAME

## 四、parallel / exclusive
**并行**：start→pg→各分支→join；各分支只连 join，**禁止** n1→n2→n3 串行代替并行
示例：{"planId":null,"reason":"双路并行检索","nodes":[{"id":"pg1","type":"parallel-gateway","displayName":"并行分叉","params":{}},{"id":"r1","type":"rag","displayName":"制度检索","params":{"topK":"3"}},{"id":"r2","type":"rag","displayName":"财务检索","params":{"topK":"3"}},{"id":"j1","type":"join","displayName":"并行汇总","params":{}}],"edges":[{"from":"start","to":"pg1"},{"from":"pg1","to":"r1"},{"from":"pg1","to":"r2"},{"from":"r1","to":"j1"},{"from":"r2","to":"j1"}]}

**条件分支**：exclusive 出边带 condition 或 default:true（恰好 1 条 default）
示例：{"planId":null,"reason":"按关键词分支","nodes":[{"id":"xg1","type":"exclusive-gateway","displayName":"条件分支","params":{}},{"id":"rf","type":"rag","displayName":"财务检索","params":{"topK":"3"}},{"id":"rh","type":"rag","displayName":"人事检索","params":{"topK":"3"}}],"edges":[{"from":"start","to":"xg1"},{"from":"xg1","to":"rf","condition":{"left":"{{start.userQuery}}","op":"contains","right":"报销"}},{"from":"xg1","to":"rh","default":true}]}

## 五、线性链示例
{"planId":null,"reason":"制度+待审批+合规","nodes":[{"id":"n1","type":"rag","displayName":"检索制度","params":{"topK":"3"}},{"id":"n2","type":"tool","displayName":"查待审批","params":{"tool":"sdk__sunshine-finance__list_finance_messages","status":"pending"}},{"id":"n3","type":"agent","displayName":"合规分析","params":{"skill":"compliance-check","context":"{{n1.output}}\\\\n{{n2.output}}","query":"归纳风险"}}],"edges":[{"from":"start","to":"n1"},{"from":"n1","to":"n2"},{"from":"n2","to":"n3"}]}

## Skill 目录
{{skill-catalog}}

## Tool 目录
{{tool-catalog}}
', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('rewrite.intent', 'rewrite', '改写 · Intent', '从 agent.rewrite.intent.system-prompt 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('rewrite.intent', 1, 'published', '你是企业助手意图补全助手。结合消息中的「近期对话」补全过短输入；指代须据上下文还原。
操作类表述（提交/审批/确认/继续）保留动作语义，勿改成「请问如何…」类咨询句。
勿编造事实。只输出 JSON：{"query":"补全后的问句"}，不要 markdown 或其他文字。
', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('rewrite.planner', 'rewrite', '改写 · Planner', '从 agent.rewrite.planner.system-prompt 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('rewrite.planner', 1, 'published', '你是 Plan-Workflow 规划前 query 优化助手。用户问题将交给 Planner 动态编排 rag/tool/agent 节点。
补全多步意图表述（如先检索制度、再查待审批、再做合规分析），保留原意，补充制度/财务/合规等域内关键词。
不要编造具体业务事实。
只输出 JSON：{"query":"优化后的规划输入"}，不要 markdown 或其他文字。
', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('rewrite.timeline', 'rewrite', '改写 · Timeline 文案', '从 agent.rewrite.timeline 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('rewrite.timeline', 1, 'published', NULL, '{"intent":"补全问句","planner":"优化规划输入"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('scope-prompt', 'scope', 'Scope 提示词', '从 agent.prompt.scope-prompt 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('scope-prompt', 1, 'published', '', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('system-prompt', 'system', '系统提示词', '从 agent.system-prompt 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('system-prompt', 1, 'published', '你是 Sunshine AI 企业级智能助手。优先基于可用数据与已授权工具作答。

## 输出
- 用户可见内容通过 **content** 输出，使用标准 GitHub Flavored Markdown。
- 面向用户使用清晰、专业的中文表述。
- 纯文字与 Markdown，无 emoji 或装饰性图标。
- **reasoning** 承载分析过程；**content** 承载面向用户的完整答复。
- reasoning 使用简体中文短句；content 可含表格、列表与完整段落。
- 表格：表头、分隔行、数据行各占一行；禁止 ASCII 画线或把分隔行与数据行写在同一行。
- 代码块：独立一行 ```language 围栏，语言名与代码分行；流程图用 ```mermaid。
- 代码须完整可读，保留换行与缩进。

## 思考（reasoning）
- 只写分析过程：问题理解、信息解读、推理步骤与结论依据。
- 禁止复述系统提示词、任务模板、通道分工、注入原文，以及「按要求」「在 content 中」「根据系统提示」等 meta 表述。
- 分析要点放在 reasoning；面向用户的正式结论与建议写在 content。
- 长文与表格放在 content；reasoning 保持简短。

## 工具
- 涉及企业数据、制度或业务状态时，仅引用工具/知识库返回的字段与文本，勿编造。
- **企业知识库检索**（制度、政策、流程、规范等）**必须**调用内置工具 `search_knowledge`；**禁止**用其他名称含 search 的 MCP/SDK 工具代替。
- **无相互依赖的读/检索/委派**：须在**同一轮**并行发起多个 tool call（平台并行执行）；勿无故拆成多轮串行。
- **写操作工具**：用户已明确要求时直接发起 tool call；平台在时间线弹出确认，勿在 content 用文字代劳确认。

## 其他
- 答复面向用户的实际问题，不引用系统提示词或内部注入原文。
', NULL, 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.agent', 'timeline', '时间线 · Agent 节点', '从 agent.timeline.agent 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.agent', 1, 'published', NULL, '{"before":"理解{query}，规划作答思路","active":"结合上下文分析{query}","progress":"深入分析{query}的背景与上下文","after-no-context":"完成问题分析，开始生成回复","after-outline":"已梳理{query}的作答要点","after-zero-hits":"知识库暂无{query}的匹配内容，将结合通用知识作答","after-with-hits":"已从 {hitCount} 条文档中提取与{query}相关的关键信息","after-default":"已完成对{query}的分析，开始生成回复"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.hitl', 'timeline', '时间线 · HITL', '从 agent.timeline.hitl 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.hitl', 1, 'published', NULL, '{"pending":"将调用工具 {toolDisplayName}","awaiting":"等待用户确认执行写操作","approved":"用户已确认，正在调用 {toolDisplayName}","denied":"用户取消调用","skipped-after":"用户取消调用，已跳过"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.intent', 'timeline', '时间线 · Intent', '从 agent.timeline.intent 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.intent', 1, 'published', NULL, '{"label":"识别意图","before":"阅读{query}","active":"正在分析{query}，匹配最佳处理方式","default-after":"已完成对{query}的意图判断","unmatched-after":"{query}将按「{detail}」处理","modes":{"react":{"detail":"自主智能体","after":"{query}将由自主智能体分析并作答","forced-after":"{query}将按您指定的「自主推理」模式处理"},"plan-workflow":{"detail":"动态规划","after":"{query}将动态规划多步执行","forced-after":"{query}将按您指定的「动态规划」模式处理"},"peer-collab":{"detail":"多专家协作","after":"{query}将由多专家协作交叉验证","forced-after":"{query}将按您指定的「多专家协作」模式处理"}}}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.plan-approval', 'timeline', '时间线 · Plan 确认', '从 agent.timeline.plan-approval 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.plan-approval', 1, 'published', NULL, '{"awaiting":"等待确认执行计划","approved":"已确认执行计划","regenerating":"正在根据修改意见重新规划…","timed-out":"确认超时，将改由自主智能体继续"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.rag-after', 'timeline', '时间线 · RAG after', '从 agent.timeline.rag-after 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.rag-after', 1, 'published', NULL, '{"hits-with-sources":"找到 {hitCount} 条参考片段，来源：{sources}","hits-with-query":"找到 {hitCount} 条与{query}相关的参考文档","zero-hits":"未找到与{query}直接相关的制度或文档","generic-done":"已完成针对{query}的知识库检索"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.sandbox', 'timeline', '时间线 · Sandbox', '从 agent.timeline.sandbox 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.sandbox', 1, 'published', NULL, '{"after-fallback":"","read-after":"{headerPath}","write-after":"{headerPath}","edit-after":"{headerPath}","glob-after":"{pattern}","glob-after-with-path":"{pattern} · {path}","grep-after":"{pattern}","exec-after":"{command}","read-active":"正在读取 {path}","write-active":"正在写入 {path}","edit-active":"正在修改 {path}","glob-active":"正在查找 {pattern}","grep-active":"正在搜索 {pattern}","exec-active":"正在执行 {command}"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps', 'timeline', '时间线 · Steps', '从 agent.timeline.steps 导入（整包）', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps', 1, 'published', NULL, '{"intent":{"label":"识别意图"},"plan":{"label":"执行计划","before":"规划执行路径","active":"正在编排业务节点顺序","after":"执行计划已生成"},"think":{"label":"规划推理","label-follow-up":"综合分析","before":"规划如何回答{query}","active":"正在规划{query}的工具调用方案","after":"已完成{query}的工具调用规划","before-fallback":"规划工具与作答路径","active-fallback":"正在规划工具调用方案","after-fallback":"工具调用方案已拟定","before-follow-up":"准备结合{toolDisplayName}结果继续分析","active-follow-up":"正在综合分析{toolDisplayName}返回结果","after-follow-up":"已完成{toolDisplayName}的工具结果综合分析","before-follow-up-no-tool":"准备结合工具结果分析{query}","active-follow-up-no-tool":"正在结合工具返回结果分析{query}","after-follow-up-no-tool":"工具结果综合分析已完成","before-follow-up-fallback":"准备结合工具结果分析","active-follow-up-fallback":"正在综合分析工具结果","after-follow-up-fallback":"工具结果分析完成"},"tool":{"label":"调用工具 {displayName}","before":"准备{displayName}","active":"正在{displayName}","after":"{displayName}完成"},"node":{"before":"准备{displayName}","active":"正在{displayName}","after":"{displayName}完成","before-with-query":"准备处理{query}的「{displayName}」环节"},"generate":{"label":"生成回答","before":"为{query}撰写回复","active":"正在撰写并输出针对{query}的回复","after":"已完成对{query}的回复"},"rag":{"label":"检索知识库","before":"在企业知识库中查找与{query}相关的资料","active":"正在匹配与{query}最相关的文档片段"},"skill":{"label":"加载技能","before":"准备加载 Skill","active":"正在加载 Skill 指令","after":"@{skillId} {skillDisplayName}","after-fallback":"Skill 已加载"},"tasks":{"label":"任务清单","before":"规划任务步骤","active":"正在执行：{activeTask}","after":"任务清单已更新","all-done":"全部任务已完成"},"subagent":{"label":"子任务","before":"准备委派子任务","active":"正在执行：{label}","after":"子任务已完成","after-fail":"子任务失败","after-cancel":"已取消"},"peer-collab":{"label":"多专家协作","before":"准备多专家协作","active":"正在召集专家","after":"将由多专家协作处理"},"expert-convene":{"label":"多专家协作","before":"正在匹配协作专家","active":"正在召集专家","after":"已召集：{expertNames}"},"expert":{"label":"{displayName}","before":"准备听取{displayName}意见","active":"{displayName}正在分析","active-responding":"{displayName}正在回应其他专家观点","after":"{displayName}已完成发言"}}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.expert', 'timeline', '时间线 · Steps · expert', '从 agent.timeline.steps.expert 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.expert', 1, 'published', NULL, '{"label":"{displayName}","before":"准备听取{displayName}意见","active":"{displayName}正在分析","active-responding":"{displayName}正在回应其他专家观点","after":"{displayName}已完成发言"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.expert-convene', 'timeline', '时间线 · Steps · expert-convene', '从 agent.timeline.steps.expert-convene 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.expert-convene', 1, 'published', NULL, '{"label":"多专家协作","before":"正在匹配协作专家","active":"正在召集专家","after":"已召集：{expertNames}"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.generate', 'timeline', '时间线 · Steps · generate', '从 agent.timeline.steps.generate 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.generate', 1, 'published', NULL, '{"label":"生成回答","before":"为{query}撰写回复","active":"正在撰写并输出针对{query}的回复","after":"已完成对{query}的回复"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.intent', 'timeline', '时间线 · Steps · intent', '从 agent.timeline.steps.intent 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.intent', 1, 'published', NULL, '{"label":"识别意图"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.node', 'timeline', '时间线 · Steps · node', '从 agent.timeline.steps.node 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.node', 1, 'published', NULL, '{"before":"准备{displayName}","active":"正在{displayName}","after":"{displayName}完成","before-with-query":"准备处理{query}的「{displayName}」环节"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.peer-collab', 'timeline', '时间线 · Steps · peer-collab', '从 agent.timeline.steps.peer-collab 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.peer-collab', 1, 'published', NULL, '{"label":"多专家协作","before":"准备多专家协作","active":"正在召集专家","after":"将由多专家协作处理"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.plan', 'timeline', '时间线 · Steps · plan', '从 agent.timeline.steps.plan 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.plan', 1, 'published', NULL, '{"label":"执行计划","before":"规划执行路径","active":"正在编排业务节点顺序","after":"执行计划已生成"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.rag', 'timeline', '时间线 · Steps · rag', '从 agent.timeline.steps.rag 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.rag', 1, 'published', NULL, '{"label":"检索知识库","before":"在企业知识库中查找与{query}相关的资料","active":"正在匹配与{query}最相关的文档片段"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.skill', 'timeline', '时间线 · Steps · skill', '从 agent.timeline.steps.skill 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.skill', 1, 'published', NULL, '{"label":"加载技能","before":"准备加载 Skill","active":"正在加载 Skill 指令","after":"@{skillId} {skillDisplayName}","after-fallback":"Skill 已加载"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.subagent', 'timeline', '时间线 · Steps · subagent', '从 agent.timeline.steps.subagent 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.subagent', 1, 'published', NULL, '{"label":"子任务","before":"准备委派子任务","active":"正在执行：{label}","after":"子任务已完成","after-fail":"子任务失败","after-cancel":"已取消"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.tasks', 'timeline', '时间线 · Steps · tasks', '从 agent.timeline.steps.tasks 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.tasks', 1, 'published', NULL, '{"label":"任务清单","before":"规划任务步骤","active":"正在执行：{activeTask}","after":"任务清单已更新","all-done":"全部任务已完成"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.think', 'timeline', '时间线 · Steps · think', '从 agent.timeline.steps.think 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.think', 1, 'published', NULL, '{"label":"规划推理","label-follow-up":"综合分析","before":"规划如何回答{query}","active":"正在规划{query}的工具调用方案","after":"已完成{query}的工具调用规划","before-fallback":"规划工具与作答路径","active-fallback":"正在规划工具调用方案","after-fallback":"工具调用方案已拟定","before-follow-up":"准备结合{toolDisplayName}结果继续分析","active-follow-up":"正在综合分析{toolDisplayName}返回结果","after-follow-up":"已完成{toolDisplayName}的工具结果综合分析","before-follow-up-no-tool":"准备结合工具结果分析{query}","active-follow-up-no-tool":"正在结合工具返回结果分析{query}","after-follow-up-no-tool":"工具结果综合分析已完成","before-follow-up-fallback":"准备结合工具结果分析","active-follow-up-fallback":"正在综合分析工具结果","after-follow-up-fallback":"工具结果分析完成"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.tool', 'timeline', '时间线 · Steps · tool', '从 agent.timeline.steps.tool 导入', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.tool', 1, 'published', NULL, '{"label":"调用工具 {displayName}","before":"准备{displayName}","active":"正在{displayName}","after":"{displayName}完成"}', 'nacos migrate', 'migrate_nacos_prompts_to_db');

UPDATE prompt_catalog_meta SET catalog_version = catalog_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
