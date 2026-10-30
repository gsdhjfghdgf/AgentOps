import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
import { mkdtempSync, writeFileSync, readFileSync, existsSync } from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'

const runtime = path.resolve('agent-server/.runtime'), base = 'http://127.0.0.1:8080/api'
const tenant = randomUUID(), user = randomUUID()
const headers = { 'X-Tenant-Id': tenant, 'X-User-Id': user, 'Content-Type': 'application/json' }
const delay = ms => new Promise(resolve => setTimeout(resolve, ms)), gates = new Map(), active = []
const plan = '## 实施计划\n\n1. 创建验证文本文件，写入 MODE_BUILD_CANARY。\n2. 检查文件内容并汇报结果。\n\n以上是计划，尚未修改文件；切换 Build 后执行。'
function data(res, payload) { res.write('data: ' + (typeof payload === 'string' ? payload : JSON.stringify(payload)) + '\n\n') }
function call(res, name, args) {
  data(res, { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: randomUUID(), function: { name, arguments: JSON.stringify(args) } }] } }] })
  data(res, { choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }], usage: { prompt_tokens: 80, completion_tokens: 30 } })
  data(res, '[DONE]'); res.end()
}
const requests = []
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://fixture')
    if (url.pathname === '/state') { res.end(JSON.stringify({ gates: [...gates.keys()], requests })); return }
    if (url.pathname === '/release') { gates.get(url.searchParams.get('tag'))?.(); res.end('ok'); return }
    if (url.pathname === '/shutdown') { for (const release of gates.values()) release(); res.end('ok'); server.close(); setTimeout(() => process.exit(0), 100); return }
    let raw = ''; for await (const part of req) raw += part
    const body = JSON.parse(raw), goal = body.messages.findLast(m => m.role === 'user')?.content || ''
    const tag = goal.match(/MODE_[A-Z_]+/)?.[0] || 'GREETING'
    const tools = body.tools?.map(t => t.function.name) || []
    requests.push({ tag, tools, stream: body.stream === true })
    const current = body.messages.slice(body.messages.findLastIndex(m => m.role === 'user'))
    const has = name => current.some(m => m.tool_calls?.some(t => t.function.name === name))
    res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8' })
    if (!body.tools) {
      assert.match(goal, /你好/)
      data(res, { choices: [{ delta: { content: '你好！', reasoning_content: 'PRIVATE_MODE_CANARY' } }] })
      await delay(120)
      data(res, { choices: [{ delta: { content: '我可以和你聊天，也可以帮你查询资料。' } }] })
      data(res, { choices: [{ delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 20, completion_tokens: 18 } })
      data(res, '[DONE]'); res.end(); return
    }
    if (tag.includes('HOLD')) {
      data(res, { choices: [{ delta: { content: '正在查询资料，稍后给出结果。' } }] })
      await new Promise(resolve => { gates.set(tag, resolve); res.once('close', resolve) })
      gates.delete(tag)
      if (!res.destroyed) call(res, 'finish_task', { answer: '查询已完成。' })
      return
    }
    if (tag.includes('WAIT_USER')) { call(res, 'ask_user', { question: '需要哪个时间范围？', options: ['本月', '上月'] }); return }
    if (tag.includes('WAIT_APPROVAL')) { assert.ok(tools.includes('web_search')); call(res, 'web_search', { query: 'site:spring.io Spring Boot' }); return }
    if (tag.includes('CHAT_QUERY')) {
      assert.ok(tools.includes('calculator') && tools.includes('web_search') && tools.includes('web_read'))
      assert.ok(!tools.includes('plan_update') && !tools.includes('command_run'))
      if (!has('calculator')) { call(res, 'calculator', { expression: '(19+23)*2' }); return }
      assert.ok(body.messages.some(m => m.role === 'tool' && m.content.includes('84')))
      call(res, 'finish_task', { answer: '计算结果是 **84**。' }); return
    }
    if (tag.includes('PLAN_BLOCK')) {
      assert.ok(!tools.includes('project_write'))
      if (!has('project_write')) { call(res, 'project_write', { path: 'forbidden.txt', content: 'MUST_NOT_WRITE', expectedSha256: '' }); return }
      call(res, 'finish_task', { answer: 'Plan 模式未执行文件写入，继续保留原文件。' }); return
    }
    if (tag.includes('PLAN')) {
      assert.ok(tools.includes('plan_update') && !tools.includes('project_write') && !tools.includes('command_run'))
      if (!has('plan_update')) { call(res, 'plan_update', { revision: '0', stepsJson: JSON.stringify([
        { id: 'create', title: '创建验证文本文件', status: 'pending' }, { id: 'verify', title: '检查文件内容', status: 'pending' },
      ]) }); return }
      call(res, 'finish_task', { answer: plan }); return
    }
    if (tag.includes('BUILD')) {
      assert.ok(tools.includes('project_write'))
      assert.ok(body.messages.some(m => m.role === 'system' && m.content.includes(plan)), 'original approved plan must be pinned in system context')
      const filename = tag.toLowerCase() + '.txt'
      if (!has('project_write')) { call(res, 'project_write', { path: filename, content: 'MODE_BUILD_CANARY\n', expectedSha256: '' }); return }
      call(res, 'finish_task', { answer: '已按照计划创建验证文本文件，内容为 MODE_BUILD_CANARY。' }); return
    }
    throw Error('unhandled fixture tag ' + tag)
  } catch (e) { console.error(e.message); res.destroy(e) }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const fixture = 'http://127.0.0.1:' + server.address().port
