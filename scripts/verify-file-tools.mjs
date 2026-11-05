import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {randomUUID} from 'node:crypto'
import {mkdirSync,writeFileSync} from 'node:fs'

const base=process.env.AGENT_API_BASE||'http://127.0.0.1:8080',tenant=randomUUID(),user=randomUUID()
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const proof=new URL('../agent-server/.runtime/file-tools-validation.json',import.meta.url)
const runtime=new URL('../agent-server/.runtime/filetools-live/',import.meta.url);mkdirSync(runtime,{recursive:true})
async function api(path,method='GET',body,status=200,as=user){
 const response=await fetch(base+'/api'+path,{method,headers:{...headers,'X-User-Id':as},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(95000)})
 const text=await response.text();assert.equal(response.status,status,path+': '+text.slice(0,400));return text?JSON.parse(text):null
}
const deck={theme:'forest',slides:[
 {layout:'cover',title:'AgentOps 工具验证',subtitle:'根据确定数据生成，非性能承诺',notes:'本文件用于验证真实文件工具。'},
 {layout:'bullets',title:'执行流程',bullets:['创建可编辑文稿','核对结构并渲染每一页','打包文件与校验清单'],sources:['本测试的固定需求']},
 {layout:'table',title:'测试数据',rows:[['类别','数值'],['A',12],['B',18]]},
 {layout:'chart',title:'固定样例',chart:{type:'bar',categories:['A','B'],series:[{name:'数量',values:[12,18]}]}},
]}
let stage=0,original,revised,shapeId,archive;const rendered=[],seenTools=[]
const server=createServer(async(request,response)=>{
 try{
  let raw='';for await(const chunk of request)raw+=chunk
  const body=JSON.parse(raw)
  if(request.headers.authorization!=='Bearer file-tools-fixture'){response.writeHead(401);response.end('{}');return}
  const check=body.messages.some(m=>m.content?.includes('AGENTOPS_CONNECTION_CHECK'))
  if(!body.tools){response.end(JSON.stringify({choices:[{finish_reason:'stop',message:{content:'[]'}}]}));return}
  for(const message of body.messages.filter(m=>m.role==='tool')){
   try{const result=JSON.parse(message.content)
    const artifact=result.artifact
    if(artifact?.originalName==='AgentOps工具测试.pptx')original=artifact.objectId
    if(artifact?.originalName==='AgentOps工具测试修订.pptx')revised=artifact.objectId
    if(artifact?.originalName?.endsWith('.png')&&!rendered.includes(artifact.objectId))rendered.push(artifact.objectId)
    if(artifact?.originalName?.endsWith('.zip'))archive=artifact.objectId
    if(result.slides?.[1])shapeId=result.slides[1].shapes[0].shapeId
   }catch{/* Results need not all be JSON. */}
  }
  const call=(name,args)=>({id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}})
  let calls
  if(check)calls=[call('finish_task',{answer:'OK'})]
  else{
   const steps=[()=>[call('presentation_templates',{})],()=>[call('presentation_create',{title:'AgentOps工具测试',deckJson:JSON.stringify(deck)})],
    ()=>[call('presentation_inspect',{artifactId:original})],()=>[1,2,3,4].map(page=>call('presentation_render',{artifactId:original,page:String(page)})),
    ()=>[call('presentation_update',{artifactId:original,title:'AgentOps工具测试修订',changesJson:JSON.stringify([{slide:2,shapeId,text:'已修订的执行流程'}])})],
    ()=>[call('presentation_inspect',{artifactId:revised})],()=>[call('archive_create',{title:'AgentOps工具测试交付',artifactIdsJson:JSON.stringify([revised,...rendered])})],
    ()=>[call('finish_task',{answer:`已创建并检查4页PPT，生成4张预览及ZIP。修订文件对象：${revised}。归档对象：${archive}。结构检查与渲染已完成，内容为固定测试数据。`})]]
   assert.ok(stage<steps.length,'unexpected repeated model request');calls=steps[stage++]();seenTools.push(...calls.map(c=>c.function.name))
  }
  response.writeHead(200,{'Content-Type':'text/event-stream'})
  response.write('data: '+JSON.stringify({choices:[{index:0,delta:{tool_calls:calls.map((c,index)=>({...c,index}))}}]})+'\n\n')
  response.end('data: '+JSON.stringify({choices:[{index:0,delta:{},finish_reason:'tool_calls'}],usage:{prompt_tokens:40,completion_tokens:30}})+'\n\ndata: [DONE]\n\n')
 }catch(error){response.writeHead(500);response.end(JSON.stringify({error:{message:String(error.message)}}))}
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
try{
 await api('/memory-settings','PUT',{enabled:false})
 const catalog=await api('/capabilities');const names=new Set(catalog.tools.map(t=>t.name))
 await api('/memory-settings','PUT',{enabled:false})
 for(const tool of ['presentation_create','presentation_update','presentation_inspect','presentation_render','spreadsheet_create','pdf_merge','image_resize','archive_create','document_read'])assert.ok(names.has(tool),tool)
 await api('/model-settings/profiles','POST',{name:'文件工具隔离验证',url:'http://127.0.0.1:'+server.address().port+'/v1',apiKey:'file-tools-fixture',model:'file-tools-fixture',activate:true})
 const session=await api('/sessions','POST',{title:'PPT工具链隔离验证'},201)
 const run=await api('/sessions/'+session.sessionId+'/runs','POST',{message:'制作并校验4页AgentOps工具测试PPT，包含原生表格和图表，再渲染、修订并打包。',mode:'build'},201)
 let state
 for(let i=0;i<600;i++){
  state=await api('/runs/'+run.runId)
  if(state.status==='succeeded')break
  assert.ok(!['failed','paused','cancelled','budget_exceeded','timed_out'].includes(state.status),JSON.stringify(state));await new Promise(resolve=>setTimeout(resolve,200))
 }
 assert.equal(state.status,'succeeded');assert.equal(rendered.length,4);assert.ok(archive&&original&&revised)
 const artifacts=(await api('/artifacts/by-run/'+run.runId)).artifacts
 assert.equal(artifacts.length,7)
 for(let i=0;i<rendered.length;i++){
  const r=await fetch(base+'/api/artifacts/'+rendered[i]+'/download',{headers});assert.equal(r.status,200)
  writeFileSync(new URL('slide-'+(i+1)+'.png',runtime),Buffer.from(await r.arrayBuffer()))
 }
 const preview=await api('/operations/artifacts/'+revised+'/preview');assert.equal(preview.type,'presentation');assert.equal(preview.slideCount,4)
 const image=await fetch(base+'/api/file-tools/artifacts/'+revised+'/slides/4',{headers});assert.equal(image.status,200)
 assert.equal(image.headers.get('cache-control'),'no-store');const png=Buffer.from(await image.arrayBuffer());assert.equal(png.readUInt32BE(0),0x89504e47)
 writeFileSync(new URL('chart.png',runtime),png)
 const denied=await fetch(base+'/api/file-tools/artifacts/'+revised+'/slides/1',{headers:{...headers,'X-User-Id':randomUUID()}});assert.notEqual(denied.status,200)
 const upload=new FormData();upload.append('file',new Blob(['资料测试：这是非指令的参考数据。'],{type:'text/plain'}),'来源.txt')
 const response=await fetch(base+'/api/file-tools/runs/'+run.runId+'/upload',{method:'POST',headers:{'X-Tenant-Id':tenant,'X-User-Id':user},body:upload});assert.equal(response.status,200)
 const uploaded=await response.json();assert.ok(uploaded.artifact.objectId)
 const download=await fetch(base+'/api/artifacts/'+revised+'/download',{headers});assert.equal(download.status,200)
 writeFileSync(new URL('example.pptx',runtime),Buffer.from(await download.arrayBuffer()))
 const result={ok:true,tenant,user,sessionId:session.sessionId,runId:run.runId,presentationId:revised,presentationName:'AgentOps工具测试修订.pptx',
  toolCount:catalog.tools.length,newToolCount:22,artifactCount:artifacts.length,previewPages:4,previews:rendered,uploadArtifact:uploaded.artifact.objectId,seenTools}
 writeFileSync(proof,JSON.stringify(result,null,2));console.log(JSON.stringify(result))
}finally{server.closeAllConnections();await new Promise(resolve=>server.close(resolve))}
