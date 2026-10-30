import {createServer} from 'node:http'
import {randomUUID} from 'node:crypto'
import {execFileSync} from 'node:child_process'
import {writeFileSync} from 'node:fs'
import assert from 'node:assert/strict'
const base='http://127.0.0.1:8080/api',headers={'X-Tenant-Id':randomUUID(),'X-User-Id':randomUUID(),'Content-Type':'application/json'}
const delay=ms=>new Promise(r=>setTimeout(r,ms))
const script="const f=require('fs');console.log('UID='+process.getuid());f.writeFileSync('/tmp/qa.txt','ok');console.log('SCRATCH_OK');try{f.writeFileSync('/sandbox-write-test','blocked');throw Error('root was writable')}catch(e){if(!['EROFS','EACCES'].includes(e.code))throw e;console.log('ROOT_WRITE_BLOCKED')}if(f.existsSync('/var/run/docker.sock')||f.existsSync('/mnt/c/Users')||f.existsSync('/project'))throw Error('host mounted');console.log('NO_HOST_MOUNTS');require('net').connect({host:'1.1.1.1',port:443,timeout:500}).on('error',()=>{console.log('NETWORK_BLOCKED')}).on('connect',()=>{throw Error('network allowed')}).on('timeout',function(){this.destroy();console.log('NETWORK_BLOCKED')})"
const fixture=createServer(async(req,res)=>{let raw='';for await(const c of req)raw+=c;const b=JSON.parse(raw);let message={content:'[]'},finish='stop';if(b.tools){const goal=b.messages.find(m=>m.role==='system').content.match(/当前任务目标：([^\n]*)/)?.[1],out=b.messages.filter(m=>m.role==='tool');let name='finish_task',args={answer:'隔离验证完成'};if(goal==='SANDBOX_QA'&&out.length<2){name='sandbox_run';args={command:out.length===0?"node -e "+("'"+script.replaceAll("'", "'\"'\"'")+"'"):"test ! -e /tmp/qa.txt && printf 'FRESH_CONTAINER_OK\\n'",timeoutSeconds:'30'}}else if(goal==='SANDBOX_CANCEL'&&!out.length){name='sandbox_run';args={command:'echo STARTED; sleep 120',timeoutSeconds:'120'}}message={content:null,tool_calls:[{id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}}]};finish='tool_calls'}res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({choices:[{finish_reason:finish,message}],usage:{prompt_tokens:40,completion_tokens:20}}))})
await new Promise(r=>fixture.listen(0,'127.0.0.1',r))
async function api(p,m='GET',v){const r=await fetch(base+p,{method:m,headers,...(v!==undefined?{body:JSON.stringify(v)}:{})});const text=await r.text();assert.ok(r.ok,p+':'+r.status+' '+text.slice(0,300));return text?JSON.parse(text):null}
async function task(goal){const s=await api('/sessions','POST',{title:goal}),r=await api('/sessions/'+s.sessionId+'/runs','POST',{message:goal});return {...r,sessionId:s.sessionId}}
async function wait(run,status){for(let i=0;i<500;i++){const s=await api('/runs/'+run.runId);if(s.status===status)return s;assert.ok(!['failed','timed_out','budget_exceeded','waiting_reconcile'].includes(s.status),JSON.stringify(s));await delay(200)}throw Error('timeout '+status)}
const ssh=command=>execFileSync('ssh',['-p','2222','-i','D:/VM/scripts/vm_key','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=8','zww@127.0.0.1',command],{encoding:'utf8',timeout:15000}).trim()
try{
 await api('/memory-settings','PUT',{enabled:false});await api('/model-settings','PUT',{url:'http://127.0.0.1:'+fixture.address().port+'/v1',apiKey:'fixture',model:'sandbox-fixture',clearKey:false})
 const run=await task('SANDBOX_QA');await wait(run,'waiting_approval');const a=(await api('/runs/'+run.runId+'/approvals')).approvals[0];assert.equal(a.scope,'task_sandbox');await api('/approvals/'+a.approvalId+'/decision','POST',{decision:'approved'});await wait(run,'succeeded')
 const results=(await api('/sessions/'+run.sessionId+'/messages')).messages.filter(m=>m.role==='tool').map(m=>m.content).join('\n')
 for(const marker of ['UID=65534','SCRATCH_OK','ROOT_WRITE_BLOCKED','NO_HOST_MOUNTS','NETWORK_BLOCKED','FRESH_CONTAINER_OK'])assert.ok(results.includes(marker),'Missing '+marker+' in '+results)
 assert.equal((await api('/operations/runs/'+run.runId+'/permissions')).permissions[0].scope,'sandbox')
 assert.equal((await api('/capabilities')).tools.find(t=>t.name==='command_run').enabled,false)
 console.log('PASS real VM Docker: non-root, read-only root, writable temporary scratch, no host mounts, blocked network, fresh container each call, single task sandbox grant without host terminal permission')
 const cancel=await task('SANDBOX_CANCEL');await wait(cancel,'waiting_approval');const b=(await api('/runs/'+cancel.runId+'/approvals')).approvals[0];await api('/approvals/'+b.approvalId+'/decision','POST',{decision:'approved'})
 let active='';for(let i=0;i<25;i++){active=ssh('docker ps --filter name=agentops- --format "{{.Names}}"');if(active)break;await delay(200)}assert.ok(active)
 await api('/runs/'+cancel.runId+'/cancel','POST',{});await wait(cancel,'cancelled')
 for(let i=0;i<25;i++){if(!ssh('docker ps --filter name=agentops- --format "{{.Names}}"'))break;await delay(200)}assert.equal(ssh('docker ps --filter name=agentops- --format "{{.Names}}"'),'')
 console.log('PASS cancellation terminates the server-side sandbox container rather than only closing SSH')
 writeFileSync('agent-server/.runtime/operations-sandbox-validation.json',JSON.stringify({passed:true,runId:run.runId,cancelRunId:cancel.runId,image:JSON.parse(ssh("docker image inspect node:22-bookworm-slim --format '{{json .RepoDigests}}'"))},null,2))
}finally{fixture.close()}
