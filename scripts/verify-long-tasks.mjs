import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {randomUUID} from 'node:crypto'
import {execFileSync} from 'node:child_process'
const tenant=randomUUID(),user=randomUUID(),base='http://127.0.0.1:8080/api'
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const delay=ms=>new Promise(r=>setTimeout(r,ms))
const server=createServer(async(req,res)=>{
  let raw='';for await(const chunk of req)raw+=chunk
  const body=JSON.parse(raw);let message={content:'[]'},finish='stop'
  if(body.tools){
    const tag=body.messages.find(m=>m.role==='user').content
    const outputs=body.messages.filter(m=>m.role==='tool')
    let name='finish_task',args={answer:'LONG_TASK_OK'}
    if(tag==='LONG_NETWORK'){
      const steps=[...Array.from({length:19},()=>({name:'current_time',args:{timezone:'UTC'}})),
        {name:'web_read',args:{url:'https://example.com'}},
        {name:'web_read',args:{url:'https://www.iana.org/domains/reserved'}},
        {name:'web_search',args:{query:'IANA reserved example domains'}},
        {name:'text_file_create',args:{filename:'network-scope.txt',content:'Write still requires approval'}}]
      const step=steps[outputs.length];if(step)({name,args}=step)
    } else if(tag==='COMMAND_SCOPE') {
      const steps=[{name:'command_run',args:{command:"Write-Output 'COMMAND_FIRST'"}},
        {name:'command_run',args:{command:"Write-Output 'COMMAND_SECOND'"}},
        {name:'web_read',args:{url:'https://example.com'}},
        {name:'text_file_create',args:{filename:'command-scope.txt',content:'File tool keeps its own approval'}}]
      const step=steps[outputs.length];if(step)({name,args}=step)
    } else if(tag==='NEW_COMMAND') {if(!outputs.length){name='command_run';args={command:"Write-Output 'NEW_COMMAND'"}}}
    else if(tag==='TOOL_BOUNDARY'&&outputs.length===0){
      message={content:null,tool_calls:[0,1,2].map(()=>({id:randomUUID(),type:'function',function:{name:'current_time',arguments:JSON.stringify({timezone:'UTC'})}}))}
    } else if(tag==='MODEL_BOUNDARY'&&outputs.length<8){
      name=outputs.length===1?'text_file_create':'current_time'
      args=outputs.length===1?{filename:'once.txt',content:'ONCE_ONLY'}:{timezone:'UTC'}
    } else if(tag==='NEW_NETWORK') {if(!outputs.length){name='web_read';args={url:'https://example.com'}}}
    if(!message.tool_calls)message={content:null,tool_calls:[{id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}}]}
    finish='tool_calls'
  }
  res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({choices:[{finish_reason:finish,message}],usage:{prompt_tokens:40,completion_tokens:20}}))
})
await new Promise(r=>server.listen(0,'127.0.0.1',r))
async function api(route,method='GET',body,status=200,as=user){
  const r=await fetch(base+route,{method,headers:{...headers,'X-User-Id':as},...(body?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(180000)})
  const text=await r.text();assert.equal(r.status,status,route+': '+text.slice(0,300));return text?JSON.parse(text):null
}
function sql(query){
  const encoded=Buffer.from('SET search_path TO agent_memory,public; '+query).toString('base64')
  return execFileSync('ssh',['-p','2222','-i','D:/VM/scripts/vm_key','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','zww@127.0.0.1',
    `printf %s ${encoded} | base64 -d | docker exec -i agent-postgres psql -U agent -d agent_memory -X -v ON_ERROR_STOP=1 -At`],{encoding:'utf8',timeout:15000}).trim()
}
async function task(tag){const s=await api('/sessions','POST',{title:'[长任务回归]'},201);const r=await api('/sessions/'+s.sessionId+'/runs','POST',{message:tag},201);return {...r,sessionId:s.sessionId}}
async function wait(run,expected){for(let i=0;i<1200;i++){const r=await api('/runs/'+run.runId);if(r.status===expected)return r;assert.ok(!['failed','timed_out','budget_exceeded'].includes(r.status),JSON.stringify(r));await delay(100)}throw Error('timeout '+expected)}
async function approve(run){await wait(run,'waiting_approval');const pending=await api('/runs/'+run.runId+'/approvals');const a=pending.approvals[0];await api('/approvals/'+a.approvalId+'/decision','POST',{decision:'approved'});return a}
try{
  await api('/model-settings','PUT',{url:'http://127.0.0.1:'+server.address().port+'/v1',apiKey:'local-fixture',model:'long-tasks-fixture',clearKey:false})
  const long=await task('LONG_NETWORK')
  const first=await approve(long);assert.equal(first.scope,'task_network')
  const write=await approve(long);assert.equal(write.tool,'text_file_create');assert.equal(write.scope,'operation')
  await wait(long,'succeeded')
  const approvalCount=sql(`SELECT count(*) FROM run_approvals WHERE tenant_id='${tenant}' AND run_id='${long.runId}' AND tool_name IN ('web_read','web_search');`)
  assert.equal(approvalCount.split('\n').at(-1),'1')
  console.log('PASS >16 model calls complete; one task network permission covers different URLs and search; write remains gated')
  const fresh=await task('NEW_NETWORK');await wait(fresh,'waiting_approval')
  assert.equal((await api('/runs/'+fresh.runId+'/approvals')).approvals[0].scope,'task_network')
  await api('/runs/'+fresh.runId+'/cancel','POST',{})
  console.log('PASS network permission does not carry into a new task')
  await api('/capabilities/tools/command_run','PUT',{enabled:true,askApproval:true})
  const commands=await task('COMMAND_SCOPE')
  assert.equal((await approve(commands)).scope,'task_command')
  assert.equal((await approve(commands)).scope,'task_network')
  assert.equal((await approve(commands)).scope,'operation')
  await wait(commands,'succeeded')
  const commandMessages=(await api('/sessions/'+commands.sessionId+'/messages')).messages.filter(m=>m.role==='tool').map(m=>m.content).join('\n')
  assert.match(commandMessages,/COMMAND_FIRST/);assert.match(commandMessages,/COMMAND_SECOND/)
  const commandApprovals=sql(`SELECT count(*) FROM run_approvals WHERE tenant_id='${tenant}' AND run_id='${commands.runId}' AND tool_name='command_run';`)
  assert.equal(commandApprovals.split('\n').at(-1),'1')
  const nextCommand=await task('NEW_COMMAND');await wait(nextCommand,'waiting_approval')
  assert.equal((await api('/runs/'+nextCommand.runId+'/approvals')).approvals[0].scope,'task_command')
  await api('/runs/'+nextCommand.runId+'/cancel','POST',{})
  console.log('PASS different terminal commands share one task grant; network and file approvals stay separate; next task asks again')
  for(const tag of ['MODEL_BOUNDARY','TOOL_BOUNDARY']){
    const r=await task(tag)
    sql(`UPDATE budget_accounts SET max_model_attempts=${tag==='MODEL_BOUNDARY'?3:128},max_tool_attempts=${tag==='TOOL_BOUNDARY'?2:512} WHERE tenant_id='${tenant}' AND user_id='${user}' AND period_key='run:${r.runId}' AND budget_type='run';`)
    if(tag==='MODEL_BOUNDARY')await approve(r)
    await wait(r,'budget_exceeded')
    await api('/runs/'+r.runId+'/continue','POST',{},404,randomUUID())
    await api('/runs/'+r.runId+'/continue','POST',{})
    await wait(r,'succeeded')
    assert.equal((await api('/sessions/'+r.sessionId+'/runs')).runs.length,1)
    await api('/runs/'+r.runId+'/continue','POST',{},409)
    if(tag==='MODEL_BOUNDARY')assert.equal((await api('/artifacts/by-run/'+r.runId)).artifacts.length,1)
    console.log('PASS '+tag+': same task resumes at checkpoint, completed effects are not repeated, foreign/finished continuation rejected')
  }
}finally{
  await api('/sessions','DELETE');await api('/model-settings','DELETE');server.close()
}
