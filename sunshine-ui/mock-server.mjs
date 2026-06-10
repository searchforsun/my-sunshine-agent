/**
 * Mock SSE Server — 模拟后端流式输出 Markdown 内容
 * 用法：node mock-server.mjs
 * 监听 :8001，前端 npm run dev 后直接对话即可
 */

import http from 'node:http'

const PORT = 8001

// 测试内容：覆盖所有常见 Markdown 特性
const TEST_CONTENT = `## 📝 Markdown 流式渲染测试

> 本测试覆盖加粗、斜体、代码块、表格、列表、引用、链接、分割线等全部常用语法。

---

### 1. 内联格式

这是 **粗体文字**，这是 *斜体文字*，这是 ~~删除线~~，这是 \`行内代码\`，以及 ***粗斜体***。

化学式 H~2~O，数学公式 x^2^ + y^2^ = 1。

### 2. 代码块

\`\`\`python
def quick_sort(arr: list[int]) -> list[int]:
    """快速排序"""
    if len(arr) <= 1:
        return arr
    pivot = arr[len(arr) // 2]
    left = [x for x in arr if x < pivot]
    middle = [x for x in arr if x == pivot]
    right = [x for x in arr if x > pivot]
    return quick_sort(left) + middle + quick_sort(right)

print(quick_sort([3, 6, 8, 10, 1, 2, 1]))
# 输出: [1, 1, 2, 3, 6, 8, 10]
\`\`\`

\`\`\`sql
SELECT u.name, COUNT(o.id) AS order_count
FROM users u
LEFT JOIN orders o ON u.id = o.user_id
WHERE u.created_at > '2025-01-01'
GROUP BY u.name
HAVING COUNT(o.id) > 5
ORDER BY order_count DESC;
\`\`\`

### 3. 表格

| 服务 | 端口 | 技术栈 | 状态 |
|------|------|--------|:----:|
| BFF | 8001 | Spring WebFlux + SSE | ✅ |
| Orchestrator | 8200 | AgentScope + Reactor | ✅ |
| LLM Gateway | 8300 | DeepSeek / Qwen 适配 | ✅ |
| RAG Service | 8400 | Milvus + 通义 Embedding | 🔶 |

### 4. 无序列表与多层嵌套

- 第一阶段：基础设施
  - LLM Gateway OpenAI 兼容接口
  - Nacos 服务注册与配置中心
  - Redis 语义缓存（成本降低 98%）
- 第二阶段：智能体核心
  - ReActAgent 多轮推理
  - 知识库检索（Milvus + 通义 Embedding）
  - 业务工具动态调用
- 第三阶段：企业落地
  - 多租户数据隔离
  - 权限精细化控制
    - RBAC 角色权限模型
    - 数据行级安全过滤
  - SkyWalking 全链路监控
  - Grafana 实时告警大屏

### 5. 有序列表

1. 用户通过 BFF 发送消息
2. Orchestrator 根据意图分流
    1. 简单对话 → 直连 LLM Gateway 逐 token 流式
    2. 知识检索 → ReActAgent + RAG 工具链
    3. 业务操作 → ReActAgent + OA/财务工具
3. ReActAgent 调用工具链（检索 / 计算 / API）
4. LLM 结合工具结果生成最终回答
5. 逐 token 流式返回前端渲染

### 6. 任务列表

- [x] LLM Gateway OpenAI 兼容接口
- [x] ReActAgent 流式对话
- [x] RAG 知识库检索
- [x] Markdown 实时渲染
- [ ] 多模态支持（图片 / 文件识别）
- [ ] 会话记忆与上下文管理
- [ ] WebSocket 双向通信
- [ ] 多语言国际化

### 7. 引用嵌套

> **Sunshine AI Platform** — 企业级 AI 中台
>
> 基于 AgentScope 多智能体编排框架，集成知识库检索、业务工具调用、
> 流式对话等核心能力。
>
> > *"让每个企业都拥有自己的 AI 中台"*

### 8. 链接

- 官网：[Sunshine AI](https://github.com/sunshine-ai)
- 文档：[AgentScope Java SDK](https://github.com/agentscope-ai/agentscope-java)
- 邮箱：<dev@sunshine-ai.com>

### 9. Mermaid 图表

\`\`\`mermaid
flowchart TD
    A[用户请求] --> B{意图分流}
    B -->|简单对话| C[LLM Gateway]
    B -->|知识检索| D[RAG Service]
    B -->|业务操作| E[Tool Manager]
    C --> F[流式返回]
    D --> G[ReActAgent]
    G --> C
    E --> G
    F --> H[前端渲染]
\`\`\`

\`\`\`mermaid
sequenceDiagram
    participant U as 用户
    participant B as BFF
    participant O as Orchestrator
    participant L as LLM Gateway
    U->>B: 发送消息
    B->>O: 转发请求
    O->>O: 意图分类
    alt 简单对话
        O->>L: 直连流式
        L-->>B: token 流
    else 知识检索
        O->>O: ReActAgent 推理
        O->>L: 调用 LLM
        L-->>O: 工具调用结果
        O->>L: 最终生成
    end
    B-->>U: SSE 流式输出
\`\`\`

---

✨ **全部测试通过！** 以上所有 Markdown 元素均应在流式输出过程中正常渲染，且不出现原生 \`**\` \`#\` \`-\` 等标记字符的闪现。
`

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

