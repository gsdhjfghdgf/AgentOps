import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {randomUUID} from 'node:crypto'
const tenant=randomUUID(),user=randomUUID(),base='http://127.0.0.1:8080/api'
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const source='https://github.com/Leonxlnx/taste-skill'
let preview,installed,loadedInSystem=false
const delay=ms=>new Promise(r=>setTimeout(r,ms))
const fixture=createServer(async(req,res)=>{
  let raw='';for await(const c of req)raw+=c;const body=JSON.parse(raw)
  let message={content:'[]'},finish='stop'
  if(body.tools){
    const goal=body.messages.find(m=>m.role==='user').content,results=body.messages.filter(m=>m.role==='tool')
    let name='finish_task',args={answer:'SKILL_QA_COMPLETE'}
    if(goal==='SKILL_INSTALL_QA'){
      if(!results.length){name='skill_preview';args={url:source}}
      else if(results.length===1){const p=JSON.parse(results[0].content);name='skill_install';args={url:p.url,revision:p.revision,sha256:p.sha256}}
      else if(results.length===2){const skill=JSON.parse(results[1].content);name='skill_list';args={query:skill.name}}
    } else if(goal==='SKILL_USE_QA') {
      if(!results.length){name='skill_use';args={name:installed.name}}
      else loadedInSystem=body.messages.some(m=>m.role==='system'&&m.content.includes('## 适用技能：'+installed.name)&&m.content.includes('技能资源位于专属工作区：'+installed.directory))
    }
    message={content:null,tool_calls:[{id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}}]};finish='tool_calls'
  }
  res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({choices:[{finish_reason:finish,message}],usage:{prompt_tokens:40,completion_tokens:20}}))
})
await new Promise(r=>fixture.listen(0,'127.0.0.1',r))
async function api(path,method='GET',body,as=user,status=200){const r=await fetch(base+path,{method,headers:{...headers,'X-User-Id':as},...(body?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(240000)});const text=await r.text();assert.equal(r.status,status,path+': '+text.slice(0,300));return text?JSON.parse(text):null}
async function task(message){const s=await api('/sessions','POST',{title:'[技能安装验证]'},user,201);const r=await api('/sessions/'+s.sessionId+'/runs','POST',{message},user,201);return {...r,sessionId:s.sessionId}}
async function wait(run,status){for(let i=0;i<1500;i++){const r=await api('/runs/'+run.runId);if(r.status===status)return r;assert.ok(!['failed','budget_exceeded','timed_out'].includes(r.status),JSON.stringify(r));await delay(150)}throw Error('task timeout '+status)}
async function approve(run){await wait(run,'waiting_approval');const a=(await api('/runs/'+run.runId+'/approvals')).approvals[0];await api('/approvals/'+a.approvalId+'/decision','POST',{decision:'approved'});return a}
try{
  await api('/model-settings','PUT',{url:'http://127.0.0.1:'+fixture.address().port+'/v1',apiKey:'fixture-key',model:'skill-install-fixture',clearKey:false})
  const run=await task('SKILL_INSTALL_QA')
  assert.equal((await approve(run)).scope,'task_network')
  await wait(run,'waiting_approval')
  const installApproval=(await api('/runs/'+run.runId+'/approvals')).approvals[0];assert.equal(installApproval.tool,'skill_install');assert.equal(installApproval.scope,'operation')
  assert.ok(!(await api('/capabilities')).skills.some(s=>s.name==='design-taste-frontend'))
  await api('/approvals/'+installApproval.approvalId+'/decision','POST',{decision:'approved'})
  await wait(run,'succeeded')
  const results=(await api('/sessions/'+run.sessionId+'/messages')).messages.filter(m=>m.role==='tool').map(m=>JSON.parse(m.content))
  preview=results[0];installed=results[1]
  assert.equal(installed.registered,true);assert.equal(installed.enabled,true);assert.equal(installed.scriptsExecuted,false)
  const catalog=await api('/capabilities');const skill=catalog.skills.find(s=>s.id===installed.id)
  assert.ok(skill&&skill.enabled&&skill.content.length>22000)
  const files=(await api('/workspace/files')).files
  assert.ok(files.some(f=>f.path===installed.directory+'/library/sites.json'&&f.bytes>256000))
  assert.ok(files.some(f=>f.path===installed.directory+'/scripts/lib/probe.mjs'))
  assert.equal((await api('/capabilities','GET',undefined,randomUUID())).skills.filter(s=>!s.builtin).length,0)
  console.log('PASS conversation installation requires preview/network and install approval, registers/enables real taste-skill and downloads full large/mjs resources')
  const second=await api('/skills/import-url','POST',{url:preview.url,revision:preview.revision,sha256:preview.sha256})
  assert.equal(second.id,installed.id);assert.equal(second.directory,installed.directory)
  assert.equal((await api('/workspace/files')).files.length,files.length)
  console.log('PASS repeated pinned install keeps one skill and one resource directory; other users remain isolated')
  const use=await task('SKILL_USE_QA');await wait(use,'succeeded');assert.equal(loadedInSystem,true)
  console.log('PASS skill_use loads full registered guidance into next actual model request system context')
}finally{await api('/sessions','DELETE');await api('/model-settings','DELETE');fixture.close()}
