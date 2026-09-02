import { test, expect, type Page, type Locator } from '@playwright/test'

/**
 * 真实后端 E2E — 依赖 BFF(:8001) + Orchestrator(:8200) + LLM Gateway(:8300)
 * 运行前请确保 mock-server 未占用 8001
 *
 * 注意（AS2 P0-8 基线对齐）：
 * - Chat 顶层 OperationStack 传入 messageStatus，时间线默认折叠（timeline-summary 概要行），
 *   需点击概要行展开后才能看到步骤卡片。
 * - ReAct 模式 generate 步骤行被前端刻意隐藏（正文 inline 穿插），断言「生成回答」步骤行
 *   属于过期预期；正文可见即代表生成完成。
 */
import { sendChatMessage, waitForStreamComplete } from './helpers'

/** 点击 timeline-summary 概要行，展开当前 assistant 消息的步骤列表 */
async function expandTimeline(lines: Locator) {
  const summaryRow = lines.locator('.timeline-summary .op-line-row')
  await expect(summaryRow).toBeVisible({ timeout: 30_000 })
  const expanded = await lines.locator('.timeline-summary.is-expanded').count()
  if (!expanded) {
    await summaryRow.click()
  }
  await expect(lines.locator('.timeline-summary.is-expanded')).toBeVisible({ timeout: 10_000 })
}

test.describe('处理过程时间线（真实后端）', () => {
  // 真实 LLM 链路耗时不定，文件内串行避免并发资源竞争
  test.describe.configure({ mode: 'serial' })

  test('simple 路径：意图识别 + 正文生成', async ({ page }) => {
    test.setTimeout(150_000)
    const question = '你好，今天天气不错'
    await sendChatMessage(page, question)

    const lines = page.locator('.operation-lines').last()
    // 真实 LLM 链路偶发慢响应，放宽首步等待
    await expect(lines).toBeVisible({ timeout: 60_000 })
    await waitForStreamComplete(page, 120_000)

    // 时间线默认折叠：展开后可见「识别意图」步骤（ReAct generate 步骤行刻意隐藏）
    await expandTimeline(lines)
    await expect(lines.locator('.operation-card-title', { hasText: '识别意图' })).toBeVisible()
    // 正文生成完成（generate 步正文 inline 展示）
    await expect(page.locator('.assistant-body').last()).not.toBeEmpty()
  })

  test('knowledge 意图：展示知识库分类与 Agent 推理', async ({ page }) => {
    test.setTimeout(180_000)
    const question = '公司考勤制度是什么？'
    await sendChatMessage(page, question)

    const lines = page.locator('.operation-lines').last()
    await expect(lines).toBeVisible({ timeout: 60_000 })
    await expect(lines.getByText(/知识库|企业知识/).first()).toBeVisible({ timeout: 45_000 })
    await waitForStreamComplete(page, 120_000)

    await expandTimeline(lines)
    // 现行时间线契约：识别意图 → 知识检索（RAG 步固定 label）→ 正文终稿（无独立 think 行）
    await expect(lines.locator('.operation-card-title', { hasText: '检索知识库' }).first()).toBeVisible({ timeout: 30_000 })
    // RAG 检索步已展示召回结果（现行 catalog 文案：「找到 N 条参考片段」/「未找到…」）
    await expect(lines.getByText(/未找到|找到 \d+ 条/).first()).toBeVisible({ timeout: 30_000 })
    // ReAct 无「生成回答」步骤行；正文即终稿
    await expect(page.locator('.assistant-body').last()).not.toBeEmpty()
  })

  test('单步可展开详情', async ({ page }) => {
    test.setTimeout(180_000)
    await sendChatMessage(page, '公司考勤制度是什么？')

    const lines = page.locator('.operation-lines').last()
    await expect(lines).toBeVisible({ timeout: 60_000 })
    await waitForStreamComplete(page, 120_000)

    await expandTimeline(lines)
    // 任一可展开步骤（tool/RAG 均有内容）点击后展开区（.op-detail）展示详情；
    // 用 .op-row 排除 timeline-summary 概要行
    const clickableRow = lines.locator('.op-row .op-line.is-clickable .op-line-row').first()
    await expect(clickableRow).toBeVisible({ timeout: 15_000 })
    await clickableRow.click()
    await expect(lines.locator('.op-detail').first()).toBeVisible({ timeout: 10_000 })
  })

  test('刷新页面后步骤行仍保留', async ({ page }) => {
    test.setTimeout(150_000)
    await sendChatMessage(page, '你好，请简短回复')

    const lines = page.locator('.operation-lines').last()
    await expect(lines).toBeVisible({ timeout: 60_000 })
    await waitForStreamComplete(page, 120_000)
    await expandTimeline(lines)
    await expect(lines.locator('.operation-card-title', { hasText: '识别意图' })).toBeVisible()

    await page.reload()
    await expect(page.locator('.composer-editor, .composer-textarea').first()).toBeVisible({ timeout: 15_000 })

    const restored = page.locator('.operation-lines').last()
    await expect(restored).toBeVisible({ timeout: 15_000 })
    await expandTimeline(restored)
    await expect(restored.locator('.operation-card-title', { hasText: '识别意图' })).toBeVisible()
    // ReAct generate 步骤行隐藏；正文保留即终稿保留
    await expect(page.locator('.assistant-body').last()).not.toBeEmpty()
  })
})
