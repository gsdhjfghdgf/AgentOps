import { createApp } from 'vue'
import App from './App.vue'
import './style.css'

// 挂载前应用主题，避免首帧闪烁；首次打开默认浅色。
const initialTheme = localStorage.getItem('agentops-theme') === 'dark' ? 'dark' : 'light'
document.documentElement.dataset.theme = initialTheme
// 同步窗口底色（浏览器环境下 desktopEnv 不存在，安全忽略）
window.desktopEnv?.setWindowTheme?.(initialTheme as 'dark' | 'light')

createApp(App).mount('#app')
