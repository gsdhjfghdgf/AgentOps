import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {randomUUID} from 'node:crypto'
import {writeFileSync} from 'node:fs'
const tenant=randomUUID(),user=randomUUID(),base='http://127.0.0.1:8080/api'
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms)),calls=new Map(),allowed=new Set()
let endpoint
async function api(path,method='GET',body,expected=200){
 const r=await fetch(base+path,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(90000)})
 const text=await r.text();assert.equal(r.status,expected,path+':'+text.slice(0,200));return text?JSON.parse(text):null
}
const server=createServer(async(req,res)=>{
 try{
  if(req.url.startsWith('/allow?')){allowed.add(new URL(req.url,'http://local').searchParams.get('case'));res.end('OK');return}
  if(req.url==='/shutdown'){res.end('OK');server.closeAllConnections();server.close();return}
  let raw='';for await(const chunk of req)raw+=chunk
  const body=JSON.parse(raw)
  if(req.headers.authorization!=='Bearer fixture-key'){res.writeHead(401);res.end('{"error":"fixture bad key"}');return}
  if(body.model!=='fixture'){res.writeHead(404);res.end('{"error":"fixture model absent"}');return}
  if(body.messages.some(m=>m.content?.includes('AGENTOPS_CONNECTION_CHECK'))){
    res.setHeader('Content-Type','application/json')
    res.end(JSON.stringify({choices:[{finish_reason:'tool_calls',message:{content:null,tool_calls:[
      {id:randomUUID(),type:'function',function:{name:'finish_task',arguments:'{"answer":"OK"}'}}]}}]}));return
  }
  if(!body.tools){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({choices:[{finish_reason:'stop',message:{content:'[]'}}]}));return}
  const label=body.messages.filter(m=>m.role==='user').at(-1).content
  const entries=calls.get(label)||[];entries.push(Date.now());calls.set(label,entries)
  const toolCount=body.messages.filter(m=>m.role==='tool').length
  const fail=(label==='TRANSIENT'&&entries.length<=2)
    ||(label==='RESET'&&entries.length===5)
    ||(label.startsWith('PAUSE')&&!allowed.has(label))
  if(fail){res.writeHead(503,{'Content-Type':'application/json'});res.end('{"error":{"message":"fixture overloaded"}}');return}
  if(label==='RESET'&&toolCount<4){
    res.setHeader('Content-Type','application/json');res.end(JSON.stringify({choices:[{finish_reason:'tool_calls',
      message:{content:'计算第'+toolCount+'步',tool_calls:[{id:randomUUID(),type:'function',function:{name:'calculator',arguments:JSON.stringify({expression:toolCount+'+1'})}}]}}],usage:{prompt_tokens:15,completion_tokens:10}}));return
  }
  res.writeHead(200,{'Content-Type':'text/event-stream'})
  res.write('data: '+JSON.stringify({choices:[{index:0,delta:{content:'已恢复，'}}]})+'\n\n')
  await delay(150)
  res.write('data: '+JSON.stringify({choices:[{index:0,delta:{content:'这是完整回答。'}}]})+'\n\n')
  res.end('data: '+JSON.stringify({choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:25,completion_tokens:12}})+'\n\ndata: [DONE]\n\n')
 }catch(e){res.writeHead(500);res.end('fixture failure')}
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));endpoint='http://127.0.0.1:'+server.address().port
async function start(text){
 const s=await api('/sessions','POST',{title:'[恢复测试] '+text},201)
 const r=await api('/sessions/'+s.sessionId+'/runs','POST',{message:text,mode:'chat'},201)
 return{sessionId:s.sessionId,runId:r.runId}
}
async function wait(run,status){
 for(let i=0;i<400;i++){const r=await api('/runs/'+run.runId);if(r.status===status)return r
  assert.ok(!['failed','timed_out','budget_exceeded'].includes(r.status),JSON.stringify(r));await delay(100)}
 throw Error('task did not reach '+status)
}
try{
 await api('/model-settings/profiles','POST',{name:'恢复测试',url:endpoint+'/v1',apiKey:'fixture-key',model:'fixture',activate:true})
 const original=await api('/model-settings'),saved=original.profiles[0]
 await api('/model-settings/profiles','POST',{name:'拒绝保存',url:endpoint+'/v1',apiKey:'wrong-key',model:'fixture',activate:true},400)
 let unchanged=await api('/model-settings');assert.equal(unchanged.profiles.length,1);assert.equal(unchanged.selectedProfileId,original.selectedProfileId)
 await api('/model-settings/profiles/'+saved.id,'PUT',{name:'拒绝覆盖',url:saved.url,apiKey:'wrong-key',model:'fixture',versionId:saved.versionId,activate:true},400)
 unchanged=await api('/model-settings');assert.equal(unchanged.profiles[0].versionId,saved.versionId)
 console.log('PASS failed connection blocks both new configuration and edits without changing selection')
 const transient=await start('TRANSIENT');await wait(transient,'succeeded');assert.equal(calls.get('TRANSIENT').length,3)
 const transcript=await api('/sessions/'+transient.sessionId+'/messages')
 assert.ok(transcript.messages.some(m=>m.kind==='candidate'&&m.content==='已恢复，这是完整回答。'))
 console.log('PASS two failures recover into a streamed plain Chat answer')
 const reset=await start('RESET');await wait(reset,'succeeded')
 const retry=calls.get('RESET');assert.equal(retry.length,6)
 assert.ok(retry[5]-retry[4]<12000,'successful calls must not increase retry backoff')
 console.log('PASS successful tool steps reset failure backoff; Chat still calls tools')
 const paused=await start('PAUSE_API'),state=await wait(paused,'paused')
 assert.equal(state.reasonCode,'MODEL_RETRY_PAUSED_MODEL_HTTP_503');assert.equal(calls.get('PAUSE_API').length,3)
 await delay(2500);assert.equal(calls.get('PAUSE_API').length,3)
 allowed.add('PAUSE_API');await api('/runs/'+paused.runId+'/resume','POST',{commandId:randomUUID(),text:''})
 await wait(paused,'succeeded');assert.equal(calls.get('PAUSE_API').length,4)
 console.log('PASS three consecutive failures pause durably; same-task resume recovers')
 writeFileSync(new URL('../agent-server/.runtime/model-recovery-validation.json',import.meta.url),
  JSON.stringify({tenant,user,base,endpoint,passed:true,transient,reset,paused},null,2))
 console.log('READY recovery fixture '+endpoint)
 setTimeout(()=>{server.closeAllConnections();server.close()},30*60*1000).unref()
}catch(e){console.error(e.stack);server.closeAllConnections();server.close();process.exitCode=1}
