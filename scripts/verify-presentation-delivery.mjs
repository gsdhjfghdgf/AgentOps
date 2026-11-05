import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {randomUUID} from 'node:crypto'
import {mkdirSync,writeFileSync} from 'node:fs'

// Isolated deterministic model fixture; exercises real engine, append, artifact and preview APIs.
const base='http://127.0.0.1:8080/api',tenant=randomUUID(),user=randomUUID()
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const runtime=new URL('../agent-server/.runtime/',import.meta.url)
mkdirSync(runtime,{recursive:true})
let stage=0,deckId,theme,templates;const ids=[]
async function api(path,method='GET',body){const r=await fetch(base+path,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)});const text=await r.text();assert.ok(r.ok,path+': '+text.slice(0,200));return text?JSON.parse(text):null}
const server=createServer(async(req,res)=>{
 try{
  let raw='';for await(const chunk of req)raw+=chunk;const request=JSON.parse(raw)
  for(const message of request.messages.filter(m=>m.role==='tool'))try{
   const result=JSON.parse(message.content)
   if(result.artifact?.originalName?.endsWith('.pptx')&&!ids.includes(result.artifact.objectId)){ids.push(result.artifact.objectId);deckId=result.artifact.objectId}
   if(result.theme)theme=result.theme
   if(result.themeCatalog)templates=result.themeCatalog
  }catch{}
  const call=(name,args)=>({id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}})
  const cover={layout:'cover',title:'AI对生活的影响',subtitle:'适合生活科普的温暖风格'}
  const body={layout:'bullets',title:'在日常生活中使用AI',bullets:['辅助整理资料','帮助理解陌生知识','重要信息仍需核实']}
  const check=request.messages.some(m=>m.content?.includes('AGENTOPS_CONNECTION_CHECK'))
  let calls
  if(check)calls=[call('finish_task',{answer:'OK'})]
  else {
   const steps=[
    ()=>[call('presentation_templates',{})],
    ()=>[call('presentation_create',{title:'生活科普-草稿',deckJson:JSON.stringify({theme:'warm',slides:[cover]})})],
    ()=>[call('presentation_create',{title:'AI对生活的影响',deckJson:JSON.stringify({purpose:'生活科普',slides:[cover,body]})})],
    ()=>[call('presentation_append',{artifactId:deckId,title:'AI对生活的影响-10页',deckJson:JSON.stringify({theme:'auto',slides:[{layout:'statement',title:'使用建议',body:'让AI协助你，重要决定由你核实。'}]})})],
    ()=>[call('presentation_inspect',{artifactId:deckId}),call('presentation_render',{artifactId:deckId,page:'3'})],
    ()=>[call('finish_task',{answer:'已按生活科普用途选择温暖生活风格，交付一个完整PPT，可逐页查看。'})],
   ]
   assert.ok(stage<steps.length,'unexpected model repeat');calls=steps[stage++]()
  }
  res.writeHead(200,{'Content-Type':'text/event-stream'});res.write('data: '+JSON.stringify({choices:[{delta:{tool_calls:calls.map((c,index)=>({...c,index}))}}]})+'\n\n');res.end('data: '+JSON.stringify({choices:[{delta:{},finish_reason:'tool_calls'}],usage:{prompt_tokens:40,completion_tokens:30}})+'\n\ndata: [DONE]\n\n')
 }catch(error){console.error(error.message);res.writeHead(500);res.end('{}')}
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
let run,passed=false
try{
 await api('/memory-settings','PUT',{enabled:false})
 await api('/model-settings/profiles','POST',{name:'单文件交付隔离验证',url:'http://127.0.0.1:'+server.address().port+'/v1',apiKey:'fixture',model:'delivery-fixture',activate:true})
 const session=await api('/sessions','POST',{title:'单文件与多风格验收'})
 run=await api('/sessions/'+session.sessionId+'/runs','POST',{message:'制作AI对生活的影响的3页PPT，自动选择合适风格，仅交付一个最终文件。',mode:'build',contractRequirements:{allowedTools:['presentation_templates','presentation_create','presentation_append','presentation_inspect','presentation_render']}})
 let state;const end=Date.now()+90000
 do{state=await api('/runs/'+run.runId);if(['succeeded','failed','paused','waiting_user','waiting_approval','budget_exceeded'].includes(state.status))break;await new Promise(resolve=>setTimeout(resolve,200))}while(Date.now()<end)
 assert.equal(state.status,'succeeded',JSON.stringify(state));assert.equal(theme,'warm');assert.equal(templates.length,9);assert.equal(ids.length,3)
 const artifacts=(await api('/artifacts/by-run/'+run.runId)).artifacts
 const decks=artifacts.filter(a=>a.name.endsWith('.pptx'))
 assert.deepEqual(decks.map(a=>a.name),['生活科普-草稿.pptx','AI对生活的影响.pptx','AI对生活的影响.pptx'])
 assert.equal(decks.at(-1).objectId,deckId)
 const preview=await api('/operations/artifacts/'+deckId+'/preview');assert.equal(preview.slideCount,3)
 const r=await fetch(base+'/file-tools/artifacts/'+deckId+'/slides/3',{headers});assert.ok(r.ok);assert.ok((await r.arrayBuffer()).byteLength>1000)
 const result={passed:true,fixtureModel:true,tenant,user,sessionId:session.sessionId,runId:run.runId,presentationId:deckId,pages:3,theme,styleCount:templates.length,storedRevisions:decks.length}
 writeFileSync(new URL('ppt-delivery-validation.json',runtime),JSON.stringify(result,null,2));passed=true;console.log(JSON.stringify({passed:true,theme,styles:9,pages:3,storedRevisions:3}))
}finally{if(run&&!passed)await api('/runs/'+run.runId+'/cancel','POST',{}).catch(()=>{});server.closeAllConnections();await new Promise(resolve=>server.close(resolve))}
