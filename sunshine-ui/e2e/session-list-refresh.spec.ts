import { test, expect } from '@playwright/test'
import { ensureE2eLogin, fillComposer, waitForStreamComplete } from './helpers'

test.describe('会话列表刷新', () => {
  test('刷新后侧栏仍显示已有会话', async ({ page }) => {
    test.setTimeout(150_000)
    await ensureE2eLogin(page)

    await page.goto('/chat')
    await fillComposer(page, '你好，一句话介绍自己')
    await page.keyboard.press('Enter')
    await waitForStreamComplete(page)

    const title = page.locator('.history-item-title').first()
    await expect(title).toBeVisible()
    const titleText = (await title.innerText()).trim()
    expect(titleText.length).toBeGreaterThan(0)
    expect(titleText).not.toBe('暂无对话')

    const convId = await page.evaluate(() => localStorage.getItem('sunshine-current-conversation-id'))
    expect(convId).toBeTruthy()

    await page.reload()
    await expect(page.locator('.composer-editor, .composer-textarea').first()).toBeVisible({ timeout: 30_000 })

    await expect(page.locator('.history-item').first()).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('.history-item-title').first()).toContainText(titleText.slice(0, 8))
    await expect(page.evaluate(() => localStorage.getItem('sunshine-current-conversation-id'))).resolves.toBe(convId)
  })
})
