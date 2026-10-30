function webLink(value) {
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
  } catch { return false }
}

function contextMenuTemplate(params, { clipboard, shell }) {
  const items = [], flags = params.editFlags || {}
  const edit = (role, label, flag, accelerator) => ({ role, label, enabled: !!flags[flag], accelerator })
  if (params.isEditable) {
    items.push(edit('undo', '撤销', 'canUndo', 'CommandOrControl+Z'),
      edit('redo', '重做', 'canRedo', 'CommandOrControl+Y'), { type: 'separator' },
      edit('cut', '剪切', 'canCut', 'CommandOrControl+X'),
      edit('copy', '复制', 'canCopy', 'CommandOrControl+C'),
      edit('paste', '粘贴', 'canPaste', 'CommandOrControl+V'))
  } else if (params.selectionText?.length) {
    items.push(edit('copy', '复制', 'canCopy', 'CommandOrControl+C'))
  }
  if (webLink(params.linkURL)) {
    if (items.length) items.push({ type: 'separator' })
    items.push({ label: '复制链接地址', click: () => clipboard.writeText(params.linkURL) },
      { label: '在浏览器中打开链接', click: () => { void shell.openExternal(params.linkURL).catch(() => {}) } })
  }
  if (flags.canSelectAll || params.isEditable) {
    if (items.length) items.push({ type: 'separator' })
    items.push(edit('selectAll', '全选', 'canSelectAll', 'CommandOrControl+A'))
  }
  return items
}

function installContextMenu(window) {
  const { Menu, clipboard, shell } = require('electron')
  window.webContents.on('context-menu', (_event, params) => {
    if (window.isDestroyed()) return
    const template = contextMenuTemplate(params, { clipboard, shell })
    if (!template.length) return
    const options = { window }
    if (params.x >= 0 && params.y >= 0) Object.assign(options, { x: params.x, y: params.y })
    Menu.buildFromTemplate(template).popup(options)
  })
}

module.exports = { contextMenuTemplate, installContextMenu }
