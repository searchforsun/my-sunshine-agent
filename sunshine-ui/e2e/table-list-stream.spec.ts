import { test, expect } from '@playwright/test'

const FULL = `| 服务 | 端口 |
|------|------|
| BFF | 8001 |
| Orch | 8200 |

- 第一项
- 第二项
- 第三项
`

test.describe('表格流式后列表不重复', () => {
  test('流式过程中列表块最多一个', async ({ page }) => {
    await page.goto('/chat')

    const stages = [
      '| 服务 | 端口 |\n|------|------|\n',
      '| 服务 | 端口 |\n|------|------|\n| BFF | 8001 |\n',
      '| 服务 | 端口 |\n|------|------|\n| BFF | 8001 |\n| Orch | 8200 |\n\n',
      '| 服务 | 端口 |\n|------|------|\n| BFF | 8001 |\n| Orch | 8200 |\n\n- 第一项\n',
      '| 服务 | 端口 |\n|------|------|\n| BFF | 8001 |\n| Orch | 8200 |\n\n- 第一项\n- 第二项\n',
      FULL,
    ]

    for (const content of stages) {
      const stats = await page.evaluate(async (text) => {
        const { runStreamMarkdownTest } = await import(
          '/src/utils/stream-markdown/streamTestHarness.ts'
        )
        return runStreamMarkdownTest(text)
      }, content)

      expect(stats.listBlocks, `stage len=${content.length}`).toBeLessThanOrEqual(1)
      if (content.includes('- 第一项')) {
        expect(stats.liCount).toBeGreaterThanOrEqual(1)
      }
      if (content === FULL) {
        expect(stats.tables).toBe(1)
        expect(stats.liCount).toBe(3)
      }
    }

    const liveDup = await page.evaluate(async (fullText) => {
      const { runStreamMarkdownIncrementalTest } = await import(
        '/src/utils/stream-markdown/streamTestHarness.ts'
      )
      return runStreamMarkdownIncrementalTest(fullText)
    }, FULL)

    expect(liveDup.maxListBlocks).toBeLessThanOrEqual(1)
  })
})
