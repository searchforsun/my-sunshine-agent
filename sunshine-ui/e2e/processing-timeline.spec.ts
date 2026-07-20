import { test, expect } from '@playwright/test'
import { lastOperationStack, sendChatMessage, waitForStreamComplete } from './helpers'

test.describe('处理过程时间线', () => {
  // 连 live Gateway 时并行易触租户 QPS；本文件串行避免误伤
  test.describe.configure({ mode: 'serial' })

  test('ReAct 路径展示意图识别步骤', async ({ page }) => {
    test.setTimeout(90_000)
    await sendChatMessage(page, '你好，简单聊聊')

    const timeline = lastOperationStack(page)
    await expect(timeline).toBeVisible({ timeout: 20_000 })
    await waitForStreamComplete(page, 60_000)
    const summary = timeline.locator('.timeline-summary')
    await expect(summary).toBeVisible()
    await summary.locator('.op-line-row').click() // expand to see steps

    await expect(timeline.locator('.op-label', { hasText: '识别意图' })).toBeVisible()
    // mock:「判定为：自主智能体」；live:「将由自主智能体分析并作答」
    await expect(timeline.getByText(/自主智能体/)).toBeVisible()
    await expect(timeline.locator('.op-dur').first()).toBeVisible()
  })

  test('knowledge 路径展示 RAG 检索步骤', async ({ page }) => {
    test.setTimeout(90_000)
    await sendChatMessage(page, '考勤制度是什么？')

    const timeline = lastOperationStack(page)
    await expect(timeline).toBeVisible({ timeout: 20_000 })
    await waitForStreamComplete(page, 60_000)
    const summary = timeline.locator('.timeline-summary')
    await expect(summary).toBeVisible()
    await summary.locator('.op-line-row').click() // expand to see steps

    await expect(timeline.locator('.op-label', { hasText: '检索知识库' })).toBeVisible()
    // mock:「命中 3 条」；live tool 摘要：「找到 3 条参考片段」
    await expect(timeline.getByText(/命中 \d+ 条|找到 \d+ 条/)).toBeVisible()
    await expect(timeline.locator('.op-dur').first()).toBeVisible()
  })

  test('流式结束后步骤行可展开折叠', async ({ page }) => {
    test.setTimeout(90_000)
    await sendChatMessage(page, '你好，简单聊聊')

    const timeline = lastOperationStack(page)
    await expect(timeline).toBeVisible({ timeout: 20_000 })
    await waitForStreamComplete(page, 60_000)

    // 完成后默认折叠总览，先展开实现线再测步骤行折叠
    const summary = timeline.locator('.timeline-summary')
    await expect(summary).toBeVisible()
    await summary.locator('.op-line-row').click()

    // mock 意图步可展开；live 意图常无 detail，取首个可点实现步
    const stepLine = timeline.locator('.op-line.is-clickable:not(.timeline-summary)').first()
    await expect(stepLine).toBeVisible()
    await expect(stepLine.locator('.op-detail')).toHaveCount(0)

    await stepLine.locator('.op-line-row').click()
    await expect(stepLine.locator('.op-detail')).toBeVisible()

    await stepLine.locator('.op-line-row').click()
    await expect(stepLine.locator('.op-detail')).toHaveCount(0)
  })

  test('总览行：完成后默认折叠，展开可恢复步骤', async ({ page }) => {
    test.setTimeout(90_000)
    await sendChatMessage(page, '你好，简单聊聊')
    const timeline = lastOperationStack(page)
    await expect(timeline).toBeVisible({ timeout: 20_000 })
    await waitForStreamComplete(page, 60_000)

    const summary = timeline.locator('.timeline-summary')
    await expect(summary).toBeVisible()
    await expect(summary).toContainText(/已完成/)
    // 默认折叠：实现线意图步不可见
    await expect(timeline.locator('.op-label', { hasText: '识别意图' })).toHaveCount(0)

    await summary.locator('.op-line-row').click()
    await expect(timeline.locator('.op-label', { hasText: '识别意图' })).toBeVisible()

    await summary.locator('.op-line-row').click()
    await expect(timeline.locator('.op-label', { hasText: '识别意图' })).toHaveCount(0)
    // 折叠后不应出现两份终稿（Stack 内一块 + 底栏）
    const answers = page.locator('.assistant-body').last().locator('.msg-md, .timeline-collapsed-answer .msg-md')
    await expect(answers).toHaveCount(1)
  })
})
