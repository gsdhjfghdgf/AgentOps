<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'

interface TrialRow {
  taskId: string
  score: number
  passed: boolean
  errorType: string
  durationMs: number
  trialNo?: number
  usedTokens?: number
  trace?: { model?: string; finalStatus?: string; toolNames?: string[]; runId?: string }
}

const suiteId = ref('')
const suiteName = ref('')
const importing = ref(false)
const running = ref(false)
const notice = ref('')
const trials = ref<TrialRow[]>([])
const summary = ref<{ totalCases: number; passedCases: number; avgScore: number; totalDurationMs: number } | null>(null)
const report = ref<Record<string, unknown> | null>(null)
const runtime = ref<{ provider: string; chatModel: string; embeddingModel: string } | null>(null)

async function importSuite() {
  importing.value = true
  notice.value = ''
  try {
    const r = await api.post<{ suiteId: string; name: string; caseCount?: number }>(
      '/api/eval/suites/builtin',
    )
    suiteId.value = r.suiteId
    suiteName.value = r.name
    notice.value = `套件「${r.name}」已导入${r.caseCount ? `（${r.caseCount} 用例）` : ''}`
  } catch (e) {
    notice.value = '导入失败：' + (e as Error).message
  } finally {
    importing.value = false
  }
}

async function runSuite() {
  if (!suiteId.value) return
  running.value = true
  notice.value = ''
  trials.value = []
  summary.value = null
  try {
    const r = await api.post<{
      name: string
      totalCases: number
      passedCases: number
      avgScore: number
      totalDurationMs: number
      trials: TrialRow[]
    }>(`/api/eval/suites/${suiteId.value}/run?timeoutSecondsPerCase=120`)
    summary.value = {
      totalCases: r.totalCases,
      passedCases: r.passedCases,
      avgScore: r.avgScore,
      totalDurationMs: r.totalDurationMs,
    }
    trials.value = r.trials
    notice.value = `评测完成：${r.passedCases}/${r.totalCases} 通过`
    await loadReport()
  } catch (e) {
    notice.value = '评测失败：' + (e as Error).message
  } finally {
    running.value = false
  }
}

async function loadReport() {
  if (!suiteId.value) return
  try {
    report.value = await api.get<Record<string, unknown>>(`/api/eval/suites/${suiteId.value}/report`)
    const history = report.value.trials as TrialRow[]
    if (Array.isArray(history)) {
      const latest = new Map<string, TrialRow>()
      history.forEach(row => { if (!latest.has(row.taskId) || (row.trialNo || 0) > (latest.get(row.taskId)?.trialNo || 0)) latest.set(row.taskId, row) })
      trials.value = Array.from(latest.values())
    }
  } catch {
    report.value = null
  }
}

onMounted(() => {
  notice.value = '先导入内置套件，再运行评测'
  refreshModel()
  window.addEventListener('agent:model-settings-changed', refreshModel)
})
function refreshModel() { void api.get<NonNullable<typeof runtime.value>>('/api/runtime').then(value => { runtime.value = value }).catch(() => {}) }
onBeforeUnmount(() => window.removeEventListener('agent:model-settings-changed', refreshModel))
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">评测</h1>
        <p class="small dim">检查真实任务状态、工具调用、生成文件与批准边界。</p>
      </div>
      <span v-if="notice" class="badge" :class="running ? 'run' : 'ok'">{{ notice }}</span>
    </header>

    <section class="card controls">
      <div v-if="runtime" class="small dim">{{ runtime.provider === 'mock' ? '演示模型' : '真实模型 · ' + runtime.chatModel }} · 向量 {{ runtime.embeddingModel }}<p>运行六项用例会调用当前模型并产生用量。销售与邮件工具使用演示数据；知识检索和文件生成走实际链路。</p></div>
      <p class="small dim">查询和报告须完成指定工具与结果校验；模糊请求须等待用户补充；邮件须停在等待批准，直接成功会判为失败。</p>
      <div class="ctl">
        <button class="btn" :disabled="importing" @click="importSuite">
          {{ importing ? '导入中…' : '导入内置套件' }}
        </button>
        <button class="btn btn-primary" :disabled="!suiteId || running" @click="runSuite">
          {{ running ? '评测运行中…' : '运行评测' }}
        </button>
        <span v-if="suiteId" class="mono dim small">suite: {{ suiteId }}</span>
      </div>

      <div v-if="summary" class="stats">
        <div class="stat">
          <span class="stat-num mono ok-num">{{ summary.passedCases }}/{{ summary.totalCases }}</span>
          <span class="stat-label">通过用例</span>
        </div>
        <div class="stat">
          <span class="stat-num mono amber-num">{{ (summary.avgScore * 100).toFixed(1) }}%</span>
          <span class="stat-label">平均得分</span>
        </div>
        <div class="stat">
          <span class="stat-num mono">{{ (summary.totalDurationMs / 1000).toFixed(1) }}s</span>
          <span class="stat-label">总耗时</span>
        </div>
      </div>
    </section>

    <section v-if="trials.length" class="card" aria-label="评测用例结果">
      <table class="table">
        <thead>
          <tr>
            <th>任务</th><th>得分</th><th>结果</th><th>执行状态 / 工具</th><th>错误类型</th><th>用量 / 耗时</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(t, i) in trials" :key="i">
            <td class="mono small">{{ t.taskId }}</td>
            <td class="mono">{{ (t.score * 100).toFixed(0) }}</td>
            <td>
              <span class="badge" :class="t.passed ? 'ok' : 'bad'">{{ t.passed ? '通过' : '失败' }}</span>
            </td>
            <td class="small"><div>{{ t.trace?.finalStatus || '—' }}</div><span class="dim">{{ t.trace?.toolNames?.join(' → ') || '无工具调用' }}</span></td>
            <td class="mono dim small">{{ t.errorType || '—' }}</td>
            <td class="mono dim small">{{ t.usedTokens ?? '—' }} tokens<div>{{ (t.durationMs / 1000).toFixed(1) }}s</div></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="report" class="card" aria-label="套件报告">
      <details><summary>查看历次评测原始报告</summary><pre class="report mono">{{ JSON.stringify(report, null, 2) }}</pre></details>
    </section>
  </div>
</template>

<style scoped>
.page {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--s5);
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s4);
}
.page-title { margin: 0; font-size: 22px; font-weight: 800; }

.controls { display: flex; flex-direction: column; gap: var(--s4); }
.ctl { display: flex; align-items: center; gap: var(--s3); flex-wrap: wrap; }

.stats { display: flex; gap: var(--s5); }
.stat { display: flex; flex-direction: column; align-items: center; gap: 2px; min-width: 110px; }
.stat-num { font-size: 30px; font-weight: 700; line-height: 1.2; }
.stat-label { font-size: 12px; letter-spacing: 0.14em; color: var(--txt-3); }
.ok-num { color: var(--green); }
.amber-num { color: var(--amber); }

.pane-title {
  margin: 0 0 var(--s3);
  font-size: 13px;
  letter-spacing: 0.2em;
  color: var(--amber);
  font-weight: 700;
  text-transform: uppercase;
}
.report {
  margin: 0;
  max-height: 420px;
  overflow: auto;
  font-size: 12.5px;
  color: var(--txt-2);
  white-space: pre-wrap;
}
</style>
