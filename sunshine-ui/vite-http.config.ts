import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  // 与 vite.config.ts(5173) 共用默认 node_modules/.vite 会导致预构建缓存互相覆盖、hash 失效
  cacheDir: 'node_modules/.vite-http',
  server: {
    host: '0.0.0.0',
    port: 5174,
    allowedHosts: ['ecs4c16g', 'localhost'],
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8000',
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
      '/health': { target: 'http://127.0.0.1:8000', changeOrigin: true },
      '/v1': { target: 'http://127.0.0.1:8000', changeOrigin: true },
    },
  },
})
