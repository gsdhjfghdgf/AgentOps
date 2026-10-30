import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import * as Vue from 'vue'
import ts from 'typescript'

let moduleNo = 0
test('composer pause preserves text and sending the edited draft resumes the same task',async t=>{
  let status='running';const resumed=[],created=[]
  const fixture=await mount({
    listRuns:async()=>[run('r1',status)],getRun:async()=>run('r1',status),
    pauseRun:async()=>{status='paused'},
    resumeRun:async(...args)=>{resumed.push(args);status='running'},
    createRun:async(...args)=>created.push(args),
  })
  t.after(fixture.close)
  fixture.state.draft.value='普通输入框未发送的草稿'
  await fixture.state.onPause()
  assert.equal(fixture.state.runStatus.value,'paused')
  assert.equal(fixture.state.draft.value,'普通输入框未发送的草稿')
  fixture.state.draft.value='  先只检查登录模块  '
  await fixture.state.onResume()
  assert.equal(resumed[0][0],'r1')
  assert.equal(resumed[0][2],'先只检查登录模块')
  assert.equal(fixture.state.runId.value,'r1')
  assert.equal(fixture.state.draft.value,'')
  assert.equal(created.length,0)
})
test('a paused task stays idle and an uncertain resume keeps its text and request id',async t=>{
  let sse=0;const calls=[]
  const fixture=await mount({listRuns:async()=>[run('r1','paused')],getRun:async()=>run('r1','paused'),
    openRunEvents:()=>{sse++;return()=>{}},
    resumeRun:async(...args)=>{calls.push(args);throw Error('断线')},
  })
  t.after(fixture.close)
  assert.equal(sse,0)
  assert.equal(fixture.state.sessionBusy.value,true)
  fixture.state.draft.value='保留已完成的工作'
  await fixture.state.onResume();await fixture.state.onResume()
  assert.equal(calls[0][1],calls[1][1])
  assert.equal(fixture.state.draft.value,'保留已完成的工作')
  assert.equal(fixture.state.runStatus.value,'paused')
})
test('run synchronization uses message cursors and phase events do not trigger HTTP bursts',async t=>{
  let receive;const cursors=[]
  const fixture=await mount({
    listMessagePage:async()=>page([message(5,'已加载')]),
    listRuns:async()=>[run('r1','running')],
    getRunSnapshot:async(_id,cursor)=>{cursors.push(cursor);return{run:run('r1','running'),messages:[message(6,'新增')],artifacts:[],hasMore:false}},
    openRunEvents:(_id,_seq,callback)=>{receive=callback;return()=>{}},
  })
  t.after(fixture.close)
  assert.deepEqual(cursors,[5])
  for(let i=0;i<30;i++)receive({type:'run.phase',phase:'tools',seq:i+1})
  await fixture.flush()
  assert.deepEqual(cursors,[5])
  assert.deepEqual(fixture.state.messages.value.map(m=>m.seq),[5,6])
})
test('terminal synchronization drains every delta page before closing the task',async t=>{
  let calls=0
  const fixture=await mount({listRuns:async()=>[run('r1','running')],
    getRunSnapshot:async()=>{calls++;return{run:run('r1','succeeded'),messages:[message(calls,'结果'+calls)],artifacts:[],hasMore:calls===1}},
  })
  t.after(fixture.close)
  await fixture.flush()
  assert.equal(calls,2)
  assert.deepEqual(fixture.state.messages.value.map(m=>m.seq),[1,2])
  assert.equal(fixture.timers.size,0)
})
test('new conversations default to Chat and submissions carry the selected mode', async t => {
  const calls=[]
  const fixture=await mount({createRun:async(...args)=>{calls.push(args);return 'new'}})
  t.after(fixture.close)
  assert.equal(fixture.state.mode.value,'chat')
  fixture.state.draft.value='查询最新的网络资料'
  await fixture.state.send()
  assert.equal(calls[0][3],'chat')
  assert.equal(calls[0][4],undefined)
})

