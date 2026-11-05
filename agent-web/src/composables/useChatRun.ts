import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import {
  cancelRun, continueRun, createRun, decideApproval, downloadArtifact, getRunSnapshot, listApprovals,
  listMessagePage, listRuns, openRunEvents, supplement, steerRun, pauseRun, resumeRun,
  type ApprovalItem, type MessageItem, type RunEvent, type RunView, type RunMode,
} from '../api'

const TERMINAL = ['succeeded', 'failed', 'cancelled', 'timed_out', 'budget_exceeded']
const preferredModes = new Map<string, RunMode>()
export function modelFailureText(code: string): string {
  if(code.startsWith('MODEL_RETRY_PAUSED_'))return '模型连续请求失败，已自动暂停。'+modelFailureText(code.slice('MODEL_RETRY_PAUSED_'.length))+' 可在底部点击继续重试；已有步骤会保留。'
  const status=code.match(/^MODEL_HTTP_(\d{3})$/)?.[1]
  if(status)return ['401','403'].includes(status)?'模型服务拒绝访问（HTTP '+status+'），请检查 Key 和模型权限。'
    :status==='429'?'模型服务限流或配额不足（HTTP 429）。':'模型服务请求失败（HTTP '+status+'）。'
  return ({MODEL_TIMEOUT:'模型响应超时。',MODEL_NETWORK_ERROR:'模型连接中断，未能收到完整回答。',
    MODEL_STREAM_ERROR:'模型服务在输出过程中返回错误。',MODEL_INVALID_RESPONSE:'模型返回格式不完整或不符合接口要求。',
    MODEL_OUTPUT_TRUNCATED:'模型达到单次输出长度上限，不完整的工具调用未执行。可分批处理后继续。',
    MODEL_UNAVAILABLE:'模型请求失败，未能收到完整回答。'} as Record<string,string>)[code] || code
}

