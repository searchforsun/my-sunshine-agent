import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const gatewayProxy = {
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
  '/health': {
    target: 'http://127.0.0.1:8000',
    changeOrigin: true,
  },
  '/v1': {
    target: 'http://127.0.0.1:8000',
    changeOrigin: true,
  },
}

export default defineConfig({
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
})
