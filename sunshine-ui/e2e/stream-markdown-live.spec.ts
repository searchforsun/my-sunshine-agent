import { test, expect } from '@playwright/test'

const PROMPT = '用一句话介绍 RAG'

test.describe('真实链路流式 Markdown', () => {
  test('用户提示词只出现一次，流式回复正常结束', async ({ page }) => {
    await page.goto('/chat')

    const input = page.getByRole('textbox', { name: '发消息，Enter 发送' })
    await input.fill(PROMPT)
    await page.getByRole('button', { name: '发送' }).click()

    await expect(page.locator('.user-bubble').filter({ hasText: PROMPT })).toHaveCount(1)

    const assistantBody = page.locator('.assistant-body').last()
    await expect(assistantBody).toBeVisible({ timeout: 30_000 })

    const streamingStatus = page.getByText('AI 正在回复…')
    await expect(streamingStatus).toBeVisible({ timeout: 30_000 })
    await expect(streamingStatus).toBeHidden({ timeout: 120_000 })

    const bubbles = page.locator('.user-bubble')
    await expect(bubbles).toHaveCount(1)
    await expect(bubbles.first()).toHaveText(PROMPT)

    const assistantText = await assistantBody.innerText()
    expect(assistantText.trim().length).toBeGreaterThan(10)

    const promptOccurrences = assistantText.split(PROMPT).length - 1
    expect(promptOccurrences, `助手回复不应重复用户提示词 (${promptOccurrences} 次)`).toBeLessThanOrEqual(1)
  })
})
