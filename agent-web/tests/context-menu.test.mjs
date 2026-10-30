import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { contextMenuTemplate } = createRequire(import.meta.url)('../electron/context-menu.cjs')
const dependencies = () => { const copied = [], opened = []; return { copied, opened, clipboard: { writeText: text => copied.push(text) }, shell: { openExternal: async url => { opened.push(url) } } } }

test('selected answer text offers native copy without edit commands', () => {
  const menu = contextMenuTemplate({ selectionText: '选中的回答', isEditable: false, editFlags: { canCopy: true, canSelectAll: true } }, dependencies())
  assert.equal(menu.find(item => item.role === 'copy').enabled, true)
  assert.ok(menu.some(item => item.role === 'selectAll'))
  assert.ok(!menu.some(item => ['cut', 'paste', 'undo'].includes(item.role)))
})
test('editable fields respect native edit flags including protected selections', () => {
  const menu = contextMenuTemplate({ isEditable: true, selectionText: '', editFlags: { canUndo: true, canPaste: true, canSelectAll: true, canCut: false, canCopy: false } }, dependencies())
  for (const role of ['undo', 'paste', 'selectAll']) assert.equal(menu.find(item => item.role === role).enabled, true)
  for (const role of ['cut', 'copy', 'redo']) assert.equal(menu.find(item => item.role === role).enabled, false)
})
test('link commands act only after selection and preserve the exact address', async () => {
  const state = dependencies(), url = 'https://github.com/spring-projects/spring-boot/issues/1?x=1#comment'
  const menu = contextMenuTemplate({ linkURL: url, editFlags: {} }, state)
  assert.deepEqual(state.copied, []); assert.deepEqual(state.opened, [])
  menu.find(item => item.label === '复制链接地址').click()
  await menu.find(item => item.label === '在浏览器中打开链接').click()
  assert.deepEqual(state.copied, [url]); assert.deepEqual(state.opened, [url])
})
test('executable and file links do not become external menu actions', () => {
  for (const linkURL of ['javascript:alert(1)', 'file:///C:/secret', 'data:text/html,x', 'https://user:secret@example.com/']) {
    assert.equal(contextMenuTemplate({ linkURL, editFlags: {} }, dependencies()).length, 0)
  }
  assert.equal(contextMenuTemplate({ linkURL: '', editFlags: {} }, dependencies()).length, 0)
})