async function api(p, method = 'GET', value, expected) {
  const res = await fetch(base + p, { method, headers, ...(value === undefined ? {} : { body: JSON.stringify(value) }) })
  const text = await res.text(); assert.ok(expected ? res.status === expected : res.ok, p + ':' + text.slice(0, 250))
  return text ? JSON.parse(text) : null
}
async function until(check, description, timeout = 60000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { const value = await check(); if (value) return value; await delay(100) }
  throw Error('timeout: ' + description)
}
async function session(title, projectId) { return api('/sessions', 'POST', { title, projectId }) }
async function run(s, message, mode, planRunId) {
  const r = await api('/sessions/' + s.sessionId + '/runs', 'POST', { message, mode, ...(planRunId ? { planRunId } : {}) })
  active.push(r.runId); return r.runId
}
async function finished(id) {
  return until(async () => {
    const r = await api('/runs/' + id)
    assert.ok(!['failed', 'waiting_user', 'waiting_approval', 'budget_exceeded', 'timed_out'].includes(r.status), JSON.stringify(r))
    return r.status === 'succeeded' && r
  }, 'task ' + id)
}
try {
  await api('/memory-settings', 'PUT', { enabled: false })
  await api('/model-settings', 'PUT', { url: fixture + '/v1', model: 'mode-fixture', apiKey: '', clearKey: true })
  const directory = mkdtempSync(path.join(runtime, 'mode-project-'))
  const project = await api('/projects', 'POST', { directory })
  const helloSession = await session('轻量聊天验证'), hello = await run(helloSession, '你好', 'chat')
  const greeting = await finished(hello)
  assert.equal(greeting.mode, 'chat')
  const chatSession = await session('Chat 查询验证'), query = await run(chatSession, 'MODE_CHAT_QUERY', 'chat')
  await finished(query)
  const planSession = await session('Plan 与 Build 验证', project.id), planRun = await run(planSession, 'MODE_PLAN', 'plan')
  assert.equal((await finished(planRun)).mode, 'plan')
  assert.deepEqual((await api('/operations/runs/' + planRun + '/plan')).steps.map(s => s.status), ['pending', 'pending'])
  assert.ok(!existsSync(path.join(directory, 'mode_build_api.txt')))
  const build = await run(planSession, 'MODE_BUILD_API', 'build', planRun)
  assert.equal((await finished(build)).planRunId, planRun)
  assert.equal(readFileSync(path.join(directory, 'mode_build_api.txt'), 'utf8'), 'MODE_BUILD_CANARY\n')
  const blockedSession = await session('Plan 写入拦截验证', project.id), blocked = await run(blockedSession, 'MODE_PLAN_BLOCK', 'plan')
  await until(async () => ['succeeded','failed'].includes((await api('/runs/' + blocked)).status), 'mode policy interception')
  assert.ok(!existsSync(path.join(directory, 'forbidden.txt')))
  const other = await session('其他会话'), invalid = await api('/sessions/' + other.sessionId + '/runs', 'POST',
    { message: 'MODE_BUILD_FOREIGN', mode: 'build', planRunId: planRun }, 404)
  assert.ok(invalid)
  const waitSession = await session('等待补充标识验证'), waiting = await run(waitSession, 'MODE_WAIT_USER', 'chat')
  await until(async () => (await api('/runs/' + waiting)).status === 'waiting_user', 'waiting input')
  const approvalSession = await session('等待授权标识验证'), approving = await run(approvalSession, 'MODE_WAIT_APPROVAL', 'chat')
  await until(async () => (await api('/runs/' + approving)).status === 'waiting_approval', 'waiting approval')
  const list = (await api('/sessions?limit=100')).sessions
  assert.equal(list.find(s => s.sessionId === waitSession.sessionId).activeRunStatus, 'waiting_user')
  assert.equal(list.find(s => s.sessionId === approvalSession.sessionId).activeRunStatus, 'waiting_approval')
  assert.equal(list.find(s => s.sessionId === chatSession.sessionId).activeRunStatus, '')
  writeFileSync(path.join(runtime, 'modes-validation.json'), JSON.stringify({
    passed: true, base, fixture, tenant, user, directory, projectId: project.id,
    planSessionId: planSession.sessionId, planRunId: planRun, waitSessionId: waitSession.sessionId,
    approvalSessionId: approvalSession.sessionId, greetingTokenUsed: greeting.tokenUsed,
    checks: ['chat direct greeting', 'chat lightweight tools', 'plan stays pending', 'same-session build handoff',
      'real project write only in build', 'plan unsolicited writes blocked', 'foreign plan denied', 'sidebar waiting states'],
  }, null, 2))
  console.log('PASS run modes API + policy + same-session plan handoff. READY desktop fixture ' + fixture)
  setTimeout(() => process.exit(2), 30 * 60 * 1000).unref()
} catch (e) {
  console.error(e.stack)
  for (const id of active) try { await api('/runs/' + id + '/cancel', 'POST') } catch {}
  for (const release of gates.values()) release()
  server.close(); process.exit(1)
}
