/**
 * Mock 模式 FG 联调脚本 — Track G 重连 + Track F 续传
 * 用法: node scripts/mock-fg-integration.mjs
 * 前置: node mock-server.mjs 已在 :8001 运行
 */

const BASE = 'http://localhost:8001'
const USER = 'mock-test-alice'
const BOB = 'mock-test-bob'
const HEADERS = {
  'Content-Type': 'application/json',
  'x-user-id': USER,
  'x-tenant-id': 'default',
}

let passed = 0
let failed = 0

function ok(name, cond, detail = '') {
  if (cond) {
    passed++
    console.log(`  ✅ ${name}${detail ? ` — ${detail}` : ''}`)
  } else {
    failed++
    console.log(`  ❌ ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

function parseSseEvents(raw) {
  const events = []
  for (const block of raw.split('\n\n')) {
    if (!block.trim()) continue
    let id = ''
    const dataLines = []
    for (const line of block.split('\n')) {
      if (line.startsWith('id:')) id = line.slice(3).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5))
    }
    if (dataLines.length) events.push({ id, data: dataLines.join('\n') })
  }
  return events
}

async function readSseUntil(controller, minChunks = 5, timeoutMs = 15000) {
  const body = controller.body
  const resp = await fetch(`${BASE}/api/chat/stream`, {
    method: 'POST',
    headers: { ...HEADERS, Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal: controller.signal,
  })
  if (!resp.ok) throw new Error(`stream HTTP ${resp.status}`)

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  const events = []
  let generationId = null
  let messageId = null
  let chunkCount = 0

  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    const parts = buf.split('\n\n')
    buf = parts.pop() || ''
    for (const block of parts) {
      const ev = parseSseEvents(block + '\n\n')[0]
      if (!ev) continue
      events.push(ev)
      try {
        const meta = JSON.parse(ev.data)
        if (meta.type === 'generation') {
          generationId = meta.id
          messageId = meta.messageId
        }
      } catch { /* chunk */ }
      if (ev.id && !ev.data.startsWith('{') && ev.data !== '[DONE]') {
        chunkCount++
        if (chunkCount >= minChunks) {
          controller.abort()
          reader.cancel().catch(() => {})
          return { generationId, messageId, events, chunkCount, conversationId: body.conversationId }
        }
      }
    }
  }
  controller.abort()
  return { generationId, messageId, events, chunkCount, conversationId: body.conversationId }
}

async function readSseUntilWithBody(body, minChunks = 5) {
  const ac = new AbortController()
  ac.body = body
  return readSseUntil(ac, minChunks)
}

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms))
}

async function main() {
  console.log('\n🎭 Mock FG 联调 — http://localhost:8001\n')

  // health
  try {
    await fetch(`${BASE}/api/conversations`, { headers: HEADERS })
  } catch {
    console.error('❌ Mock server 未启动，请先运行: node mock-server.mjs')
    process.exit(1)
  }

  // ── 1. 创建会话 ──
  console.log('【1】会话 CRUD')
  const createResp = await fetch(`${BASE}/api/conversations`, { method: 'POST', headers: HEADERS, body: '{}' })
  const conv = await createResp.json()
  ok('POST /api/conversations', createResp.ok && conv.id, `id=${conv.id?.slice(0, 8)}...`)

  // ── 2. Track G: 开流 + 中途断开 ──
  console.log('\n【2】Track G — 开流 + generation meta + 中途断开')
  const ac = new AbortController()
  const streamBody = { content: 'mock FG 联调测试', conversationId: conv.id }
  ac.body = streamBody
  const streamResult = await readSseUntil(ac, 5)
  ok('收到 generation meta', !!streamResult.generationId, streamResult.generationId?.slice(0, 8))
  ok('收到 ≥5 chunks 后断开', streamResult.chunkCount >= 5, `chunks=${streamResult.chunkCount}`)

  const genId = streamResult.generationId
  const convId = streamResult.conversationId || conv.id
  const msgId = streamResult.messageId

  // ── 3. 后台 buffer 继续增长 ──
  console.log('\n【3】Track G — 断开后 buffer 继续（轮询 lastSeq）')
  let seqAfterDisconnect = 0
  for (let i = 0; i < 20; i++) {
    await sleep(200)
    const st = await fetch(`${BASE}/api/generations/${genId}`, { headers: HEADERS })
    if (st.ok) {
      const body = await st.json()
      seqAfterDisconnect = body.lastSeq
      if (body.lastSeq > streamResult.chunkCount) break
    }
  }
  ok('断开后 lastSeq 继续增长', seqAfterDisconnect > streamResult.chunkCount,
    `disconnect@${streamResult.chunkCount} → lastSeq=${seqAfterDisconnect}`)

  // ── 4. afterSeq 重连 ──
  console.log('\n【4】Track G — reconnect afterSeq')
  const reconnectResp = await fetch(
    `${BASE}/api/chat/stream/${genId}?afterSeq=${streamResult.chunkCount}`,
    { headers: { ...HEADERS, Accept: 'text/event-stream' } },
  )
  ok('GET reconnect 200', reconnectResp.ok, `status=${reconnectResp.status}`)

  const reconnectText = await reconnectResp.text()
  const reconnectEvents = parseSseEvents(reconnectText)
  const reconnectSeqs = reconnectEvents
    .filter(e => e.data && !e.data.startsWith('{') && e.data !== '[DONE]')
    .map(e => parseInt(e.id, 10))
    .filter(n => !Number.isNaN(n))

  ok('重连 chunk seq > afterSeq', reconnectSeqs.every(s => s > streamResult.chunkCount),
    `seqs=${reconnectSeqs.slice(0, 5).join(',')}... (${reconnectSeqs.length} total)`)
  ok('重连含 completed meta', reconnectText.includes('"status":"completed"'))

  // ── 5. 越权 reconnect 404 ──
  console.log('\n【5】Track G — 越权 reconnect → 404')
  const forbidden = await fetch(`${BASE}/api/chat/stream/${genId}?afterSeq=0`, {
    headers: { 'x-user-id': BOB, 'x-tenant-id': 'default', Accept: 'text/event-stream' },
  })
  ok('bob 重连 alice generation → 404', forbidden.status === 404, `status=${forbidden.status}`)

  // ── 6. cancel → 410 ──
  console.log('\n【6】Track G — cancel → reconnect 410')
  const stream2 = await readSseUntilWithBody({ content: 'cancel test', conversationId: conv.id }, 3)
  const genId2 = stream2.generationId
  ok('第二次开流 generation', !!genId2)

  const cancelResp = await fetch(`${BASE}/api/generations/${genId2}/cancel`, {
    method: 'POST',
    headers: HEADERS,
  })
  ok('POST cancel 200', cancelResp.ok)

  await sleep(300)
  const reconnect410 = await fetch(`${BASE}/api/chat/stream/${genId2}?afterSeq=0`, {
    headers: { ...HEADERS, Accept: 'text/event-stream' },
  })
  ok('cancel 后 reconnect → 410', reconnect410.status === 410, `status=${reconnect410.status}`)

  const status410 = await fetch(`${BASE}/api/generations/${genId2}`, { headers: HEADERS })
  ok('GET status 也返回 410', status410.status === 410, `status=${status410.status}`)

  // ── 7. Track F — resume ──
  console.log('\n【7】Track F — 继续生成 (resumeMessageId)')
  const convDetail = await fetch(`${BASE}/api/conversations/${convId}`, { headers: HEADERS })
  const detail = await convDetail.json()
  const interrupted = detail.messages?.find(m => m.role === 'assistant' && m.status === 'interrupted')
  const resumeMsgId = interrupted?.id || msgId

  const resumeResp = await fetch(`${BASE}/api/chat/stream`, {
    method: 'POST',
    headers: { ...HEADERS, Accept: 'text/event-stream' },
    body: JSON.stringify({ conversationId: convId, resumeMessageId: resumeMsgId }),
  })
  ok('POST resume 200', resumeResp.ok)

  const resumeText = await resumeResp.text()
  ok('resume 含续传内容', resumeText.includes('续传内容') || resumeText.includes('completed'),
    resumeText.includes('续传内容') ? 'found 续传内容' : 'found completed meta')

  // ── 汇总 ──
  console.log(`\n${'─'.repeat(40)}`)
  console.log(`结果: ${passed} 通过, ${failed} 失败`)
  if (failed === 0) {
    console.log('🎉 Mock FG 联调全部通过\n')
    console.log('浏览器演示: http://localhost:5173/chat')
    console.log('  1. 发送消息 → 流式输出中途 F5 → 自动追读')
    console.log('  2. 点击停止 → 出现「继续生成」按钮\n')
  } else {
    process.exit(1)
  }
}

main().catch(err => {
  console.error('\n💥 联调异常:', err)
  process.exit(1)
})
