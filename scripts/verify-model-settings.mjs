import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
const tenant=randomUUID(),user=randomUUID(),other=randomUUID(),base='http://127.0.0.1:8080/api'
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const calls=[],runIds=[]
let release,arrived
const gate=new Promise(resolve=>{release=resolve}), received=new Promise(resolve=>{arrived=resolve})
let delayed=false,passed=0
const server=createServer(async(req,res)=>{
  let raw='';for await(const chunk of req)raw+=chunk
  const body=JSON.parse(raw),profile=req.url.startsWith('/a/')?'A':'B'
  const key=profile==='A'?'fixture-key-a':'fixture-key-b'
  if(req.headers.authorization!=='Bearer '+key){res.writeHead(401);res.end('{"error":{"message":"bad key"}}');return}
  calls.push({profile,model:body.model,tools:!!body.tools})
  let message={content:'[]'},finish='stop'
  if(body.tools){
    const isPinned=body.messages.some(m=>m.content?.includes('PIN_PROFILE'))
    const hasTool=body.messages.some(m=>m.role==='tool')
    if(profile==='A'&&isPinned&&!hasTool&&!delayed){delayed=true;arrived();await gate}
    const call=profile==='A'&&isPinned&&!hasTool
      ?{id:randomUUID(),type:'function',function:{name:'knowledge_search',arguments:JSON.stringify({query:'配置回归测试'})}}
      :{id:randomUUID(),type:'function',function:{name:'finish_task',arguments:JSON.stringify({answer:'PROFILE-'+profile})}}
    message={content:null,tool_calls:[call]};finish='tool_calls'
  }
  res.writeHead(200,{'Content-Type':'application/json'})
  res.end(JSON.stringify({choices:[{finish_reason:finish,message}],usage:{prompt_tokens:42,completion_tokens:12}}))
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
const endpoint='http://127.0.0.1:'+server.address().port
async function req(path,method='GET',body,expected=200,as=user){
  const r=await fetch(base+path,{method,headers:{...headers,'X-User-Id':as},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(90000)})
  const text=await r.text();assert.equal(r.status,expected,path+': '+text.slice(0,300));return text?JSON.parse(text):null
}
function pass(label){console.log('PASS '+label);passed++}
async function talk(text){
  const s=await req('/sessions','POST',{title:'[模型配置验证]'},201)
  const r=await req('/sessions/'+s.sessionId+'/runs','POST',{message:text},201);runIds.push(r.runId)
  return {runId:r.runId,sessionId:s.sessionId}
}
async function complete(run){
  for(let i=0;i<300;i++){
    const r=await req('/runs/'+run.runId)
    if(r.status==='succeeded')return (await req('/sessions/'+run.sessionId+'/messages')).messages.map(m=>m.content).join('\n')
    assert.ok(!['failed','timed_out','budget_exceeded'].includes(r.status),JSON.stringify(r));await new Promise(resolve=>setTimeout(resolve,150))
  }throw Error('run timed out')
}
try{
  const original=await req('/model-settings'),vectorBefore=await req('/runtime')
  const a={url:endpoint+'/a/v1',apiKey:'fixture-key-a',model:'fixture-model-a',clearKey:false}
  const saved=await req('/model-settings','PUT',a)
  assert.equal(saved.custom,true);assert.equal(saved.model,a.model);assert.equal(saved.keyConfigured,true)
  assert.ok(!JSON.stringify(saved).includes(a.apiKey));assert.ok(!('apiKey'in saved))
  assert.equal((await req('/model-settings','GET',undefined,200,other)).custom,false)
  pass('URL/key/model saved per owner; keys never returned')
  const checked=await req('/model-settings/test','POST',{...a,apiKey:''});assert.equal(checked.ok,true)
  const bad=await req('/model-settings/test','POST',{...a,apiKey:'wrong'});assert.equal(bad.ok,false);assert.match(bad.message,/401/)
  assert.equal((await req('/model-settings')).model,a.model)
  await req('/model-settings','PUT',{...a,url:endpoint+'/b/v1',apiKey:''},400)
  pass('draft connection test does not save; blank key reuse stays on its original endpoint')
  const pinned=await talk('PIN_PROFILE：检索知识库后返回配置标识。')
  await Promise.race([received,new Promise((_,reject)=>setTimeout(()=>reject(Error('fixture was not called')),30000))])
  const b={url:endpoint+'/b/v1',apiKey:'fixture-key-b',model:'fixture-model-b',clearKey:false,embeddingModel:'must-not-change'}
  await req('/model-settings','PUT',b);release()
  assert.match(await complete(pinned),/PROFILE-A/)
  const fresh=await talk('只需返回当前配置标识。');assert.match(await complete(fresh),/PROFILE-B/)
  assert.ok(calls.filter(c=>c.profile==='A'&&c.tools&&c.model==='fixture-model-a').length>=2)
  assert.ok(calls.some(c=>c.profile==='B'&&c.tools&&c.model==='fixture-model-b'))
  const vectorAfter=await req('/runtime');assert.equal(vectorAfter.embeddingModel,vectorBefore.embeddingModel)
  assert.equal(vectorAfter.embeddingProvider,vectorBefore.embeddingProvider)
  pass('new tasks switch immediately; in-flight tool continuation keeps old profile; vector configuration stays fixed')
  const keyless=await req('/model-settings','PUT',{url:endpoint+'/b/v1',apiKey:'',model:'keyless-fixture',clearKey:true})
  assert.equal(keyless.keyConfigured,false)
  await req('/model-settings','DELETE')
  const restored=await req('/model-settings')
  for(const field of ['url','model','custom','provider','keyConfigured'])assert.equal(restored[field],original[field])
  pass('explicit key removal and restoring system defaults')
  // Test a persisted user profile against the actual system service without reading its Key.
  if(original.provider==='openai'&&original.keyConfigured){
    await req('/model-settings','PUT',{url:original.url,model:original.model,apiKey:'',clearKey:false})
    const real=await req('/model-settings/test','POST',{url:original.url,model:original.model,apiKey:'',clearKey:false})
    assert.equal(real.ok,true,real.message)
    pass('saved user profile connects to the actual configured model with an unrevealed Key')
  }
  console.log(`PASS ${passed} model-settings integration groups`)
}finally{
  release()
  for(const id of runIds)try{await req('/runs/'+id+'/cancel','POST',{})}catch{}
  try{await req('/model-settings','DELETE');await req('/sessions','DELETE')}catch{}
  await new Promise(resolve=>server.close(resolve))
}