/**
 * 正确写入 SSE 事件 — 数据中的 \n 编码为多行 data:
 */
function writeSSEEvent(res, id, data) {
  res.write(`id:${id}\n`)
  const lines = data.split('\n')
  for (const line of lines) {
    res.write(`data:${line}\n`)
  }
  res.write('\n') // 事件分隔空行
}

const server = http.createServer(async (req, res) => {
  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, x-user-id, x-tenant-id')

  if (req.method === 'OPTIONS') {
    res.writeHead(204)
    res.end()
    return
  }

  if (req.method === 'POST' && req.url === '/api/chat/stream') {
    // 读取请求体
    let body = ''
    for await (const chunk of req) {
      body += chunk
    }
    console.log(`[Mock] 收到请求: ${body.substring(0, 100)}...`)

    // SSE headers
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',  // 禁用 nginx 缓冲
    })
    // 立即刷新 headers 和禁用 Nagle 算法
    res.flushHeaders()
    if (res.socket) res.socket.setNoDelay(true)

    // 按 token 粒度切分（模拟逐 token 输出）
    const tokens = tokenize(TEST_CONTENT)
    console.log(`[Mock] 共 ${tokens.length} 个 token，开始流式输出...`)

    let counter = 0
    for (const token of tokens) {
      const id = String(counter++).padStart(8, '0')
      writeSSEEvent(res, id, token)

      // 模拟延迟：15-35ms，接近真实 LLM 逐 token 输出节奏
      await sleep(15 + Math.random() * 20)
    }

    // 发送结束标记
    writeSSEEvent(res, 'done9999', '[DONE]')
    res.end()
    console.log(`[Mock] 流式完成，共 ${counter} 个 token`)
    return
  }

  // 其他请求返回 404
  res.writeHead(404, { 'Content-Type': 'text/plain' })
  res.end('Not Found')
})

server.listen(PORT, () => {
  console.log(`\n🎭 Mock SSE Server 已启动: http://localhost:${PORT}`)
  console.log(`   端点: POST http://localhost:${PORT}/api/chat/stream`)
  console.log(`   前端 npm run dev 后直接对话即可测试\n`)
})

/**
 * 简单分词：中文逐字 + 英文/数字/标点按组切分
 */
function tokenize(text) {
  const tokens = []
  let i = 0
  while (i < text.length) {
    const ch = text[i]
    if (/[一-鿿]/.test(ch)) {
      // 中文字：逐字
      tokens.push(ch)
      i++
    } else if (/[a-zA-Z0-9]/.test(ch)) {
      // 英文/数字：连续组成一个 token
      let word = ''
      while (i < text.length && /[a-zA-Z0-9]/.test(text[i])) {
        word += text[i]
        i++
      }
      tokens.push(word)
    } else if (ch === '\n') {
      // 换行符合并到下一个 token 前面，避免裸 \n 被 SSE 解析器丢弃
      let newlines = ''
      while (i < text.length && text[i] === '\n') {
        newlines += text[i]
        i++
      }
      // 如果后面还有内容，合并；否则单独追加
      if (tokens.length > 0) {
        tokens[tokens.length - 1] += newlines
      } else {
        tokens.push(newlines)
      }
    } else if (ch === ' ') {
      // 空格合并到前一个或后一个 token，避免裸空格作为独立 SSE data 的问题
      let spaces = ''
      while (i < text.length && text[i] === ' ') {
        spaces += text[i]
        i++
      }
      // 追加到前一个 token（大多数空格是词间分隔）
      if (tokens.length > 0) {
        tokens[tokens.length - 1] += spaces
      } else {
        tokens.push(spaces)
      }
    } else {
      // 标点等其他字符：逐字
      tokens.push(ch)
      i++
    }
  }
  return tokens
}
