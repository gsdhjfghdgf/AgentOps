<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { useChatRun, modelFailureText } from '../composables/useChatRun'
import AppIcon from './AppIcon.vue'
import ComposerModelPicker from './ComposerModelPicker.vue'
import ComposerModePicker from './ComposerModePicker.vue'
import type { RunMode } from '../api'
import QuestionCard from './QuestionCard.vue'
import TaskTimeline from './TaskTimeline.vue'
import ProjectPicker from './ProjectPicker.vue'
import ArtifactImage from './ArtifactImage.vue'
import RunWorkspacePanel from './RunWorkspacePanel.vue'
import RunProgress from './RunProgress.vue'
import ArtifactPreview from './ArtifactPreview.vue'
import { groupTaskMessages } from '../composables/chatTurns'

const props = defineProps<{ sessionId: string; projectId?:string; projectName?:string }>()
const previewArtifact=ref<{id:string;name:string}|null>(null)
const emit = defineEmits<{ (e: 'sessions-changed'): void; (e: 'start-task', text: string, mode?: RunMode, submit?: boolean): void; (e: 'configure-model'): void }>()
const {
  messages, draft, loading, sending, error, runId, runStatus, phase, tokenUsed, questionId,
  mode, runMode, planRunId, setMode, preparePlan,
  onPause, onResume,
  questionText, questionOptions, eventLog, approvals, artifacts, runtime, active, sessionBusy, reasonCode,
  streamText, commentaryText,streamNotice, messagePanel, hasMore, loadingOlder, runHistory, selectRun, loadOlder,
  send, onCancel, onContinue, onDecision, onSupplementText, onSteer, canSteer, commandNotice, download, onKeydown,
} = useChatRun(props, () => emit('sessions-changed'))
watch(() => approvals.value.map(item => item.approvalId).join(','), async (ids, previous) => {
  if (!ids || ids === previous) return
  await nextTick()
  messagePanel.value?.querySelector('.approve')?.scrollIntoView({ block: 'end' })
})
watch(questionId,async id=>{if(!id)return;await nextTick();messagePanel.value?.querySelector('.question-card')?.scrollIntoView({block:'end'})})
const monitorOpen=ref(false)
const failureExplanation=computed(()=>({BUDGET_EXCEEDED:'当前执行额度已用完，进度已保存。点击“延长并继续”可从当前步骤接着执行，无需新建任务。',
  APPROVAL_CHECK_FAILED:'旧版工具准备流程失败。修复后可在当前会话重新发送任务。',approval_rejected:'你已拒绝本次操作，工具没有获准执行。'} as Record<string,string>)[reasonCode.value] || modelFailureText(reasonCode.value))

