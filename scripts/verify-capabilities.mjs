import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
const tenant=randomUUID(),user=randomUUID(),foreign=randomUUID(),base='http://127.0.0.1:8080/api'
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'},runIds=[],requests=[],documents=[]
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));let passed=0
const fixture=createServer(async(req,res)=>{
  let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw)
  let result={content:'[]'},reason='stop'
  if(body.tools){
    const message=body.messages.filter(m=>m.role==='user').at(-1)?.content||'',task=JSON.parse(message.replace('CAP_TOOL_JSON:',''))
    requests.push({tag:task.tag,system:body.messages.filter(m=>m.role==='system').map(m=>m.content).join('\n')})
    const outputs=body.messages.filter(m=>m.role==='tool'),output=outputs.at(-1)?.content
    const step=task.tool==='sequence'?task.args.steps[outputs.length]:outputs.length?null:{tool:task.tool,args:task.args}
    const call=step?{name:step.tool,arguments:JSON.stringify(step.args)}:{name:'finish_task',arguments:JSON.stringify({answer:output||'done'})}
    result={content:null,tool_calls:[{id:randomUUID(),type:'function',function:call}]};reason='tool_calls'
  }
  res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({choices:[{finish_reason:reason,message:result}],usage:{prompt_tokens:40,completion_tokens:20}}))
})
await new Promise(resolve=>fixture.listen(0,'127.0.0.1',resolve))
async function req(path,method='GET',body,expected=200,as=user){
  const response=await fetch(base+path,{method,headers:{...headers,'X-User-Id':as},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(180000)})
  const text=await response.text();assert.equal(response.status,expected,path+': '+text.slice(0,400));return text?JSON.parse(text):null
}
async function poll(get,accept,seconds=60){const end=Date.now()+seconds*1000;while(Date.now()<end){const value=await get();if(accept(value))return value;await delay(200)}throw Error('operation timed out')}
function pass(text){passed++;console.log('PASS '+text)}
async function task(tool,args={},tag=''){
  const session=await req('/sessions','POST',{title:'[扩展能力验证]'},201)
  const run=await req('/sessions/'+session.sessionId+'/runs','POST',{message:'CAP_TOOL_JSON:'+JSON.stringify({tool,args,tag})},201)
  runIds.push(run.runId);return {runId:run.runId,sessionId:session.sessionId}
}
async function wait(run,status='succeeded'){
  return poll(()=>req('/runs/'+run.runId),r=>{if(r.status===status)return true;assert.ok(!['failed','budget_exceeded','timed_out'].includes(r.status),JSON.stringify(r));return false})
}
async function text(run){return (await req('/sessions/'+run.sessionId+'/messages')).messages.filter(m=>m.role==='assistant').map(m=>m.content).join('\n')}
async function approval(run,decision='approved'){
  await wait(run,'waiting_approval');const pending=await req('/runs/'+run.runId+'/approvals');assert.equal(pending.approvals.length,1)
  const item=pending.approvals[0];assert.ok(item.arguments&&item.description)
  await req('/approvals/'+item.approvalId+'/decision','POST',{decision});return item
}
try{
  await req('/model-settings','PUT',{url:'http://127.0.0.1:'+fixture.address().port+'/v1',apiKey:'fixture-capabilities-key',model:'capabilities-fixture',clearKey:false})
  const catalog=await req('/capabilities');assert.equal(catalog.tools.length,48);assert.ok(catalog.skills.length>=7)
  assert.equal(catalog.tools.find(t=>t.name==='command_run').enabled,false)
  pass('40 registered tools, seven builtin skills and commands disabled by default')

  const calculate=await task('calculator',{expression:'(1200+300)*0.8'});await wait(calculate);assert.match(await text(calculate),/1200/)
  const csv=await task('csv_analyze',{csv:'项目,金额\nA,12\nB,18\nC,\n',column:'金额'});await wait(csv);assert.match(await text(csv),/"sum":"30"/)
  const time=await task('current_time',{timezone:'UTC'});await wait(time);assert.match(await text(time),/\d{4}-\d\d-\d\dT/)
  pass('calculator and CSV analysis execute through the actual tool gateway')

  const document=await req('/knowledge/documents','POST',{title:'能力测试文档',content:'CAPABILITY_DOCUMENT_CONTENT，住宿上限600元。'},201);documents.push(document.documentId)
  const listing=await task('knowledge_list');await wait(listing);assert.match(await text(listing),/能力测试文档/)
  const reading=await task('knowledge_read',{documentId:document.documentId});await wait(reading);assert.match(await text(reading),/CAPABILITY_DOCUMENT_CONTENT/)
  pass('document listing and original-content reading use owned knowledge documents')

  await req('/capabilities/tools/calculator','PUT',{enabled:true,askApproval:true})
  const ask=await task('calculator',{expression:'7*8'});const askApproval=await approval(ask);assert.equal(askApproval.arguments.expression,'7*8');await wait(ask)
  await req('/capabilities/tools/calculator','PUT',{enabled:false,askApproval:true})
  const denied=await task('calculator',{expression:'2+3'});await wait(denied);assert.match(await text(denied),/拒绝/)
  assert.equal((await req('/capabilities','GET',undefined,200,foreign)).tools.find(t=>t.name==='calculator').enabled,true)
  await req('/capabilities/tools/calculator','PUT',{enabled:true,askApproval:false})
  pass('one-time approval, disabling tools, and per-user permission isolation')

  const rejected=await task('text_file_create',{filename:'reject.md',content:'MUST_NOT_BE_WRITTEN'})
  await wait(rejected,'waiting_approval');assert.equal((await req('/artifacts/by-run/'+rejected.runId)).artifacts.length,0)
  await approval(rejected,'rejected');await poll(()=>req('/runs/'+rejected.runId),r=>r.status==='failed')
  assert.equal((await req('/artifacts/by-run/'+rejected.runId)).artifacts.length,0)
  const created=await task('text_file_create',{filename:'approved.md',content:'# Actual approved output'})
  const details=await approval(created);assert.equal(details.arguments.content,'# Actual approved output');await wait(created)
  const artifacts=(await req('/artifacts/by-run/'+created.runId)).artifacts;assert.equal(artifacts.length,1)
  const downloaded=await fetch(base+'/artifacts/'+artifacts[0].objectId+'/download',{headers}).then(r=>r.text());assert.match(downloaded,/Actual approved output/)
  pass('full approval preview, rejected operation has no artifact, approved file is downloadable')

  const first=await req('/workspace/file','PUT',{path:'src/example.py',content:'print("first")'})
  await req('/workspace/file','PUT',{path:'../outside.txt',content:'bad'},400)
  await req('/workspace/file','PUT',{path:'src/example.py',content:'overwrite'},400)
  const edit=await task('workspace_write',{path:'src/example.py',content:'print("second")',expectedSha256:first.sha256})
  await wait(edit,'waiting_approval');assert.equal((await req('/workspace/file?path=src/example.py')).content,'print("first")')
  await approval(edit);await wait(edit);assert.equal((await req('/workspace/file?path=src/example.py')).content,'print("second")')
  assert.equal((await req('/workspace/files','GET',undefined,200,foreign)).files.length,0)
  pass('workspace ownership, path protection and approved hash-checked edits')

  const version=await req('/workspace/file?path=src/example.py')
  const sequence=await task('sequence',{steps:[{tool:'workspace_read',args:{path:'src/example.py'}},
    {tool:'workspace_write',args:{path:'src/example.py',content:'print("third")',expectedSha256:version.sha256}},
    {tool:'workspace_read',args:{path:'src/example.py'}}]})
  await approval(sequence);await wait(sequence);assert.match(await text(sequence),/third/)
  pass('same-run file read after modification returns fresh contents')

  const command={command:process.platform==='win32'?"Write-Output 'COMMAND_OK'":"printf COMMAND_OK"}
  const blockedCommand=await task('command_run',command);await wait(blockedCommand);assert.match(await text(blockedCommand),/拒绝/)
  await req('/capabilities/tools/command_run','PUT',{enabled:true,askApproval:false})
  assert.equal((await req('/capabilities')).tools.find(t=>t.name==='command_run').askApproval,true)
  const execution=await task('command_run',command);const request=await approval(execution);assert.equal(request.arguments.command,command.command)
  await wait(execution);assert.match(await text(execution),/COMMAND_OK/)
  pass('command execution requires explicit enabling and approval; cannot disable mandatory gate')

  const saveMemory=await task('memory_store',{key:'cap.fixture',kind:'preference',content:'用户明确保存的测试偏好'});await approval(saveMemory);await wait(saveMemory)
  assert.ok((await req('/memories?limit=100')).memories.some(m=>m.memoryKey==='cap.fixture'))
  const forgetMemory=await task('memory_forget',{key:'cap.fixture'});await approval(forgetMemory);await wait(forgetMemory)
  assert.ok(!(await req('/memories?limit=100')).memories.some(m=>m.memoryKey==='cap.fixture'))
  pass('explicit memory saving and forgetting both require approval')

  const custom=await req('/skills','POST',{name:'cap-test',description:'fixture',keywords:'caps-fixture-keyword',content:'SKILL_MARKER_42'})
  const withSkill=await task('calculator',{expression:'1+2'},'caps-fixture-keyword');await wait(withSkill)
  assert.ok(requests.filter(r=>r.tag==='caps-fixture-keyword').some(r=>r.system.includes('SKILL_MARKER_42')))
  await req('/skills/'+encodeURIComponent(custom.id)+'/enabled','PUT',{enabled:false})
  const withoutSkill=await task('calculator',{expression:'1+2'},'caps-fixture-keyword-disabled');await wait(withoutSkill)
  assert.ok(requests.filter(r=>r.tag==='caps-fixture-keyword-disabled').every(r=>!r.system.includes('SKILL_MARKER_42')))
  await req('/skills/'+custom.id.replace('user:',''),'DELETE',undefined,404,foreign)
  await req('/skills/'+custom.id.replace('user:',''),'DELETE')
  pass('custom skills are injected, disabled and deleted with owner checks')

  if(!process.argv.includes('--local')) {
  const page=await task('web_read',{url:'https://jsoup.org/'});await approval(page);await wait(page);assert.equal(JSON.parse(await text(page)).url,'https://jsoup.org/');assert.equal(JSON.parse(await text(page)).status,'ok')
  pass('approved public web page reading returns actual source text')
  const search=await task('web_search',{query:'jsoup official documentation'});await approval(search);await wait(search)
  const found=await text(search);assert.ok(found.includes('"url"'),found)
  pass('approved web search returns live public result links')

  const preview=await req('/skills/preview-url','POST',{url:'https://github.com/anthropics/skills/tree/main/skills/webapp-testing'})
  assert.ok(preview.revision&&preview.sha256&&preview.files.length)
  const installed=await req('/skills/import-url','POST',{url:preview.url,revision:preview.revision,sha256:preview.sha256})
  assert.ok(installed.downloadedFiles>0)
  assert.ok((await req('/capabilities')).skills.some(s=>s.id===installed.id&&s.enabled))
  assert.ok((await req('/workspace/files')).files.some(f=>f.path.startsWith(installed.directory)&&f.path.endsWith('SKILL.md')))
  await req('/skills/'+installed.id.replace('user:',''),'DELETE')
  pass('actual GitHub skill preview and pinned-version import with text attachments')
  }
  console.log(`PASS ${passed} expanded capability integration groups`)
}finally{
  for(const id of runIds)try{await req('/runs/'+id+'/cancel','POST',{})}catch{}
  for(const id of documents)try{await req('/knowledge/documents/'+id,'DELETE')}catch{}
  try{await req('/model-settings','DELETE');await req('/sessions','DELETE')}catch{}
  await new Promise(resolve=>fixture.close(resolve))
}
