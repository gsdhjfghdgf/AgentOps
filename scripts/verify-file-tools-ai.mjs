import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {writeFileSync} from 'node:fs'
const base='http://127.0.0.1:8080/api'
const headers={'X-Tenant-Id':'11111111-1111-1111-1111-111111111111','X-User-Id':'22222222-2222-2222-2222-222222222222','Content-Type':'application/json'}
async function api(path,method='GET',body){
 const r=await fetch(base+path,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)})
 const text=await r.text();assert.ok(r.ok,path+' HTTP '+r.status+': '+text.slice(0,180));return text?JSON.parse(text):null
}
const session=await api('/sessions','POST',{title:'[工具验收] 真实 AI 生成 PPT'})
const message='这是一次真实文件工具验收，不是长期偏好。请直接制作3页中文PPT，文件名为真实AI工具验收。第1页封面：文件工具验收；第2页原生表格：类别/数量，A/12，B/18；第3页结论：这些数字是测试样例，不是生产指标。不要联网，不要创建额外计划，也无需询问。按顺序使用已提供Schema的四种工具：presentation_templates确认格式，presentation_create生成，presentation_inspect检查实际3页且issues为空，presentation_render逐页生成3张PNG。不要再查找其他工具或做额外工作。3张预览完成后立即用finish_task返回简短结果，引用生成时已返回的PPT与PNG对象ID；无需再次查询文件或元数据。不要只给大纲，不要声称未查看的预览已人工验收。'
const task=await api('/sessions/'+session.sessionId+'/runs','POST',{message,mode:'build',requestKey:randomUUID(),
 contractRequirements:{allowedTools:['presentation_templates','presentation_create','presentation_inspect','presentation_render']}})
const start=Date.now();let state,passed=false
try{
 while(Date.now()-start<240000){
  state=await api('/runs/'+task.runId)
  if(['succeeded','failed','cancelled','paused','timed_out','budget_exceeded'].includes(state.status))break
  await new Promise(resolve=>setTimeout(resolve,1000))
 }
 if(!['succeeded','failed','cancelled','paused','timed_out','budget_exceeded'].includes(state?.status)){
  await api('/runs/'+task.runId+'/cancel','POST',{});throw Error('real model acceptance exceeded four-minute validation limit')
 }
 assert.equal(state.status,'succeeded','actual provider did not complete: '+state.reasonCode)
 const items=(await api('/artifacts/by-run/'+task.runId)).artifacts,ppt=items.find(f=>f.name.endsWith('.pptx'))
 assert.ok(ppt,'no real PPTX file');assert.equal(items.filter(f=>f.name.endsWith('.png')).length,3)
 const preview=await api('/operations/artifacts/'+ppt.objectId+'/preview');assert.equal(preview.slideCount,3)
 const messages=(await api('/sessions/'+session.sessionId+'/messages')).messages
 assert.ok(messages.some(m=>m.role==='tool'&&m.content.includes('"issueCount":0')))
 const result={passed:true,sessionId:session.sessionId,runId:task.runId,milliseconds:Date.now()-start,presentationId:ppt.objectId,previews:3,status:state.status}
 writeFileSync(new URL('../agent-server/.runtime/file-tools-real-ai.json',import.meta.url),JSON.stringify(result,null,2));passed=true
 console.log(JSON.stringify(result))
}finally{
 if(!passed)writeFileSync(new URL('../agent-server/.runtime/file-tools-real-ai.json',import.meta.url),JSON.stringify({passed:false,sessionId:session.sessionId,runId:task.runId,status:state?.status,reasonCode:state?.reasonCode,milliseconds:Date.now()-start},null,2))
}
