import assert from 'node:assert/strict'
import{createServer}from'node:http'
import{randomUUID}from'node:crypto'
import{mkdtempSync,writeFileSync,readFileSync,mkdirSync,existsSync}from'node:fs'
import{execFileSync}from'node:child_process'
import path from'node:path'
const tenant=randomUUID(),user=randomUUID(),base='http://127.0.0.1:8080/api',headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const root=mkdtempSync(path.resolve('agent-server/.runtime/project-qa-')),first=path.join(root,'first'),second=path.join(root,'second');mkdirSync(first);mkdirSync(second)
writeFileSync(path.join(first,'notes.txt'),'before\n');writeFileSync(path.join(second,'notes.txt'),'SECOND_PROJECT\n')
execFileSync('git',['init','--template=','-q',first]);execFileSync('git',['-C',first,'config','core.autocrlf','false']);execFileSync('git',['-C',first,'add','notes.txt']);execFileSync('git',['-C',first,'-c','core.hooksPath=NUL','-c','user.name=AgentOps QA','-c','user.email=qa@example.invalid','commit','-qm','baseline'])
execFileSync('git',['-C',first,'config','filter.qa.clean',`node -e "require('fs').writeFileSync('filter-executed.txt','BAD');process.stdin.pipe(process.stdout)"`]);writeFileSync(path.join(first,'.gitattributes'),'notes.txt filter=qa\n');
let blockedHits=0;const blocked=createServer((q,r)=>{blockedHits++;r.end('PRIVATE_CANARY')});await new Promise(r=>blocked.listen(0,'127.0.0.1',r))
const website=createServer((req,res)=>{res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});res.end(`<!doctype html><title>AgentOps browser QA</title><div id="app">loading</div><script>setTimeout(()=>{app.innerHTML='<h1>Dynamic browser form</h1><label>Name <input placeholder="Your name" id="name"></label><button id="apply">Apply</button><p id="result"></p>';apply.onclick=()=>{result.textContent='Saved '+document.getElementById('name').value};fetch('http://127.0.0.1:${blocked.address().port}/probe').catch(()=>{});},80)</script>`)})
await new Promise(r=>website.listen(0,'127.0.0.1',r));const origin='http://127.0.0.1:'+website.address().port
const fixture=createServer(async(req,res)=>{
 let raw='';for await(const c of req)raw+=c;const body=JSON.parse(raw);let message={content:'[]'},finish='stop'
 if(body.tools){const goal=body.messages.find(m=>m.role==='user').content,results=body.messages.filter(m=>m.role==='tool');let name='finish_task',args={answer:'WORKBENCH_OK'}
  if(goal==='PROJECT_PATCH_QA'){
   if(!results.length){name='project_read';args={path:'notes.txt'}}
   else if(results.length===1){const old=JSON.parse(results[0].content);name='project_patch';args={path:'notes.txt',find:'before',content:'after',expectedSha256:old.sha256}}
   else if(results.length===2){name='project_git_diff';args={}}
  }else if(goal==='BROWSER_QA'){
   if(!results.length){name='browser_open';args={url:origin}}
   else if(results.length===1){const snapshot=JSON.parse(results[0].content);name='browser_fill';args={ref:snapshot.elements.find(e=>e.tag==='input').ref,text:'HELLO_BROWSER'}}
   else if(results.length===2){const snapshot=JSON.parse(results[1].content);name='browser_click';args={ref:snapshot.elements.find(e=>e.tag==='button').ref}}
   else if(results.length===3){name='browser_screenshot';args={}}
   else if(results.length===4){name='browser_close';args={}}
  }else if(goal==='COMMAND_PROJECT_QA'&&!results.length){name='command_run';args={command:'(Get-Location).Path; node --version'}}
  else if(goal==='LONG_COMMAND_QA'&&!results.length){name='command_run';args={command:"Start-Sleep -Seconds 21; Write-Output 'LONG_COMMAND_OK'",timeoutSeconds:'25'}}
  else if(goal==='CANCEL_COMMAND_QA'&&!results.length){name='command_run';args={command:"Set-Content -LiteralPath 'cancel-started.txt' -Value STARTED; Start-Sleep -Seconds 120; Write-Output 'CANCEL_NOT_EXPECTED'",timeoutSeconds:'300'}}
  message={content:null,tool_calls:[{id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}}]};finish='tool_calls'
 }
 res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({choices:[{finish_reason:finish,message}],usage:{prompt_tokens:40,completion_tokens:20}}))
})
await new Promise(r=>fixture.listen(0,'127.0.0.1',r));const delay=ms=>new Promise(r=>setTimeout(r,ms))
async function api(route,method='GET',body,status=200,as=user){const r=await fetch(base+route,{method,headers:{...headers,'X-User-Id':as},...(body?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(300000)});const text=await r.text();assert.equal(r.status,status,route+': '+text.slice(0,400));return text?JSON.parse(text):null}
async function task(message){const s=await api('/sessions','POST',{title:'[项目浏览器验证]',projectId:(await api('/projects')).selected?.id||null},201);const r=await api('/sessions/'+s.sessionId+'/runs','POST',{message},201);return {...r,sessionId:s.sessionId}}
async function wait(run,status){for(let i=0;i<1200;i++){const r=await api('/runs/'+run.runId);if(r.status===status)return r;assert.ok(!['failed','timed_out','budget_exceeded','waiting_reconcile'].includes(r.status),JSON.stringify(r));await delay(150)}throw Error('timeout '+status)}
async function pending(run){await wait(run,'waiting_approval');return (await api('/runs/'+run.runId+'/approvals')).approvals[0]}
async function approve(a){await api('/approvals/'+a.approvalId+'/decision','POST',{decision:'approved'})}
try{
 await api('/model-settings','PUT',{url:'http://127.0.0.1:'+fixture.address().port+'/v1',apiKey:'fixture',model:'workbench-fixture',clearKey:false})
 const a=await api('/projects','POST',{directory:first});const run=await task('PROJECT_PATCH_QA')
 await api('/projects','POST',{directory:second});await wait(run,'succeeded')
 assert.equal((await api('/runs/'+run.runId+'/approvals')).approvals.length,0)
 assert.equal(readFileSync(path.join(first,'notes.txt'),'utf8'),'after\n');assert.equal(readFileSync(path.join(second,'notes.txt'),'utf8'),'SECOND_PROJECT\n')
 assert.match((await api('/projects/'+a.id+'/git?diff=true')).text,/after/);assert.equal(existsSync(path.join(first,'filter-executed.txt')),false)
 const change=(await api('/projects/'+a.id+'/changes')).changes[0];await api(`/projects/${a.id}/changes/${change.id}/undo`,'POST',{})
 assert.equal(readFileSync(path.join(first,'notes.txt'),'utf8'),'before\n');assert.equal((await api('/projects/'+a.id+'/git?diff=true')).text,'')
 await api('/projects/'+a.id+'/changes','GET',null,404,randomUUID())
 console.log('PASS real Git project: session-pinned directory, no file approval, applied patch, exact undo and ownership isolation')
 await api('/projects/selection','PUT',{projectId:a.id});await api('/projects/'+a.id+'/preview-origin','PUT',{origin})
 const browser=await task('BROWSER_QA');assert.equal((await pending(browser)).scope,'task_network');await approve(await pending(browser));const interaction=await pending(browser);assert.equal(interaction.scope,'task_browser');await approve(interaction);await wait(browser,'succeeded')
 const outputs=(await api('/sessions/'+browser.sessionId+'/messages')).messages.filter(m=>m.role==='tool').map(m=>m.content);assert.ok(outputs.some(s=>s.includes('Saved HELLO_BROWSER')))
 assert.equal(blockedHits,0)
 const screenshot=(await api('/artifacts/by-run/'+browser.runId)).artifacts.find(a=>a.name.endsWith('.png'));assert.ok(screenshot)
 const image=await fetch(base+'/artifacts/'+screenshot.objectId+'/download',{headers});const bytes=Buffer.from(await image.arrayBuffer());assert.equal(bytes.subarray(0,8).toString('hex'),'89504e470d0a1a0a')
 console.log('PASS real browser executes dynamic JS, fills and clicks form with one interaction grant, blocks other loopback origins, saves actual PNG')
 const globalBefore=execFileSync('node',['--version'],{encoding:'utf8'}).trim();const installed=await api('/runtimes/node','POST',{major:22});assert.match(installed.version,/^v22\./);assert.match(installed.sha256,/^[a-f0-9]{64}$/i)
 assert.equal(execFileSync('node',['--version'],{encoding:'utf8'}).trim(),globalBefore)
 await api('/capabilities/tools/command_run','PUT',{enabled:true,askApproval:true});const command=await task('COMMAND_PROJECT_QA');await approve(await pending(command));await wait(command,'succeeded')
 const commandOutput=(await api('/sessions/'+command.sessionId+'/messages')).messages.filter(m=>m.role==='tool').map(m=>m.content).join('\n');assert.match(commandOutput,/v22\./);assert.ok(commandOutput.toLowerCase().includes(first.toLowerCase()))
 console.log('PASS official SHA256-verified private Node22 is used by project commands; system Node unchanged')
 const long=await task('LONG_COMMAND_QA');await approve(await pending(long));await wait(long,'succeeded');assert.match((await api('/sessions/'+long.sessionId+'/messages')).messages.filter(m=>m.role==='tool').map(m=>m.content).join(' '),/LONG_COMMAND_OK/)
 const cancel=await task('CANCEL_COMMAND_QA');await approve(await pending(cancel));for(let i=0;i<100&&!existsSync(path.join(first,'cancel-started.txt'));i++)await delay(100);assert.equal(existsSync(path.join(first,'cancel-started.txt')),true)
 const cancelledAt=Date.now();await api('/runs/'+cancel.runId+'/cancel','POST',{});await wait(cancel,'cancelled');assert.ok(Date.now()-cancelledAt<10000)
 assert.ok(!(await api('/sessions/'+cancel.sessionId+'/messages')).messages.filter(m=>m.role==='tool').some(m=>m.content.includes('CANCEL_NOT_EXPECTED')))
 console.log('PASS commands can exceed the old 20-second limit and a running 300-second command still stops promptly')
 const skill={name:'versioned-qa',description:'version test',keywords:'qa',content:'Requires Node 22+\nVERSION_ONE',sourceUrl:''};const saved=await api('/skills','POST',skill);await api('/skills','POST',{...skill,content:'Requires Node 22+\nVERSION_TWO'})
 const id=saved.id.replace('user:',''),versions=(await api('/skills/'+id+'/versions')).versions;assert.equal(versions.length,1);await api('/skills/'+id+'/rollback','POST',{versionId:versions[0].id})
 assert.match((await api('/capabilities')).skills.find(s=>s.id===saved.id).content,/VERSION_ONE/);assert.equal((await api('/skills/'+encodeURIComponent(saved.id)+'/dependencies')).status,'ready')
 await api('/skills/'+id+'/versions','GET',null,404,randomUUID())
 console.log('PASS skill dependency state, saved version history, rollback and version ownership')
}finally{await api('/sessions','DELETE');await api('/model-settings','DELETE');fixture.close();website.close();blocked.close()}
