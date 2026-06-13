import { test, expect } from '@playwright/test'

const USER_KEY = 'sunshine-user-id'
const CURRENT_KEY = 'sunshine-current-conversation-id'

test.describe('会话列表刷新', () => {
  test('刷新后侧栏仍显示已有会话', async ({ page }) => {
    test.setTimeout(150_000)
    const userId = `e2e-list-${Date.now()}`
    await page.addInitScript((uid) => {
      if (!localStorage.getItem('sunshine-user-id')) {
        localStorage.setItem('sunshine-user-id', uid)
      }
    }, userId)

    await page.goto('/chat')
    await expect(page.getByPlaceholder('发消息，Enter 发送')).toBeVisible()

    const input = page.getByPlaceholder('发消息，Enter 发送')
    await input.fill('你好，一句话介绍自己')
    await input.press('Enter')
    await expect(page.locator('.composer-box--streaming')).toHaveCount(0, { timeout: 120_000 })

    const title = page.locator('.history-item-title').first()
    await expect(title).toBeVisible()
    const titleText = (await title.innerText()).trim()
    expect(titleText.length).toBeGreaterThan(0)
    expect(titleText).not.toBe('暂无对话')

    const convId = await page.evaluate(() => localStorage.getItem('sunshine-current-conversation-id'))
    expect(convId).toBeTruthy()

    await page.reload()
    await expect(page.getByPlaceholder('发消息，Enter 发送')).toBeVisible({ timeout: 30_000 })

    await expect(page.locator('.history-item').first()).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('.history-item-title').first()).toContainText(titleText.slice(0, 8))
    await expect(page.evaluate(() => localStorage.getItem('sunshine-current-conversation-id'))).resolves.toBe(convId)
  })
})
