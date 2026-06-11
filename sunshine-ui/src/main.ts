import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import router from './router'
import App from './App.vue'
import './styles/global.css'
import { theme } from './composables/useTheme'

// 确保主题在挂载前已写入 DOM
void theme.value
import { useChatStore } from './stores/chatStore'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(naive)

// 启动时从后端加载会话列表
void useChatStore().init()

app.mount('#app')
