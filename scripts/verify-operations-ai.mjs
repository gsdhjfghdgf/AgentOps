import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {mkdtempSync,writeFileSync,readFileSync} from 'node:fs'
import path from 'node:path'
const base=process.env.AGENT_TEST_BASE||'http://127.0.0.1:18080/api',headers={'X-Tenant-Id':randomUUID(),'X-User-Id':randomUUID(),'Content-Type':'application/json'}
const directory=mkdtempSync(path.resolve('agent-server/.runtime/operations-real-ai-'));writeFileSync(path.join(directory,'notes.txt'),'OPERATIONS_REAL_CANARY_728\n项目成本为128元。\n')
async function api(p,m='GET',v){const r=await fetch(base+p,{method:m,headers,...(v!==undefined?{body:JSON.stringify(v)}:{})});const text=await r.text();assert.ok(r.ok,p+': '+r.status+' '+text.slice(0,300));return text?JSON.parse(text):null}
await api('/memory-settings','PUT',{enabled:false})
const model=await api('/model-settings');assert.equal(model.custom,false)
const project=await api('/projects','POST',{directory}),session=await api('/sessions','POST',{title:'[真实AI任务计划与导出验证]',projectId:project.id})
const run=await api('/sessions/'+session.sessionId+'/runs','POST',{message:'请实际完成这个小任务：先用plan_update建立两步计划（读取原文、导出文件），然后读取当前项目notes.txt，将其中完整原文通过report_export生成Word文档（docx，标题“任务结果验证”）。完成各步后更新计划为done。不要修改项目文件，不运行命令，不访问互联网，不需要问我问题。最终简短说明生成文件。',requiredArtifactKinds:['output']})
let state
for(let i=0;i<1200;i++){state=await api('/runs/'+run.runId);if(state.status==='succeeded')break;assert.ok(!['failed','timed_out','budget_exceeded','waiting_approval','waiting_reconcile','waiting_user'].includes(state.status),JSON.stringify(state));await new Promise(r=>setTimeout(r,200))}
assert.equal(state.status,'succeeded');assert.equal(readFileSync(path.join(directory,'notes.txt'),'utf8'),'OPERATIONS_REAL_CANARY_728\n项目成本为128元。\n')
const files=(await api('/artifacts/by-run/'+run.runId)).artifacts,word=files.find(f=>f.name.endsWith('.docx'));assert.ok(word)
const preview=await api('/operations/artifacts/'+word.objectId+'/preview');assert.match(preview.content,/OPERATIONS_REAL_CANARY_728/);assert.match(preview.content,/128/)
const plan=await api('/operations/runs/'+run.runId+'/plan');assert.ok(plan.steps.length>=2);assert.ok(plan.steps.every(s=>s.status==='done'))
writeFileSync(path.resolve('agent-server/.runtime/operations-real-ai.json'),JSON.stringify({model:model.model,runId:run.runId,tokenUsed:state.tokenUsed,steps:plan.steps.length,artifact:word.name,passed:true},null,2))
console.log('PASS real default AI creates and completes visible plan, reads actual project text and exports a readable Word artifact with exact source content; project unchanged')
