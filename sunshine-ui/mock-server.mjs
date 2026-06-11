/**
 * Mock SSE Server — 模拟后端流式输出 Markdown 内容
 * 用法：node mock-server.mjs
 * 监听 :8001，前端 npm run dev 后直接对话即可
 */

import http from 'node:http'
import { randomUUID } from 'node:crypto'

const PORT = 8001

/** @type {Map<string, { id: string, userId: string, title: string, createdAt: string, updatedAt: string, messages: object[] }>} */
const conversations = new Map()

/**
 * @type {Map<string, {
 *   events: { seq: number, text: string }[],
 *   status: 'RUNNING' | 'COMPLETED' | 'INTERRUPTED',
 *   messageId: string,
 *   conversationId: string,
 *   userId: string,
 *   lastSeq: number,
 *   cancelled: boolean,
 *   waiters: (() => void)[],
 * }>}
 */
const generations = new Map()

function nowIso() {
  return new Date().toISOString()
}

function newId() {
  return randomUUID().replace(/-/g, '')
}

function getUserId(req) {
  return req.headers['x-user-id'] || 'anonymous'
}

function readBody(req) {
  return new Promise((resolve) => {
    let body = ''
    req.on('data', c => { body += c })
    req.on('end', () => resolve(body))
  })
}

function json(res, status, data) {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(data))
}

function listForUser(userId) {
  return [...conversations.values()]
    .filter(c => c.userId === userId)
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    .map(({ messages, userId: _u, ...rest }) => rest)
}

function getOwned(id, userId) {
  const c = conversations.get(id)
  if (!c || c.userId !== userId) return null
  return c
}

function getOwnedGeneration(id, userId) {
  const g = generations.get(id)
  if (!g || g.userId !== userId) return null
  return g
}

function notifyGenerationWaiters(gen) {
  const waiters = gen.waiters.splice(0)
  for (const w of waiters) w()
}

function waitForGenerationEvent(gen, timeoutMs = 500) {
  return new Promise(resolve => {
    const timer = setTimeout(resolve, timeoutMs)
    gen.waiters.push(() => {
      clearTimeout(timer)
      resolve()
    })
  })
}

async function emitGenerationChunks(generationId, tokens, res, assistantMsg, conv) {
  const gen = generations.get(generationId)
  if (!gen) return

  let clientConnected = true
  res.on('close', () => { clientConnected = false })

  for (const token of tokens) {
    if (gen.cancelled) break

    const seq = gen.lastSeq + 1
    gen.events.push({ seq, text: token })
    gen.lastSeq = seq
    assistantMsg.content += token
    notifyGenerationWaiters(gen)

    if (clientConnected && !res.writableEnded) {
      writeSSEEvent(res, seq, token)
    }

    await sleep(15 + Math.random() * 20)
  }

  conv.updatedAt = nowIso()

  if (gen.cancelled) {
    gen.status = 'INTERRUPTED'
    assistantMsg.status = 'interrupted'
    notifyGenerationWaiters(gen)
    if (clientConnected && !res.writableEnded) {
      writeSSEEvent(res, 'meta-done', JSON.stringify({ type: 'message', id: assistantMsg.id, status: 'interrupted' }))
      res.end()
    }
    return
  }

  gen.status = 'COMPLETED'
  assistantMsg.status = 'completed'
  notifyGenerationWaiters(gen)

  if (clientConnected && !res.writableEnded) {
    writeSSEEvent(res, 'meta-done', JSON.stringify({ type: 'message', id: assistantMsg.id, status: 'completed' }))
    writeSSEEvent(res, 'done9999', '[DONE]')
    res.end()
  }
  console.log(`[Mock] generation 完成 id=${generationId} seq=${gen.lastSeq}`)
}

async function replayGenerationStream(gen, res, afterSeq) {
  let cursor = afterSeq

  while (true) {
    if (gen.status === 'INTERRUPTED') {
      if (!res.writableEnded) res.end()
      return
    }

    const pending = gen.events.filter(e => e.seq > cursor)
    for (const e of pending) {
      writeSSEEvent(res, e.seq, e.text)
      cursor = e.seq
    }

    if (gen.status === 'COMPLETED') {
      writeSSEEvent(res, 'meta-done', JSON.stringify({ type: 'message', id: gen.messageId, status: 'completed' }))
      writeSSEEvent(res, 'done9999', '[DONE]')
      res.end()
      return
    }

    await waitForGenerationEvent(gen)
  }
}

