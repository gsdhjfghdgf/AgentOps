/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}

interface Window {
  /** Electron 主进程注入的桌面信息 */
  desktopEnv?: {
    platform: string
    versions: { electron: string; chrome: string; node: string }
    /** 同步 BrowserWindow 底色，跟随渲染层主题 */
    setWindowTheme?: (theme: 'dark' | 'light') => void
    selectDirectory?: () => Promise<string | null>
    notify?: (title:string,body:string) => void
  }
  /** main.cjs did-finish-load 时注入的 API 地址 */
  __AGENT_DESKTOP__?: { apiBase: string }
}
