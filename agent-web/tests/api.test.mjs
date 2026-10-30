import test from 'node:test'
import assert from 'node:assert/strict'
import { loadTypeScript } from './helpers/load-typescript.mjs'

test('concurrent reads share transport but not mutable response objects',async()=>{
  const {api}=await client();let calls=0,release
  globalThis.fetch=async()=>{calls++;return new Promise(resolve=>release=resolve)}
  const first=api.get('/shared'),second=api.get('/shared')
  assert.equal(calls,1)
  release(new Response('{"items":[{"value":1}]}'))
  const [a,b]=await Promise.all([first,second])
  a.items[0].value=99
  assert.equal(b.items[0].value,1)
  globalThis.fetch=async()=>{calls++;return new Response('{"fresh":true}')}
  await api.get('/shared')
  assert.equal(calls,2)
})
test('writes invalidate in-flight read sharing and workspace switches reject late old data',async()=>{
  const {api,saveIdentity}=await client();let release,reads=0
  globalThis.fetch=async(_url,options)=>{
    if(options.method!=='GET')return new Response('{}')
    reads++;return reads===1?new Promise(resolve=>release=resolve):new Response('{"value":"new"}')
  }
  const old=api.get('/state')
  await api.put('/state',{value:'new'})
  assert.deepEqual(await api.get('/state'),{value:'new'})
  release(new Response('{"value":"old"}'));await old
  assert.equal(reads,2)
  globalThis.fetch=async()=>new Promise(resolve=>release=resolve)
  const pending=api.get('/private')
  const rejected=assert.rejects(pending,/工作空间已切换/)
  saveIdentity('other-tenant','other-user')
  release(new Response('{"private":"must not reach the new workspace"}'))
  await rejected
})
test('failed GET results are not retained and all network requests have abort signals',async()=>{
  const {api}=await client();let calls=0
  globalThis.fetch=async(_url,options)=>{
    assert.ok(options.signal instanceof AbortSignal);calls++
    return new Response(calls===1?'{"message":"temporary"}':'{"ok":true}',{status:calls===1?503:200})
  }
  await assert.rejects(api.get('/retry'),/temporary/)
  assert.deepEqual(await api.get('/retry'),{ok:true})
  assert.equal(calls,2)
})
import { File } from 'node:buffer'

test('mode and explicit plan source are included without attaching unrelated plans',async()=>{
  const api=await client(),bodies=[]
  globalThis.fetch=async(_url,options)=>{bodies.push(JSON.parse(options.body));return new Response('{"runId":"new"}')}
  await api.createRun('s1','查询网页','chat-key','chat')
  await api.createRun('s1','按计划执行','build-key','build','source-plan')
  assert.equal(bodies[0].mode,'chat')
  assert.equal(Object.hasOwn(bodies[0],'planRunId'),false)
  assert.equal(bodies[1].mode,'build')
  assert.equal(bodies[1].planRunId,'source-plan')
})
test('project chats explicitly carry their project and ordinary chats carry null', async () => {
  const api = await client(), bodies=[]
  globalThis.fetch=async (_url,options)=>{bodies.push(JSON.parse(options.body));return new Response(JSON.stringify({sessionId:'new-chat'}))}
  await api.createSession('项目会话','project-a')
  await api.createSession('普通会话')
  assert.deepEqual(bodies,[{title:'项目会话',projectId:'project-a'},{title:'普通会话',projectId:null}])
})
async function client() {
  globalThis.localStorage = { getItem: () => null, setItem() {} }
  globalThis.window = {}
  return loadTypeScript(new URL('../src/api.ts', import.meta.url))
}

test('each intentional submission gets a fresh key, explicit retries preserve the key', async () => {
  const api = await client()
  const bodies = []
  globalThis.fetch = async (_url, options) => {
    bodies.push(JSON.parse(options.body))
    return new Response(JSON.stringify({ runId: 'r1' }))
  }
  await api.createRun('s1', '相同的问题')
  await api.createRun('s1', '相同的问题')
  await api.createRun('s1', '相同的问题', 'retry-key')
  await api.createRun('s1', '相同的问题', 'retry-key')
  assert.notEqual(bodies[0].requestKey, bodies[1].requestKey)
  assert.equal(bodies[2].requestKey, bodies[3].requestKey)
})

test('message pagination sends the cursor and preserves page metadata', async () => {
  const api = await client()
  let requested
  globalThis.fetch = async url => {
    requested = url
    return new Response(JSON.stringify({ messages: [{ seq: 2 }], nextBeforeSeq: 2, hasMore: true }))
  }
  const page = await api.listMessagePage('s1', 51)
  assert.match(requested, /beforeSeq=51/)
  assert.equal(page.nextBeforeSeq, 2)
  assert.equal(page.hasMore, true)
})

test('named deltas are deduplicated and terminal snapshots close the connection', async () => {
  const api = await client()
  let connection
  globalThis.EventSource = class {
    listeners = new Map()
    closed = false
    constructor(url) { this.url = url; connection = this }
    addEventListener(type, callback) { this.listeners.set(type, callback) }
    close() { this.closed = true }
    emit(type, data) { this.listeners.get(type)?.({ type, data: JSON.stringify(data) }) }
  }
  const received = [], done = []
  api.openRunEvents('r1', 4, e => received.push(e), reason => done.push(reason))
  connection.emit('message.delta', { runId: 'r1', seq: 5, delta: '你好' })
  connection.emit('message.delta', { runId: 'r1', seq: 5, delta: '你好' })
  connection.emit('heartbeat', { status: 'running' })
  connection.emit('run.snapshot', { runId: 'r1', seq: 5, status: 'cancelled' })
  assert.deepEqual(received.map(e => e.type), ['message.delta', 'run.snapshot'])
  assert.deepEqual(done, ['run.snapshot'])
  assert.equal(connection.closed, true)
})

test('a reset snapshot permits a corrected cursor while later replay duplicates are dropped', async () => {
  const api = await client()
  let connection
  globalThis.EventSource = class {
    listeners = new Map()
    constructor() { connection = this }
    addEventListener(type, callback) { this.listeners.set(type, callback) }
    close() {}
    emit(type, data) { this.listeners.get(type)?.({ type, data: JSON.stringify(data) }) }
  }
  const received = []
  api.openRunEvents('r1', 999, e => received.push(e), () => {})
  connection.emit('run.snapshot', { runId: 'r1', seq: 5, reset: true, status: 'running' })
  connection.emit('message.delta', { runId: 'r1', seq: 6, delta: '新内容' })
  assert.deepEqual(received.map(e => e.seq), [5, 6])
})

test('document upload sends multipart data and exposes progress without overriding its boundary', async () => {
  const api = await client()
  let sent, connection
  globalThis.XMLHttpRequest = class {
    upload = {}; headers = {}; status = 201; responseText = '{"chunkCount":2}'
    constructor() { connection = this }
    open(method, url) { this.method = method; this.url = url }
    setRequestHeader(name, value) { this.headers[name] = value }
    send(body) { sent = body; queueMicrotask(() => { this.upload.onprogress({ lengthComputable:true, loaded:10, total:10 }); this.onload() }) }
  }
  const progress = []
  const result = await api.api.upload('/api/knowledge/documents/upload', new File(['差旅标准'],'制度.txt'), p => progress.push(p))
  assert.equal(result.chunkCount,2)
  assert.equal(sent.get('file').name,'制度.txt')
  assert.equal(connection.headers['Content-Type'],undefined)
  assert.deepEqual(progress,[100])
})
