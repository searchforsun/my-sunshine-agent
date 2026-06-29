import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/600.css'
import '@fontsource/inter/700.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import router from './router'
import App from './App.vue'
import './styles/global.css'
import './styles/markdown-content.css'
import { registerGlobalHandlers } from './utils/stream-markdown/globalHandlers'
import { theme } from './composables/useTheme'

registerGlobalHandlers()

// 确保主题在挂载前已写入 DOM
void theme.value

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(naive)

app.mount('#app')
