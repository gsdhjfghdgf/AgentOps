import { randomUUID } from 'node:crypto'
import { mkdtempSync, writeFileSync, readFileSync, existsSync } from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'

const base = 'http://127.0.0.1:8080/api', runtime = path.resolve('agent-server/.runtime')
const headers = { 'X-Tenant-Id': randomUUID(), 'X-User-Id': randomUUID(), 'Content-Type': 'application/json' }
const delay = ms => new Promise(resolve => setTimeout(resolve, ms)), active = [], results = {}
async function api(p, method = 'GET', body) {
  const r = await fetch(base + p, { method, headers, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const text = await r.text(); assert.ok(r.ok, p + ':' + text.slice(0, 300)); return text ? JSON.parse(text) : null
}
async function create(message, mode, projectId, sessionId, planRunId) {
  const id = sessionId || (await api('/sessions', 'POST', { title: '真实 AI 模式验证 ' + mode, projectId })).sessionId
  const task = await api('/sessions/' + id + '/runs', 'POST', { message, mode, ...(planRunId ? { planRunId } : {}) })
  active.push(task.runId); return { id: task.runId, sessionId: id }
}
async function finished(task, allowNetwork = false) {
  const end = Date.now() + 180000, approved = new Set()
  while (Date.now() < end) {
    const run = await api('/runs/' + task.id)
    assert.ok(!['failed','timed_out','budget_exceeded','waiting_reconcile','waiting_user'].includes(run.status), JSON.stringify(run))
    if (run.status === 'waiting_approval') {
      assert.ok(allowNetwork, 'unexpected approval')
      for (const a of (await api('/runs/' + task.id + '/approvals')).approvals) {
        if (approved.has(a.approvalId)) continue
        assert.equal(a.scope, 'task_network'); approved.add(a.approvalId)
        assert.equal(approved.size, 1, 'all reads/searches share task network consent')
        await api('/approvals/' + a.approvalId + '/decision', 'POST', { decision: 'approved' })
      }
    }
    if (run.status === 'succeeded') {
      const messages = (await api('/sessions/' + task.sessionId + '/messages?limit=100')).messages.filter(m => m.runId === task.id)
      return { run, messages, approvals: approved.size }
    }
    await delay(150)
  }
  throw Error('timeout ' + task.id)
}
try {
  await api('/memory-settings', 'PUT', { enabled: false })
  const hello = await create('你好', 'chat'), greeting = await finished(hello)
  assert.ok(greeting.messages.some(m => m.kind === 'candidate' && m.content.length > 0))
  assert.ok(!greeting.messages.some(m => m.role === 'tool' || m.toolCalls?.length))
  assert.ok(greeting.run.tokenUsed > 0 && greeting.run.tokenUsed < 2000)
  results.greeting = { passed: true, tokenUsed: greeting.run.tokenUsed, noQuestionnaire: true }
  console.log('PASS real AI greeting: direct answer, no tools or questionnaire; tokens=' + greeting.run.tokenUsed)
  const question = await create('用两句话解释Java接口是什么，不需要联网或操作项目。', 'chat')
  const answer = await finished(question)
  assert.ok(answer.messages.findLast(m => m.kind === 'candidate')?.content.includes('接口'))
  results.ordinaryQuestion = { passed: true, tokenUsed: answer.run.tokenUsed }
  console.log('PASS real AI Chat answers an ordinary knowledge question directly')
  const lookup = await create('请用web_search搜索 site:spring.io Spring Boot，然后用web_read读取一个有效的官方结果。用两句话介绍Spring Boot并附实际读取的来源链接。最多一次搜索、一次读取；失败就说明检索限制，不要编造。不要制定任务计划。', 'chat')
  const research = await finished(lookup, true)
  const calls = research.messages.flatMap(m => m.toolCalls || []).map(c => c.name || c.toolName || c.function?.name)
  assert.ok(calls.includes('web_search') && calls.includes('web_read'), JSON.stringify(calls))
  assert.ok(!calls.includes('plan_update'))
  const final = research.messages.findLast(m => m.kind === 'candidate')?.content || ''
  assert.match(final, /https:\/\/[^)\s]+/)
  results.chatResearch = { passed: true, networkApprovals: research.approvals, tokenUsed: research.run.tokenUsed, calls }
  console.log('PASS real AI Chat searches and reads a page; network authorization requested once')
  const directory = mkdtempSync(path.join(runtime, 'modes-ai-project-'))
  const project = await api('/projects', 'POST', { directory })
  const proposal = await create('请只制定实施计划：在当前项目创建hello.txt，内容为MODE_REAL_OK加末尾换行，再读回核对。不要执行命令，不要创建文件。最多3步，用plan_update保存所有步骤为pending，最后给出简短计划。', 'plan', project.id)
  const planned = await finished(proposal)
  assert.ok(!existsSync(path.join(directory, 'hello.txt')))
  const steps = (await api('/operations/runs/' + proposal.id + '/plan')).steps
  assert.ok(steps.length > 0 && steps.every(s => s.status === 'pending'))
  results.plan = { passed: true, runId: proposal.id, pendingSteps: steps.length, projectUnchanged: true }
  console.log('PASS real AI Plan creates pending steps and leaves project files unchanged')
  const execution = await create('请执行刚才的计划：检查hello.txt是否存在，不存在时创建并写入MODE_REAL_OK加换行；然后用读取工具核对。只做这件事，不联网、不运行命令或安装软件，完成后简短汇报。', 'build', project.id, proposal.sessionId, proposal.id)
  const built = await finished(execution)
  assert.equal(built.run.planRunId, proposal.id)
  assert.equal(readFileSync(path.join(directory, 'hello.txt'), 'utf8'), 'MODE_REAL_OK\n')
  results.build = { passed: true, runId: execution.id, planRunId: proposal.id, sameSession: true, fileVerified: true }
  writeFileSync(path.join(runtime, 'modes-real-ai.json'), JSON.stringify({ passed: true, ...results }, null, 2))
  console.log('PASS real AI Build executes the preceding plan in the same conversation and verifies the file')
} finally {
  for (const id of active) try {
    const run = await api('/runs/' + id)
    if (!['succeeded','failed','cancelled','timed_out','budget_exceeded'].includes(run.status)) await api('/runs/' + id + '/cancel', 'POST')
  } catch {}
}
