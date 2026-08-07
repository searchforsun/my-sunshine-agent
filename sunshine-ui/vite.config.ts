import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  // GitHub Pages 项目站须设 VITE_BASE_PATH=/repo-name/；本地 dev 默认 /
  const base = process.env.VITE_BASE_PATH?.replace(/\/?$/, '/') || '/'
  // CI mock E2E 走 mock-server :8001；日常 dev 仍代理 Gateway :8000
  const apiProxyTarget = mode === 'e2e-mock' ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:8000'

  // biz Admin CRUD 直连各域服务（路径前缀区分；须排在 /api 通配之前）
  const mockBizProxy = {
    '/api/biz/finance': {
      target: 'http://ecs4c16g:8710',
      changeOrigin: true,
    },
    '/api/biz/oa': {
      target: 'http://ecs4c16g:8700',
      changeOrigin: true,
    },
    '/api/biz/hr': {
      target: 'http://ecs4c16g:8720',
      changeOrigin: true,
    },
  }

  const gatewayProxy = {
    ...mockBizProxy,
    '/api': {
      target: apiProxyTarget,
      changeOrigin: true,
      configure: (proxy) => {
        proxy.on('proxyRes', (proxyRes) => {
          const ct = proxyRes.headers['content-type']
          if (typeof ct === 'string' && ct.includes('text/event-stream')) {
            proxyRes.headers['cache-control'] = 'no-cache, no-transform'
            proxyRes.headers['x-accel-buffering'] = 'no'
          }
        })
      },
    },
    '/health': {
      target: 'http://127.0.0.1:8000',
      changeOrigin: true,
    },
    '/v1': {
      target: 'http://127.0.0.1:8000',
      changeOrigin: true,
    },
  }

  return {
    base,
    plugins: [vue()],
    server: {
      host: '0.0.0.0',
      port: 5173,
      // 允许通过 ecs4c16g 主机名访问（Vite 6 默认拦截非 localhost Host）
      allowedHosts: ['ecs4c16g', 'localhost'],
      proxy: gatewayProxy,
    },
    preview: {
      host: '0.0.0.0',
      port: 5173,
      allowedHosts: ['ecs4c16g', 'localhost'],
      proxy: gatewayProxy,
    },
    optimizeDeps: {
      include: ['markdown-it', 'highlight.js', 'markdown-it-highlightjs', 'markdown-it-task-lists', '@mdit/plugin-katex', 'katex'],
    },
    // 单测仅收 src；e2e 由 playwright 负责（test:e2e），避免 vitest 误收集 e2e/*.spec.ts
    test: {
      include: ['src/**/*.{test,spec}.ts'],
      exclude: ['e2e/**', 'node_modules/**'],
    },
  }
})
