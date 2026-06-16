import { test, expect } from '@playwright/test'

/**
 * 真实后端 E2E — 依赖 BFF(:8001) + Orchestrator(:8200) + LLM Gateway(:8300)
 * 运行前请确保 mock-server 未占用 8001
 */
const INPUT = '发消息，Enter 发送'

async function sendMessage(page: import('@playwright/test').Page, text: string) {
  await page.goto('/chat')
  const input = page.getByRole('textbox', { name: INPUT })
  await expect(input).toBeVisible({ timeout: 30_000 })
  await input.fill(text)
  await page.keyboard.press('Enter')
}

test.describe('处理过程时间线（真实后端）', () => {
  test('simple 路径：意图识别 + 生成回答', async ({ page }) => {
    test.setTimeout(120_000)
    const question = '你好，今天天气不错'
    await sendMessage(page, question)

    const lines = page.locator('.operation-lines').last()
    await expect(lines).toBeVisible({ timeout: 30_000 })
    await expect(lines.locator('.operation-card-title', { hasText: '识别意图' })).toBeVisible()
    await expect(lines.getByText(/阅读/)).toBeVisible()
    await expect(lines.locator('.operation-card-title', { hasText: '生成回答' })).toBeVisible({ timeout: 30_000 })
  })

  test('knowledge 意图：展示知识库分类与 Agent 推理', async ({ page }) => {
    test.setTimeout(180_000)
    const question = '公司考勤制度是什么？'
    await sendMessage(page, question)

    const lines = page.locator('.operation-lines').last()
    await expect(lines).toBeVisible({ timeout: 30_000 })
    await expect(lines.getByText(/知识库|企业知识/)).toBeVisible({ timeout: 45_000 })
    await expect(page.locator('.composer-box--streaming')).toBeHidden({ timeout: 120_000 })
    await expect(lines.locator('.operation-card-title', { hasText: '分析作答' })).toBeVisible({ timeout: 15_000 })
    await expect(lines.locator('.operation-card-title', { hasText: '检索知识库' })).toBeVisible({ timeout: 30_000 })
    await expect(lines.getByText(/未找到与|找到 \d+ 条与/).first()).toBeVisible({ timeout: 30_000 })
    await expect(lines.locator('.operation-card-title', { hasText: '生成回答' })).toBeVisible()
  })

  test('单步可展开详情', async ({ page }) => {
    test.setTimeout(120_000)
    await sendMessage(page, '用一句话介绍你自己')

    const lines = page.locator('.operation-lines').last()
    await expect(lines).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('.composer-box--streaming')).toBeHidden({ timeout: 90_000 })

    const intentLine = lines.locator('.op-line-row').filter({ hasText: '识别意图' }).first()
    await intentLine.click()
    await expect(lines.locator('.op-detail-line').filter({ hasText: /阅读/ })).toBeVisible()
  })

  test('刷新页面后步骤行仍保留', async ({ page }) => {
    test.setTimeout(120_000)
    await sendMessage(page, '你好，请简短回复')

    const lines = page.locator('.operation-lines').last()
    await expect(lines).toBeVisible({ timeout: 30_000 })
    await expect(lines.locator('.operation-card-title', { hasText: '识别意图' })).toBeVisible()
    await expect(page.locator('.composer-box--streaming')).toBeHidden({ timeout: 90_000 })

    await page.reload()
    await expect(page.getByRole('textbox', { name: INPUT })).toBeVisible({ timeout: 15_000 })

    const restored = page.locator('.operation-lines').last()
    await expect(restored).toBeVisible({ timeout: 15_000 })
    await expect(restored.locator('.operation-card-title', { hasText: '识别意图' })).toBeVisible()
    await expect(restored.locator('.operation-card-title', { hasText: '生成回答' })).toBeVisible()
  })
})
