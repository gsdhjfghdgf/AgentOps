import assert from'node:assert/strict'
import{randomUUID}from'node:crypto'
import{mkdtempSync,writeFileSync,readFileSync}from'node:fs'
import path from'node:path'
const base='http://127.0.0.1:8080/api',headers={'X-Tenant-Id':randomUUID(),'X-User-Id':randomUUID(),'Content-Type':'application/json'}
const dir=mkdtempSync(path.resolve('agent-server/.runtime/read-notes-real-ai-')),source="// AUDIT_CANARY_917\nexport function run(req) { return eval(req.body.code); }\n"
writeFileSync(path.join(dir,'app.js'),source)
async function api(p,m='GET',v){const r=await fetch(base+p,{method:m,headers,...(v!==undefined?{body:JSON.stringify(v)}:{})});const text=await r.text();assert.ok(r.ok,p+': '+r.status+' '+text.slice(0,300));return text?JSON.parse(text):null}
await api('/memory-settings','PUT',{enabled:false});const project=await api('/projects','POST',{directory:dir}),session=await api('/sessions','POST',{title:'真实AI阶段发现验证',projectId:project.id})
const task=await api('/sessions/'+session.sessionId+'/runs','POST',{message:'只审阅当前项目的app.js，指出一个可确认的问题并给出文件行号。读取一次后请用task_notes保存包含路径和证据的阶段发现，然后用finish_task简短给出结论。不要重复读取，不要改文件，不要联网，不运行命令，不要扩展检查范围。'})
let state;for(let i=0;i<900;i++){state=await api('/runs/'+task.runId);if(state.status==='succeeded')break;assert.ok(!['failed','timed_out','budget_exceeded','waiting_user','waiting_approval'].includes(state.status),JSON.stringify(state));await new Promise(r=>setTimeout(r,150))}
assert.equal(state.status,'succeeded');const progress=await api(`/operations/runs/${task.runId}/progress`);assert.equal(progress.reads,1);assert.equal(progress.unique_pages,1);assert.ok(progress.revision>0);assert.match(progress.notes,/app\.js/);assert.equal(readFileSync(path.join(dir,'app.js'),'utf8'),source)
writeFileSync('agent-server/.runtime/read-notes-real-ai.json',JSON.stringify({runId:task.runId,reads:progress.reads,notesRevision:progress.revision,tokenUsed:state.tokenUsed,passed:true},null,2))
console.log('PASS real default AI reads once, records evidence-bearing task notes, returns a review conclusion and leaves the project unchanged')
