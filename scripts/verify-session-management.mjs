import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
const base='http://127.0.0.1:8080', tenant=randomUUID(), user=randomUUID(), foreign=randomUUID()
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
let passed=0
async function req(path,method='GET',body,expected=200,as=user) {
  const response=await fetch(base+'/api'+path,{method,headers:{...headers,'X-User-Id':as},
    ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(60000)})
  const text=await response.text()
  assert.equal(response.status,expected,method+' '+path+': '+text.slice(0,400))
  return text?JSON.parse(text):null
}
const delay=ms=>new Promise(r=>setTimeout(r,ms))
function pass(name){passed++;console.log('PASS '+name)}
async function wait(id,status='succeeded'){
  for(let i=0;i<150;i++){
    const run=await req('/runs/'+id)
    if(run.status===status)return run
    assert.ok(!['failed','budget_exceeded','timed_out'].includes(run.status),JSON.stringify(run))
    await delay(100)
  }
  throw Error('Timed out: '+id+' -> '+status)
}
async function main(){
  const session=await req('/sessions','POST',{title:'新会话'},201), sid=session.sessionId
  await req('/sessions/'+sid,'PUT',{title:'销售报表',pinned:true})
  assert.equal((await req('/sessions?query='+encodeURIComponent('销售'))).sessions[0].pinned,true)
  assert.equal((await req('/sessions?query=unmatched')).sessions.length,0)
  await req('/sessions/'+sid,'PUT',{title:' чужой'},404,foreign)
  pass('rename, pin, search and owner protection')

  await req('/sessions/'+sid,'PUT',{archived:true})
  assert.equal((await req('/sessions')).sessions.length,0)
  assert.equal((await req('/sessions?archived=true')).sessions[0].sessionId,sid)
  await req('/sessions/'+sid,'PUT',{archived:false})
  assert.equal((await req('/sessions')).sessions[0].sessionId,sid)
  pass('archive and restore')

  const auto=await req('/sessions','POST',{title:'新会话'},201)
  const task=await req('/sessions/'+auto.sessionId+'/runs','POST',{message:'生成销售概况'},201)
  await wait(task.runId)
  assert.equal((await req('/sessions/'+auto.sessionId)).title,'生成销售概况')
  pass('automatic title from first request')

  const steering=await req('/sessions','POST',{title:'新会话'},201)
  const run=await req('/sessions/'+steering.sessionId+'/runs','POST',{message:'你好'},201)
  const command=randomUUID(), text='请保留原始目标，额外使用中文表格'
  await req('/runs/'+run.runId+'/steer','POST',{commandId:command,text})
  await req('/runs/'+run.runId+'/steer','POST',{commandId:command,text})
  await req('/runs/'+run.runId+'/steer','POST',{commandId:command,text:'改成别的'},409)
  await wait(run.runId)
  const messages=(await req('/sessions/'+steering.sessionId+'/messages')).messages
  assert.equal(messages.filter(m=>m.role==='user'&&m.content===text).length,1)
  const stream=await fetch(base+'/api/runs/'+run.runId+'/events',{headers}).then(r=>r.text())
  assert.ok(stream.includes('event:run.command_received'))
  assert.ok(stream.includes('event:run.command_applied'))
  assert.ok(stream.indexOf('event:run.command_applied')<stream.indexOf('event:run.succeeded'))
  pass('steering persistence, idempotency, safe application and success ordering')

  const stopped=await req('/sessions','POST',{title:'停止任务测试'},201)
  const stopping=await req('/sessions/'+stopped.sessionId+'/runs','POST',{message:'帮我查'},201)
  await wait(stopping.runId,'waiting_user')
  await req('/sessions/'+stopped.sessionId,'DELETE',undefined,409)
  await req('/sessions','DELETE',undefined,409)
  await req('/runs/'+stopping.runId+'/cancel','POST',{})
  await wait(stopping.runId,'cancelled')
  await req('/sessions/'+stopped.sessionId,'DELETE')
  await req('/sessions/'+stopped.sessionId+'/messages','GET',undefined,404)
  pass('stop before delete and protect unfinished work')

  const suite=await req('/eval/suites/builtin','POST',{})
  const report=await req('/eval/suites/'+suite.suiteId+'/run?timeoutSecondsPerCase=15','POST',{})
  assert.equal(report.passedCases,report.totalCases)
  const visible=(await req('/sessions')).sessions
  assert.ok(visible.every(s=>!s.title.startsWith('eval-')))
  pass('evaluation sessions isolated from daily conversations')

  const before=(await req('/sessions')).sessions.length
  assert.ok(before>0)
  await req('/sessions','DELETE')
  assert.equal((await req('/sessions')).sessions.length,0)
  assert.equal((await req('/sessions?archived=true')).sessions.length,0)
  assert.equal((await req('/eval/suites/'+suite.suiteId+'/report')).suiteId,suite.suiteId)
  pass('clear only session history while retaining evaluation results')
  console.log(JSON.stringify({passed,isolatedTenant:tenant}))
}
main().catch(error=>{console.error(error.message);process.exitCode=1})
