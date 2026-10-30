import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

const base = process.env.AGENT_API_BASE || 'http://127.0.0.1:8080'
const tenant = randomUUID(), user = randomUUID(), otherUser = randomUUID()
let checks = 0
const terminal = ['succeeded', 'failed', 'cancelled', 'timed_out', 'budget_exceeded']
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
async function request(path, { method = 'GET', body, expected = 200, as = user, extra = {} } = {}) {
  const response = await fetch(base + path, {
    method, headers: { 'X-Tenant-Id': tenant, 'X-User-Id': as, 'Content-Type': 'application/json', ...extra },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(60000),
  })
  const text = await response.text()
  assert.equal(response.status, expected, method + ' ' + path + ': ' + text.slice(0, 400))
  if (!text) return null
  try { return JSON.parse(text) } catch { return text }
}
function pass(name) { checks++; console.log('PASS ' + name) }
async function session() {
  return (await request('/api/sessions', { method: 'POST', body: { title: '[回归] ' + new Date().toISOString() }, expected: 201 })).sessionId
}
async function start(sessionId, message, extra = {}) {
  return request('/api/sessions/' + sessionId + '/runs', { method: 'POST', body: { message, ...extra }, expected: 201 })
}
async function wait(runId, target = 'succeeded') {
  for (let i = 0; i < 100; i++) {
    const run = await request('/api/runs/' + runId)
    if (run.status === target) return run
    assert.ok(!terminal.includes(run.status), 'unexpected terminal: ' + JSON.stringify(run))
    await delay(200)
  }
  throw new Error('run did not reach ' + target + ': ' + runId)
}
async function main() {
  let healthy = false
  for (let i = 0; i < 30; i++) {
    try {
      const response = await fetch(base + '/actuator/health', { signal: AbortSignal.timeout(3000) })
      healthy = response.ok && (await response.json()).status === 'UP'
      if (healthy) break
    } catch {}
    await delay(500)
  }
  assert.ok(healthy, 'backend health must be UP')
  pass('PostgreSQL / Redis / backend health')

  const sid = await session()
  const first = await start(sid, '你好', { requestKey: 'direct-1' })
  await wait(first.runId)
  pass('create session and complete direct answer')

  const history = await request('/api/sessions/' + sid + '/messages?limit=50')
  assert.ok(history.messages.length >= 2)
  assert.deepEqual(history.messages.map(m => m.seq), [...history.messages.map(m => m.seq)].sort((a,b) => a-b))
  assert.equal(new Set(history.messages.map(m => m.seq)).size, history.messages.length)
  pass('chronological message history')

  const hit = await request('/api/sessions/' + sid + '/runs', {
    method: 'POST', body: { message: '你好', requestKey: 'direct-1' },
  })
  assert.equal(hit.runId, first.runId)
  assert.equal(hit.idempotentHit, true)
  assert.equal(hit.status, 'succeeded')
  await request('/api/sessions/' + sid + '/runs', {
    method: 'POST', expected: 409, body: { message: '你好', requestKey: 'direct-1', evidenceRequired: true },
  })
  pass('full-contract idempotency and actual status')

  const second = await start(sid, '你好')
  assert.notEqual(second.runId, first.runId)
  await wait(second.runId)
  pass('intentional repeated text creates a new task')

  const page = await request('/api/sessions/' + sid + '/messages?limit=1')
  assert.equal(page.hasMore, true)
  const older = await request('/api/sessions/' + sid + '/messages?limit=1&beforeSeq=' + page.nextBeforeSeq)
  assert.ok(older.messages[0].seq < page.messages[0].seq)
  pass('history cursor pagination')

  const clarifySession = await session()
  const clarify = await start(clarifySession, '帮我查')
  const question = await wait(clarify.runId, 'waiting_user')
  await request('/api/sessions/' + clarifySession + '/runs/' + clarify.runId + '/supplement', {
    method: 'POST', expected: 409, body: { questionId: randomUUID(), answer: '查询本月销售数据' },
  })
  await request('/api/sessions/' + clarifySession + '/runs/' + clarify.runId + '/supplement', {
    method: 'POST', body: { questionId: question.questionId, answer: '查询本月销售数据' },
  })
  await wait(clarify.runId)
  pass('stale question rejection and successful supplement')

  const cancelSession = await session()
  const cancelling = await start(cancelSession, '帮我查')
  await wait(cancelling.runId, 'waiting_user')
  await request('/api/runs/' + cancelling.runId + '/cancel', { method: 'POST' })
  await wait(cancelling.runId, 'cancelled')
  const afterCancel = await start(cancelSession, '你好')
  await wait(afterCancel.runId)
  pass('cancel waiting task and reuse its session')

  const approvalSession = await session()
  const approvalRun = await start(approvalSession, '发送邮件通知')
  await wait(approvalRun.runId, 'waiting_approval')
  const pending = await request('/api/runs/' + approvalRun.runId + '/approvals')
  assert.equal(pending.approvals.length, 1)
  const approvalId = pending.approvals[0].approvalId
  await request('/api/runs/' + approvalRun.runId + '/approvals', { as: otherUser, expected: 404 })
  await request('/api/approvals/' + approvalId, { as: otherUser, expected: 404 })
  await request('/api/approvals/' + approvalId + '/decision', {
    as: otherUser, method: 'POST', expected: 409, body: { decision: 'approved' },
  })
  await request('/api/approvals/' + approvalId + '/decision', { method: 'POST', body: { decision: 'approved' } })
  await wait(approvalRun.runId)
  pass('approval ownership and resume (simulated mail tool)')

  const reportSession = await session()
  const report = await start(reportSession, '生成上周销售周报')
  await wait(report.runId)
  const files = await request('/api/artifacts/by-run/' + report.runId)
  assert.ok(files.artifacts.length > 0)
  const artifactId = files.artifacts[0].objectId
  const download = await request('/api/artifacts/' + artifactId + '/download')
  assert.ok(download.length > 0)
  await request('/api/artifacts/by-run/' + report.runId, { as: otherUser, expected: 404 })
  await request('/api/artifacts/' + artifactId + '/download', { as: otherUser, expected: 404 })
  pass('report generation, artifact recovery, download and ownership')

  const runList = await request('/api/sessions/' + reportSession + '/runs')
  assert.equal(runList.runs[0].runId, report.runId)
  await request('/api/sessions/' + reportSession + '/runs', { as: otherUser, expected: 404 })
  pass('session run recovery API and ownership')

  const stream = await request('/api/runs/' + report.runId + '/events')
  const records = stream.split(/\r?\n\r?\n/).filter(Boolean)
  const sequences = records.filter(r => !r.includes('event:run.snapshot')).map(r => Number(/^id:(\d+)/m.exec(r)?.[1]))
  assert.ok(sequences.length > 0)
  assert.equal(new Set(sequences).size, sequences.length)
  assert.deepEqual(sequences, [...sequences].sort((a,b) => a-b))
  assert.ok(stream.includes('event:message.delta'))
  const last = sequences.at(-1)
  const resumed = await request('/api/runs/' + report.runId + '/events?lastEventSeq=0', { extra: { 'Last-Event-ID': String(last) } })
  assert.ok(resumed.includes('event:run.snapshot'))
  assert.ok(!resumed.includes('event:message.delta'))
  pass('SSE ordered replay, deltas, Last-Event-ID and terminal snapshot')

  const raceSession = await session()
  const raceBody = { message: '帮我查', requestKey: 'same-race-key' }
  const responses = await Promise.all([1, 2].map(() => fetch(base + '/api/sessions/' + raceSession + '/runs', {
    method: 'POST', headers: { 'X-Tenant-Id': tenant, 'X-User-Id': user, 'Content-Type': 'application/json' },
    body: JSON.stringify(raceBody),
  })))
  assert.deepEqual(responses.map(r => r.status).sort(), [200, 201])
  const raceResults = await Promise.all(responses.map(r => r.json()))
  assert.equal(raceResults[0].runId, raceResults[1].runId)
  await wait(raceResults[0].runId, 'waiting_user')
  await request('/api/runs/' + raceResults[0].runId + '/cancel', { method: 'POST' })
  pass('concurrent idempotent submissions create exactly one task')

  console.log(JSON.stringify({ passed: checks, isolatedTenant: tenant, base }))
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
