import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'

const base='http://127.0.0.1:8080/api', tenant=randomUUID(), user=randomUUID()
const headers={'X-Tenant-Id':tenant,'X-User-Id':user}
const sessions=[], documents=[], runs=[]
let count=0
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms))
async function req(path,method='GET',body,expected=200,identity=headers) {
  const response=await fetch(base+path,{method,headers:{...identity,'Content-Type':'application/json'},
    ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(190000)})
  const text=await response.text()
  assert.equal(response.status,expected,path+': '+text.slice(0,400))
  return text?JSON.parse(text):null
}
async function poll(get,accept,seconds=120) {
  const until=Date.now()+seconds*1000
  while(Date.now()<until) { const value=await get();if(accept(value))return value;await delay(300) }
  throw Error('Background operation timed out')
}
function pass(name){count++;console.log('PASS '+name)}
async function create(message) {
  const session=await req('/sessions','POST',{title:'[真实AI验证]'},201)
  sessions.push(session.sessionId)
  const run=await req('/sessions/'+session.sessionId+'/runs','POST',{message},201)
  runs.push(run.runId)
  return {sessionId:session.sessionId,runId:run.runId}
}
async function completed(id,status='succeeded') {
  return poll(()=>req('/runs/'+id),run=>{
    if(run.status===status)return true
    assert.ok(!['failed','timed_out','budget_exceeded','cancelled'].includes(run.status),JSON.stringify(run))
    return false
  })
}
async function messages(id){return (await req('/sessions/'+id+'/messages')).messages.map(m=>m.content).join('\n')}

try {
  const config=await req('/runtime')
  assert.equal(config.provider,'openai');assert.equal(config.embeddingProvider,'ollama')
  const checked=await req('/runtime/check','POST',{})
  assert.equal(checked.chat.ok,true);assert.equal(checked.embedding.ok,true)
  pass('live DeepSeek and Ollama connections; no mock adapter')

  for(const [name,bytes] of [
    ['住宿政策.md',Buffer.from('员工出差住宿费用：酒店每晚最高600元，须提供有效发票。')],
    ['travel.pdf',await readFile(new URL('../agent-server/target/test-fixtures/travel.pdf',import.meta.url))],
    ['travel.docx',await readFile(new URL('../agent-server/target/test-fixtures/travel.docx',import.meta.url))],
  ]) {
    const data=new FormData();data.append('file',new Blob([bytes]),name)
    const response=await fetch(base+'/knowledge/documents/upload',{method:'POST',headers,body:data,signal:AbortSignal.timeout(90000)})
    const result=await response.json();assert.equal(response.status,201,JSON.stringify(result));assert.ok(result.chunkCount>0)
    documents.push(result.documentId)
  }
  const hits=await req('/knowledge/search','POST',{query:'员工外出公干住宾馆能报销多少钱？',limit:5})
  assert.ok(hits.chunks.some(hit=>hit.content.includes('600')))
  const rebuilt=await req('/knowledge/documents/'+documents[0]+'/reindex','POST',{})
  assert.equal(rebuilt.version,2)
  await req('/knowledge/documents/'+documents[0]+'/reindex','POST',{},404,{'X-Tenant-Id':tenant,'X-User-Id':randomUUID()})
  pass('PDF, Word and Markdown indexed by local vectors; semantic retrieval and owned reindex')

  const short=await create('2+2等于多少？只回答数字，不调用工具。')
  await completed(short.runId)
  assert.match(await messages(short.sessionId),/4/)
  const runView=await req('/runs/'+short.runId)
  assert.ok(runView.tokenUsed>0,JSON.stringify(runView))
  pass('real short answer succeeds with recorded token usage')

  const memory=await create('我长期偏好中文回答，解释尽量用简短段落。这次只需简短确认，不调用任何记忆工具，由系统自动整理偏好。')
  await completed(memory.runId)
  const rows=await poll(()=>req('/memories?limit=100'),result=>result.memories.some(m=>m.automatic&&m.content.includes('中文')),90)
  assert.ok(rows.memories.some(m=>m.evidence.includes('中文')))
  assert.equal((await req('/memory-settings')).mode,'model')
  const recalled=await req('/memories?query='+encodeURIComponent('我希望你用什么语言回答？'))
  assert.ok(recalled.memories.some(m=>m.content.includes('中文')))
  pass('AI summarizes user preferences with evidence and Ollama memory recall')

  const steer=await create('简要解释RAG如何结合知识库回答问题，不调用工具。')
  await req('/runs/'+steer.runId+'/steer','POST',{commandId:randomUUID(),text:'补充要求：回答最后一行写 GREEN-42。'})
  await completed(steer.runId)
  assert.ok((await messages(steer.sessionId)).includes('GREEN-42'))
  const stream=await fetch(base+'/runs/'+steer.runId+'/events',{headers}).then(r=>r.text())
  assert.ok(stream.includes('event:run.command_applied'))
  pass('real model applies steering at a persisted boundary')

  const stop=await create('请详细解释机器学习训练与推理的不同。')
  await req('/runs/'+stop.runId+'/cancel','POST',{})
  await poll(()=>req('/runs/'+stop.runId),run=>run.status==='cancelled')
  pass('task cancellation remains functional with the real adapter')

  const suite=await req('/eval/suites/builtin','POST',{})
  assert.equal(suite.caseCount,6)
  const evaluated=await req('/eval/suites/'+suite.suiteId+'/run?timeoutSecondsPerCase=120','POST',{})
  console.log('SUITE '+JSON.stringify(evaluated))
  assert.equal(evaluated.passedCases,6)
  const report=await req('/eval/suites/'+suite.suiteId+'/report')
  assert.ok(report.trials.every(t=>t.usedTokens>0&&t.trace.model==='deepseek-flash'&&t.trace.runId))
  assert.equal(report.trials.find(t=>t.taskId==='ambiguous_input').trace.finalStatus,'waiting_user')
  assert.equal(report.trials.find(t=>t.taskId==='email_needs_approval').trace.finalStatus,'waiting_approval')
  await req('/eval/suites/'+suite.suiteId+'/report','GET',undefined,404,{'X-Tenant-Id':randomUUID(),'X-User-Id':randomUUID()})
  pass('six real-model eval cases; strict approval and clarification, actual tokens and tenant isolation')
  console.log(`PASS ${count} real-AI integration groups`)
} finally {
  for(const run of runs)try{await req('/runs/'+run+'/cancel','POST',{})}catch{}
  for(const doc of documents)try{await req('/knowledge/documents/'+doc,'DELETE')}catch{}
  try {
    for(const memory of (await req('/memories?limit=100')).memories)await req('/memories/'+encodeURIComponent(memory.memoryKey),'DELETE')
    await req('/sessions','DELETE')
  } catch {}
}