test('a completed plan hands off to Build in the same conversation and can be detached',async t=>{
  const calls=[],plan={...run('p1','succeeded'),mode:'plan'}
  const fixture=await mount({listRuns:async()=>[plan],getRun:async id=>id==='p1'?plan:{...run(id,'running'),mode:'build'},
    createRun:async(...args)=>{calls.push(args);return 'build1'}})
  t.after(fixture.close)
  assert.equal(fixture.state.mode.value,'plan')
  fixture.state.setMode('build')
  assert.equal(fixture.state.planRunId.value,'p1')
  fixture.state.setMode('chat')
  assert.equal(fixture.state.planRunId.value,'')
  fixture.state.preparePlan('p1')
  assert.equal(fixture.state.mode.value,'build')
  assert.equal(fixture.state.planRunId.value,'p1')
  await fixture.state.send()
  assert.equal(calls[0][0],'A')
  assert.equal(calls[0][3],'build')
  assert.equal(calls[0][4],'p1')
  assert.equal(fixture.state.runId.value,'build1')
})

test('mode changes cannot alter an active run and an uncertain retry changes key when mode changes',async t=>{
  const active=await mount({listRuns:async()=>[{...run('r1','running'),mode:'plan'}],getRun:async()=>({...run('r1','running'),mode:'plan'})})
  t.after(active.close)
  active.state.setMode('build')
  assert.equal(active.state.mode.value,'plan')
  assert.equal(active.state.planRunId.value,'')
  const calls=[],idle=await mount({createRun:async(...args)=>{calls.push(args);throw Error('网络中断')}})
  t.after(idle.close)
  idle.state.draft.value='同一段文字'
  await idle.state.send();await idle.state.send()
  assert.equal(calls[0][2],calls[1][2])
  idle.state.setMode('plan');await idle.state.send()
  assert.notEqual(calls[1][2],calls[2][2])
  assert.equal(calls[2][3],'plan')
})
const url = code => 'data:text/javascript;base64,' + Buffer.from(code).toString('base64')
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const page = (messages = [], hasMore = false) => ({ messages, hasMore, nextBeforeSeq: messages[0]?.seq ?? null })
const message = (seq, content) => ({ seq, content, role: 'user', createdAt: '2026-10-27T00:00:00Z' })

test('task grouping retains supplements and tools in one response, while new tasks remain separate', async () => {
  const source=await readFile(new URL('../src/composables/chatTurns.ts',import.meta.url),'utf8')
  const { groupTaskMessages }=await import(url(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText))
  const rows=[{...message(1,'初始任务'),runId:'r1'}, {...message(2,''),role:'assistant',runId:'r1'},
    {...message(3,'skills'),runId:'r1'}, {...message(4,'搜索结果'),role:'tool',runId:'r1'},
    {...message(5,'github中的'),runId:'r1'}, {...message(6,'下一项任务'),runId:'r2'}]
  const turns=groupTaskMessages(rows)
  assert.equal(turns.length,2)
  assert.equal(turns[0].request.content,'初始任务')
  assert.deepEqual(turns[0].steps.map(m=>m.seq),[2,3,4,5])
  assert.equal(turns[1].request.content,'下一项任务')
  assert.equal(groupTaskMessages(rows.slice(2,5))[0].steps.length,2)
})

test('inline question answer uses its own text and preserves composer draft and restored choices', async t => {
  const answers=[]
  const pending={...run('r1','waiting_user'),questionOptions:['本月','上月']}
  const fixture=await mount({listRuns:async()=>[pending],getRun:async()=>pending,supplement:async(...args)=>answers.push(args)})
  t.after(fixture.close)
  fixture.state.draft.value='底部未发送的草稿'
  assert.deepEqual([...fixture.state.questionOptions.value],['本月','上月'])
  await fixture.state.onSupplementText('  自定义季度  ')
  assert.deepEqual(answers,[['r1','A','q1','自定义季度']])
  assert.equal(fixture.state.draft.value,'底部未发送的草稿')
  fixture.state.onKeydown({key:'Enter',shiftKey:false,isComposing:false,preventDefault(){}})
  assert.equal(answers.length,1)
  assert.equal(fixture.timers.size,1)
})

test('inline question submission failure keeps pending question for retry', async t => {
  const fixture=await mount({listRuns:async()=>[run('r1','waiting_user')],supplement:async()=>{throw Error('暂时断线')}})
  t.after(fixture.close)
  await fixture.state.onSupplementText('本月')
  assert.equal(fixture.state.questionId.value,'q1')
  assert.equal(fixture.state.error.value,'暂时断线')
})

