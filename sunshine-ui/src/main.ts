import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import router from './router'
import App from './App.vue'
import './styles/global.css'
import { theme } from './composables/useTheme'

// 确保主题在挂载前已写入 DOM
void theme.value

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(naive)

app.mount('#app')
