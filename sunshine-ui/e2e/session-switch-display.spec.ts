import { test, expect } from '@playwright/test'

const INPUT = '发消息，Enter 发送'

test.describe('会话切换展示', () => {
  test('切换侧栏会话应展示历史消息', async ({ page }) => {
    test.setTimeout(180_000)

    await page.goto('/chat')
    await page.getByRole('textbox', { name: INPUT }).fill('你好')
    await page.keyboard.press('Enter')
    await expect(page.locator('.composer-box--streaming')).toBeHidden({ timeout: 120_000 })
    await expect(page.locator('.user-bubble').filter({ hasText: '你好' })).toBeVisible()

    // 新建第二个会话
    await page.getByRole('button', { name: '新对话' }).click()
    await page.getByRole('textbox', { name: INPUT }).fill('写一段 Python 快速排序')
    await page.keyboard.press('Enter')
    await expect(page.locator('.composer-box--streaming')).toBeHidden({ timeout: 120_000 })

    const helloItem = page.locator('.history-item').filter({ hasText: '你好' })
    await expect(helloItem).toBeVisible()
    await helloItem.click()

    await expect(helloItem).toHaveClass(/active/)
    await expect(page.locator('.user-bubble').filter({ hasText: '你好' })).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.assistant-body .msg-md').first()).not.toBeEmpty()
  })
})
