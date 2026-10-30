import {createServer} from 'node:http'
import {randomUUID} from 'node:crypto'
import {mkdtempSync,writeFileSync,readFileSync} from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
const base=process.env.AGENT_TEST_BASE||'http://127.0.0.1:18080/api',tenant=randomUUID(),user=randomUUID()
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const directory=mkdtempSync(path.resolve('agent-server/.runtime/operations-project-'));writeFileSync(path.join(directory,'note.txt'),'original\n')
const delay=ms=>new Promise(r=>setTimeout(r,ms));let connectionId='',origin='',calls=0
const plan=[{id:'read',title:'读取项目并准备修改',status:'doing'},{id:'review',title:'用户审阅后应用修改',status:'pending'}]
const fixture=createServer(async(req,res)=>{
 let raw='';for await(const chunk of req)raw+=chunk
 if(req.url==='/page'){res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});res.end('<!doctype html><h1>Browser QA</h1><p id="dynamic"></p><script>document.querySelector("p").textContent="DYNAMIC_OK"</script>');return}
 const b=raw?JSON.parse(raw):{}
 if(req.url==='/mcp'){
  if(req.method==='DELETE'){res.writeHead(204);res.end();return}
  assert.equal(req.headers.authorization,'Bearer fixture-secret')
  let result={};if(b.method==='initialize'){result={protocolVersion:'2025-06-18',capabilities:{tools:{}},serverInfo:{name:'qa',version:'1'}};res.setHeader('Mcp-Session-Id','qa-session')}
  else if(b.method==='notifications/initialized'){res.writeHead(202);res.end();return}
  else if(b.method==='tools/list')result={tools:[{name:'echo',description:'Return a value',inputSchema:{type:'object',properties:{text:{type:'string'}},required:['text']}}]}
  else if(b.method==='tools/call'){calls++;assert.equal(b.params.name,'echo');assert.equal(b.params.arguments.text,'42');result={content:[{type:'text',text:'MCP_RESULT_42'}],isError:false}}
  if(b.method==='tools/list'){res.writeHead(200,{'Content-Type':'text/event-stream'});res.end('event: message\ndata: '+JSON.stringify({jsonrpc:'2.0',id:b.id,result})+'\n\n')}
  else{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({jsonrpc:'2.0',id:b.id,result}))}return
 }
 let message={content:'[]'},finish='stop'
 if(b.tools){
  const system=b.messages.find(m=>m.role==='system')?.content||'',goal=system.match(/当前任务目标：([^\n]*)/)?.[1]||'',out=b.messages.filter(m=>m.role==='tool');let name='finish_task',args={answer:'验证完成。'}
  if(goal==='OPS_PLAN'){
   if(out.length===0){name='plan_update';args={revision:'0',stepsJson:JSON.stringify(plan)}}
   else if(out.length===1){name='project_read';args={path:'note.txt'}}
   else if(out.length===2){name='project_propose_batch';args={filesJson:JSON.stringify([{path:'note.txt',content:'updated\n',expectedSha256:JSON.parse(out[1].content).sha256}])}}
   else if(!b.messages.some(m=>m.role==='user'&&m.content==='继续')){name='ask_user';args={question:'请在任务面板核对并应用预览，再继续。',options:['已应用，继续','暂不修改']}}
  }else if(goal==='OPS_MCP'){
   if(out.length===0){name='mcp_connections';args={}}
   else if(out.length===1){name='mcp_tools';args={connectionId}}
   else if(out.length===2){name='mcp_call';args={connectionId,toolName:'echo',argumentsJson:'{"text":"42"}'}}
  }else if(goal==='OPS_EXPORT'){
   const formats=['docx','pdf','xlsx'];if(out.length<3){name='report_export';args={format:formats[out.length],title:'功能验证报告',content:out.length===2?'[["项目","数量"],["已完成",12]]':'中文正式文件验证。\n这是实际产物。'}}
  }else if(goal==='OPS_BROWSER'&&!out.length){name='browser_open';args={url:origin+'/page'}}
  else if(goal==='OPS_KNOWLEDGE'&&!out.length){name='knowledge_search';args={query:'费用上限'}}
  message={content:null,tool_calls:[{id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}}]};finish='tool_calls'
 }
 res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({choices:[{finish_reason:finish,message}],usage:{prompt_tokens:60,completion_tokens:20}}))
})
await new Promise(r=>fixture.listen(0,'127.0.0.1',r));origin='http://127.0.0.1:'+fixture.address().port
async function api(route,method='GET',body,status=200,as=user){const reply=await fetch(base+route,{method,headers:{...headers,'X-User-Id':as},...(body!==undefined?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(90000)});const text=await reply.text();assert.equal(reply.status,status,route+': '+text.slice(0,800));return text?JSON.parse(text):null}
async function task(goal,projectId=null){const s=await api('/sessions','POST',{title:goal,projectId},201),r=await api('/sessions/'+s.sessionId+'/runs','POST',{message:goal},201);return {...r,sessionId:s.sessionId}}
async function wait(run,status){for(let i=0;i<500;i++){const result=await api('/runs/'+run.runId);if(result.status===status)return result;assert.ok(!['failed','timed_out','budget_exceeded','waiting_reconcile'].includes(result.status),JSON.stringify(result));await delay(150)}throw Error('Timeout waiting '+status)}
try{
 await api('/memory-settings','PUT',{enabled:false})
 await api('/model-settings','PUT',{url:origin+'/v1',apiKey:'fixture',model:'operations-fixture',clearKey:false})
 const project=await api('/projects','POST',{directory});await api(`/projects/${project.id}/preview-origin`,'PUT',{origin})
 const run=await task('OPS_PLAN',project.id),waiting=await wait(run,'waiting_user')
 let current=await api(`/operations/runs/${run.runId}/plan`);assert.equal(current.revision,1);assert.equal(current.steps.length,2)
 current.steps[1].title='核对差异并应用';await api(`/operations/runs/${run.runId}/plan`,'PUT',current)
 await api(`/operations/runs/${run.runId}/plan`,'PUT',current,409)
 assert.equal(readFileSync(path.join(directory,'note.txt'),'utf8'),'original\n')
 const batch=(await api(`/operations/runs/${run.runId}/batches`)).batches[0];assert.ok(JSON.parse(batch.files)[0].previewDiff.includes('- original'))
 await api(`/operations/runs/${run.runId}/batches/${batch.id}/apply`,'POST',{})
 assert.equal(readFileSync(path.join(directory,'note.txt'),'utf8'),'updated\n');await api(`/operations/runs/${run.runId}/batches/${batch.id}/apply`,'POST',{},409)
 await api(`/sessions/${run.sessionId}/runs/${run.runId}/supplement`,'POST',{questionId:waiting.questionId,answer:'继续'});await wait(run,'succeeded')
 await api(`/operations/runs/${run.runId}/plan`,'GET',undefined,404,randomUUID())
 console.log('PASS real backend plan persistence/edit/revision checks, preview-before-write, batch apply exactly once and owner isolation')
 connectionId=(await api('/operations/mcp','POST',{name:'Fixture MCP',url:origin+'/mcp',apiKey:'fixture-secret',enabled:true})).id
 assert.equal((await api(`/operations/mcp/${connectionId}/test`,'POST',{})).tools[0].name,'echo')
 const publicConnections=JSON.stringify(await api('/operations/mcp'));assert.ok(!publicConnections.includes('fixture-secret'));assert.ok(!publicConnections.includes('key_cipher'))
 await api(`/operations/mcp/${connectionId}/test`,'POST',{},404,randomUUID())
 const mcp=await task('OPS_MCP');await wait(mcp,'waiting_approval');assert.equal(calls,0)
 const approval=(await api(`/runs/${mcp.runId}/approvals`)).approvals[0];assert.equal(approval.tool,'mcp_call');await api(`/approvals/${approval.approvalId}/decision`,'POST',{decision:'approved'});await wait(mcp,'succeeded');assert.equal(calls,1)
 await api(`/operations/mcp/${connectionId}`,'PUT',{enabled:false});await api(`/operations/mcp/${connectionId}/test`,'POST',{},404)
 console.log('PASS MCP initialization/session/JSON+SSE discovery, approved real remote call, encrypted-key boundary, disablement and owner isolation')
 const report=await task('OPS_EXPORT');await wait(report,'succeeded');const artifacts=(await api(`/artifacts/by-run/${report.runId}`)).artifacts;assert.equal(artifacts.length,3)
 for(const a of artifacts){const preview=await api(`/operations/artifacts/${a.objectId}/preview`);assert.ok(['text','pdf','table'].includes(preview.type));const file=await fetch(base+`/artifacts/${a.objectId}/download`,{headers});assert.equal(file.status,200);const bytes=Buffer.from(await file.arrayBuffer());assert.ok(bytes.subarray(0,4).toString()==='%PDF'||bytes.subarray(0,2).toString()==='PK');await api(`/operations/artifacts/${a.objectId}/preview`,'GET',undefined,404,randomUUID())}
 console.log('PASS actual Word/PDF/Excel tool-generated artifacts, previews, downloads, format signatures and access control')
 const browser=await task('OPS_BROWSER',project.id);await wait(browser,'waiting_approval');const network=(await api(`/runs/${browser.runId}/approvals`)).approvals[0];await api(`/approvals/${network.approvalId}/decision`,'POST',{decision:'approved'});await wait(browser,'succeeded')
 assert.equal((await api(`/operations/runs/${browser.runId}/permissions`)).permissions[0].scope,'network');await api(`/operations/runs/${browser.runId}/permissions/network`,'DELETE');assert.equal((await api(`/operations/runs/${browser.runId}/permissions`)).permissions.length,0)
 console.log('PASS actual browser network authorization visible and revocable; browser context closes on revoke')
 await api('/memories','POST',{memoryKey:'qa.preference',kind:'preference',content:'优先使用中文'})
 await api('/memories','POST',{memoryKey:'qa.preference',kind:'preference',content:'优先使用简体中文'})
 const memory=(await api('/operations/memories')).memories.find(m=>m.memory_key==='qa.preference');const history=await api(`/operations/memories/${memory.id}/history`);assert.ok(history.history.some(h=>h.content==='优先使用中文'))
 await api(`/operations/memories/${memory.id}`,'PUT',{expire:true});assert.ok((await api('/operations/memories')).memories.find(m=>m.id===memory.id).expired)
 await api(`/operations/memories/${memory.id}`,'PUT',{content:'用户确认后的中文偏好',expire:false});assert.equal((await api('/operations/memories')).memories.find(m=>m.id===memory.id).expired,false)
 assert.equal((await api(`/operations/memories/${memory.id}/history`,'GET',undefined,200,randomUUID())).history.length,0)
 await api('/knowledge/documents','POST',{title:'来源追踪制度',content:'费用上限：每日住宿不得超过600元。报销必须提交发票。',ownerScope:'user'},201)
 const knowledge=await task('OPS_KNOWLEDGE');await wait(knowledge,'succeeded');assert.ok((await api(`/operations/runs/${knowledge.runId}/sources`)).knowledge.some(k=>k.content.includes('来源追踪制度')))
 console.log('PASS memory revision history, expire/resolve/owner isolation and actual RAG retrieval provenance')
 await api('/operations/usage','PUT',{price:12.34,reminderTokens:1});const usage=await api('/operations/usage');assert.ok(usage.tasks.some(r=>r.model_calls>0&&r.tool_calls>0&&r.estimatedCost>0&&r.reminderReached))
 const schedule=(await api('/operations/schedules','POST',{name:'QA 定时报告',prompt:'SCHEDULE_QA',intervalMinutes:5,nextAt:new Date(Date.now()-1000).toISOString(),projectId:project.id})).id
 const scheduleRow=(await api('/operations/schedules')).schedules.find(s=>s.id===schedule);let scheduledRun
 for(let i=0;i<250;i++){const runs=(await api(`/sessions/${scheduleRow.session_id}/runs`)).runs;scheduledRun=runs[0];if(scheduledRun?.status==='succeeded')break;await delay(200)}assert.equal(scheduledRun?.status,'succeeded')
 await api(`/operations/schedules/${schedule}`,'PUT',{enabled:false});assert.equal((await api('/operations/schedules')).schedules.find(s=>s.id===schedule).enabled,false)
 let notices=[];for(let i=0;i<100;i++){notices=(await api('/operations/notifications')).notifications;if(notices.some(n=>n.title.includes('QA 定时报告')&&n.title.includes('succeeded')))break;await delay(200)}assert.ok(notices.some(n=>n.title.includes('QA 定时报告')));assert.ok(notices.some(n=>n.title.includes('Token提醒')))
 const notice=notices[0];await api(`/operations/notifications/${notice.id}/read`,'POST',{});assert.ok((await api('/operations/notifications')).notifications.find(n=>n.id===notice.id).read_at)
 console.log('PASS real usage counters/cost estimate/reminders, persisted scheduled task launches and notification/read/pause controls')
 writeFileSync(path.resolve('agent-server/.runtime/operations-integration.json'),JSON.stringify({tenant,user,base,projectId:project.id,sessionId:run.sessionId,runId:run.runId,reportSessionId:report.sessionId,reportRunId:report.runId,connectionId,scheduleId:schedule,completed:true},null,2))
}finally{fixture.close()}
