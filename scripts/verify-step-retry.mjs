import {readFileSync}from'node:fs'
import{createServer}from'node:http'
import{randomUUID}from'node:crypto'
import assert from'node:assert/strict'
const data=JSON.parse(readFileSync('agent-server/.runtime/operations-integration.json','utf8')),base=data.base,headers={'X-Tenant-Id':data.tenant,'X-User-Id':data.user,'Content-Type':'application/json'}
const server=createServer(async(q,r)=>{let raw='';for await(const c of q)raw+=c;const b=JSON.parse(raw),message=b.tools?{content:null,tool_calls:[{id:randomUUID(),type:'function',function:{name:'finish_task',arguments:JSON.stringify({answer:'只处理指定步骤的重试验证完成，原任务保留。'})}}]}:{content:'[]'};r.writeHead(200,{'Content-Type':'application/json'});r.end(JSON.stringify({choices:[{finish_reason:b.tools?'tool_calls':'stop',message}],usage:{prompt_tokens:40,completion_tokens:20}}))})
await new Promise(r=>server.listen(0,'127.0.0.1',r))
async function api(p,m='GET',body,status=200){const r=await fetch(base+p,{method:m,headers,...(body!==undefined?{body:JSON.stringify(body)}:{})});const text=await r.text();assert.equal(r.status,status,p+': '+text.slice(0,300));return text?JSON.parse(text):null}
try{
 await api('/model-settings','PUT',{url:'http://127.0.0.1:'+server.address().port+'/v1',model:'retry-fixture',apiKey:'fixture',clearKey:false})
 await api(`/operations/runs/${data.runId}/retry`,'POST',{stepId:'missing'},400)
 const retry=await api(`/operations/runs/${data.runId}/retry`,'POST',{stepId:'review'});assert.notEqual(retry.runId,data.runId)
 let result;for(let i=0;i<100;i++){result=await api('/runs/'+retry.runId);if(result.status==='succeeded')break;await new Promise(r=>setTimeout(r,150))}assert.equal(result.status,'succeeded');assert.equal(result.sessionId,data.sessionId)
 assert.equal((await api('/runs/'+data.runId)).status,'succeeded')
 const messages=(await api('/sessions/'+data.sessionId+'/messages')).messages;assert.ok(messages.some(m=>m.runId===retry.runId&&m.role==='user'&&m.content.includes('核对差异并应用')&&m.content.includes(data.runId)))
 console.log('PASS specified step retry creates a scoped new run in the same chat, preserves the original task and rejects an unknown step')
}finally{server.close()}