export function useChatRun(props: { sessionId: string }, changed: () => void) {
  const messages = ref<MessageItem[]>([])
  const draft = ref('')
  const loading = ref(false)
  const sending = ref(false)
  const error = ref('')
  const loadingOlder = ref(false)
  const hasMore = ref(false)
  let beforeSeq: number | null = null
  const runId = ref('')
  const runStatus = ref('')
  const mode = ref<RunMode>('chat')
  const runMode = ref<RunMode>('build')
  const planRunId = ref('')
  const controlRetries = new Map<string,{key:string;text:string}>()
  const phase = ref('')
  const tokenUsed = ref(0)
  const questionId = ref('')
  const questionOptions = ref<string[]>([])
  const questionText = ref('')
  const eventLog = ref<RunEvent[]>([])
  const approvals = ref<ApprovalItem[]>([])
  const artifacts = ref<Array<{ id: string; name: string }>>([])
  const runHistory = ref<RunView[]>([])
  const runtime = ref(0)
  const reasonCode = ref('')
  const streamText = ref('')
  const commentaryText=ref(''),streamNotice=ref('')
  let streamAttemptId=''
  const commandNotice = ref('')
  const steeringRetries = new Map<string, { text: string; key: string }>()
  const messagePanel = ref<HTMLElement | null>(null)
  let stopSse: (() => void) | null = null
  let pollTimer: number | undefined
  let frame: number | undefined
  let pending: string[] = []
  let pendingCommentary:string[]=[]
  let generation = 0
  let selection = 0
  let polling = false
  let syncRequested = false, sseConnected = false, lastSyncAt = 0, runStartedAt = 0
  let disposed = false
  const retries = new Map<string, { text: string; key: string; mode: RunMode; plan: string }>()
  const active = computed(() => !!runStatus.value && !TERMINAL.includes(runStatus.value))
  const sessionBusy = computed(() => active.value || runHistory.value.some(r => !TERMINAL.includes(r.status)))
  const canSteer = computed(() => ['queued','running','retry_wait'].includes(runStatus.value))
  const valid = (g: number, s?: number) => !disposed && g === generation && (s == null || s === selection)

  async function scrollBottom() {
    await nextTick()
    const panel = messagePanel.value
    if (panel) panel.scrollTop = panel.scrollHeight
  }
  function clearStream() {
    if (frame != null) cancelAnimationFrame(frame)
    frame = undefined
    pending = []
    streamText.value = ''
    commentaryText.value='';pendingCommentary=[];streamAttemptId=''
  }
  function play() {
    frame = undefined
    const panel = messagePanel.value
    const follow = !panel || panel.scrollHeight - panel.scrollTop - panel.clientHeight < 100
    streamText.value += pending.splice(0).join('')
    commentaryText.value+=pendingCommentary.splice(0).join('')
    if (follow) void scrollBottom()
  }
  function pushDelta(delta: string,channel='answer') {
    (channel==='commentary'?pendingCommentary:pending).push(delta)
    if (frame == null) frame = requestAnimationFrame(play)
  }
  function cleanupRun() {
    stopSse?.()
    stopSse = null
    if (pollTimer != null) window.clearInterval(pollTimer)
    pollTimer = undefined
    polling = false
    syncRequested = false; sseConnected = false
    clearStream()
    streamNotice.value=''
  }
  function applyRun(run: RunView) {
    runStatus.value = run.status
    runMode.value = run.mode || 'build'
    if (run.status === 'paused') controlRetries.delete(run.runId + ':pause')
    if (['queued','running','retry_wait'].includes(run.status)) controlRetries.delete(run.runId + ':resume')
    phase.value = run.phase
    tokenUsed.value = run.tokenUsed
    questionId.value = run.status === 'waiting_user' ? run.questionId || '' : ''
    questionOptions.value = run.status === 'waiting_user' ? run.questionOptions || [] : []
    questionText.value = run.questionText || ''
    reasonCode.value = run.reasonCode || ''
    if(run.status==='succeeded')streamNotice.value=''
    if(run.status==='paused')streamNotice.value=reasonCode.value.startsWith('MODEL_RETRY_PAUSED_')
      ? modelFailureText(reasonCode.value)
      : '任务已暂停。'+(reasonCode.value.startsWith('MODEL_')?modelFailureText(reasonCode.value):'')+'可在底部补充要求后继续。'
    else if(run.status==='retry_wait'&&!streamNotice.value.includes('次重试'))streamNotice.value=modelFailureText(reasonCode.value)+' 正在等待重试。'
    const end = run.finishedAt ? Date.parse(run.finishedAt) : Date.now()
    runStartedAt = Date.parse(run.createdAt || new Date().toISOString())
    runtime.value = Math.max(0, Math.floor((end - runStartedAt) / 1000))
    runHistory.value = runHistory.value.map(r => r.runId === run.runId ? run : r)
  }
  async function loadOlder() {
    if (loadingOlder.value || !hasMore.value || beforeSeq == null) return
    const g = generation
    const panel = messagePanel.value
    const height = panel?.scrollHeight || 0
    loadingOlder.value = true
    try {
      const page = await listMessagePage(props.sessionId, beforeSeq)
      if (!valid(g)) return
      const merged = new Map(messages.value.map(m => [m.seq, m]))
      page.messages.forEach(m => merged.set(m.seq, m))
      messages.value = [...merged.values()].sort((a, b) => a.seq - b.seq)
      beforeSeq = page.nextBeforeSeq
      hasMore.value = page.hasMore
      await nextTick()
      if (valid(g) && panel) panel.scrollTop += panel.scrollHeight - height
    } catch (e) { if (valid(g)) error.value = (e as Error).message }
    finally { if (valid(g)) loadingOlder.value = false }
  }
  async function syncRun() {
    if (!runId.value) return
    if (polling) { syncRequested = true; return }
    const g = generation, s = selection, id = runId.value
    polling = true
    lastSyncAt = Date.now()
    try {
      const afterSeq = messages.value.at(-1)?.seq || 0
      const snapshot = await getRunSnapshot(id, afterSeq), run = snapshot.run
      const pendingApprovals = run.status === 'waiting_approval' ? await listApprovals(id) : []
      if (!valid(g, s)) return
      if (error.value.startsWith('同步任务失败')) error.value = ''
      applyRun(run)
      approvals.value = pendingApprovals
      artifacts.value = snapshot.artifacts.map(a => ({ id: a.objectId, name: a.name }))
      const merged = new Map(messages.value.map(message => [message.seq, message]))
      snapshot.messages.forEach(message => merged.set(message.seq, message))
      messages.value = [...merged.values()].sort((a, b) => a.seq - b.seq)
      if (streamAttemptId && snapshot.messages.some(message => message.attemptId === streamAttemptId)) clearStream()
      if (beforeSeq == null && messages.value.length) beforeSeq = messages.value[0].seq
      if (snapshot.hasMore) syncRequested = true
      else if (TERMINAL.includes(run.status)) { cleanupRun(); changed() }
      else if (run.status === 'paused') { stopSse?.(); stopSse = null; sseConnected = false; clearStream() }
      else if (!stopSse) startEvents(g,s)
    } catch (e) {
      if (valid(g, s)) { sseConnected = false; error.value = '同步任务失败，将自动重试：' + (e as Error).message }
    } finally {
      if (valid(g, s)) {
        polling = false
        if (syncRequested) { syncRequested = false; queueMicrotask(() => { if (valid(g, s)) void syncRun() }) }
      }
    }
  }
  function startEvents(g: number, s: number) {
    const id = runId.value
    stopSse = openRunEvents(id, 0, event => {
      if (!valid(g, s)) return
      sseConnected = true
      if (event.type === 'run.snapshot' && event.reset === true) {
        eventLog.value = []
        clearStream()
        void syncRun()
      }
      eventLog.value.push(event)
      if (event.type === 'run.command_received') commandNotice.value = '补充要求已接收，将在当前工具批次完成后应用。'
      if (event.type === 'run.command_applied') {
        commandNotice.value = '补充要求已应用，Agent 将按最新要求继续。'
      }
      if (eventLog.value.length > 300) eventLog.value.shift()
      if (event.type === 'run.phase' && typeof event.phase === 'string') {
        phase.value = event.phase
      }
      const attempt=typeof event.attemptId==='string'?event.attemptId:''
      if(event.type==='message.start'){clearStream();streamAttemptId=attempt;streamNotice.value=''}
      if(event.type==='message.delta'&&typeof event.delta==='string'){
        if(attempt&&messages.value.some(m=>m.attemptId===attempt))return
        if(attempt&&streamAttemptId&&attempt!==streamAttemptId)return
        if(attempt)streamAttemptId=attempt
        pushDelta(event.delta,typeof event.channel==='string'?event.channel:'answer')
      }
      if(event.type==='message.interrupted'&&(!attempt||attempt===streamAttemptId)){
        clearStream()
        streamNotice.value=event.reason==='model_error'?modelFailureText(String(event.code||'MODEL_UNAVAILABLE'))+' 未完成的草稿不会作为最终答案保存。'
          :event.reason==='paused'?'任务已暂停，未完成的草稿未作为最终答案保存。':'本次生成已中断，未完成的草稿没有作为最终答案保存。任务状态会继续同步。'
      }
      if(event.type==='run.retry_wait')streamNotice.value=modelFailureText(String(event.reason||'MODEL_UNAVAILABLE'))
        +(event.failures?' 正在等待第 '+event.failures+' 次重试，连续失败 3 次会暂停。':' 正在等待重试。')
      if (['run.snapshot','run.status','run.retry_wait','run.succeeded','run.failed','run.cancelled','run.timed_out','run.budget_exceeded',
        'run.waiting_user','run.waiting_reconcile','approval.requested','approval.decided','message.committed',
        'message.interrupted','artifact.created','run.command_applied','run.pause_requested','run.paused','run.resumed'].includes(event.type)) void syncRun()
    }, () => { if (valid(g, s)) void syncRun() }, connected => { if (valid(g, s)) sseConnected = connected })
  }
  async function selectRun(id: string) {
    cleanupRun()
    const g = generation, s = ++selection
    runId.value = id
    eventLog.value = []
    approvals.value = []
    artifacts.value = []
    questionId.value = ''
    questionOptions.value = []
    const known = runHistory.value.find(r => r.runId === id)
    if (known) applyRun(known)
    // Poll terminal runs once too: a failed load must remain retryable.
    pollTimer = window.setInterval(() => {
      if (active.value && runStatus.value !== 'paused' && runStartedAt) runtime.value = Math.max(0, Math.floor((Date.now() - runStartedAt) / 1000))
      if ((runStatus.value !== 'paused' && !sseConnected) || Date.now() - lastSyncAt >= 10000) void syncRun()
    }, 1500)
    if (!known || (!TERMINAL.includes(known.status) && known.status !== 'paused')) startEvents(g, s)
    await syncRun()
  }
  async function load() {
    generation++
    selection++
    cleanupRun()
    const g = generation
    messages.value = []
    runHistory.value = []
    runId.value = ''
    runStatus.value = ''
    phase.value = ''
    tokenUsed.value = 0
    runtime.value = 0
    reasonCode.value = ''
    approvals.value = []
    artifacts.value = []
    eventLog.value = []
    questionId.value = ''
    questionOptions.value = []
    questionText.value = ''
    commandNotice.value = ''
    draft.value = ''
    mode.value = preferredModes.get(props.sessionId) || 'chat'
    planRunId.value = ''
    error.value = ''
    loadingOlder.value = false
    sending.value = false
    beforeSeq = null
    hasMore.value = false
    if (!props.sessionId) return
    loading.value = true
    try {
      const [page, runs] = await Promise.all([listMessagePage(props.sessionId), listRuns(props.sessionId)])
      if (!valid(g)) return
      messages.value = page.messages
      beforeSeq = page.nextBeforeSeq
      hasMore.value = page.hasMore
      runHistory.value = runs
      const latest = runs.find(r => !TERMINAL.includes(r.status)) || runs[0]
      mode.value = preferredModes.get(props.sessionId) || latest?.mode || 'chat'
      planRunId.value = ''
      if (latest) await selectRun(latest.runId)
      if (valid(g)) void scrollBottom()
    } catch (e) { if (valid(g)) error.value = (e as Error).message }
    finally { if (valid(g)) loading.value = false }
  }
  async function send() {
    const text = draft.value.trim(), session = props.sessionId, g = generation
    if (!text || !session || sessionBusy.value || sending.value || loading.value) return
    sending.value = true
    error.value = ''
    const retry = retries.get(session)
    const selectedMode = mode.value, plan = planRunId.value
    const requestKey = retry?.text === text && retry.mode === selectedMode && retry.plan === plan ? retry.key : crypto.randomUUID()
    retries.set(session, { text, key: requestKey, mode: selectedMode, plan })
    try {
      const id = await createRun(session, text, requestKey, selectedMode, plan || undefined)
      retries.delete(session)
      if (!valid(g)) return
      draft.value = ''
      planRunId.value = ''
      runHistory.value.unshift({ runId: id, sessionId: session, status: 'queued', phase: 'prepare',
        mode: selectedMode, planRunId: plan, tokenUsed: 0, createdAt: new Date().toISOString() })
      await selectRun(id)
      if (valid(g)) { changed(); void scrollBottom() }
    } catch (e) { if (valid(g)) error.value = (e as Error).message }
    finally { if (valid(g)) sending.value = false }
  }
  function setMode(value: RunMode) {
    if (sessionBusy.value || sending.value || loading.value) return
    mode.value = value
    preferredModes.set(props.sessionId, value)
    planRunId.value = value === 'build' && runMode.value === 'plan' && runStatus.value === 'succeeded' ? runId.value : ''
  }
  function preparePlan(id: string) {
    if (sessionBusy.value || sending.value || loading.value) return
    const source = runHistory.value.find(r => r.runId === id && r.mode === 'plan' && r.status === 'succeeded')
    if (!source) return
    mode.value = 'build'
    preferredModes.set(props.sessionId, 'build')
    planRunId.value = id
    if (!draft.value.trim()) draft.value = '请按这份计划执行，更新进度并验证结果。'
  }
  async function act(action: () => Promise<unknown>, after?: () => void) {
    if (sending.value) return
    const g = generation, s = selection
    sending.value = true
    error.value = ''
    try {
      await action()
      if (!valid(g, s)) return
      after?.()
      await syncRun()
    } catch (e) { if (valid(g, s)) error.value = (e as Error).message }
    finally { if (valid(g, s)) sending.value = false }
  }
  const onCancel = () => act(() => cancelRun(runId.value))
  function controlKey(action: string,text: string) {
    const id = runId.value + ':' + action, prior = controlRetries.get(id)
    if (prior?.text === text) return prior.key
    const key = crypto.randomUUID()
    controlRetries.set(id,{key,text})
    return key
  }
  const onPause = () => act(() => pauseRun(runId.value,controlKey('pause','')))
  const onResume = () => {
    const text = draft.value.trim(), id = runId.value
    return act(() => resumeRun(id,controlKey('resume',text),text), () => {
      if (draft.value.trim() === text) draft.value = ''
      runStatus.value = 'queued'
    })
  }
  const onContinue = () => act(() => continueRun(runId.value), () => {
    runStatus.value='queued'
    reasonCode.value=''
    cleanupRun()
    pollTimer=window.setInterval(()=>void syncRun(),1500)
    startEvents(generation,selection)
  })
  const onSteer = () => {
    const text = draft.value.trim(), id = runId.value
    if (!text || !canSteer.value) return
    const prior = steeringRetries.get(id)
    const key = prior?.text === text ? prior.key : crypto.randomUUID()
    steeringRetries.set(id, { text, key })
    return act(() => steerRun(id, text, key), () => {
      steeringRetries.delete(id)
      draft.value = ''
      commandNotice.value = '补充要求已接收，将在下一个安全决策点应用。'
    })
  }
  const onDecision = (a: ApprovalItem, decision: 'approved' | 'rejected') =>
    act(() => decideApproval(a.approvalId, decision))
  const onSupplement = () => {
    const answer = draft.value.trim()
    if (!answer || !questionId.value) return
    return act(() => supplement(runId.value, props.sessionId, questionId.value, answer), () => { draft.value = '' })
  }
  const onSupplementText = (text: string) => {
    const answer=text.trim()
    if(!answer || !questionId.value)return
    return act(() => supplement(runId.value,props.sessionId,questionId.value,answer))
  }
  async function download(id: string) {
    try { await downloadArtifact(id) }
    catch (e) { error.value = (e as Error).message }
  }
  function onKeydown(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
      e.preventDefault()
      if (questionId.value) return
      else if (runStatus.value === 'paused') void onResume()
      else if (canSteer.value) void onPause()
      else if (runStatus.value === 'pause_requested') return
      else void send()
    }
  }
  watch(() => props.sessionId, () => void load(), { immediate: true, flush: 'sync' })
  onBeforeUnmount(() => { disposed = true; generation++; cleanupRun() })
  return {
    messages, draft, loading, sending, error, runId, runStatus, phase, tokenUsed, questionId,
    mode, runMode, planRunId, setMode, preparePlan,
    onPause, onResume,
    questionText, questionOptions, eventLog, approvals, artifacts, runtime, active, sessionBusy, reasonCode,
    streamText,commentaryText,streamNotice, messagePanel, hasMore, loadingOlder, runHistory, selectRun, loadOlder,
    send, onCancel, onContinue, onDecision, onSupplement, onSupplementText, onSteer, canSteer, commandNotice, download, onKeydown,
  }
}
