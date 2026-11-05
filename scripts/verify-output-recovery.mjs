import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {randomUUID} from 'node:crypto'
import {writeFileSync} from 'node:fs'

const counts=new Map(),base='http://127.0.0.1:8080/api'
const server=createServer(async(request,response)=>{
 let text='';for await(const chunk of request)text+=chunk
 const body=JSON.parse(text),check=body.messages.some(m=>m.content?.includes('AGENTOPS_CONNECTION_CHECK'))
 const number=check?0:(counts.get(body.model)||0)+1
 if(!check)counts.set(body.model,number)
 const complete=check||(body.model==='recover'&&number===3)
 const tool=complete?{name:'finish_task',arguments:JSON.stringify({answer:check?'OK':'截断恢复完成，未执行不完整工具。'})}:{name:'calculator',arguments:'{"expression":"1+'}
 response.writeHead(200,{'Content-Type':'text/event-stream'})
 response.write('data: '+JSON.stringify({choices:[{delta:{tool_calls:[{index:0,id:randomUUID(),function:tool}]}}]})+'\n\n')
 response.end('data: '+JSON.stringify({choices:[{delta:{},finish_reason:complete?'tool_calls':'length'}],usage:{prompt_tokens:123,completion_tokens:45}})+'\n\ndata: [DONE]\n\n')
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
try {
 const reports=await Promise.all(['recover','permanent'].map(async model=>{
  const headers={'Content-Type':'application/json','X-Tenant-Id':randomUUID(),'X-User-Id':randomUUID()}
  async function api(path,method='GET',body){const r=await fetch(base+path,{method,headers,...(body?{body:JSON.stringify(body)}:{})});const text=await r.text();assert.ok(r.ok,path+': '+text.slice(0,250));return JSON.parse(text)}
  await api('/memory-settings','PUT',{enabled:false})
  await api('/model-settings','PUT',{url:'http://127.0.0.1:'+server.address().port+'/v1/chat/completions',model,apiKey:'',clearKey:true})
  const session=await api('/sessions','POST',{title:'[隔离验收] 输出截断 '+model})
  const task=await api('/sessions/'+session.sessionId+'/runs','POST',{message:'计算测试，仅用于验证截断恢复。',mode:'build',requestKey:randomUUID(),contractRequirements:{allowedTools:['calculator']}})
  let state;const end=Date.now()+60000
  do {state=await api('/runs/'+task.runId);if(['succeeded','paused','failed','waiting_user','budget_exceeded'].includes(state.status))break;await new Promise(resolve=>setTimeout(resolve,150))}while(Date.now()<end)
  assert.equal(state.status,model==='recover'?'succeeded':'paused',JSON.stringify(state))
  if(model==='permanent')assert.equal(state.reasonCode,'MODEL_RETRY_PAUSED_MODEL_OUTPUT_TRUNCATED')
  assert.ok(!state.questionId,'internal truncation must not ask the user for information')
  assert.equal(counts.get(model),3)
  assert.equal(state.tokenUsed,3*(123+45),'known usage must be settled exactly, not a conservative fallback')
  const messages=(await api('/sessions/'+session.sessionId+'/messages')).messages
  assert.equal(messages.filter(m=>m.role==='tool').length,0,'partial tools must never execute')
  return {model,status:state.status,attempts:counts.get(model),knownTokens:state.tokenUsed,partialToolsExecuted:0}
 }))
 const result={passed:true,reports};writeFileSync(new URL('../agent-server/.runtime/output-recovery-live.json',import.meta.url),JSON.stringify(result,null,2));console.log(JSON.stringify(result))
}finally{server.closeAllConnections();await new Promise(resolve=>server.close(resolve))}
