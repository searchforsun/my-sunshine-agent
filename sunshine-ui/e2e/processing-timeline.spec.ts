import { test, expect } from '@playwright/test'
import { lastOperationStack, sendChatMessage, waitForStreamComplete } from './helpers'

test.describe('处理过程时间线', () => {
  test('simple 路径展示意图识别步骤', async ({ page }) => {
    await sendChatMessage(page, '你好，简单聊聊')

    const timeline = lastOperationStack(page)
    await expect(timeline).toBeVisible({ timeout: 20_000 })
    await expect(timeline.locator('.op-label', { hasText: '识别意图' })).toBeVisible()
    await expect(timeline.getByText(/判定为/)).toBeVisible()
    await expect(timeline.getByText('简单对话')).toBeVisible()
    await expect(timeline.locator('.op-dur').first()).toBeVisible()
  })

  test('knowledge 路径展示 RAG 检索步骤', async ({ page }) => {
    await sendChatMessage(page, '考勤制度是什么？')

    const timeline = lastOperationStack(page)
    await expect(timeline).toBeVisible({ timeout: 20_000 })
    await expect(timeline.locator('.op-label', { hasText: '检索知识库' })).toBeVisible()
    await expect(timeline.getByText('知识库查询')).toBeVisible()
    await expect(timeline.getByText('命中 3 条')).toBeVisible()
    await expect(timeline.locator('.op-dur').first()).toBeVisible()
  })

  test('流式结束后步骤行可展开折叠', async ({ page }) => {
    test.setTimeout(90_000)
    await sendChatMessage(page, '你好，简单聊聊')

    const timeline = lastOperationStack(page)
    await expect(timeline).toBeVisible({ timeout: 20_000 })
    await waitForStreamComplete(page, 60_000)

    const intentLine = timeline.locator('.op-line').filter({ hasText: '识别意图' })
    await expect(intentLine).toBeVisible()
    await expect(intentLine.locator('.op-detail')).toHaveCount(0)

    await intentLine.locator('.op-line-row').click()
    await expect(intentLine.locator('.op-detail')).toBeVisible()
    await expect(intentLine.locator('.op-detail')).toContainText('简单对话')

    await intentLine.locator('.op-line-row').click()
    await expect(intentLine.locator('.op-detail')).toHaveCount(0)
  })
})
