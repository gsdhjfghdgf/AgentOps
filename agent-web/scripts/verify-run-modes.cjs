const { app, BrowserWindow, ipcMain } = require('electron')
const fs = require('fs'), path = require('path'), assert = require('node:assert/strict')
const root = path.resolve(__dirname, '..'), runtime = path.resolve(root, '../agent-server/.runtime')
const state = JSON.parse(fs.readFileSync(path.join(runtime, 'modes-validation.json'), 'utf8'))
const label = process.argv.includes('--source') ? 'source' : 'packaged'
const nonce = require('node:crypto').randomUUID().replace(/[^a-z]/g, '').toUpperCase()
const headers = { 'X-Tenant-Id': state.tenant, 'X-User-Id': state.user, 'Content-Type': 'application/json' }
const delay = ms => new Promise(resolve => setTimeout(resolve, ms)), active = []
app.setPath('userData', path.join(runtime, 'modes-ui-' + label))
app.disableHardwareAcceleration(); ipcMain.on('agentops:notify', () => {})
async function api(p, method = 'GET', value) {
  const r = await fetch(state.base + p, { method, headers, ...(value === undefined ? {} : { body: JSON.stringify(value) }) })
  const text = await r.text(); assert.ok(r.ok, p + ':' + text.slice(0, 250)); return text ? JSON.parse(text) : null
}
app.whenReady().then(async () => {
  let win
  try {
    const archive = label === 'source' ? root : path.join(root, 'release/win-unpacked/resources/app.asar')
    win = new BrowserWindow({ show: false, width: 1400, height: 1000,
      webPreferences: { preload: path.join(archive, 'electron/preload.cjs'), offscreen: true, backgroundThrottling: false } })
    win.webContents.session.webRequest.onBeforeSendHeaders({ urls: ['http://127.0.0.1:8080/api/*'] },
      (r, cb) => cb({ requestHeaders: { ...r.requestHeaders, ...headers } }))
    const js = code => win.webContents.executeJavaScript('(async()=>{' + code + '})()')
    async function wait(code, timeout = 30000) {
      const end = Date.now() + timeout
      while (Date.now() < end) { if (await js('return Boolean(' + code + ')')) return; await delay(100) }
      throw Error('UI timeout: ' + code)
    }
    const selector = id => '[data-session-id="' + id + '"]'
    async function chooseMode(name) {
      await wait('!document.querySelector(".mode-toggle")?.disabled')
      await js('document.querySelector(".mode-toggle").click()')
      await wait('!!document.querySelector(".mode-menu")')
      await js('Array.from(document.querySelectorAll(".mode-menu button")).find(b=>b.textContent.includes(' + JSON.stringify(name) + ')).click()')
      await wait('document.querySelector(".mode-toggle").textContent.includes(' + JSON.stringify(name) + ')&&!document.querySelector(".mode-menu")')
    }
    async function screenshot(name) {
      await delay(100); fs.writeFileSync(path.join(runtime, 'modes-' + label + '-' + name + '.png'), (await win.webContents.capturePage()).toPNG())
    }
    await win.loadFile(path.join(archive, 'dist/index.html'))
    await wait('document.querySelector(' + JSON.stringify(selector(state.planSessionId)) + ')')
    await js('localStorage.setItem("agent.tenantId",' + JSON.stringify(state.tenant) + ');localStorage.setItem("agent.userId",' + JSON.stringify(state.user) + ')')
    await win.webContents.reload()
    await wait('document.querySelector(' + JSON.stringify(selector(state.planSessionId)) + ')')
    await js('document.querySelector(' + JSON.stringify(selector(state.planSessionId)) + ').click()')
    await wait('document.querySelector(".plan-execute")&&!document.querySelector(".plan-execute").disabled')
    assert.ok(await js('return document.querySelector(".composer-box").contains(document.querySelector(".mode-picker"))'))
    assert.ok(await js('const a=document.querySelector(".composer-box").getBoundingClientRect(),b=document.querySelector(".mode-toggle").getBoundingClientRect();return b.left>=a.left&&b.bottom<=a.bottom&&b.width>60'))
    assert.ok(await js('return !!document.querySelector(' + JSON.stringify(selector(state.waitSessionId) + ' [data-run-state="waiting_user"]') + ')'))
    assert.ok(await js('return !!document.querySelector(' + JSON.stringify(selector(state.approvalSessionId) + ' [data-run-state="waiting_approval"]') + ')'))
    await chooseMode('Chat')
    await js('document.querySelector(".mode-toggle").click()')
    await screenshot('picker')
    await js('document.querySelector(".mode-picker").dispatchEvent(new KeyboardEvent("keydown",{key:"Escape",bubbles:true}))')
    await wait('!document.querySelector(".mode-menu")')
    await js('document.querySelector(".plan-execute").click()')
    await wait('document.querySelector(".plan-handoff")&&document.querySelector(".mode-toggle").textContent.includes("Build")')
    assert.ok(await js('return document.querySelector(".composer-input").getBoundingClientRect().width>500'))
    await screenshot('handoff')
    const goal = 'MODE_BUILD_' + label.toUpperCase() + '_' + nonce
    await js('const input=document.querySelector(".composer-input");input.value=' + JSON.stringify(goal) + ';input.dispatchEvent(new Event("input",{bubbles:true}))')
    await js('document.querySelector(".composer .send").click()')
    await wait('document.querySelector(".run-bar .badge")?.textContent.includes("已完成")&&!document.querySelector(".plan-handoff")', 60000)
    const last = (await api('/sessions/' + state.planSessionId + '/runs')).runs[0]
    assert.equal(last.mode, 'build'); assert.equal(last.planRunId, state.planRunId)
    assert.equal(fs.readFileSync(path.join(state.directory, goal.toLowerCase() + '.txt'), 'utf8'), 'MODE_BUILD_CANARY\n')
    const hold = await api('/sessions', 'POST', { title: '运行标识 ' + label, projectId: state.projectId })
    const holdTag = 'MODE_HOLD_' + label.toUpperCase() + '_' + nonce
    const held = await api('/sessions/' + hold.sessionId + '/runs', 'POST', { message: holdTag, mode: 'chat' })
    active.push(held.runId)
    const spinner = selector(hold.sessionId) + ' .session-spinner'
    await wait('!!document.querySelector(' + JSON.stringify(spinner) + ')')
    await js('document.querySelector(' + JSON.stringify(selector(state.waitSessionId)) + ').click()')
    await wait('document.querySelector(".question-card")')
    assert.ok(await js('return !!document.querySelector(' + JSON.stringify(spinner) + ')'))
    await screenshot('running-other-session')
    for (let i = 0; i < 300; i++) {
      if ((await fetch(state.fixture + '/state').then(r => r.json())).gates.includes(holdTag)) break
      if (i === 299) throw Error('held model response never reached fixture')
      await delay(100)
    }
    await fetch(state.fixture + '/release?tag=' + holdTag)
    await wait('!!document.querySelector(' + JSON.stringify(selector(hold.sessionId)) + ')&&!document.querySelector(' + JSON.stringify(spinner) + ')')
    // Starting from an empty project view must preserve the selected mode and send on the first click.
    await js('document.querySelector(".project-name").click()')
    await wait('document.querySelector(".composer-input")&&!document.querySelector(".composer-input").disabled')
    await chooseMode('Plan')
    await js('const input=document.querySelector(".composer-input");input.value="MODE_PLAN_INITIAL_' + label.toUpperCase() + '";input.dispatchEvent(new Event("input",{bubbles:true}))')
    await js('document.querySelector(".composer .send").click()')
    await wait('document.querySelector(".run-bar .badge")?.textContent.includes("已完成")&&document.querySelector(".plan-execute")', 60000)
    assert.ok(await js('return document.querySelector(".mode-toggle").textContent.includes("Plan")'))
    const newSessionId = await js('return document.querySelector(".session-item.on").dataset.sessionId')
    assert.notEqual(newSessionId, state.planSessionId)
    assert.equal((await api('/sessions/' + newSessionId + '/runs')).runs[0].mode, 'plan')
    fs.writeFileSync(path.join(runtime, 'modes-ui-' + label + '.json'), JSON.stringify({
      passed: true, pickerInsideComposer: true, threeModes: true, planHandoffSameSession: true,
      sidebarAcrossSessions: true, waitingStates: true, spinnerClearsOnFinish: true,
      emptyProjectFirstSendPreservesMode: true, buildRunId: last.runId,
    }, null, 2))
    console.log('PASS ' + label + ': composer mode picker, same-session handoff, cross-session spinner, waiting states, first-click Plan submission')
    win.destroy(); app.exit(0)
  } catch (e) {
    console.error(e.stack)
    for (const id of active) try { await api('/runs/' + id + '/cancel', 'POST') } catch {}
    if (win && !win.isDestroyed()) {
      fs.writeFileSync(path.join(runtime, 'modes-ui-' + label + '-failed.png'), (await win.webContents.capturePage()).toPNG())
      win.destroy()
    }
    app.exit(1)
  }
})
