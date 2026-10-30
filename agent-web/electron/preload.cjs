// preload：仅暴露无害的只读信息，保持 contextIsolation
const { contextBridge, ipcRenderer } = require('electron')
// Available before the first renderer module runs, including a custom backend URL.
const baseArgument = process.argv.find(value => value.startsWith('--agent-api-base='))
contextBridge.exposeInMainWorld('__AGENT_DESKTOP__', {
  apiBase: baseArgument ? baseArgument.slice('--agent-api-base='.length) : 'http://127.0.0.1:8080',
})

contextBridge.exposeInMainWorld('desktopEnv', {
  platform: process.platform,
  versions: {
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
  },
  // 主题切换时同步 BrowserWindow 底色（避免白屏/黑屏闪跳）
  setWindowTheme: (theme) => ipcRenderer.send('agentops:theme', theme),
  selectDirectory: () => ipcRenderer.invoke('agentops:select-project'),
  notify: (title,body) => ipcRenderer.send('agentops:notify',title,body),
})
