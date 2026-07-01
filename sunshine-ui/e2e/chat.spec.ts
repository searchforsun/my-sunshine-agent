import { test, expect } from '@playwright/test'
import { ensureE2eLogin } from './helpers'

test.describe('AI 对话页', () => {
  test('应展示欢迎页与输入框', async ({ page }) => {
    await ensureE2eLogin(page)
    await page.goto('/chat')

    await expect(page.getByRole('heading', { name: '有什么可以帮你的？' })).toBeVisible()
    await expect(page.locator('.composer-input-area')).toBeVisible()
    await expect(page.getByRole('button', { name: '新对话' })).toBeVisible()
  })

  test('快捷提示可触发对话', async ({ page }) => {
    await ensureE2eLogin(page)
    await page.goto('/chat')

    await page.getByRole('button', { name: '制度检索' }).click()
    await expect(page.locator('.user-bubble').filter({ hasText: '检索知识库：公司的差旅报销制度有哪些要点？' })).toBeVisible()
  })
})
