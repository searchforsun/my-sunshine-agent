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

    const timeline = page.locator('.timeline-panel').last()
    await expect(timeline).toBeVisible({ timeout: 30_000 })
    await expect(timeline.locator('.timeline-label', { hasText: '识别意图' })).toBeVisible()
    await expect(timeline.getByText(/阅读/)).toBeVisible()
    await expect(timeline.locator('.summary-line.is-after').filter({ hasText: /日常对话|直接生成/ })).toBeVisible({ timeout: 30_000 })
    await expect(timeline.locator('.timeline-label', { hasText: '生成回答' })).toBeVisible()
    await expect(timeline.locator('.timeline-total')).toBeVisible({ timeout: 30_000 })
  })

  test('knowledge 意图：展示知识库分类与 Agent 推理', async ({ page }) => {
    test.setTimeout(180_000)
    const question = '公司考勤制度是什么？'
    await sendMessage(page, question)

    const timeline = page.locator('.timeline-panel').last()
    await expect(timeline).toBeVisible({ timeout: 30_000 })
    await expect(timeline.locator('.summary-line.is-after').filter({ hasText: /知识库|企业知识/ })).toBeVisible({ timeout: 45_000 })
    await expect(page.locator('.composer-box--streaming')).toBeHidden({ timeout: 120_000 })
    if (await timeline.locator('.timeline-body').isHidden()) {
      await timeline.locator('.timeline-toggle').click()
    }
    await expect(timeline.locator('.timeline-label', { hasText: '分析作答' })).toBeVisible({ timeout: 15_000 })
    await expect(timeline.locator('.timeline-label', { hasText: '检索知识库' })).toBeVisible({ timeout: 30_000 })
    await expect(timeline.getByText(/未找到与|找到 \d+ 条与/).first()).toBeVisible({ timeout: 30_000 })
    await expect(timeline.locator('.timeline-label', { hasText: '生成回答' })).toBeVisible()
    await expect(timeline.getByText(/已完成对/)).toBeVisible()
  })

  test('流式结束后时间线可折叠', async ({ page }) => {
    test.setTimeout(120_000)
    await sendMessage(page, '用一句话介绍你自己')

    const timeline = page.locator('.timeline-panel').last()
    await expect(timeline).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('.composer-box--streaming')).toBeHidden({ timeout: 90_000 })

    const toggle = timeline.locator('.timeline-toggle')
    await expect(timeline.locator('.timeline-body')).toBeHidden()
    await toggle.click()
    await expect(timeline.locator('.timeline-body')).toBeVisible()
    await toggle.click()
    await expect(timeline.locator('.timeline-body')).toBeHidden()
  })

  test('刷新页面后处理过程仍保留', async ({ page }) => {
    test.setTimeout(120_000)
    await sendMessage(page, '你好，请简短回复')

    const timeline = page.locator('.timeline-panel').last()
    await expect(timeline).toBeVisible({ timeout: 30_000 })
    await expect(timeline.locator('.timeline-label', { hasText: '识别意图' })).toBeVisible()
    await expect(page.locator('.composer-box--streaming')).toBeHidden({ timeout: 90_000 })

    await page.reload()
    await expect(page.getByRole('textbox', { name: INPUT })).toBeVisible({ timeout: 15_000 })

    const restored = page.locator('.timeline-panel').last()
    await expect(restored).toBeVisible({ timeout: 15_000 })
    await restored.locator('.timeline-toggle').click()
    await expect(restored.locator('.timeline-label', { hasText: '识别意图' })).toBeVisible()
    await expect(restored.getByText(/阅读|判定/)).toBeVisible()
    await expect(restored.locator('.timeline-label', { hasText: '生成回答' })).toBeVisible()
    await expect(restored.locator('.timeline-total')).toBeVisible()
  })
})
