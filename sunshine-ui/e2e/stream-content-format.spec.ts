import { test, expect } from '@playwright/test'

const PROMPT = '写一段 Python 快速排序，只给代码和一行说明'

test.describe('流式 content JSON 与 Markdown 显示', () => {
  test('正文有换行、代码块可渲染、无思考内容泄露', async ({ page }) => {
    test.setTimeout(180_000)

    await page.goto('/chat')

    const input = page.getByRole('textbox', { name: '发消息，Enter 发送' })
    await input.fill(PROMPT)
    await page.getByRole('button', { name: '发送' }).click()

    await expect(page.locator('.user-bubble').filter({ hasText: PROMPT })).toHaveCount(1)

    const streamingStatus = page.getByText('AI 正在回复…')
    await expect(streamingStatus).toBeVisible({ timeout: 30_000 })
    await expect(streamingStatus).toBeHidden({ timeout: 150_000 })

    const msgMd = page.locator('.assistant-body .msg-md').last()
    await expect(msgMd).toBeVisible({ timeout: 10_000 })

    const codeBlock = msgMd.locator('pre code, pre:not(.smd-mermaid-source)')
    await expect(codeBlock.first()).toBeVisible({ timeout: 15_000 })

    const codeText = await codeBlock.first().innerText()
    expect(codeText).toMatch(/def\s+quicksort/i)
    expect(codeText).not.toMatch(/defquicksort/i)
    expect(codeText.split('\n').length).toBeGreaterThan(3)

    const reasoningBody = page.locator('.assistant-body').last().locator('.reasoning-body')
    const contentText = await msgMd.innerText()
    expect(contentText.trim().length).toBeGreaterThan(20)
    expect(contentText).not.toMatch(/我们需要|根据对话历史|输出协议|reasoning_content/i)
    if (await reasoningBody.count() > 0) {
      expect(await reasoningBody.innerText()).not.toBe('')
    }
  })
})