test('continuing a budget task retains run id and restores a single polling loop', async t => {
  let continued=false
  const fixture=await mount({listRuns:async()=>[run('r1','budget_exceeded')],
    getRun:async()=>run('r1',continued?'running':'budget_exceeded'),continueRun:async id=>{assert.equal(id,'r1');continued=true}})
  t.after(fixture.close)
  assert.equal(fixture.timers.size,0)
  await fixture.state.onContinue()
  assert.equal(fixture.state.runId.value,'r1')
  assert.equal(fixture.state.runStatus.value,'running')
  assert.equal(fixture.timers.size,1)
})

test('tool call and matching result form a single collapsible activity', async () => {
  const source=await readFile(new URL('../src/composables/taskTimeline.ts',import.meta.url),'utf8')
  const {taskTimeline}=await import(url(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText))
  const entries=taskTimeline([{...message(1,''),role:'assistant',toolCalls:[{id:'c1',name:'web_read',arguments:{url:'https://example.com'}}]},
    {...message(2,'page text'),role:'tool',toolCallId:'c1'},message(3,'补充要求'),{...message(4,'最终回答'),role:'assistant'}])
  assert.equal(entries.length,3)
  assert.equal(entries[0].activities.length,1)
  assert.equal(entries[0].activities[0].result,'page text')
  assert.equal(entries[1].type,'supplement')
  assert.equal(entries[2].type,'text')
})
const run = (id, status, session = 'A') => ({ runId: id, sessionId: session, status, phase: 'model', tokenUsed: 2,
  questionId: 'q1', questionText: '请补充', createdAt: '2026-10-27T00:00:00Z' })

async function mount(overrides = {}) {
  const timers = new Map()
  let timerId = 0
  globalThis.window = { setInterval: callback => { const id = ++timerId; timers.set(id, callback); return id },
    clearInterval: id => timers.delete(id) }
  const frames=new Map();let frameId=0
  globalThis.requestAnimationFrame = callback => {const id=++frameId;frames.set(id,callback);return id}
  globalThis.cancelAnimationFrame = id => frames.delete(id)
  const api = {
    listMessagePage: async () => page(), listRuns: async () => [], getRun: async id => run(id, 'waiting_user'),
    listApprovals: async () => [], listArtifacts: async () => [],
    openRunEvents: () => () => {}, createRun: async () => 'new', cancelRun: async () => {},
    pauseRun: async () => {}, resumeRun: async () => {},
    decideApproval: async () => {}, downloadArtifact: async () => {}, supplement: async () => {}, continueRun: async () => {}, steerRun: async () => ({ status:'received' }), ...overrides,
  }
  if (!api.getRunSnapshot) api.getRunSnapshot = async id => {
    const session = props.sessionId
    const run = await api.getRun(id)
    const [messages, artifacts] = await Promise.all([api.listMessagePage(session), api.listArtifacts(id)])
    return { run, messages: messages.messages, artifacts, hasMore: false }
  }
  const registry = '__chatTestApi' + moduleNo++
  globalThis[registry] = api
  const dependency = url(Object.keys(api).map(name =>
    `export const ${name} = (...args) => globalThis.${registry}.${name}(...args);`).join('\n'))
  const source = await readFile(new URL('../src/composables/useChatRun.ts', import.meta.url), 'utf8')
  const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
    .replace("'../api'", JSON.stringify(dependency))
    .replace("'vue'", JSON.stringify(new URL('../node_modules/vue/index.mjs', import.meta.url).href))
  const { useChatRun } = await import(url(compiled))
  const props = Vue.reactive({ sessionId: 'A' })
  let state
  const renderer = Vue.createRenderer({
    createElement: () => ({ children: [] }), insert: (child, parent) => { parent.children.push(child) },
    remove() {}, createText: () => ({}), createComment: () => ({}), setText() {}, setElementText() {},
    parentNode: () => null, nextSibling: () => null, patchProp() {},
  })
  const app = renderer.createApp({ setup() { state = useChatRun(props, () => {}); return () => null } })
  app.mount({ children: [] })
  async function flush() { for (let i = 0; i < 30; i++) await Promise.resolve();const queued=[...frames.values()];frames.clear();queued.forEach(f=>f(performance.now()));await Vue.nextTick() }
  await flush()
  return { props, state, api, timers, flush, close: () => { app.unmount(); delete globalThis[registry] } }
}

