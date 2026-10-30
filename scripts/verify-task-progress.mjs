import{createServer}from'node:http'
import{randomUUID}from'node:crypto'
import{mkdtempSync,writeFileSync,readFileSync}from'node:fs'
import path from'node:path'
import assert from'node:assert/strict'
const base='http://127.0.0.1:8080/api',tenant=randomUUID(),user=randomUUID(),headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const directory=mkdtempSync(path.resolve('agent-server/.runtime/read-progress-')),source='FULL_SOURCE_MARKER\n'+'a'.repeat(12900)+'\nend'
writeFileSync(path.join(directory,'source.txt'),source);writeFileSync(path.join(directory,'changing.txt'),'version one')
let compacted=false,freshSourcePreserved=false,savedNotesVisible=false
const server=createServer(async(req,res)=>{let raw='';for await(const chunk of req)raw+=chunk;const b=JSON.parse(raw);let message={content:'[]'},finish='stop';if(b.tools){
 const system=b.messages.find(m=>m.role==='system')?.content||'',goal=system.match(/当前任务目标：([^\n]*)/)?.[1],out=b.messages.filter(m=>m.role==='tool');let name='finish_task',args={answer:'已根据已有证据收尾。'}
 if(goal==='PROGRESS_LOOP'&&!b.messages.some(m=>m.role==='user'&&m.content==='整理已有发现并结束')){name='project_read';args={path:'source.txt'}}
 else if(goal==='PROGRESS_NOTES'){
  if(!out.length){name='project_read';args={path:'source.txt'}}
  else if(out.length===1){name='task_notes';args={revision:'0',content:'FINDING_CANARY：已核对 source.txt 第1行，剩余区间尚未读取。'}}
  else if(out.length===2){savedNotesVisible=system.includes('FINDING_CANARY');const previous=JSON.parse(out[0].content);compacted=!previous.content&&previous.sha256&&previous.path==='source.txt';name='project_read';args={path:'source.txt',offset:'6000'}}
  else if(out.length===3){freshSourcePreserved=JSON.parse(out[2].content).content==='a'.repeat(6000);name='task_notes';args={revision:'1',content:'FINDING_CANARY：已核对source.txt第1行及[6000,12000)区间，阶段结论已保留。'}}
 }else if(goal==='PROGRESS_CHANGE'&&out.length<2){if(out.length===1)writeFileSync(path.join(directory,'changing.txt'),'version two');name='project_read';args={path:'changing.txt'}}
 message={content:null,tool_calls:[{id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}}]};finish='tool_calls'
 }res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({choices:[{finish_reason:finish,message}],usage:{prompt_tokens:50,completion_tokens:20}}))})
await new Promise(r=>server.listen(0,'127.0.0.1',r))
const delay=ms=>new Promise(r=>setTimeout(r,ms))
async function api(p,m='GET',v,status=200,as=user){const r=await fetch(base+p,{method:m,headers:{...headers,'X-User-Id':as},...(v!==undefined?{body:JSON.stringify(v)}:{})});const text=await r.text();assert.equal(r.status,status,p+':'+text.slice(0,500));return text?JSON.parse(text):null}
async function task(goal,project){const s=await api('/sessions','POST',{title:goal,projectId:project},201),r=await api('/sessions/'+s.sessionId+'/runs','POST',{message:goal},201);return {...r,sessionId:s.sessionId}}
async function wait(run,status){for(let i=0;i<500;i++){const r=await api('/runs/'+run.runId);if(r.status===status)return r;assert.ok(!['failed','timed_out','budget_exceeded','waiting_reconcile'].includes(r.status),JSON.stringify(r));await delay(120)}throw Error('timeout '+status)}
try{
 await api('/memory-settings','PUT',{enabled:false});await api('/model-settings','PUT',{url:'http://127.0.0.1:'+server.address().port+'/v1',model:'progress-fixture',apiKey:'fixture',clearKey:false})
 const project=await api('/projects','POST',{directory}),loop=await task('PROGRESS_LOOP',project.id),question=await wait(loop,'waiting_user')
 assert.equal(question.questionOptions.length,2);assert.match(question.questionText,/已读过/)
 const loopProgress=await api(`/operations/runs/${loop.runId}/progress`);assert.equal(loopProgress.reads,13);assert.equal(loopProgress.unique_pages,1);assert.equal(loopProgress.duplicateStreak,12)
 const original=(await api(`/sessions/${loop.sessionId}/messages`)).messages.filter(m=>m.role==='tool');const first=JSON.parse(original[0].content);assert.equal(first.content.length,6000);assert.equal(first.eof,false);assert.equal(first.fromLine,1)
 await delay(1200);assert.equal((await api(`/operations/runs/${loop.runId}/progress`)).reads,13)
 await api(`/sessions/${loop.sessionId}/runs/${loop.runId}/supplement`,'POST',{questionId:question.questionId,answer:'整理已有发现并结束'});await wait(loop,'succeeded');assert.equal((await api(`/operations/runs/${loop.runId}/progress`)).duplicateStreak,0)
 console.log('PASS repeated unchanged reads pause before the next paid model call, offer inline choices, stop consuming while paused and resume the same task')
 const notes=await task('PROGRESS_NOTES',project.id);await wait(notes,'succeeded');assert.ok(compacted&&freshSourcePreserved&&savedNotesVisible)
 const progress=await api(`/operations/runs/${notes.runId}/progress`);assert.equal(progress.reads,2);assert.equal(progress.unique_pages,2);assert.equal(progress.revision,2);assert.match(progress.notes,/FINDING_CANARY/)
 assert.ok((await api(`/sessions/${notes.sessionId}/messages`)).messages.some(m=>m.role==='tool'&&m.content.includes('FULL_SOURCE_MARKER')))
 await api(`/operations/runs/${notes.runId}/progress`,'GET',undefined,404,randomUUID())
 console.log('PASS notes survive in system context, covered source is compacted only for future model input, newer source remains intact and full stored evidence is unchanged')
 const change=await task('PROGRESS_CHANGE',project.id);await wait(change,'succeeded');const changed=await api(`/operations/runs/${change.runId}/progress`);assert.equal(changed.unique_pages,2);assert.equal(changed.duplicateStreak,0)
 assert.equal(readFileSync(path.join(directory,'source.txt'),'utf8'),source)
 console.log('PASS a changed file SHA is new progress rather than a loop; source files are not modified by the guard')
 writeFileSync('agent-server/.runtime/read-progress-validation.json',JSON.stringify({tenant,user,base,loopRunId:loop.runId,loopSessionId:loop.sessionId,notesRunId:notes.runId,notesSessionId:notes.sessionId,passed:true},null,2))
}finally{server.close()}
