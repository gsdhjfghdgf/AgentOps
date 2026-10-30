const { app, BrowserWindow, Menu, clipboard } = require('electron')
const fs = require('fs'), path = require('path'), assert = require('assert/strict')
const root = path.resolve(__dirname, '..'), runtime = path.resolve(root, '../agent-server/.runtime')
const label = process.argv.includes('--source') ? 'source' : 'packaged'
const archive = label === 'source' ? root : path.join(root, 'release/win-unpacked/resources/app.asar')
app.setPath('userData', path.join(runtime, 'context-menu-ui-' + label)); app.disableHardwareAcceleration()
const popup = Menu.prototype.popup
let menu, popupOptions, nativePopup = false
Menu.prototype.popup = function (options) { menu = this; popupOptions = options; if (nativePopup) return popup.call(this, options) }
require(path.join(archive, 'electron/main.cjs'))
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))

app.whenReady().then(async () => {
  let win, backup, changed = false
  const expected = new Set(), initial = 'AGENTOPS_CONTEXT_INITIAL'
  try {
    win = BrowserWindow.getAllWindows()[0]; assert.ok(win, 'Production main process must create the window')
    for (let i = 0; i < 200 && (win.webContents.isLoading() || !win.webContents.getURL()); i++) await delay(50)
    const js = code => win.webContents.executeJavaScript('(async()=>{' + code + '})()')
    await js(`const box=document.createElement('section');box.id='clipboard-fixture';box.style='position:fixed;inset:90px 30px 30px 300px;z-index:9999;padding:30px;background:white;color:black;font:16px sans-serif';box.innerHTML='<p id="copy-answer">AGENTOPS_答案复制测试</p><table id="copy-table"><tr><td>Issue编号</td><td>标题</td></tr><tr><td>#123</td><td>测试内容</td></tr></table><p><a id="copy-link" href="https://github.com/spring-projects/spring-boot/issues/123">Issue 原文</a></p><p><textarea id="copy-editor" style="width:500px;height:60px"></textarea></p><input id="copy-readonly" readonly value="READONLY_CANARY"><p><input id="copy-password" type="password" value="PASSWORD_CANARY"></p>';document.body.append(box)`)
    backup = { text: clipboard.readText(), html: clipboard.readHTML(), rtf: clipboard.readRTF() }
    const image = clipboard.readImage(); if (!image.isEmpty()) backup.image = image
    clipboard.writeText(initial); changed = true; expected.add(initial)
    assert.ok(clipboard.readText() === initial, 'Cannot safely set the clipboard test marker')
    win.show(); win.focus(); win.webContents.focus()
    async function context(selector, select = false) {
      menu = null
      const point = await js(`const el=document.querySelector(${JSON.stringify(selector)});let rect=el.getBoundingClientRect();if(${select}){if(el.setSelectionRange){el.focus();el.select()}else{const range=document.createRange();range.selectNodeContents(el);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);rect=range.getClientRects()[0]||rect}}else{getSelection().removeAllRanges();el.focus()}return{x:Math.round(rect.x+10),y:Math.round(rect.y+Math.min(rect.height/2,12))}`)
      const delivered = new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(Error('Native context-menu event was not delivered')), 3000)
        win.webContents.once('context-menu', (_event, params) => { clearTimeout(timer); resolve(params) })
      })
      win.webContents.sendInputEvent({ type: 'mouseDown', button: 'right', clickCount: 1, ...point })
      win.webContents.sendInputEvent({ type: 'mouseUp', button: 'right', clickCount: 1, ...point })
      const params = await delivered
      assert.ok(menu && popupOptions.window === win, 'Native right click must invoke the production menu')
      return params
    }
    function activate(roleOrLabel) {
      const item = menu.items.find(item => item.role?.toLowerCase() === roleOrLabel.toLowerCase() || item.label === roleOrLabel)
      assert.ok(item?.enabled, 'Required menu item is not enabled: ' + roleOrLabel)
      // MenuItem dispatches native roles to this production window and its webContents.
      item.click({}, win, win.webContents)
    }
    let params = await context('#copy-answer', true)
    assert.ok(params.selectionText.includes('答案复制测试'), 'Native context must retain the selected answer')
    expected.add('AGENTOPS_答案复制测试'); activate('copy'); await delay(50)
    assert.ok(clipboard.readText() === 'AGENTOPS_答案复制测试', 'Native copy did not copy the selected answer')

    params = await context('#copy-table', true); activate('copy'); await delay(50)
    const tableText = clipboard.readText()
    assert.ok(tableText.includes('#123') && tableText.includes('标题'), 'Table selection must copy all selected cells')
    expected.add(tableText)

    await context('#copy-link')
    const link = 'https://github.com/spring-projects/spring-boot/issues/123'; expected.add(link)
    activate('复制链接地址'); assert.ok(clipboard.readText() === link, 'Link command must copy its actual address')

    const pasteText = 'AGENTOPS_PASTE 中文测试'; expected.add(pasteText); clipboard.writeText(pasteText)
    await context('#copy-editor'); activate('paste'); await delay(50)
    assert.ok(await js('return document.querySelector("#copy-editor").value===' + JSON.stringify(pasteText)), 'Native paste must update the editable text')
    await context('#copy-editor', true); activate('cut'); await delay(50)
    assert.ok(await js('return document.querySelector("#copy-editor").value===""'), 'Native cut must remove the selected input')
    assert.ok(clipboard.readText() === pasteText, 'Native cut must place the input on the clipboard')
    await context('#copy-editor'); activate('undo'); await delay(50)
    assert.ok(await js('return document.querySelector("#copy-editor").value===' + JSON.stringify(pasteText)), 'Undo must restore the cut input')
    await js('const el=document.querySelector("#copy-editor");el.focus();el.setSelectionRange(el.value.length,el.value.length)')
    await context('#copy-editor'); activate('selectAll'); await delay(50)
    assert.ok(await js('const el=document.querySelector("#copy-editor");return el.selectionStart===0&&el.selectionEnd===el.value.length'), 'Select all must stay within the focused input')

    await context('#copy-readonly', true)
    assert.ok(!menu.items.some(item => ['cut', 'paste'].includes(item.role) && item.enabled), 'Read-only input must not expose enabled write actions')
    await context('#copy-password', true)
    assert.ok(!menu.items.some(item => item.role === 'copy' && item.enabled), 'Password fields must retain native copy protection')

    nativePopup = true; await context('#copy-link'); await delay(80); menu.closePopup(win)
    fs.writeFileSync(path.join(runtime, 'context-menu-' + label + '.json'), JSON.stringify({ passed: true, nativeEvent: true, selectedAnswer: true, tableCopy: true, linkCopy: true, paste: true, cut: true, undo: true, inputSelectAll: true, protectedFields: true, nativePopup: true }, null, 2))
    console.log('PASS ' + label + ': production native context menu, real text/table/link copy, input paste/cut/undo/select-all and protected fields')
  } catch (error) { console.error(error.stack || error); process.exitCode = 1 }
  finally {
    Menu.prototype.popup = popup
    // Keep the prior clipboard in memory only. Do not overwrite a new user copy made during the check.
    if (changed && expected.has(clipboard.readText())) clipboard.write(backup)
    if (win && !win.isDestroyed()) win.destroy()
    app.exit(process.exitCode || 0)
  }
})
