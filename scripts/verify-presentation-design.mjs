import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {randomUUID} from 'node:crypto'
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs'
const tenant=randomUUID(),user=randomUUID(),headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'},base='http://127.0.0.1:8080/api'
const sample=process.argv.includes('--sample'),presentationName=sample?'科技产品风-4页样张.pptx':'人工智能对生活影响-科技产品风.pptx'
const runtime=new URL('../agent-server/.runtime/'+(sample?'ppt-design-sample/':'ppt-design/'),import.meta.url);mkdirSync(runtime,{recursive:true})
let stage=0,cover,education,deckId,searchResults=[],coverSource,educationSource,designReview;const previewIds=[],seen=[]
const fullStoryboard=JSON.parse(readFileSync(new URL('../docs/examples/ppt-tech-storyboard.json',import.meta.url),'utf8'))
const storyboard=sample?{theme:fullStoryboard.theme,slides:[0,2,7,4].map(i=>fullStoryboard.slides[i])}:fullStoryboard,pageCount=storyboard.slides.length,imagePage=sample?3:4
async function api(path,method='GET',body){const r=await fetch(base+path,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(110000)});const text=await r.text();assert.ok(r.ok,path+': '+text.slice(0,500));return text?JSON.parse(text):null}
const choose=()=>searchResults.find(i=>i.width>=1000&&i.height>=600)||searchResults[0]
const server=createServer(async(req,res)=>{
 try{
  let text='';for await(const part of req)text+=part;const body=JSON.parse(text),check=body.messages.some(m=>m.content?.includes('AGENTOPS_CONNECTION_CHECK'))
  if(!body.tools){res.end(JSON.stringify({choices:[{finish_reason:'stop',message:{content:'[]'}}]}));return}
  for(const message of body.messages.filter(m=>m.role==='tool')){
   try{const result=JSON.parse(message.content)
    if(result.images)searchResults=result.images
    if(result.artifact?.originalName==='科技封面配图.png'){cover=result.artifact.objectId;coverSource=result.source}
    if(result.artifact?.originalName==='学习场景配图.png'){education=result.artifact.objectId;educationSource=result.source}
    if(result.artifact?.originalName===presentationName)deckId=result.artifact.objectId
    if(result.artifact?.originalName?.startsWith('幻灯片-')&&!previewIds.includes(result.artifact.objectId))previewIds.push(result.artifact.objectId)
    if(result.designReview)designReview=result.designReview
   }catch{}
  }
  const call=(name,args)=>({id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}})
  let calls
  if(check)calls=[call('finish_task',{answer:'OK'})]
  else{
   const steps=[
    ()=>[call('image_search',{query:'microchip'})],
    ()=>{assert.ok(choose()?.id,'image search did not return a usable cover');return[call('image_download',{imageId:choose().id,title:'科技封面配图'})]},
    ()=>[call('image_search',{query:'classroom'})],
    ()=>{assert.ok(choose()?.id,'image search did not return a usable education picture');return[call('image_download',{imageId:choose().id,title:'学习场景配图'})]},
    ()=>[call('presentation_templates',{})],
    ()=>{assert.ok(cover&&education,'real image download did not complete');const deck=structuredClone(storyboard);deck.slides[0].imageArtifactId=cover;deck.slides[imagePage].imageArtifactId=education;
     for(const [index,source] of [[0,coverSource],[imagePage,educationSource]])deck.slides[index].notes+='\n配图：'+source.creator+'；来源：'+source.foreign_landing_url+'；许可：'+source.license+' '+source.license_url+'。配图为场景示意。'
     return[call('presentation_create',{title:presentationName.replace(/\.pptx$/,''),deckJson:JSON.stringify(deck)})]},
    ()=>[call('presentation_inspect',{artifactId:deckId})],
    ()=>Array.from({length:pageCount},(_,i)=>call('presentation_render',{artifactId:deckId,page:String(i+1)})),
    ()=>[call('finish_task',{answer:'科技产品风文稿已生成，完整'+pageCount+'页，文本、表格和图表可编辑。素材来源见讲稿，页面已渲染供查看。'})]
   ];assert.ok(stage<steps.length,'unexpected model repeat');calls=steps[stage]();stage++;seen.push(...calls.map(c=>c.function.name));console.log('stage',stage,calls.map(c=>c.function.name).join(','))
  }
  res.writeHead(200,{'Content-Type':'text/event-stream'});res.write('data: '+JSON.stringify({choices:[{delta:{tool_calls:calls.map((c,index)=>({...c,index}))}}]})+'\n\n');res.end('data: '+JSON.stringify({choices:[{delta:{},finish_reason:'tool_calls'}],usage:{prompt_tokens:40,completion_tokens:30}})+'\n\ndata: [DONE]\n\n')
 }catch(e){console.error('Fixture:',e.message);res.writeHead(500);res.end('{}')}
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
let run,passed=false
try{
 await api('/memory-settings','PUT',{enabled:false})
 await api('/model-settings/profiles','POST',{name:'设计验收隔离模型',url:'http://127.0.0.1:'+server.address().port+'/v1',apiKey:'fixture',model:'design-fixture',activate:true})
 const session=await api('/sessions','POST',{title:'科技产品风PPT实际验收'})
 run=await api('/sessions/'+session.sessionId+'/runs','POST',{message:'生成'+pageCount+'页科技产品风PPT，联网搜索配图，保留来源和许可，检查并渲染全部页面。',mode:'build',contractRequirements:{allowedTools:['image_search','image_download','presentation_templates','presentation_create','presentation_inspect','presentation_render']}})
 writeFileSync(new URL('pending.json',runtime),JSON.stringify({tenant,user,sessionId:session.sessionId,runId:run.runId},null,2))
 let state,approvals=0;const end=Date.now()+240000
 while(Date.now()<end){state=await api('/runs/'+run.runId)
  if(state.status==='waiting_approval'){const pending=await api('/runs/'+run.runId+'/approvals');for(const item of pending.approvals){assert.equal(item.scope,'task_network');await api('/approvals/'+item.approvalId+'/decision','POST',{decision:'approved'});approvals++}}
  if(['succeeded','failed','paused','cancelled','waiting_user','budget_exceeded','timed_out'].includes(state.status))break
  await new Promise(resolve=>setTimeout(resolve,200))
 }
 assert.equal(state.status,'succeeded',JSON.stringify(state));assert.equal(approvals,1,'all image calls must share one network grant');assert.ok(deckId);assert.equal(previewIds.length,pageCount)
 const metadata=await api('/operations/artifacts/'+deckId+'/preview');assert.equal(metadata.slideCount,pageCount);assert.equal(designReview.visionReviewed,false)
 const files=(await api('/artifacts/by-run/'+run.runId)).artifacts;for(const file of files){const r=await fetch(base+'/artifacts/'+file.objectId+'/download',{headers});assert.ok(r.ok);writeFileSync(new URL(encodeURIComponent(file.name),runtime),Buffer.from(await r.arrayBuffer()))}
 const result={passed:true,tenant,user,sessionId:session.sessionId,runId:run.runId,presentationId:deckId,presentationName,actualPages:pageCount,previews:pageCount,networkApprovals:approvals,designReview,toolCount:(await api('/capabilities')).tools.length,fixtureModel:true,realPublicImages:true,sources:[coverSource,educationSource],seen}
 writeFileSync(new URL('validation.json',runtime),JSON.stringify(result,null,2));passed=true;console.log(JSON.stringify({passed:true,presentationId:deckId,actualPages:pageCount,previews:pageCount,networkApprovals:approvals,designWarnings:designReview.warnings.length}))
}finally{if(run&&!passed)await api('/runs/'+run.runId+'/cancel','POST',{}).catch(()=>{});server.closeAllConnections();await new Promise(resolve=>server.close(resolve))}