test('reopening a session restores waiting input and downloadable files', async t => {
  const fixture = await mount({ listRuns: async () => [run('r1', 'waiting_user')],
    listArtifacts: async () => [{ objectId: 'file1', name: '报告.md' }] })
  t.after(fixture.close)
  assert.equal(fixture.state.runId.value, 'r1')
  assert.equal(fixture.state.questionId.value, 'q1')
  assert.equal(fixture.state.sessionBusy.value, true)
  assert.deepEqual(fixture.state.artifacts.value, [{ id: 'file1', name: '报告.md' }])
})

test('a slow response from the previous session cannot overwrite the new session', async t => {
  const old = deferred()
  const fixture = await mount({ listMessagePage: session => session === 'A' ? old.promise : Promise.resolve(page([message(2, 'B 内容')])) })
  t.after(fixture.close)
  fixture.props.sessionId = 'B'
  await fixture.flush()
  old.resolve(page([message(1, 'A 内容')]))
  await fixture.flush()
  assert.deepEqual(fixture.state.messages.value.map(m => m.content), ['B 内容'])
})

test('older pages merge in order and remain available after task refresh', async t => {
  const fixture = await mount({ listMessagePage: async (_session, cursor) =>
    cursor ? page([message(1, '旧消息')]) : page([message(2, '新消息')], true) })
  t.after(fixture.close)
  await fixture.state.loadOlder()
  assert.deepEqual(fixture.state.messages.value.map(m => m.seq), [1, 2])
  assert.equal(fixture.state.hasMore.value, false)
})

test('retry after an uncertain submission reuses its request key', async t => {
  const keys = []
  const fixture = await mount({ createRun: async (_session, _text, key) => { keys.push(key); throw new Error('断线') } })
  t.after(fixture.close)
  fixture.state.draft.value = '生成报告'
  await fixture.state.send()
  await fixture.state.send()
  assert.equal(keys.length, 2)
  assert.equal(keys[0], keys[1])
})

test('supplement does not create extra polling timers', async t => {
  const fixture = await mount({ listRuns: async () => [run('r1', 'waiting_user')] })
  t.after(fixture.close)
  fixture.state.draft.value = '本月'
  await fixture.state.onSupplement()
  assert.equal(fixture.timers.size, 1)
  fixture.close()
  assert.equal(fixture.timers.size, 0)
})

test('accepted submission stays tracked when its first status read fails', async t => {
  const fixture = await mount({ getRun: async () => { throw new Error('暂时断线') } })
  t.after(fixture.close)
  fixture.state.draft.value = '生成报告'
  await fixture.state.send()
  assert.equal(fixture.state.runId.value, 'new')
  assert.equal(fixture.state.sessionBusy.value, true)
  assert.equal(fixture.timers.size, 1)
  fixture.api.getRun = async id => run(id, 'waiting_user')
  for (const callback of fixture.timers.values()) callback()
  await fixture.flush()
  assert.equal(fixture.state.questionId.value, 'q1')
  assert.equal(fixture.state.error.value, '')
})

test('terminal message fetch failure retains polling until authoritative text is recovered', async t => {
  let fail = false
  const fixture = await mount({ listRuns: async () => [run('r1', 'waiting_user')],
    listMessagePage: async () => { if (fail) throw new Error('消息读取失败'); return page() } })
  t.after(fixture.close)
  fixture.api.getRun = async id => run(id, 'succeeded')
  fail = true
  for (const callback of fixture.timers.values()) callback()
  await fixture.flush()
  assert.equal(fixture.timers.size, 1)
  fail = false
  for (const callback of fixture.timers.values()) callback()
  await fixture.flush()
  assert.equal(fixture.timers.size, 0)
})

test('running task accepts steering and retains its id when input is sent', async t => {
  const calls=[]
  const fixture=await mount({ listRuns:async()=>[run('r1','running')], getRun:async id=>run(id,'running'),
    steerRun:async (...args)=>{ calls.push(args); return {status:'received'} } })
  t.after(fixture.close)
  fixture.state.draft.value='改成中文表格'
  assert.equal(fixture.state.canSteer.value,true)
  await fixture.state.onSteer()
  assert.equal(calls[0][0],'r1')
  assert.equal(calls[0][1],'改成中文表格')
  assert.equal(fixture.state.runId.value,'r1')
  assert.equal(fixture.state.draft.value,'')
  assert.match(fixture.state.commandNotice.value,/已接收/)
})

