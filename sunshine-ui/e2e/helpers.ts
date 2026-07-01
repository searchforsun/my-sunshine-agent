import { expect, type Page } from '@playwright/test'

const API_BASE = process.env.PLAYWRIGHT_API_BASE ?? 'http://localhost:5173'

/** Mock / Gateway 注册登录，写入 sunshine-token */
export async function ensureE2eLogin(page: Page) {
  const user = `e2e${Date.now()}`
  const password = 'password123'
  const reg = await page.request.post(`${API_BASE}/api/auth/register`, {
    data: { username: user, password, nickname: 'e2e' },
  })
  expect(reg.ok()).toBeTruthy()
  const login = await page.request.post(`${API_BASE}/api/auth/login`, {
    data: { username: user, password },
  })
  expect(login.ok()).toBeTruthy()
  const body = await login.json()
  const token = body?.data?.token as string
  expect(token).toBeTruthy()
  await page.addInitScript((t: string) => {
    localStorage.setItem('sunshine-token', t)
  }, token)
}

/** 输入框：auto/react 为 contenteditable，simple/workflow 为 textarea */
export async function fillComposer(page: Page, text: string) {
  const area = page.locator('.composer-input-area')
  await expect(area).toBeVisible({ timeout: 30_000 })
  const editable = area.locator('[contenteditable="true"]')
  const textarea = area.locator('textarea')
  if (await editable.count()) {
    await editable.first().click()
    await editable.first().fill(text)
  } else {
    await textarea.first().fill(text)
  }
}

export async function sendChatMessage(page: Page, text: string) {
  await ensureE2eLogin(page)
  await page.goto('/chat')
  await expect(page.getByRole('heading', { name: '有什么可以帮你的？' })).toBeVisible({ timeout: 15_000 })
  await fillComposer(page, text)
  await page.keyboard.press('Enter')
}

/** 流式结束：composer 不再处于 busy 态 */
export async function waitForStreamComplete(page: Page, timeout = 120_000) {
  await expect(page.locator('.composer-box--busy')).toBeHidden({ timeout })
}

/** 当前 assistant 消息内的 OperationStack */
export function lastOperationStack(page: Page) {
  return page.locator('.assistant-body .operation-lines').last()
}
