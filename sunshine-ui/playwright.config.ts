import { defineConfig, devices } from '@playwright/test'

/**
 * Sunshine UI E2E — 默认连本地 Vite (:5173)，API 经 proxy 到 BFF/Mock (:8001)。
 * 运行前请启动: node mock-server.mjs  与  npm run dev
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: process.env.CI
    ? [
        {
          command: 'node mock-server.mjs',
          url: 'http://localhost:8001',
          reuseExistingServer: false,
          timeout: 30_000,
        },
        {
          command: 'npm run dev',
          url: 'http://localhost:5173',
          reuseExistingServer: false,
          timeout: 30_000,
        },
      ]
    : undefined,
})
