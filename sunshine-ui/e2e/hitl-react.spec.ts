import { test, expect } from '@playwright/test'

/**
 * ReAct 写工具 HITL — 真实后端 E2E
 * 依赖 Gateway :8000 + orchestrator + tool-manager；Vite :5173 已启动
 */
const GW = process.env.GATEWAY_URL ?? 'http://localhost:8000'

async function loginViaApi(page: import('@playwright/test').Page) {
  const user = `e2ehitl${Date.now()}`
  await page.request.post(`${GW}/api/auth/register`, {
    data: { username: user, password: 'password123', nickname: 'e2e' },
  })
  const login = await page.request.post(`${GW}/api/auth/login`, {
    data: { username: user, password: 'password123' },
  })
  const body = await login.json()
  const token = body?.data?.token as string
  expect(token).toBeTruthy()
  await page.addInitScript((t: string) => {
    localStorage.setItem('sunshine-token', t)
  }, token)
}

async function selectReactMode(page: import('@playwright/test').Page) {
  const trigger = page.locator('.mode-selector').first()
  await expect(trigger).toBeVisible({ timeout: 15_000 })
  await trigger.click()
  await page.getByRole('option', { name: '自主推理' }).click()
}

async function fillComposer(page: import('@playwright/test').Page, text: string) {
  const area = page.locator('.composer-input-area')
  const editable = area.locator('[contenteditable="true"]')
  const textarea = area.locator('textarea')
  if (await editable.count()) {
    await editable.first().click()
    await editable.first().fill(text)
    return
  }
  await textarea.first().fill(text)
}

test.describe('ReAct HITL 工具确认 UI', () => {
  test('写工具阻塞时应展示确认框', async ({ page }) => {
    test.setTimeout(180_000)
    await loginViaApi(page)
    await page.goto('/chat')
    await expect(page.getByRole('heading', { name: '有什么可以帮你的？' })).toBeVisible({ timeout: 15_000 })

    await selectReactMode(page)
    await fillComposer(page, '请调用 approve_oa_task 工具审批 OA 待办 taskId=T1001，不要查询其它工具。')
    await page.keyboard.press('Enter')

    const lines = page.locator('.operation-lines').last()
    await expect(lines).toBeVisible({ timeout: 60_000 })

    const hitlPanel = page.locator('.collapsible-confirm').filter({ hasText: '写操作确认' })
    await expect(hitlPanel).toBeVisible({ timeout: 90_000 })
    await expect(hitlPanel.getByRole('button', { name: '确认调用' })).toBeVisible()
    await expect(hitlPanel.getByRole('button', { name: '取消调用' })).toBeVisible()

    await hitlPanel.getByRole('button', { name: '确认调用' }).click()
    await expect(hitlPanel.getByText('写操作确认 · 已确认')).toBeVisible({ timeout: 30_000 })
    await expect(hitlPanel.getByRole('button', { name: '确认调用' })).toBeHidden()
  })
})
