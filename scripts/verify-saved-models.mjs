import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
import { writeFileSync } from 'node:fs'
const tenant=randomUUID(),user=randomUUID(),other=randomUUID(),base='http://127.0.0.1:8080/api'
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const file=new URL('../agent-server/.runtime/saved-models-validation.json',import.meta.url)
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms)),calls=[],passed=[]
let release,arrived,gated=false,ready=false,persisted
const gate=new Promise(resolve=>release=resolve),received=new Promise(resolve=>arrived=resolve)
async function req(path,method='GET',body,expected=200,as=user) {
  const r=await fetch(base+path,{method,headers:{...headers,'X-User-Id':as},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(90000)})
  const text=await r.text();assert.equal(r.status,expected,path+': '+text.slice(0,300));return text?JSON.parse(text):null
}
const server=createServer(async(request,response)=>{
  try {
    if(request.url==='/control/shutdown'){response.end('OK');server.closeAllConnections();server.close();return}
    if(request.url==='/control/check-persistence'){
      const current=await req('/model-settings')
      assert.deepEqual(current.profiles,persisted.profiles);assert.equal(current.selectedProfileId,persisted.selectedProfileId)
      response.setHeader('Content-Type','application/json');response.end(JSON.stringify({ok:true,profiles:current.profiles.length,selected:current.selectedProfileId}));return
    }
    if(request.url==='/control/state'){response.end(JSON.stringify({ready,calls,passed}));return}
    let raw='';for await(const chunk of request)raw+=chunk
    const body=JSON.parse(raw),model=body.model
    const key=model.startsWith('fixture-alpha')?'fixture-key-a':model==='fixture-beta'?'fixture-key-b':'fixture-key-c'
    if(request.headers.authorization!=='Bearer '+key){response.writeHead(401);response.end('{"error":{"message":"wrong fixture credential"}}');return}
    const pinned=body.messages.some(m=>m.content?.includes('PIN_MULTI'))
    const tool=body.messages.some(m=>m.role==='tool')
    calls.push({model,tools:!!body.tools,pinned,tool})
    if(model==='fixture-alpha'&&body.tools&&pinned&&!tool&&!gated){gated=true;arrived();await gate}
    let message={content:'[]'},finish='stop'
    if(body.tools){
      const call=pinned&&!tool&&model==='fixture-alpha'
        ?{id:randomUUID(),type:'function',function:{name:'calculator',arguments:'{"expression":"1+1"}'}}
        :{id:randomUUID(),type:'function',function:{name:'finish_task',arguments:JSON.stringify({answer:'MODEL '+model})}}
      message={content:null,tool_calls:[call]};finish='tool_calls'
    }
    response.writeHead(200,{'Content-Type':'application/json'})
    response.end(JSON.stringify({choices:[{finish_reason:finish,message}],usage:{prompt_tokens:42,completion_tokens:12}}))
  }catch(error){response.writeHead(500);response.end(String(error.message))}
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
const endpoint='http://127.0.0.1:'+server.address().port
function record(phase,extra={}){writeFileSync(file,JSON.stringify({tenant,user,base,endpoint,phase,passed,...extra},null,2))}
function pass(message){passed.push(message);console.log('PASS '+message)}
async function talk(text){
  const s=await req('/sessions','POST',{title:'[多模型验证] '+text.slice(0,20)},201)
  const r=await req('/sessions/'+s.sessionId+'/runs','POST',{message:text,mode:'build'},201)
  return{runId:r.runId,sessionId:s.sessionId}
}
async function complete(run){
  for(let i=0;i<250;i++){
    const r=await req('/runs/'+run.runId)
    if(r.status==='succeeded')return(await req('/sessions/'+run.sessionId+'/messages')).messages.map(m=>m.content).join('\n')
    assert.ok(!['failed','timed_out','budget_exceeded','cancelled'].includes(r.status),JSON.stringify(r))
    await delay(150)
  }throw Error('run did not complete')
}
try {
  // This legacy endpoint seeds profiles even on the previously installed version, before V22 runs.
  await req('/model-settings','PUT',{url:endpoint+'/v1',apiKey:'fixture-key-a',model:'fixture-alpha',clearKey:false})
  await req('/model-settings','PUT',{url:endpoint+'/v1',apiKey:'fixture-key-b',model:'fixture-beta',clearKey:false})
  record('awaiting-migration');console.log('READY legacy profiles seeded; awaiting updated backend')
  let catalog
  for(let i=0;i<600;i++){
    try{catalog=await req('/model-settings');if(Array.isArray(catalog.profiles))break}catch{}
    await delay(500)
  }
  assert.equal(catalog.profiles.length,2)
  let a=catalog.profiles.find(p=>p.model==='fixture-alpha'),b=catalog.profiles.find(p=>p.model==='fixture-beta')
  assert.equal(catalog.selectedProfileId,b.id);pass('legacy profiles recovered; active selection preserved')
  const update=(p,activate=false)=>({name:p.name,url:p.url,model:p.model,apiKey:'',clearKey:false,versionId:p.versionId,activate})
  catalog=await req('/model-settings/profiles/'+a.id,'PUT',{...update(a),name:'日常聊天测试'})
  a=catalog.profiles.find(p=>p.id===a.id)
  catalog=await req('/model-settings/profiles/'+b.id,'PUT',{...update(b),name:'代码助手测试'})
  b=catalog.profiles.find(p=>p.id===b.id)
  assert.equal(catalog.selectedProfileId,b.id);assert.equal(catalog.profiles.length,2)
  const tested=await req('/model-settings/profiles/'+a.id+'/test','POST',update(a));assert.equal(tested.ok,true)
  await req('/model-settings/profiles/test','POST',{...update(a),versionId:undefined},400)
  assert.ok(!JSON.stringify(catalog).includes('fixture-key'))
  pass('independent encrypted keys, inactive testing and save without changing selection')
  await req('/model-settings/selection','PUT',{profileId:a.id})
  const pinned=await talk('PIN_MULTI：先计算1+1，再返回模型标识。')
  await Promise.race([received,delay(30000).then(()=>{throw Error('model was not called')})])
  catalog=await req('/model-settings/profiles/'+a.id,'PUT',{...update(a),model:'fixture-alpha-edited'})
  const edited=catalog.profiles.find(p=>p.id===a.id)
  assert.notEqual(edited.versionId,a.versionId);assert.equal(catalog.selectedProfileId,a.id)
  await req('/model-settings/profiles/'+a.id,'PUT',{...update(a),name:'stale editor'},409)
  await req('/model-settings/selection','PUT',{profileId:b.id});release()
  assert.match(await complete(pinned),/MODEL fixture-alpha/)
  assert.ok(calls.filter(c=>c.pinned&&c.tools&&c.model==='fixture-alpha').length>=2)
  assert.match(await complete(await talk('请返回当前模型标识。')),/MODEL fixture-beta/)
  pass('editing and switching preserve pinned in-flight model; stale edits rejected')
  await req('/model-settings/selection','PUT',{profileId:a.id})
  assert.match(await complete(await talk('返回已编辑的模型标识。')),/MODEL fixture-alpha-edited/)
  const foreign=await req('/model-settings','GET',undefined,200,other);assert.equal(foreign.profiles.length,0)
  await req('/model-settings/selection','PUT',{profileId:a.id},404,other)
  await req('/model-settings/profiles/'+a.id,'DELETE',undefined,404,other)
  pass('new tasks use selected revision; cross-user model access rejected')
  catalog=await req('/model-settings/profiles','POST',{
    name:'临时配置',url:endpoint+'/v1',apiKey:'fixture-key-c',model:'fixture-gamma',clearKey:false,activate:false})
  const temporary=catalog.savedProfileId;assert.equal(catalog.selectedProfileId,a.id);assert.equal(catalog.profiles.length,3)
  catalog=await req('/model-settings/profiles/'+temporary,'DELETE');assert.equal(catalog.profiles.length,2)
  await req('/model-settings/selection','PUT',{profileId:temporary},404)
  catalog=await req('/model-settings/selection','PUT',{profileId:'default'});assert.equal(catalog.profiles.length,2)
  assert.equal(catalog.selectedProfileId,'default')
  persisted=await req('/model-settings/selection','PUT',{profileId:b.id})
  pass('add/delete are independent; selecting system default retains saved models')
  ready=true;record('ready',{a:edited,b,pinned,profiles:2});console.log('READY saved models fixture '+endpoint)
  setTimeout(()=>{server.closeAllConnections();server.close()},30*60*1000).unref()
}catch(error){
  release();record('failed',{error:error.message});console.error(error.stack);server.closeAllConnections();server.close();process.exitCode=1
}