test('failed steering retries preserve the command id', async t => {
  const ids=[]
  const fixture=await mount({ listRuns:async()=>[run('r1','running')], getRun:async id=>run(id,'running'),
    steerRun:async (_id,_text,key)=>{ids.push(key);throw new Error('断线')} })
  t.after(fixture.close)
  fixture.state.draft.value='保留统计口径'
  await fixture.state.onSteer(); await fixture.state.onSteer()
  assert.equal(ids.length,2)
  assert.equal(ids[0],ids[1])
})

test('stream channels show real chunks, reconcile saved messages and ignore late attempts',async t=>{
 let receive,rows=[]
 const fixture=await mount({listRuns:async()=>[run('r1','running')],getRun:async id=>run(id,'running'),listMessagePage:async()=>page(rows),openRunEvents:(_id,_seq,callback)=>{receive=callback;return()=>{}}})
 t.after(fixture.close)
 receive({type:'message.start',attemptId:'a1'});receive({type:'message.delta',attemptId:'a1',channel:'commentary',delta:'先核对原文。'});receive({type:'message.delta',attemptId:'a1',channel:'answer',delta:'已收到的完整分段😀'})
 await fixture.flush();assert.equal(fixture.state.streamText.value,'已收到的完整分段😀');assert.equal(fixture.state.commentaryText.value,'先核对原文。')
 rows=[{...message(1,'先核对原文。'),role:'assistant',kind:'commentary',attemptId:'a1'},{...message(2,'已收到的完整分段😀'),role:'assistant',kind:'candidate',attemptId:'a1'}]
 receive({type:'message.committed',attemptId:'a1'});await fixture.flush();assert.equal(fixture.state.streamText.value,'');assert.equal(fixture.state.commentaryText.value,'');assert.equal(fixture.state.messages.value.length,2)
 receive({type:'message.delta',attemptId:'a1',delta:'重复分段'});await fixture.flush();assert.equal(fixture.state.streamText.value,'')
 receive({type:'message.start',attemptId:'a2'});receive({type:'message.delta',attemptId:'a1',delta:'旧请求迟到'});receive({type:'message.delta',attemptId:'a2',delta:'新请求'});await fixture.flush();assert.equal(fixture.state.streamText.value,'新请求')
 receive({type:'message.interrupted',attemptId:'a2'});await fixture.flush();assert.equal(fixture.state.streamText.value,'');assert.match(fixture.state.streamNotice.value,/中断/)
 receive({type:'message.start',attemptId:'a3'});await fixture.flush();assert.equal(fixture.state.streamNotice.value,'')
 const oldReceive=receive;fixture.props.sessionId='B';await fixture.flush();oldReceive({type:'message.delta',attemptId:'a3',delta:'旧会话'});await fixture.flush();assert.equal(fixture.state.streamText.value,'')
})

