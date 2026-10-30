import assert from'node:assert/strict'
import{randomUUID}from'node:crypto'
import{mkdtempSync,writeFileSync,readFileSync}from'node:fs'
import path from'node:path'
const base='http://127.0.0.1:8080/api',tenant=randomUUID(),user=randomUUID(),headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'},delay=ms=>new Promise(r=>setTimeout(r,ms))
async function api(p,m='GET',v){const r=await fetch(base+p,{method:m,headers,...(v===undefined?{}:{body:JSON.stringify(v)})});const text=await r.text();assert.ok(r.ok,p+':'+text.slice(0,300));return text?JSON.parse(text):null}
const directory=mkdtempSync(path.resolve('agent-server/.runtime/real-stream-')),source='// REAL_STREAM_CANARY\nexport const run = req => eval(req.body.code);\n';writeFileSync(path.join(directory,'app.js'),source)
await api('/memory-settings','PUT',{enabled:false});const project=await api('/projects','POST',{directory}),session=await api('/sessions','POST',{title:'真实AI流式阶段成果验证',projectId:project.id}),task=await api('/sessions/'+session.sessionId+'/runs','POST',{message:'只检查app.js的一个确定问题，给出行号。先用一句公开说明介绍正在做什么；读取一次后用task_notes保存带原文位置的阶段发现（revision按系统提示），最后用finish_task简短总结。不要重复读取，不要修改，不要联网或执行命令。'})
const events=[],controller=new AbortController();let firstState
const stream=await fetch(`${base}/runs/${task.runId}/events?lastEventSeq=0&tenantId=${tenant}&userId=${user}`,{signal:controller.signal});assert.ok(stream.ok)
const listening=(async()=>{let text='',decoder=new TextDecoder();try{for await(const chunk of stream.body){text+=decoder.decode(chunk,{stream:true}).replaceAll('\r\n','\n');let end;while((end=text.indexOf('\n\n'))>=0){const block=text.slice(0,end);text=text.slice(end+2);const payload=block.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5)).join('\n');if(!payload)continue;const event=JSON.parse(payload);events.push(event);if(!firstState&&event.type==='message.delta')firstState=await api('/runs/'+task.runId)}}}catch(e){if(!controller.signal.aborted)throw e}})()
try{
 let state;for(let i=0;i<600;i++){state=await api('/runs/'+task.runId);if(state.status==='succeeded')break;assert.ok(!['failed','waiting_user','waiting_approval','timed_out','budget_exceeded'].includes(state.status),JSON.stringify(state));await delay(150)}assert.equal(state.status,'succeeded')
 for(let i=0;i<20&&!events.some(e=>e.type==='run.succeeded');i++)await delay(50)
 assert.ok(firstState&&!['succeeded','failed','cancelled'].includes(firstState.status),'first public chunk must arrive while the task is running')
 const messages=(await api('/sessions/'+session.sessionId+'/messages?limit=100')).messages,candidate=messages.findLast(m=>m.kind==='candidate');assert.ok(candidate)
 const chunks=events.filter(e=>e.type==='message.delta'&&e.channel==='answer'&&e.attemptId===candidate.attemptId);assert.ok(chunks.length>1,'provider must deliver genuine answer chunks');assert.equal(chunks.map(e=>e.delta).join(''),candidate.content)
 assert.ok(messages.some(m=>m.kind==='commentary'&&m.content.trim()));const progress=await api('/operations/runs/'+task.runId+'/progress');assert.equal(progress.reads,1);assert.ok(progress.revision>0);assert.match(progress.notes,/app\.js/);assert.equal(readFileSync(path.join(directory,'app.js'),'utf8'),source)
 writeFileSync('agent-server/.runtime/stream-real-ai-validation.json',JSON.stringify({runId:task.runId,passed:true,firstChunkBeforeCompletion:true,answerChunks:chunks.length,reads:progress.reads,notesRevision:progress.revision,tokenUsed:state.tokenUsed,answerMatchesStored:true},null,2));console.log('PASS real default AI: public action before completion, multiple genuine answer chunks match the saved final answer, evidence-bearing findings persist and source remains unchanged')
}finally{controller.abort();await listening}
