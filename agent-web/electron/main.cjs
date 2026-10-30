// Electron 主进程：加载渲染层（开发=Vite dev server，生产=dist/index.html）
const { app, BrowserWindow, shell, ipcMain, dialog, Notification } = require('electron')
const path = require('path')
const { installContextMenu } = require('./context-menu.cjs')
ipcMain.on('agentops:notify',(event,title,body)=>{
  if(!BrowserWindow.fromWebContents(event.sender)||!Notification.isSupported())return
  const n=new Notification({title:String(title).slice(0,120),body:String(body).slice(0,300)})
  n.on('click',()=>{const win=BrowserWindow.fromWebContents(event.sender);if(win){if(win.isMinimized())win.restore();win.show();win.focus()}})
  n.show()
})

// 渲染层主题切换时同步窗口底色（深色 #0e1116 / 浅色 #f3f1ea）
const THEME_BG = { dark: '#191c1a', light: '#fafbf9' }
ipcMain.on('agentops:theme', (event, theme) => {
  const win = BrowserWindow.fromWebContents(event.sender)
  if (win && THEME_BG[theme]) win.setBackgroundColor(THEME_BG[theme])
})

const DEV_URL = process.env.VITE_DEV_SERVER
// 桌面端直连本地后端；渲染层通过 preload 暴露的 API_BASE 访问
const API_BASE = process.env.AGENT_API_BASE || 'http://127.0.0.1:8080'
ipcMain.handle('agentops:select-project',async event=>{
  const result=await dialog.showOpenDialog(BrowserWindow.fromWebContents(event.sender),{title:'选择Agent可以读写的本地项目',properties:['openDirectory']})
  return result.canceled?null:result.filePaths[0]
})

function createWindow() {
  const win = new BrowserWindow({
    width: 1320,
    height: 860,
    minWidth: 1000,
    minHeight: 640,
    title: 'AgentOps 智能助手',
    backgroundColor: '#fafbf9',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      additionalArguments: ['--agent-api-base=' + API_BASE],
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: false,
    },
  })

  installContextMenu(win)


  if (DEV_URL) {
    win.loadURL(DEV_URL)
    win.webContents.openDevTools({ mode: 'detach' })
  } else {
    win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  }

  // 外部链接交给系统浏览器，应用内一律禁止新开窗口
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate',(event,url)=>{
    const current=win.webContents.getURL().split('#')[0]
    if(url.split('#')[0]!==current && !(DEV_URL && url.startsWith(DEV_URL)))event.preventDefault()
  })
}

app.whenReady().then(() => {
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
