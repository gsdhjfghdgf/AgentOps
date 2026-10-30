import assert from'node:assert/strict'
import{createServer}from'node:http'
import{randomUUID}from'node:crypto'
import{mkdtempSync,writeFileSync,readFileSync}from'node:fs'
import path from'node:path'
const directory=mkdtempSync(path.resolve('agent-server/.runtime/real-project-ai-'));writeFileSync(path.join(directory,'notes.txt'),'REAL_PROJECT_CANARY_42\n')
const server=createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});r.end('<!doctype html><title>Real AI browser QA</title><h1>Real AI preview</h1><p id="content">Loading</p><script>setTimeout(()=>document.getElementById("content").textContent="REAL_BROWSER_TEXT",50)</script>')})
await new Promise(r=>server.listen(0,'127.0.0.1',r));const origin='http://127.0.0.1:'+server.address().port
const headers={'X-Tenant-Id':randomUUID(),'X-User-Id':randomUUID(),'Content-Type':'application/json'},base='http://127.0.0.1:8080/api'
async function api(p,m='GET',v){const r=await fetch(base+p,{method:m,headers,...(v?{body:JSON.stringify(v)}:{})});const text=await r.text();assert.ok(r.ok,p+':'+r.status+' '+text.slice(0,200));return text?JSON.parse(text):null}
try{
 const project=await api('/projects','POST',{directory});await api('/projects/'+project.id+'/preview-origin','PUT',{origin})
 const s=await api('/sessions','POST',{title:'[真实AI项目与浏览器验证]',projectId:project.id});const run=await api('/sessions/'+s.sessionId+'/runs','POST',{message:'请读取当前项目notes.txt并报告原文；再用browser_open打开'+origin+'，报告页面动态生成的文字，并用browser_screenshot保存截图。不要修改任何文件，不执行命令，不访问其他网站。',requiredArtifactKinds:['output']})
 let completed=false
 for(let i=0;i<1500;i++){
  const state=await api('/runs/'+run.runId)
  if(state.status==='waiting_approval')for(const a of(await api('/runs/'+run.runId+'/approvals')).approvals){assert.equal(a.tool,'browser_open');assert.ok(a.arguments.url.startsWith(origin));await api('/approvals/'+a.approvalId+'/decision','POST',{decision:'approved'})}
  if(state.status==='succeeded'){completed=true;break}
  assert.ok(!['failed','timed_out','budget_exceeded','waiting_user','waiting_reconcile'].includes(state.status),JSON.stringify(state));await new Promise(r=>setTimeout(r,150))
 }
 assert.ok(completed,'real AI task timed out');assert.equal(readFileSync(path.join(directory,'notes.txt'),'utf8'),'REAL_PROJECT_CANARY_42\n')
 const messages=(await api('/sessions/'+s.sessionId+'/messages')).messages,answer=messages.filter(m=>m.role==='assistant').map(m=>m.content).join('\n')
 assert.match(answer,/REAL_PROJECT_CANARY_42/);assert.match(answer,/REAL_BROWSER_TEXT/)
 assert.ok((await api('/artifacts/by-run/'+run.runId)).artifacts.some(a=>a.name.endsWith('.png')))
 console.log('PASS real default AI reads selected project, operates real dynamic browser, reports actual text and produces required PNG evidence without modifying files')
}finally{await api('/sessions','DELETE');server.close()}