marked.setOptions({ breaks: true })
const PHASES = [
  { k: 'prepare', label: '准备' }, { k: 'model', label: '模型' },
  { k: 'tools', label: '工具' }, { k: 'validate', label: '校验' }, { k: 'finalize', label: '收尾' },
]
const phaseIndex = computed(() => PHASES.findIndex(p => p.k === phase.value))
function statusBadge(s: string): string {
  if (s === 'succeeded') return 'ok'
  if (['failed', 'timed_out', 'budget_exceeded', 'cancelled'].includes(s)) return 'bad'
  if (['waiting_approval', 'waiting_user', 'waiting_reconcile','paused','pause_requested'].includes(s)) return 'wait'
  return s ? 'run' : ''
}
function label(s: string): string {
  const map: Record<string, string> = {
    queued: '排队中', running: '执行中', retry_wait: '重试等待', waiting_reconcile: '等待核对',
    paused: '已暂停', pause_requested: '正在暂停',
    waiting_approval: '等待批准', waiting_user: '等待补充输入', cancel_requested: '取消中',
    succeeded: '已完成', failed: '已失败', cancelled: '已取消', timed_out: '超时', budget_exceeded: '预算超限',
  }
  return map[s] || s || '空闲'
}
const statusLabel = computed(() => label(runStatus.value))
function renderMd(text: string): string {
  return DOMPurify.sanitize(marked.parse(text, { async: false }) as string)
}
const taskTurns = computed(() => groupTaskMessages(messages.value))
const modeLabels = { chat: 'Chat', plan: 'Plan', build: 'Build' }
const composerMode = computed(() => sessionBusy.value ? runMode.value : mode.value)
const modeDescription = computed(() => ({
  chat: 'Chat · 轻量问答，可联网搜索和查询资料。',
  plan: 'Plan · 只制定计划，不修改文件或执行命令。',
  build: 'Build · 执行任务，重要操作按权限设置申请授权。',
})[composerMode.value])
function taskDuration(id?:string|null){
 if(id===runId.value)return runtime.value
 const row=runHistory.value.find(r=>r.runId===id)
 if(!row?.createdAt)return undefined
 return Math.max(0,Math.floor(((row.finishedAt?Date.parse(row.finishedAt):Date.now())-Date.parse(row.createdAt))/1000))
}
function evClass(type: string): string {
  if (type === 'run.succeeded') return 'ev-ok'
  if (type === 'run.failed' || type.includes('budget') || type.includes('timed')) return 'ev-bad'
  if (type.startsWith('approval')) return 'ev-wait'
  if (type.startsWith('tool')) return 'ev-tool'
  return ''
}
function eventLabel(event: { type: string; [key: string]: unknown }): string {
  const names: Record<string,string> = {
    'run.created':'任务已创建', 'run.queued':'任务已入队', 'run.progress_paused':'重复读取，等待确认', 'run.phase':'执行阶段',
    'tool.started':'开始调用工具', 'tool.completed':'工具执行结束', 'tool.finished':'工具执行结束',
    'run.command_received':'收到补充要求', 'run.command_applied':'已应用补充要求',
    'approval.requested':'等待操作批准', 'approval.decided':'操作批准已处理',
    'run.succeeded':'任务已完成', 'run.failed':'任务失败', 'run.cancelled':'任务已停止',
    'run.cancel_requested':'正在停止', 'run.snapshot':'状态已同步', 'run.retry_wait':'等待重试',
    'run.timed_out':'任务超时', 'run.budget_exceeded':'预算已用尽', 'message.delta':'生成回答片段',
    'message.start':'开始接收模型输出','message.committed':'本轮内容已保存','message.interrupted':'本轮生成已中断',
  }
  const phaseName = PHASES.find(p => p.k === event.phase)?.label
  return (names[event.type] || event.type) + (event.tool ? ' · ' + event.tool : phaseName ? ' · ' + phaseName : '')
}
function eventTime(value: string): string {
  return value ? new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) : ''
}
function toolLabel(name: string): string {
  return ({ sales_query: '查询销售数据', report_generate: '生成报告', knowledge_search: '检索知识库', send_email: '发送通知',
    calculator:'计算算式',current_time:'查询时间',csv_analyze:'统计 CSV',knowledge_list:'列出知识文档',knowledge_read:'阅读知识文档',
    text_file_create:'生成文件',workspace_list:'查看工作区',workspace_read:'读取文件',workspace_search:'搜索文件',workspace_write:'写入文件',
    memory_list:'查看记忆',memory_store:'保存记忆',memory_forget:'遗忘记忆',web_read:'阅读网页',web_search:'搜索网页',skill_list:'查看已安装技能',skill_preview:'预览技能',skill_install:'安装技能',skill_use:'加载技能',skill_dependencies:'检查技能依赖',runtime_install_node:'安装私有Node',task_notes:'保存阶段发现',plan_update:'更新任务计划',mcp_connections:'查看MCP连接',mcp_tools:'发现MCP工具',mcp_call:'调用MCP工具',report_export:'生成正式文件',project_propose_batch:'批量文件修改预览',sandbox_run:'隔离终端命令',project_list:'查看项目目录',project_read:'读取项目文件',project_search:'搜索项目',project_write:'修改项目文件',project_patch:'修改项目片段',project_undo:'撤销修改',project_git_status:'查看Git状态',project_git_diff:'查看Git差异',browser_open:'打开真实浏览器',browser_snapshot:'读取动态页面',browser_click:'点击网页元素',browser_fill:'填写网页',browser_screenshot:'浏览器截图',browser_close:'关闭浏览器',command_run:'执行命令' } as Record<string,string>)[name] || name
}
function argumentLabel(key: string): string { return ({command:'命令',directory:'工作目录',path:'文件路径',filename:'文件名',content:'完整内容',url:'网页地址',query:'查询',to:'收件人',subject:'主题',body:'正文',key:'记忆键',kind:'类型',expectedSha256:'当前文件 SHA256',timeoutSeconds:'超时（秒）'} as Record<string,string>)[key] || key }
const processEvents = computed(() => eventLog.value.filter(e => e.type !== 'message.delta'))
const showProcess = computed(() => !!runId.value && processEvents.value.length > 0)
const suggestions = [
  { icon: 'activity', title: '看清数据里的趋势', description: '查询数据，提炼关键洞察', prompt: '查询本月销售数据，分析主要趋势' },
  { icon: 'check', title: '把结果整理成报告', description: '从分析到一份完整的交付', prompt: '生成上周销售周报，整理关键指标和结论' },
  { icon: 'book', title: '在知识中寻找答案', description: '检索文档，找到可靠依据', prompt: '查询知识库中的差旅制度，并整理重点' },
]
function chooseTask(text: string) {
  if (props.sessionId) draft.value = text
  else emit('start-task', text, mode.value)
}
function composerKeydown(event: KeyboardEvent) {
  if (!props.sessionId && event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    if (draft.value.trim()) emit('start-task', draft.value.trim(), mode.value, true)
  } else onKeydown(event)
}
const pendingDraft = ref<{ sessionId: string; text: string; mode: RunMode; submit: boolean } | null>(null)
watch(() => props.sessionId, () => { pendingDraft.value = null }, { flush: 'sync' })
watch([loading, pendingDraft], ([busy, pending]) => {
  if (busy || !pending) return
  pendingDraft.value = null
  if (pending.sessionId !== props.sessionId) return
  setMode(pending.mode)
  draft.value = pending.text
  if (pending.submit) void send()
}, { flush: 'post' })
defineExpose({ fillDraft: (text: string, selectedMode: RunMode = mode.value, submit = false) => {
  pendingDraft.value = { sessionId: props.sessionId, text, mode: selectedMode, submit }
} })
</script>

