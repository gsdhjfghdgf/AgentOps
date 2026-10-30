import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
const base='http://127.0.0.1:8080', tenant=randomUUID(), user=randomUUID(), other=randomUUID()
const headers={'X-Tenant-Id':tenant,'X-User-Id':user}
let passed=0
const delay=ms=>new Promise(r=>setTimeout(r,ms))
async function req(path,{method='GET',body,as=user,expected=200}={}){
  const response=await fetch(base+'/api'+path,{method,headers:{...headers,'X-User-Id':as,'Content-Type':'application/json'},
    ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)})
  const text=await response.text()
  assert.equal(response.status,expected,path+': '+text.slice(0,400))
  return text?JSON.parse(text):null
}
async function poll(get,accept){
  for(let i=0;i<100;i++){const value=await get();if(accept(value))return value;await delay(200)}
  throw Error('Timed out waiting for background state')
}
function pass(name){passed++;console.log('PASS '+name)}
async function upload(name,bytes,expected=201){
  const data=new FormData();data.append('file',new Blob([bytes]),name)
  const response=await fetch(base+'/api/knowledge/documents/upload',{method:'POST',headers,body:data,signal:AbortSignal.timeout(30000)})
  const result=await response.json()
  assert.equal(response.status,expected,name+': '+JSON.stringify(result))
  return result
}
async function talk(text){
  const session=await req('/sessions',{method:'POST',body:{title:'[记忆验证]'},expected:201})
  const run=await req('/sessions/'+session.sessionId+'/runs',{method:'POST',body:{message:text},expected:201})
  await poll(()=>req('/runs/'+run.runId),run=>run.status==='succeeded')
  return run.runId
}
const memory=async()=> (await req('/memories?limit=100')).memories
async function main(){
  await poll(()=>fetch(base+'/actuator/health').then(r=>r.json()).catch(()=>({})),health=>health.status==='UP')
  const doc=await upload('差旅制度.txt',new TextEncoder().encode('差旅住宿标准：每晚600元。出差报销需要有效发票。'))
  assert.ok(doc.chunkCount>0)
  for(const extension of ['pdf','docx']){
    const bytes=await readFile(new URL('../agent-server/target/test-fixtures/travel.'+extension,import.meta.url))
    const result=await upload('travel.'+extension,bytes)
    assert.ok(result.chunkCount>0)
  }
  pass('direct TXT, PDF and Word upload with parsing and indexing')
  const hits=await req('/knowledge/search',{method:'POST',body:{query:'住宿标准',limit:5}})
  assert.ok(hits.chunks.some(c=>c.content.includes('600元')))
  const listed=await req('/knowledge/documents')
  assert.equal(listed.documents.length,3)
  assert.ok(listed.documents.every(d=>d.chunkCount>0))
  pass('uploaded content is searchable and documents expose real chunk counts')
  await upload('bad.exe',new Uint8Array([1]),400)
  await upload('bad.pdf',new TextEncoder().encode('not PDF'),400)
  await upload('empty.txt',new Uint8Array(),400)
  assert.equal((await req('/knowledge/documents')).documents.length,3)
  pass('invalid uploads are rejected before creating documents')
  assert.equal((await req('/knowledge/documents',{as:other})).documents.length,0)
  await req('/knowledge/documents/'+doc.documentId,{method:'DELETE',as:other,expected:404})
  pass('document ownership enforced')

  await talk('以后请用中文回答。')
  const rows=await poll(memory,rows=>rows.some(m=>m.content.includes('中文')))
  const item=rows.find(m=>m.content.includes('中文'))
  assert.equal(item.automatic,true)
  assert.equal(item.authority,'inferred')
  assert.ok(item.evidence.includes('以后请用中文回答'))
  pass('completed conversation automatically creates memory with source evidence')
  await talk('以后请用中文回答。')
  await delay(2000)
  const duplicate=(await memory()).filter(m=>m.memoryKey===item.memoryKey)
  assert.equal(duplicate.length,1)
  assert.equal(duplicate[0].version,item.version)
  pass('repeated preferences do not create duplicate memories or revisions')

  await req('/memories',{method:'POST',body:{memoryKey:item.memoryKey,kind:item.kind,content:'用户要求简体中文且使用简短段落'}})
  await talk('以后请用英文回答。')
  await delay(2000)
  assert.equal((await memory()).find(m=>m.memoryKey===item.memoryKey).content,'用户要求简体中文且使用简短段落')
  pass('manual corrections take priority over automatic summaries')
  await req('/memories/'+encodeURIComponent(item.memoryKey),{method:'DELETE'})
  await talk('以后请用中文回答。')
  await delay(2000)
  assert.ok(!(await memory()).some(m=>m.memoryKey===item.memoryKey))
  pass('forgotten memory is not resurrected by later extraction')

  await req('/memory-settings',{method:'PUT',body:{enabled:false}})
  await talk('记住我的项目叫Cedar。')
  await delay(2000)
  assert.equal((await memory()).length,0)
  await req('/memory-settings',{method:'PUT',body:{enabled:true}})
  await talk('记住我的项目叫Cedar。')
  const enabled=await poll(memory,rows=>rows.some(m=>m.content.includes('Cedar')))
  assert.ok(enabled.some(m=>m.kind==='fact'))
  assert.equal((await req('/memories',{as:other})).memories.length,0)
  pass('automatic memory switch persists and user isolation holds')
  await req('/sessions',{method:'DELETE'})
  console.log(JSON.stringify({passed,isolatedTenant:tenant,mode:(await req('/memory-settings')).mode}))
}
main().catch(error=>{console.error(error.message);process.exitCode=1})
