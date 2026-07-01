import { test, expect } from '@playwright/test'
import { fillComposer, sendChatMessage, waitForStreamComplete } from './helpers'

test.describe('会话切换展示', () => {
  test('切换侧栏会话应展示历史消息', async ({ page }) => {
    test.setTimeout(180_000)

    await sendChatMessage(page, '你好')
    await waitForStreamComplete(page)
    await expect(page.locator('.user-bubble').filter({ hasText: '你好' })).toBeVisible()

    await page.getByRole('button', { name: '新对话' }).click()
    await fillComposer(page, '写一段 Python 快速排序')
    await page.keyboard.press('Enter')
    await waitForStreamComplete(page)

    const helloItem = page.locator('.history-item').filter({ hasText: '你好' })
    await expect(helloItem).toBeVisible()
    await helloItem.click()

    await expect(helloItem).toHaveClass(/active/)
    await expect(page.locator('.user-bubble').filter({ hasText: '你好' })).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.assistant-body .msg-md').first()).not.toBeEmpty()
  })
})
