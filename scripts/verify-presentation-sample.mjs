import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {randomUUID} from 'node:crypto'
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs'

// Four-page, local-asset preview: no public image provider or paid model required.
const base='http://127.0.0.1:8080/api',tenant=randomUUID(),user=randomUUID()
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const runtime=new URL('../agent-server/.runtime/ppt-design-sample/',import.meta.url);mkdirSync(runtime,{recursive:true})
const presentationName='AgentOps-科技产品风-4页样张.pptx'
let cover,preview,deckId,review,stage=0,releaseAssets;const assetReady=new Promise(resolve=>releaseAssets=resolve),rendered=[]
async function api(path,method='GET',body){const r=await fetch(base+path,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)});const text=await r.text();assert.ok(r.ok,path+': '+text.slice(0,250));return text?JSON.parse(text):null}
const server=createServer(async(req,res)=>{
 try{
  let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw),check=body.messages.some(m=>m.content?.includes('AGENTOPS_CONNECTION_CHECK'))
  const call=(name,args)=>({id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}})
  for(const message of body.messages.filter(m=>m.role==='tool')){try{const result=JSON.parse(message.content);if(result.artifact?.originalName===presentationName)deckId=result.artifact.objectId;if(result.designReview)review=result.designReview;if(result.artifact?.originalName?.startsWith('幻灯片-')&&!rendered.includes(result.artifact.objectId))rendered.push(result.artifact.objectId)}catch{}}
  let calls
  if(check)calls=[call('finish_task',{answer:'OK'})]
  else{
   await assetReady
   const slides=[
    {layout:'cover',title:'AgentOps\n把想法交付为成果',subtitle:'一个可执行、可追踪的智能工作台',imageArtifactId:cover,imageFit:'contain',notes:'界面素材：AgentOps自身的工具工作台截图。'},
    {layout:'metric',title:'用合适的版式表达观点',metric:'12',label:'原生可编辑版式',body:'重点数字、对比、流程、时间线与图文。\n根据内容选择表达方式。',caption:'布局来自当前生成器的模板目录。',notes:'指标口径：PresentationLayoutRenderer支持12种布局；不是生产性能或客户业务数据。'},
    {layout:'process',title:'从任务到可交付文件',steps:[{title:'整理证据',body:'核对输入、来源与数据口径'},{title:'组织页面',body:'每页一个主要观点，匹配版式'},{title:'检查交付',body:'检查版面，交付文件'}],notes:'此页说明产品工作流；结构与规则检查不等于视觉模型审阅。'},
    {layout:'image_split',title:'成果可以直接查看',body:'先查看，再交付\n逐页预览实际PPT\n保留可编辑内容',imageArtifactId:preview,imageFit:'contain',caption:'截图展示项目已有的PPT逐页预览功能。',notes:'界面素材：AgentOps自身PPT预览界面截图。'}
   ]
   const steps=[()=>[call('presentation_templates',{})],()=>[call('presentation_create',{title:presentationName.replace(/\.pptx$/,''),deckJson:JSON.stringify({theme:'tech',slides})})],()=>[call('presentation_inspect',{artifactId:deckId})],()=>[1,2,3,4].map(page=>call('presentation_render',{artifactId:deckId,page:String(page)})),()=>[call('finish_task',{answer:'4页科技产品风样张已生成，包含封面、重点数字、流程与图文页。可查看和下载PPTX。'})]]
   assert.ok(stage<steps.length,'unexpected model repeat');calls=steps[stage]();stage++
  }
  res.writeHead(200,{'Content-Type':'text/event-stream'});res.write('data: '+JSON.stringify({choices:[{delta:{tool_calls:calls.map((c,index)=>({...c,index}))}}]})+'\n\n');res.end('data: '+JSON.stringify({choices:[{delta:{},finish_reason:'tool_calls'}],usage:{prompt_tokens:40,completion_tokens:30}})+'\n\ndata: [DONE]\n\n')
 }catch(error){console.error(error.message);res.writeHead(500);res.end('{}')}
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
let run,passed=false
try{
 await api('/memory-settings','PUT',{enabled:false})
 await api('/model-settings/profiles','POST',{name:'样张隔离验证',url:'http://127.0.0.1:'+server.address().port+'/v1',apiKey:'fixture',model:'sample-fixture',activate:true})
 const session=await api('/sessions','POST',{title:'4页科技产品风样张'})
 run=await api('/sessions/'+session.sessionId+'/runs','POST',{message:'使用已有项目截图生成4页科技产品风样张：封面、重点数字、流程、图文。无需联网。',mode:'build',contractRequirements:{allowedTools:['presentation_templates','presentation_create','presentation_inspect','presentation_render']}})
 async function upload(name){const data=new FormData();data.append('file',new Blob([readFileSync(new URL(name,runtime))],{type:'image/png'}),name);const r=await fetch(base+'/file-tools/runs/'+run.runId+'/upload',{method:'POST',headers:{'X-Tenant-Id':tenant,'X-User-Id':user},body:data});assert.ok(r.ok);return(await r.json()).artifact.objectId}
 cover=await upload('工具工作台界面.png');preview=await upload('PPT逐页预览界面.png');releaseAssets()
 const end=Date.now()+90000;let state
 do{state=await api('/runs/'+run.runId);if(['succeeded','failed','paused','waiting_user','waiting_approval','budget_exceeded'].includes(state.status))break;await new Promise(resolve=>setTimeout(resolve,200))}while(Date.now()<end)
 assert.equal(state.status,'succeeded',JSON.stringify(state));assert.ok(deckId);assert.equal(rendered.length,4)
 assert.equal((await api('/operations/artifacts/'+deckId+'/preview')).slideCount,4)
 assert.equal(review.visionReviewed,false);assert.deepEqual(review.warnings,[])
 const files=(await api('/artifacts/by-run/'+run.runId)).artifacts
 for(const file of files){const r=await fetch(base+'/artifacts/'+file.objectId+'/download',{headers});assert.ok(r.ok);writeFileSync(new URL(encodeURIComponent(file.name),runtime),Buffer.from(await r.arrayBuffer()))}
 const result={passed:true,tenant,user,sessionId:session.sessionId,runId:run.runId,presentationId:deckId,presentationName,actualPages:4,previews:4,designReview:review,fixtureModel:true,localAssets:true}
 writeFileSync(new URL('validation.json',runtime),JSON.stringify(result,null,2));passed=true;console.log(JSON.stringify({passed:true,pages:4,previews:4,designWarnings:0,presentationId:deckId}))
}finally{releaseAssets?.();if(run&&!passed)await api('/runs/'+run.runId+'/cancel','POST',{}).catch(()=>{});server.closeAllConnections();await new Promise(resolve=>server.close(resolve))}