<template>
  <div class="chat" :class="{ 'has-process': showProcess && monitorOpen }">
    <ArtifactPreview v-if="previewArtifact" :key="previewArtifact.id" :id="previewArtifact.id" :name="previewArtifact.name" @close="previewArtifact=null" />
    <!-- ============ 左：消息流 ============ -->
    <section class="stream" aria-label="对话消息">
      <div class="conversation-context"><AppIcon :name="projectName ? 'folder' : 'chat'" :size="15" /><span>{{ projectName || '普通会话' }}</span><small v-if="projectName">项目内文件可直接读写</small></div>
      <label v-if="runHistory.length>1" class="run-picker small dim">
        查看任务
        <select class="field" :value="runId" :disabled="sending" @change="selectRun(($event.target as HTMLSelectElement).value)">
          <option v-for="r in runHistory" :key="r.runId" :value="r.runId">
            {{ label(r.status) }} · {{ new Date(r.createdAt || '').toLocaleString('zh-CN') }} · {{ r.runId.slice(0, 8) }}
          </option>
        </select>
      </label>
      <div v-if="!sessionId" class="welcome">
        <div class="welcome-content">
          <span class="welcome-label"><AppIcon name="spark" :size="16" /> A LITTLE CLARITY, A LOT OF POSSIBILITY</span>
          <h1>让想法，<br />走向下一步<span>。</span></h1>
          <p class="welcome-description">分析数据、整理知识，或完成一份报告。<br />说说你的目标，我们一起把它变成结果。</p>
          <div class="suggestions">
            <button v-for="s in suggestions" :key="s.title" class="suggestion" @click="chooseTask(s.prompt)">
              <span class="suggestion-icon"><AppIcon :name="s.icon" :size="20" /></span>
              <strong>{{ s.title }}</strong><span>{{ s.description }}</span><AppIcon name="chevron" :size="14" class="suggestion-arrow" />
            </button>
          </div>
          <div class="welcome-footnote"><span></span>专注你的目标，执行交给 AgentOps</div>
        </div>
      </div>

      <div v-else-if="loading" class="blank small dim">加载消息…</div>

      <div v-else ref="messagePanel" class="messages" role="log" aria-live="polite">
        <RunWorkspacePanel v-if="runId && runMode !== 'chat'" :key="runId" :run-id="runId" :status="runStatus" :answer="messages.filter(m=>m.runId===runId&&m.role==='assistant'&&m.content).at(-1)?.content||''" @retried="id=>{selectRun(id);emit('sessions-changed')}" />
        <button v-if="hasMore" class="btn btn-sm history-more" :disabled="loadingOlder" @click="loadOlder">
          {{ loadingOlder ? '加载中…' : '加载更早的消息' }}
        </button>
        <div v-if="taskTurns.length === 0" class="blank">
          <div class="blank-mark"><AppIcon name="spark" :size="28" /></div>
          <h2>从一个想法开始</h2>
          <p>输入你想完成的事，我会帮你一步步推进。</p>
          <div class="quick-prompts"><button v-for="s in suggestions" :key="s.title" @click="chooseTask(s.prompt)">{{ s.title }} <span>↗</span></button></div>
        </div>

        <section v-for="turn in taskTurns" :key="turn.key" class="task-turn" :data-run-id="turn.runId">
          <article v-if="turn.request" class="msg msg-user fade-in">
            <header class="msg-head"><span class="msg-role mono">你</span></header>
            <div class="msg-body" v-html="renderMd(turn.request.content)"></div>
          </article>
          <article v-if="turn.steps.length || (active && turn.runId===runId)" class="msg msg-agent task-response fade-in">
            <header class="msg-head"><span class="msg-role mono"><AppIcon name="spark" :size="13" />AgentOps
              <span class="turn-mode">{{ modeLabels[runHistory.find(r=>r.runId===turn.runId)?.mode || 'build'] }}</span></span></header>
            <TaskTimeline :messages="turn.steps" :active="active && turn.runId===runId && !['paused','pause_requested'].includes(runStatus)" :status="runHistory.find(r=>r.runId===turn.runId)?.status" :live-commentary="active&&turn.runId===runId?commentaryText:''" :answer-streaming="active&&turn.runId===runId&&!!streamText" :duration="taskDuration(turn.runId)" :render="renderMd" :tool-label="toolLabel" @answer-started="turn.runId===runId&&(monitorOpen=false)">
              <template v-if="turn.runId===runId" #details>
                <RunProgress v-if="runId" :run-id="runId" :status="runStatus" />
                <details v-if="processEvents.length" class="execution-details">
                  <summary>运行记录 · {{ processEvents.length }} 条</summary>
                  <ol><li v-for="e in processEvents.slice(-30)" :key="e.seq"><span>{{ eventLabel(e) }}</span><span v-if="e.status" class="small dim"> · {{ e.status }}</span><p v-if="e.text" class="small muted">{{ e.text }}</p></li></ol>
                </details>
              </template>
            </TaskTimeline>
            <button v-if="runHistory.some(r=>r.runId===turn.runId&&r.mode==='plan'&&r.status==='succeeded')"
              class="btn btn-sm plan-execute" :disabled="sessionBusy || sending || loading"
              @click="preparePlan(turn.runId || '')"><AppIcon name="arrow" :size="14" />转为 Build 执行此计划</button>
          </article>
        </section>

        <article v-if="active && streamText" class="msg msg-agent live-answer" aria-label="流式回答">
          <header class="msg-head"><span class="msg-role mono">AgentOps · 正在生成，完成后校验</span><span class="stream-dot" aria-hidden="true"></span></header>
          <div class="msg-body" v-html="renderMd(streamText)"></div>
        </article>
        <p v-if="streamNotice" class="small muted stream-notice" role="status">{{ streamNotice }}</p>

        <!-- 运行状态条 -->
        <div v-if="active || (runStatus && !active)" class="run-bar card fade-in" :class="{ done: !active }">
          <div class="run-top">
            <span class="badge" :class="statusBadge(runStatus)">{{ statusLabel }}</span>
            <span class="dim small">{{ PHASES.find(p => p.k === phase)?.label || '准备' }}阶段</span>
            <span class="dim small" title="累计处理 Token：每次模型调用的输入与输出之和，包含重复上下文，并非最终回答的长度。">{{ tokenUsed.toLocaleString() }} tokens</span>
            <span class="mono dim small">{{ Math.floor(runtime / 60) }}:{{ String(runtime % 60).padStart(2, '0') }}</span>
            <button v-if="showProcess" class="btn btn-sm process-toggle" @click="monitorOpen=!monitorOpen">{{ monitorOpen ? '收起详情' : '执行详情' }}</button>

            <button v-if="active" class="btn btn-danger btn-sm cancel" :disabled="sending || runStatus==='cancel_requested'" @click="onCancel">停止任务</button>
          </div>

          <p v-if="commandNotice && active" class="small muted" role="status">{{ commandNotice }}</p>


          <!-- 待批准操作 -->
          <div v-for="a in approvals" :key="a.approvalId" class="approve">
            <div class="apv-title">
              <span class="badge wait">需批准</span>
              <strong>{{ a.scope==='task_network' ? '访问互联网' : a.scope==='task_sandbox' ? '隔离终端命令' : a.scope==='task_browser' ? '浏览器交互' : a.scope==='task_command' ? '执行终端命令' : toolLabel(a.tool) }}</strong>
            </div>
            <p v-if="a.projectDirectory" class="small muted">项目目录：{{ a.projectDirectory }}</p><pre v-if="a.previewDiff" class="approval-diff">{{ a.previewDiff }}</pre><p v-if="a.previewError" class="err small">{{ a.previewError }}</p>
            <p class="small muted">{{ a.description || '请核对操作内容后，允许或拒绝本次请求。' }}</p>
            <dl v-if="a.arguments && Object.keys(a.arguments).length" class="approval-arguments"><template v-for="(value,key) in a.arguments" :key="key"><dt>{{ argumentLabel(String(key)) }}</dt><dd><pre>{{ typeof value === 'string' ? value : JSON.stringify(value,null,2) }}</pre></dd></template></dl>
            <p class="small dim">{{ a.scope==='task_sandbox' || a.scope==='task_network' || a.scope==='task_command' || a.scope==='task_browser' ? '仅在当前任务内有效，新任务将重新申请。' : '仅授权本次操作，不授予其他权限。' }}</p>
            <div class="apv-actions">
              <button class="btn btn-primary btn-sm" :disabled="sending || !!a.previewError" @click="onDecision(a, 'approved')">{{ a.scope==='task_sandbox' ? '允许本任务隔离命令' : a.scope==='task_network' ? '允许本任务联网' : a.scope==='task_browser' ? '允许本任务浏览器交互' : a.scope==='task_command' ? '允许本任务执行命令' : '允许本次操作' }}</button>
              <button class="btn btn-danger btn-sm" :disabled="sending" @click="onDecision(a, 'rejected')">拒绝</button>
            </div>
          </div>

          <p v-if="runStatus === 'waiting_reconcile'" class="small muted">外部操作结果尚未确认，任务已暂停。请核对执行结果。</p>
          <p v-if="reasonCode && ['failed', 'timed_out', 'budget_exceeded'].includes(runStatus)" class="err small">{{ failureExplanation }}</p>
          <button v-if="runStatus==='budget_exceeded'" class="btn btn-primary btn-sm continue-task" :disabled="sending || sessionBusy" @click="onContinue">{{ sending ? '正在继续…' : '延长并继续' }}</button>

          <!-- 等待补充输入 -->
          <QuestionCard v-if="questionId" :question-id="questionId" :question="questionText" :options="questionOptions" :busy="sending" @answer="onSupplementText" />

          <!-- 产物 -->
          <div v-if="artifacts.length" class="ifacts">
            <div v-for="a in artifacts" :key="a.id" class="ifact">
              <span aria-hidden="true">📄</span>
              <span class="small">{{ a.name }}</span>
              <button class="btn btn-sm" @click="previewArtifact=a">预览</button><button class="btn btn-sm" @click="download(a.id)">下载</button><ArtifactImage v-if="a.name.toLowerCase().endsWith('.png')" :id="a.id" :name="a.name" />
            </div>
          </div>
        </div>

        <p v-if="error" class="err small" role="alert">{{ error }}</p>
      </div>

      <!-- ============ 输入台 ============ -->
      <div class="composer">
        <div class="composer-box" :class="{ 'with-plan': !!planRunId }">
        <div v-if="planRunId" class="plan-handoff" role="status"><AppIcon name="check" :size="14" />
          已接续本会话的计划，发送后开始执行
          <button class="btn btn-ghost btn-sm" aria-label="取消接续计划" @click="planRunId = ''">×</button></div>
        <textarea
          v-model="draft"
          class="field composer-input"
          rows="2"
          :placeholder="questionId ? '请在对话中的问题卡片里选择或填写补充…' : runStatus==='paused' ? '任务已暂停，输入补充要求后发送，继续未完成的任务…' : runStatus==='pause_requested' ? '正在安全暂停，可先填写补充要求…' : canSteer ? '任务执行中，可先暂停，再补充要求并发送继续…' : mode==='chat' ? '随便聊聊，也可以让我搜索资料…' : mode==='plan' ? '描述你的想法，先一起制定计划…' : '输入任务或执行要求，回车发送…'"
          :disabled="loading || sending || !!questionId || (sessionBusy && !canSteer && !['paused','pause_requested'].includes(runStatus))"
          @keydown="composerKeydown"
        ></textarea>
        <button
          class="btn btn-primary send"
          :disabled="loading || sending || !!questionId || runStatus==='pause_requested' || (sessionBusy && !canSteer && runStatus!=='paused') || (!canSteer && runStatus!=='paused' && !draft.trim())"
          :aria-label="canSteer ? '暂停任务' : runStatus==='pause_requested' ? '正在暂停' : runStatus==='paused' ? (draft.trim() ? '发送补充并继续' : '继续任务') : '发送任务'"
          @click="!sessionId ? emit('start-task', draft.trim(), mode, true) : canSteer ? onPause() : runStatus==='paused' ? onResume() : send()"
        >
          <svg v-if="canSteer" class="pause-icon" width="19" height="19" viewBox="0 0 20 20" aria-hidden="true"><rect x="5" y="4" width="3" height="12" rx="1" fill="currentColor"/><rect x="12" y="4" width="3" height="12" rx="1" fill="currentColor"/></svg>
          <AppIcon v-else :name="sending || runStatus==='pause_requested' ? 'activity' : 'arrow'" :size="20" />
        </button>
        <div class="composer-toolbar"><div class="composer-controls"><ComposerModePicker :model-value="composerMode" :disabled="sending || loading || sessionBusy" @update:model-value="setMode" /><span v-if="canSteer" class="steer-label">执行中 · 点击右侧暂停</span><span v-else-if="runStatus==='paused'" class="steer-label">已暂停 · 发送后继续</span><ComposerModelPicker @configure="emit('configure-model')" /><ProjectPicker :disabled="sending || sessionBusy" :project-id="projectId || ''" /></div><span class="composer-shortcuts">{{ canSteer ? 'Enter 暂停' : runStatus==='paused' ? 'Enter 继续' : 'Enter 发送' }} <span class="key-dot">·</span> Shift + Enter 换行</span></div>
        </div>
        <p class="composer-note">{{ modeDescription }}</p>
      </div>
    </section>

    <!-- ============ 右：运行监视 ============ -->
    <aside v-if="showProcess && monitorOpen" class="monitor" aria-label="运行事件监视">
      <div class="mon-head">
        <span class="side-title"><AppIcon name="activity" :size="16" /> 执行动态</span>
        <span class="mono dim small" v-if="runId">#{{ eventLog.length }}</span>
      </div>
      <div class="mon-body">
        <p v-if="!runId" class="small dim">尚无运行。发送任务后这里实时显示 SSE 事件。</p>
        <ol v-else class="events">
          <li v-for="e in processEvents.slice(-60)" :key="e.seq" class="ev">
            <span class="ev-seq mono dim">{{ String(e.seq).padStart(4, '0') }}</span>
            <details class="ev-type"><summary :class="evClass(e.type)">{{ eventLabel(e) }}</summary>
              <pre class="event-payload">{{ JSON.stringify(e, null, 2) }}</pre>
            </details>
            <span class="ev-time mono dim">{{ eventTime(e.at) }}</span>
          </li>
        </ol>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.live-commentary{border-left:1px solid var(--line);padding-left:20px}.live-commentary .msg-body{color:var(--txt-2);font-size:13px}.stream-dot{width:6px;height:6px;border-radius:50%;background:var(--green);margin-left:8px;animation:stream-pulse 1.2s ease-in-out infinite}.stream-notice{padding:10px 0;line-height:1.8}@keyframes stream-pulse{50%{opacity:.3}}@media(prefers-reduced-motion:reduce){.stream-dot{animation:none}}