test('only successfully saved task notes become public stage findings',async()=>{
 const source=await readFile(new URL('../src/composables/taskTimeline.ts',import.meta.url),'utf8')
 const {taskTimeline}=await import(url(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText))
 const entries=taskTimeline([{...message(1,'检查关键文件。'),role:'assistant',kind:'commentary',toolCalls:[{id:'ok',name:'task_notes',arguments:{content:'app.js:2 存在可核对的问题'}},{id:'bad',name:'task_notes',arguments:{content:'未保存的结论'}}]},
  {...message(2,'{"saved":true,"revision":1}'),role:'tool',toolCallId:'ok'},{...message(3,'{"error":"conflict"}'),role:'tool',toolCallId:'bad'}])
 assert.equal(entries[0].type,'commentary');assert.deepEqual(entries.filter(e=>e.type==='milestone').map(e=>e.content),['app.js:2 存在可核对的问题'])
})
test('retry events immediately show the reason and a recoverable pause survives reopening',async t=>{
 let receive,status='running',reason=''
 const fixture=await mount({listRuns:async()=>[run('r1',status)],getRun:async()=>({...run('r1',status),reasonCode:reason}),
   openRunEvents:(_id,_seq,callback)=>{receive=callback;return()=>{}}})
 t.after(fixture.close)
 receive({type:'message.start',attemptId:'failure'})
 receive({type:'message.interrupted',attemptId:'failure',reason:'model_error',code:'MODEL_HTTP_503'})
 assert.match(fixture.state.streamNotice.value,/HTTP 503/)
 status='retry_wait';reason='MODEL_HTTP_503'
 receive({type:'run.retry_wait',reason,failures:1,limit:3})
 await fixture.flush();assert.equal(fixture.state.runStatus.value,'retry_wait')
 assert.match(fixture.state.streamNotice.value,/第 1 次重试/)
 status='paused';reason='MODEL_RETRY_PAUSED_MODEL_HTTP_503'
 receive({type:'run.paused',reason});await fixture.flush()
 assert.match(fixture.state.streamNotice.value,/自动暂停/);assert.match(fixture.state.streamNotice.value,/HTTP 503/)
 assert.equal(fixture.state.runId.value,'r1');assert.equal(fixture.state.canSteer.value,false)
})

test('search sources preserve evidence metadata and reject executable links',async()=>{
 const source=await readFile(new URL('../src/composables/taskTimeline.ts',import.meta.url),'utf8')
 const {taskTimeline,extractWebSources}=await import(url(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText))
 const payload=JSON.stringify({query:'完整人名 最新消息',searchedAt:'2026-10-30T00:00:00Z',results:[{title:'待核验报道',url:'https://example.com/original',snippet:'原始摘要',dateLabel:'2026年10月29日'},{title:'恶意链接',url:'javascript:alert(1)'}],warnings:['部分搜索源不可用']})
 const entries=taskTimeline([{...message(1,''),role:'assistant',toolCalls:[{id:'s1',name:'web_search',arguments:{query:'原查询'}}]},{...message(2,payload),role:'tool',toolCallId:'s1'}])
 const sources=entries.find(e=>e.type==='sources').sources;assert.equal(sources.results.length,1);assert.equal(sources.results[0].domain,'example.com');assert.equal(sources.query,'完整人名 最新消息');assert.deepEqual(sources.warnings,['部分搜索源不可用'])
 const legacy=extractWebSources('[{"title":"旧结果","url":"https://example.org/"}]','旧查询');assert.equal(legacy.legacy,true);assert.match(legacy.warnings[0],/未执行/)
 assert.equal(extractWebSources('工具失败','查询'),null)
})

test('execution history keeps commentary, sources and findings separate from the final answer',async()=>{
 const source=await readFile(new URL('../src/composables/taskTimeline.ts',import.meta.url),'utf8')
 const {splitTaskTimeline}=await import(url(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText))
 const entries=[{key:'c',type:'commentary',content:'先检查来源'},{key:'a',type:'activities',activities:[]},{key:'s',type:'sources',sources:{}},{key:'n',type:'milestone',content:'阶段发现'},{key:'f',type:'text',kind:'candidate',content:'最终结论'}]
 const split=splitTaskTimeline(entries);assert.deepEqual(split.process.map(e=>e.key),['c','a','s','n']);assert.equal(split.answer.content,'最终结论');assert.equal(entries.length,5)
 const legacy=splitTaskTimeline([{key:'old',type:'commentary',content:'操作说明'},{key:'final',type:'text',content:'旧版最终回答'}]);assert.equal(legacy.answer.content,'旧版最终回答')
})

test('a superseded draft remains inside history until the latest answer is available',async()=>{
 const source=await readFile(new URL('../src/composables/taskTimeline.ts',import.meta.url),'utf8')
 const {splitTaskTimeline}=await import(url(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText))
 const history=[{key:'draft',type:'text',kind:'candidate',content:'旧草稿'},{key:'supplement',type:'supplement',content:'补充要求'},{key:'tools',type:'activities',activities:[]}]
 assert.equal(splitTaskTimeline(history).answer,null);const split=splitTaskTimeline([...history,{key:'answer',type:'text',kind:'candidate',content:'修订后回答'}]);assert.equal(split.answer.content,'修订后回答');assert.equal(split.process[0].content,'旧草稿')
})
