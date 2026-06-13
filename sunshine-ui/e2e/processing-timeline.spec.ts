import { test, expect } from '@playwright/test'

const INPUT = '发消息，Enter 发送'

async function sendMessage(page: import('@playwright/test').Page, text: string) {
  await page.goto('/chat')
  await page.getByRole('textbox', { name: INPUT }).fill(text)
  await page.keyboard.press('Enter')
}

test.describe('处理过程时间线', () => {
  test('simple 路径展示意图识别与生成回答步骤', async ({ page }) => {
    await sendMessage(page, '你好，简单聊聊')

    const timeline = page.locator('.timeline-panel').last()
    await expect(timeline).toBeVisible({ timeout: 20000 })
    await expect(timeline.getByText('处理过程')).toBeVisible()
    await expect(timeline.getByText('准备识别意图')).toBeVisible()
    await expect(timeline.getByText('正在分析用户输入')).toBeVisible()
    await expect(timeline.getByText(/判定为/)).toBeVisible()
    await expect(timeline.getByText('简单对话')).toBeVisible()
    await expect(timeline.locator('.timeline-label', { hasText: '识别意图' })).toBeVisible()
    await expect(timeline.locator('.timeline-label', { hasText: '生成回答' })).toBeVisible()
    await expect(timeline.getByText(/\d+ms|\d+\.\d+s/).first()).toBeVisible()
  })

  test('knowledge 路径展示 RAG 检索步骤', async ({ page }) => {
    await sendMessage(page, '考勤制度是什么？')

    const timeline = page.locator('.timeline-panel').last()
    await expect(timeline).toBeVisible({ timeout: 20000 })
    await expect(timeline.getByText('准备检索向量库')).toBeVisible()
    await expect(timeline.getByText('正在查询 Milvus')).toBeVisible()
    await expect(timeline.getByText('知识库查询')).toBeVisible()
    await expect(timeline.locator('.timeline-label', { hasText: '检索知识库' })).toBeVisible()
    await expect(timeline.getByText('命中 3 条')).toBeVisible()
    await expect(timeline.getByText(/\d+ms|\d+\.\d+s/).first()).toBeVisible()
  })

  test('流式结束后时间线可折叠', async ({ page }) => {
    test.setTimeout(90_000)
    await sendMessage(page, '__e2e_reasoning__')

    const timeline = page.locator('.timeline-panel').last()
    await expect(timeline).toBeVisible({ timeout: 20000 })

    await expect(page.locator('.composer-box--streaming')).toBeHidden({ timeout: 60_000 })

    const toggle = timeline.locator('.timeline-toggle')
    await expect(timeline.locator('.timeline-body')).toBeHidden()

    await toggle.click()
    await expect(timeline.locator('.timeline-body')).toBeVisible()

    await toggle.click()
    await expect(timeline.locator('.timeline-body')).toBeHidden()
  })
})