.task-step + .task-step { margin-top: 12px; }
.task-supplement { display:flex; gap:12px; align-items:baseline; padding:11px 14px; border-left:2px solid var(--green); border-radius:0 8px 8px 0; background:var(--ink-2); font-size:12px; }
.task-supplement > span { flex-shrink:0; color:var(--txt-3); font-size:10px; }
.task-supplement :deep(p) { margin:0; }
.chat { display:flex; flex:1; min-height:0; }
.stream { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; }
.welcome { flex:1; min-height:0; overflow:auto; display:grid; place-items:center; padding:40px 52px 48px; }
.welcome-content { width:min(790px,100%); }
.welcome-label { display:flex; align-items:center; gap:9px; font-size:9px; letter-spacing:2px; color:var(--amber); font-weight:600; margin-bottom:23px; }
.welcome h1 { font-size:clamp(36px,4vw,53px); letter-spacing:-1.8px; line-height:1.42; font-weight:550; margin:0 0 20px; }
.welcome h1 span { color:var(--amber); }
.welcome-description { font-size:13px; line-height:1.95; color:var(--txt-2); margin:0; }
.suggestions { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:13px; margin-top:38px; }
.suggestion { position:relative; display:flex; flex-direction:column; align-items:flex-start; padding:20px 17px 18px; border:1px solid var(--line); border-radius:13px; background:var(--ink-2); text-align:left; cursor:pointer; transition:transform .18s,border-color .18s,box-shadow .18s; }
.suggestion:hover { transform:translateY(-3px); border-color:var(--amber-deep); box-shadow:var(--shadow-2); }
.suggestion-icon { display:grid; place-items:center; width:35px; height:35px; border-radius:10px; background:var(--amber-soft); color:var(--amber); margin-bottom:20px; }
.suggestion:nth-child(2) .suggestion-icon { background:var(--blue-soft); color:var(--blue); }
.suggestion:nth-child(3) .suggestion-icon { background:var(--red-soft); color:var(--red); }
.suggestion strong { color:var(--txt-1); font-size:12px; font-weight:550; }
.suggestion > span:not(.suggestion-icon) { font-size:10px; color:var(--txt-3); margin-top:7px; }
.suggestion-arrow { position:absolute; top:20px; right:15px; color:var(--txt-3); }
.welcome-footnote { display:flex; align-items:center; gap:7px; margin-top:24px; color:var(--txt-3); font-size:10px; }
.welcome-footnote > span { width:5px; height:5px; background:var(--green); border-radius:50%; opacity:.6; }
.messages { flex:1; width:100%; min-height:0; overflow:auto; display:flex; flex-direction:column; gap:26px; padding:32px max(32px,calc((100% - 770px)/2)) 24px; }
.blank { margin:auto; text-align:center; padding:35px 20px; color:var(--txt-2); }
.blank-mark { display:inline-grid; place-items:center; width:58px; height:58px; border-radius:17px; background:var(--amber-soft); color:var(--amber); }
.blank h2 { color:var(--txt-1); font-size:25px; font-weight:550; letter-spacing:-.6px; margin:22px 0 10px; }
.blank p { font-size:12px; }
.quick-prompts { display:flex; flex-wrap:wrap; justify-content:center; gap:8px; margin-top:26px; }
.quick-prompts button { border:1px solid var(--line); background:var(--ink-2); border-radius:8px; padding:8px 11px; color:var(--txt-2); font-size:11px; cursor:pointer; }
.quick-prompts button:hover { border-color:var(--amber-deep); color:var(--amber); }
.quick-prompts span { margin-left:8px; color:var(--txt-3); }
.msg { max-width:100%; }
.task-turn { display:flex; flex-direction:column; gap:26px; flex-shrink:0; width:100%; }
.msg-user { align-self:flex-end; max-width:85%; width:fit-content; }
.msg-user .msg-head { display:none; }
.task-response>.msg-head { display:none; }
.msg-agent { align-self:flex-start; width:100%; }
.msg-head { margin-bottom:8px; }
.msg-role { display:inline-flex; align-items:center; gap:6px; font-family:var(--font-ui); font-size:11px; font-weight:600; color:var(--txt-3); letter-spacing:0; }
.msg-user .msg-head { text-align:right; }
.msg-user .msg-role { font-weight:400; }
.msg-body { font-size:14px; line-height:1.9; color:var(--txt-1); overflow-wrap:anywhere; }
.msg-user .msg-body { padding:13px 18px; border-radius:17px; background:var(--blue-soft); }
.msg-body :deep(p) { margin:0 0 .8em; }
.msg-body :deep(p:last-child) { margin:0; }
.msg-body :deep(h1),.msg-body :deep(h2),.msg-body :deep(h3) { font-weight:600; line-height:1.5; margin:1.3em 0 .6em; }
.msg-body :deep(h1) { font-size:23px; } .msg-body :deep(h2) { font-size:20px; } .msg-body :deep(h3) { font-size:16px; }
.msg-body :deep(pre) { background:var(--ink-1); border:1px solid var(--line); border-radius:10px; padding:16px; overflow:auto; font:12px/1.8 var(--font-mono); }
.msg-body :deep(code) { font-family:var(--font-mono); font-size:.9em; }
.msg-body :deep(table) { border-collapse:collapse; font-size:12px; display:block; overflow:auto; }
.msg-body :deep(th),.msg-body :deep(td) { border-bottom:1px solid var(--line); padding:9px 14px; text-align:left; }
.msg-body :deep(th) { background:var(--ink-1); font-weight:500; }
.msg-body :deep(ul),.msg-body :deep(ol) { padding-left:22px; }
.msg-body :deep(blockquote) { margin:16px 0; padding-left:16px; border-left:2px solid var(--amber-deep); color:var(--txt-2); }
.tool-call { border:1px solid var(--line); border-radius:10px; padding:12px 15px; background:var(--ink-2); margin-bottom:12px; }
.tc-name { font-size:11px; color:var(--amber); }
.tc-name,.tool-result summary { display:flex; align-items:center; gap:8px; cursor:pointer; font-size:11px; color:var(--txt-2); }
.tc-name > span,.tool-result summary > span { margin-left:auto; font-size:10px; color:var(--txt-3); }
.tool-result { padding:11px 14px; border-radius:9px; border:1px solid var(--line); background:var(--ink-2); }
.tool-result .msg-body { padding-top:14px; font-size:12px; }
.tc-args { font-size:10px; color:var(--txt-2); white-space:pre-wrap; max-height:120px; overflow:auto; margin-bottom:0; }
.run-bar { padding:12px 0 18px 20px; box-shadow:none; border:0; border-radius:0; background:transparent; border-left:1px solid var(--line); flex-shrink:0; }
.conversation-context{display:flex;align-items:center;gap:8px;padding:14px 0;color:var(--txt-2);font-size:12px}.conversation-context small{margin-left:auto;font-size:10px;color:var(--txt-3)}
.run-top { color:var(--txt-3); }
.process-toggle { border:0; background:transparent; color:var(--txt-3); }
.continue-task { margin-top:12px; }
.run-bar.done { background:transparent; }
.run-top { display:flex; align-items:center; flex-wrap:wrap; gap:12px; }
.run-top .mono { font-family:var(--font-ui); }
.cancel { margin-left:auto; }
.timeline { list-style:none; display:flex; gap:5px; padding:0; margin:16px 0 0; }
.tl { flex:1; display:flex; align-items:center; gap:6px; font-size:10px; color:var(--txt-3); padding:6px 4px; border-bottom:2px solid var(--line); }
.tl-dot { width:4px; height:4px; border-radius:50%; background:var(--line-strong); }
.tl.passed { color:var(--green); border-color:var(--green-line); }
.tl.current { color:var(--amber); border-color:var(--amber); }
.tl.current .tl-dot { background:var(--amber); animation:pulse 1.5s infinite; }
.approve { margin-top:15px; padding:15px; border-radius:10px; border:1px solid var(--blue-line); background:var(--blue-soft); }
.apv-title { display:flex; align-items:center; gap:8px; flex-wrap:wrap; font-size:12px; }
.apv-actions { display:flex; gap:8px; margin-top:12px; }
.ifacts { margin-top:15px; display:grid; gap:8px; }
.approval-diff {max-height:320px;overflow:auto;white-space:pre-wrap;font:11px/1.8 var(--font-mono);padding:14px;background:var(--ink-1);border-radius:8px;}
.ifact { flex-wrap:wrap; display:flex; align-items:center; gap:9px; padding:12px; border:1px solid var(--line); background:var(--ink-2); border-radius:9px; }
.ifact .btn { margin-left:auto; }
.err { color:var(--red); }
.execution-details { margin-top:15px; padding-top:12px; border-top:1px solid var(--line); font-size:11px; color:var(--txt-2); }
.execution-details summary,.ev summary { cursor:pointer; }
.execution-details ol { max-height:240px; overflow:auto; padding-left:20px; line-height:2; }
.event-payload { font:10px/1.7 var(--font-mono); color:var(--txt-2); white-space:pre-wrap; overflow-wrap:anywhere; }
.composer { padding:0 max(32px,calc((100% - 800px)/2)) 16px; flex-shrink:0; }
.composer-box { display:grid; grid-template-columns:minmax(0,1fr) 55px; grid-template-rows:auto 36px; background:var(--ink-2); border:1px solid var(--line-strong); border-radius:17px; box-shadow:0 4px 22px #20362906; transition:box-shadow .2s,border-color .2s; }
.composer-box:focus-within { border-color:var(--amber-deep); box-shadow:0 4px 22px #2036290b; }
.composer-input { resize:none; background:transparent; border:0; padding:19px 20px 8px; min-height:75px; line-height:1.7; font-size:13px; }
.composer-input:focus { box-shadow:none; border:0; outline:none; }
.send { width:34px; height:34px; border-radius:10px; padding:0; margin-top:17px; border:0; align-self:start; }
.send:disabled { background:var(--ink-3); color:var(--txt-3); opacity:1; }
.composer-toolbar { grid-column:1 / 3; display:flex; justify-content:space-between; align-items:center; padding:0 17px 9px; color:var(--txt-3); font-size:9px; }
.composer-box.with-plan{grid-template-rows:auto auto 36px}
.turn-mode{font-size:10px;color:var(--txt-3);font-weight:400;margin-left:5px}.plan-execute{display:flex;align-items:center;gap:6px;margin-top:16px;color:var(--txt-2)}
.plan-handoff{grid-column:1 / 3;display:flex;align-items:center;gap:7px;padding:8px 16px 0;font-size:11px;color:var(--txt-2)}.plan-handoff button{margin-left:auto}.steer-label{font-size:10px;color:var(--txt-3);white-space:nowrap}
.composer-controls { display:flex; align-items:center; gap:10px; min-width:0; }
.composer-shortcuts { flex-shrink:0; margin-left:8px; }
@media(max-width:1150px) { .composer-shortcuts { display:none; } }
.key-dot { margin:0 6px; opacity:.5; }
.composer-note { font-size:9px; text-align:center; color:var(--txt-3); margin:10px 0 0; }
.approval-arguments { margin:12px 0; padding:12px 14px; background:var(--ink-1); border:1px solid var(--line); border-radius:8px; max-height:280px; overflow:auto; }
.approval-arguments dt { color:var(--txt-3); font-size:11px; margin:8px 0 4px; } .approval-arguments dt:first-child { margin-top:0; }
.approval-arguments dd { margin:0; } .approval-arguments pre { white-space:pre-wrap; overflow-wrap:anywhere; font:12px/1.7 var(--font-mono); margin:0; }
.run-picker { display:flex; align-items:center; gap:12px; font-size:11px; color:var(--txt-3); padding:14px 32px; border-bottom:1px solid var(--line); }
.run-picker select { flex:1; min-width:0; background:transparent; border:0; font-size:11px; padding:4px; box-shadow:none; }
.monitor { width:244px; display:flex; flex-direction:column; min-height:0; border-left:1px solid var(--line); background:var(--ink-0); }
.mon-head { display:flex; justify-content:space-between; align-items:center; padding:23px 18px; }
.side-title { display:flex; align-items:center; gap:7px; font-size:11px; color:var(--txt-2); font-weight:500; }
.mon-body { flex:1; overflow:auto; padding:0 14px; }
.events { list-style:none; padding:0; margin:0; }
.ev { display:flex; gap:7px; align-items:baseline; padding:12px 3px; border-bottom:1px solid var(--line); font-size:10px; }
.ev-seq { display:none; }
.ev-type { flex:1; min-width:0; }
.ev summary { font-family:var(--font-ui); }
.ev-time { font-size:9px; font-family:var(--font-ui); }
.ev-ok { color:var(--green); } .ev-bad { color:var(--red); } .ev-wait { color:var(--blue); } .ev-tool { color:var(--amber); }
.history-more { align-self:center; }
@media(max-width:1190px) { .monitor { display:none; } }
@media(max-width:1060px) { .welcome { padding:35px; } .welcome h1 { font-size:43px; } .suggestions { gap:9px; } .suggestion { padding:16px 12px; } .suggestion strong { font-size:11px; } .suggestion > span:not(.suggestion-icon) { font-size:9px; } }

</style>
