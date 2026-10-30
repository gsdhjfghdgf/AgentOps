import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import assert from 'node:assert/strict'

const base='http://127.0.0.1:8080/api',tenant=randomUUID(),user=randomUUID()
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const gates=new Map(),calls=[],tasks=[],delay=ms=>new Promise(r=>setTimeout(r,ms))
let connectionId,effects=0
const server=createServer(async(req,res)=>{
  try {
    const url=new URL(req.url,'http://fixture')
    if(url.pathname==='/state'){res.end(JSON.stringify({gates:[...gates.keys()],calls,effects}));return}
    if(url.pathname==='/release'){gates.get(url.searchParams.get('tag'))?.();res.end('ok');return}
    if(url.pathname==='/shutdown'){res.end('ok');for(const release of gates.values())release();server.close();setTimeout(()=>process.exit(0),100);return}
    let raw='';for await(const chunk of req)raw+=chunk
    const body=JSON.parse(raw)
    if(url.pathname==='/mcp'){
      res.setHeader('Content-Type','application/json')
      if(body.method==='notifications/initialized'){res.statusCode=202;res.end();return}
      let result
      if(body.method==='initialize')result={protocolVersion:'2025-03-26',capabilities:{tools:{}},serverInfo:{name:'pause-probe',version:'1'}}
      else if(body.method==='tools/list')result={tools:[{name:'pause_probe',description:'A gated verification operation',inputSchema:{type:'object',properties:{}}}]}
      else if(body.method==='tools/call'){
        effects++
        await new Promise(resolve=>{gates.set('MCP_OPERATION',resolve);res.once('close',resolve)})
        gates.delete('MCP_OPERATION')
        result={content:[{type:'text',text:'probe completed once'}]}
      } else throw Error('Unexpected MCP method')
      res.end(JSON.stringify({jsonrpc:'2.0',id:body.id,result}));return
    }
    const content=body.messages.filter(m=>m.role==='user').map(m=>m.content).join('\n')
    const resumed=content.includes('RESUME_NEW_REQUIREMENT'),tool=content.includes('PAUSE_TOOL')
    calls.push({tool,resumed,stream:body.stream===true})
    res.writeHead(200,{'Content-Type':'text/event-stream; charset=utf-8'})
    const data=value=>res.write('data: '+(typeof value==='string'?value:JSON.stringify(value))+'\n\n')
    if(!tool&&!resumed){
      data({choices:[{delta:{content:'正在检查项目的当前状态。'}}]})
      await new Promise(resolve=>{gates.set('MODEL_OPERATION',resolve);res.once('close',resolve)})
      gates.delete('MODEL_OPERATION')
      if(res.destroyed)return
    }
    const name=resumed?'finish_task':'mcp_call'
    const args=resumed?{answer:'已按 RESUME_NEW_REQUIREMENT 补充继续，保留已有结果，没有重复执行外部操作。'}
      :{connectionId,toolName:'pause_probe',argumentsJson:'{}'}
    data({choices:[{delta:{tool_calls:[{index:0,id:randomUUID(),function:{name,arguments:JSON.stringify(args)}}]}}]})
    data({choices:[{delta:{},finish_reason:'tool_calls'}],usage:{prompt_tokens:100,completion_tokens:30}})
    data('[DONE]');res.end()
  }catch(error){console.error(error.message);res.destroy(error)}
})
await new Promise(r=>server.listen(0,'127.0.0.1',r))
const fixture='http://127.0.0.1:'+server.address().port
async function api(path,method='GET',value,expected){
  const res=await fetch(base+path,{method,headers,...(value===undefined?{}:{body:JSON.stringify(value)})})
  const body=await res.text();assert.ok(expected?res.status===expected:res.ok,path+':'+body.slice(0,250))
  return body?JSON.parse(body):null
}
async function until(check,label,timeout=30000){const end=Date.now()+timeout;while(Date.now()<end){const result=await check();if(result)return result;await delay(80)}throw Error('timeout '+label)}
async function create(goal){
  const session=await api('/sessions','POST',{title:goal})
  const run=await api('/sessions/'+session.sessionId+'/runs','POST',{message:goal,mode:'build'})
  tasks.push(run.runId);return{runId:run.runId,sessionId:session.sessionId}
}
function deadlineForProbe(id){
  assert.match(id,/^[0-9a-f-]{36}$/)
  const sql="UPDATE agent_memory.runs SET deadline_at=now()+interval '5 seconds' WHERE id='"+id+"' AND tenant_id='"+tenant+"' AND user_id='"+user+"';"
  execFileSync('ssh.exe',['-p','2222','-i','D:\\VM\\scripts\\vm_key','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','zww@127.0.0.1',
    'docker exec agent-postgres psql -U agent -d agent_memory -qAtc "'+sql+'"'],{stdio:'pipe'})
}
try {
  await api('/memory-settings','PUT',{enabled:false})
  await api('/model-settings','PUT',{url:fixture+'/v1',model:'pause-fixture',apiKey:'',clearKey:true})
  connectionId=(await api('/operations/mcp','POST',{name:'Pause verification',url:fixture+'/mcp',apiKey:'',enabled:true})).id
  const model=await create('PAUSE_MODEL：检查当前进度')
  await until(()=>gates.has('MODEL_OPERATION'),'model stream')
  deadlineForProbe(model.runId)
  const pauseKey=randomUUID()
  await api('/runs/'+model.runId+'/pause','POST',{commandId:pauseKey})
  await until(async()=>(await api('/runs/'+model.runId)).status==='paused','paused model')
  const count=calls.length
  await delay(6000) // Cross the original deadline while paused.
  assert.equal((await api('/runs/'+model.runId)).status,'paused')
  assert.equal(calls.length,count)
  assert.equal((await api('/runs/'+model.runId+'/pause','POST',{commandId:pauseKey})).idempotentHit,true)
  const resumeKey=randomUUID(),text='RESUME_NEW_REQUIREMENT：先只总结现有发现，不修改文件'
  await api('/runs/'+model.runId+'/resume','POST',{commandId:resumeKey,text})
  assert.equal((await api('/runs/'+model.runId+'/resume','POST',{commandId:resumeKey,text})).idempotentHit,true)
  await until(async()=>(await api('/runs/'+model.runId)).status==='succeeded','resumed model',15000)
  const messages=(await api('/sessions/'+model.sessionId+'/messages?limit=100')).messages
  assert.equal(messages.filter(m=>m.role==='user'&&m.content===text).length,1)
  assert.equal((await api('/sessions/'+model.sessionId+'/runs')).runs.length,1)
  const delta=await api('/runs/'+model.runId+'/sync?afterSeq='+messages.at(-1).seq)
  assert.equal(delta.messages.length,0)
  console.log('PASS pause interrupts generation, freezes timeout, consumes no calls while paused and resumes same task exactly once')
  const operation=await create('PAUSE_TOOL：调用MCP进行一次探测')
  await until(async()=>{
    const state=await api('/runs/'+operation.runId)
    if(state.status==='waiting_approval')for(const approval of(await api('/runs/'+operation.runId+'/approvals')).approvals)
      await api('/approvals/'+approval.approvalId+'/decision','POST',{decision:'approved'})
    return gates.has('MCP_OPERATION')
  },'external operation')
  await api('/runs/'+operation.runId+'/pause','POST',{commandId:randomUUID()})
  await delay(700)
  assert.equal((await api('/runs/'+operation.runId)).status,'pause_requested')
  assert.equal(effects,1)
  gates.get('MCP_OPERATION')()
  await until(async()=>(await api('/runs/'+operation.runId)).status==='paused','safe tool boundary')
  await api('/runs/'+operation.runId+'/resume','POST',{commandId:randomUUID(),text:'RESUME_NEW_REQUIREMENT：不要再次调用MCP，只总结已有结果'})
  await until(async()=>(await api('/runs/'+operation.runId)).status==='succeeded','resume after tool')
  assert.equal(effects,1)
  console.log('PASS an in-flight external operation completes before pause and is not repeated after resume')
  writeFileSync('agent-server/.runtime/pause-validation.json',JSON.stringify({
    passed:true,base,fixture,tenant,user,modelSessionId:model.sessionId,toolSessionId:operation.sessionId,
    modelRunId:model.runId,toolRunId:operation.runId,interruptedGeneration:true,deadlineFrozen:true,
    noModelCallsWhilePaused:true,idempotentResume:true,sameTask:true,externalEffects:effects,
  },null,2))
  console.log('READY pause UI fixture '+fixture)
  setTimeout(()=>process.exit(2),30*60*1000).unref()
}catch(error){
  console.error(error.stack)
  for(const id of tasks)try{await api('/runs/'+id+'/cancel','POST')}catch{}
  for(const release of gates.values())release()
  server.close();process.exit(1)
}