// 测试内容：覆盖所有常见 Markdown 特性
const TEST_CONTENT = `## Markdown 流式渲染测试

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
| RAG Service | 8400 | Milvus + 通义 Embedding | ✅ |

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
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, DELETE, OPTIONS')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, x-user-id, x-tenant-id')

  if (req.method === 'OPTIONS') {
    res.writeHead(204)
    res.end()
    return
  }

  const userId = getUserId(req)
  const url = req.url || ''

  // ── 会话 CRUD ──
  if (req.method === 'GET' && url === '/api/conversations') {
    return json(res, 200, listForUser(userId))
  }

  if (req.method === 'POST' && url === '/api/conversations') {
    const id = newId()
    const ts = nowIso()
    const conv = { id, userId, title: '新对话', createdAt: ts, updatedAt: ts, messages: [] }
    conversations.set(id, conv)
    return json(res, 200, { id, title: conv.title, createdAt: ts, updatedAt: ts })
  }

  const detailMatch = url.match(/^\/api\/conversations\/([^/?]+)$/)
  if (detailMatch) {
    const id = detailMatch[1]
    const conv = getOwned(id, userId)

    if (req.method === 'GET') {
      if (!conv) return json(res, 404, { error: 'not found' })
      return json(res, 200, {
        id: conv.id,
        title: conv.title,
        createdAt: conv.createdAt,
        updatedAt: conv.updatedAt,
        messages: conv.messages,
      })
    }

    if (req.method === 'PATCH') {
      if (!conv) return json(res, 404, { error: 'not found' })
      const body = JSON.parse(await readBody(req) || '{}')
      if (body.title) conv.title = body.title
      conv.updatedAt = nowIso()
      return json(res, 200, { id: conv.id, title: conv.title, createdAt: conv.createdAt, updatedAt: conv.updatedAt })
    }

    if (req.method === 'DELETE') {
      if (!conv) return json(res, 404, { error: 'not found' })
      conversations.delete(id)
      res.writeHead(204)
      res.end()
      return
    }
  }

  // ── Generation status / cancel ──
  const genApiMatch = url.match(/^\/api\/generations\/([^/?]+)(?:\/(cancel))?$/)
  if (genApiMatch) {
    const generationId = genApiMatch[1]
    const isCancel = genApiMatch[2] === 'cancel'
    const gen = getOwnedGeneration(generationId, userId)

    if (!gen) return json(res, 404, { error: 'not found' })

    if (req.method === 'GET' && !isCancel) {
      if (gen.status === 'INTERRUPTED') {
        return json(res, 410, {
          status: gen.status,
          lastSeq: gen.lastSeq,
          messageId: gen.messageId,
          conversationId: gen.conversationId,
          generationId,
        })
      }
      return json(res, 200, {
        status: gen.status,
        lastSeq: gen.lastSeq,
        messageId: gen.messageId,
        conversationId: gen.conversationId,
        generationId,
      })
    }

    if (req.method === 'POST' && isCancel) {
      gen.cancelled = true
      gen.status = 'INTERRUPTED'
      notifyGenerationWaiters(gen)
      const conv = conversations.get(gen.conversationId)
      const msg = conv?.messages.find(m => m.id === gen.messageId)
      if (msg) msg.status = 'interrupted'
      console.log(`[Mock] generation 已取消 id=${generationId}`)
      return json(res, 200, { status: 'INTERRUPTED' })
    }
  }

  // ── Generation reconnect SSE ──
  const reconnectMatch = url.match(/^\/api\/chat\/stream\/([^/?]+)(?:\?.*)?$/)
  if (req.method === 'GET' && reconnectMatch) {
    const generationId = reconnectMatch[1]
    const afterSeq = parseInt(new URL(url, 'http://localhost').searchParams.get('afterSeq') || '0', 10)
    const gen = getOwnedGeneration(generationId, userId)

    if (!gen) {
      res.writeHead(404, { 'Content-Type': 'text/plain' })
      res.end('Generation not found')
      return
    }

    if (gen.status === 'INTERRUPTED') {
      res.writeHead(410, { 'Content-Type': 'text/plain' })
      res.end('Generation interrupted')
      return
    }

    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',
    })
    res.flushHeaders?.()
    if (res.socket) res.socket.setNoDelay(true)

    console.log(`[Mock] 重连 generation=${generationId} afterSeq=${afterSeq} status=${gen.status}`)
    await replayGenerationStream(gen, res, afterSeq)
    return
  }

  if (req.method === 'POST' && url === '/api/chat/stream') {
    const body = await readBody(req)
    console.log(`[Mock] 收到请求: ${body.substring(0, 120)}...`)
    const payload = JSON.parse(body || '{}')

    let conv = payload.conversationId ? getOwned(payload.conversationId, userId) : null
    if (!conv && payload.conversationId) {
      res.writeHead(404, { 'Content-Type': 'text/plain' })
      res.end('Conversation not found')
      return
    }
    if (!conv) {
      const id = newId()
      const ts = nowIso()
      conv = { id, userId, title: '新对话', createdAt: ts, updatedAt: ts, messages: [] }
      conversations.set(id, conv)
    }

    const isResume = !!payload.resumeMessageId
    let assistantMsg

    if (isResume) {
      assistantMsg = conv.messages.find(m => m.id === payload.resumeMessageId)
      if (!assistantMsg) {
        res.writeHead(404, { 'Content-Type': 'text/plain' })
        res.end('Message not found')
        return
      }
      assistantMsg.status = 'streaming'
    } else {
      conv.messages.push({ id: newId(), role: 'user', content: payload.content, status: 'completed', seq: conv.messages.length + 1, createdAt: nowIso() })
      assistantMsg = { id: newId(), role: 'assistant', content: '', status: 'streaming', intent: 'simple', seq: conv.messages.length + 1, createdAt: nowIso() }
      conv.messages.push(assistantMsg)
      if (conv.title === '新对话' && payload.content) {
        conv.title = payload.content.length > 28 ? payload.content.slice(0, 28) : payload.content
      }
    }
    conv.updatedAt = nowIso()

    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',
    })
    res.flushHeaders?.()
    if (res.socket) res.socket.setNoDelay(true)

    writeSSEEvent(res, 'meta0', JSON.stringify({ type: 'conversation', id: conv.id }))
    writeSSEEvent(res, 'meta1', JSON.stringify({ type: 'message', id: assistantMsg.id, status: 'streaming', resume: isResume }))

    if (isResume) {
      const suffix = '\n\n--- 续传内容 ---'
      const tokens = tokenize(suffix)
      console.log(`[Mock] 续传 conv=${conv.id} tokens=${tokens.length}`)

      let counter = 2
      for (const token of tokens) {
        writeSSEEvent(res, String(counter++).padStart(8, '0'), token)
        await sleep(15 + Math.random() * 20)
      }

      assistantMsg.content += suffix
      assistantMsg.status = 'completed'
      conv.updatedAt = nowIso()

      writeSSEEvent(res, 'meta-done', JSON.stringify({ type: 'message', id: assistantMsg.id, status: 'completed' }))
      writeSSEEvent(res, 'done9999', '[DONE]')
      res.end()
      console.log(`[Mock] 续传完成`)
      return
    }

    const generationId = newId()
    generations.set(generationId, {
      events: [],
      status: 'RUNNING',
      messageId: assistantMsg.id,
      conversationId: conv.id,
      userId,
      lastSeq: 0,
      cancelled: false,
      waiters: [],
    })

    writeSSEEvent(res, 'meta2', JSON.stringify({
      type: 'generation',
      id: generationId,
      messageId: assistantMsg.id,
      seq: 0,
    }))

    const tokens = tokenize(TEST_CONTENT)
    console.log(`[Mock] 新消息 conv=${conv.id} generation=${generationId} tokens=${tokens.length}`)

    emitGenerationChunks(generationId, tokens, res, assistantMsg, conv).catch(err => {
      console.error(`[Mock] generation 异常 id=${generationId}`, err)
    })
    return
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' })
  res.end('Not Found')
})

server.listen(PORT, () => {
  console.log(`\n🎭 Mock SSE Server 已启动: http://localhost:${PORT}`)
  console.log(`   CRUD: GET/POST/PATCH/DELETE /api/conversations`)
  console.log(`   流式: POST /api/chat/stream`)
  console.log(`   重连: GET /api/chat/stream/:generationId?afterSeq=N`)
  console.log(`   状态: GET /api/generations/:id  |  取消: POST /api/generations/:id/cancel`)
  console.log(`   前端 npm run dev 后直接对话即可测试（F5 可演示无感重连）\n`)
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
