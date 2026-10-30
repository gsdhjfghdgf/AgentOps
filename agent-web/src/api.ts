/** Public route facade. Transport and SSE lifecycles live in services/. */
import { api, API_BASE, headers, parse } from './services/httpClient'
export { api, API_BASE, identity, saveIdentity } from './services/httpClient'
export { openRunEvents } from './services/runEvents'

// ==================== 类型 ====================

export type RunMode = 'chat' | 'plan' | 'build'

export interface SessionItem {
  sessionId: string
  projectId?: string | null
  title: string
  pinned?: boolean
  archived?: boolean
  updatedAt?: string
  createdAt?: string
  activeRunId?: string
  activeRunStatus?: string
}

export interface MessageItem {
  toolCallId?: string | null
  seq: number
  role: string
  content: string
  runId?: string | null
  createdAt: string
  toolCalls?: Array<{ id: string; name: string; arguments: unknown }>
  kind?: string
  attemptId?: string
}

export interface MessagePage {
  messages: MessageItem[]
  nextBeforeSeq: number | null
  hasMore: boolean
}

export interface ArtifactItem { objectId: string; name: string; kind: string; byteSize: number }

export interface RunView {
  runId: string
  sessionId: string
  status: string
  phase: string
  mode?: RunMode
  planRunId?: string
  questionId?: string | null
  questionText?: string | null
  questionOptions?: string[]
  reasonCode?: string | null
  tokenUsed: number
  createdAt?: string
  finishedAt?: string | null
}

export interface ApprovalItem {
  scope?: 'task_network' | 'task_command' | 'task_browser' | 'task_sandbox' | 'operation'
  projectDirectory?: string
  previewDiff?: string
  previewError?: string
  approvalId: string
  tool: string
  digest?: string
  expiresAt?: string
  description?: string
  effectClass?: string
  arguments?: Record<string, unknown>
}

export interface ApprovalDetail extends ApprovalItem {
  runId: string
  status: string
  reason?: string
}

export interface RunEvent {
  runId: string
  seq: number
  type: string
  at: string
  [k: string]: unknown
}

// ==================== SSE（EventSource 无法带 header，主体走 query） ====================


export async function createSession(title: string, projectId: string | null = null): Promise<string> {
  const r = await api.post<{ sessionId: string }>('/api/sessions', { title, projectId })
  return r.sessionId
}

export async function listSessions(query = '', archived = false): Promise<SessionItem[]> {
  const r = await api.get<{ sessions: SessionItem[] }>(`/api/sessions?limit=100&query=${encodeURIComponent(query)}&archived=${archived}`)
  return r.sessions
}

export async function listMessages(sessionId: string): Promise<MessageItem[]> {
  return (await listMessagePage(sessionId)).messages
}

export const updateSession = (id: string, changes: { title?: string; pinned?: boolean; archived?: boolean }) =>
  api.put(`/api/sessions/${id}`, changes)
export const deleteSession = (id: string) => api.del(`/api/sessions/${id}`)
export const clearSessions = () => api.del('/api/sessions')
export const steerRun = (runId: string, text: string, commandId: string) =>
  api.post<{ commandId: string; status: string }>(`/api/runs/${runId}/steer`, { commandId, text })

export const continueRun = (runId: string) => api.post(`/api/runs/${runId}/continue`, {})

export function listMessagePage(sessionId: string, beforeSeq?: number): Promise<MessagePage> {
  return api.get(`/api/sessions/${sessionId}/messages?limit=50${beforeSeq == null ? '' : `&beforeSeq=${beforeSeq}`}`)
}

export async function listRuns(sessionId: string): Promise<RunView[]> {
  return (await api.get<{ runs: RunView[] }>(`/api/sessions/${sessionId}/runs?limit=100`)).runs
}

export async function listArtifacts(runId: string): Promise<ArtifactItem[]> {
  return (await api.get<{ artifacts: ArtifactItem[] }>(`/api/artifacts/by-run/${runId}`)).artifacts
}
export async function artifactBlob(id: string): Promise<Blob> {
  const response=await fetch(`${API_BASE}/api/artifacts/${id}/download`,{headers:headers()})
  if(!response.ok)throw new Error('图片加载失败');return response.blob()
}

export async function createRun(sessionId: string, message: string, requestKey: string = crypto.randomUUID(),
  mode: RunMode = 'build', planRunId?: string): Promise<string> {
  const r = await api.post<{ runId: string }>(`/api/sessions/${sessionId}/runs`, {
    message,
    taskType: 'chat',
    requestKey,
    mode,
    ...(planRunId ? { planRunId } : {}),
  })
  return r.runId
}

export function getRun(runId: string): Promise<RunView> {
  return api.get<RunView>(`/api/runs/${runId}`)
}

export interface RunSnapshot {
  run: RunView
  messages: MessageItem[]
  artifacts: ArtifactItem[]
  hasMore: boolean
}
export function getRunSnapshot(runId: string, afterSeq = 0): Promise<RunSnapshot> {
  return api.get('/api/runs/' + runId + '/sync?afterSeq=' + afterSeq)
}

export function cancelRun(runId: string): Promise<unknown> {
  return api.post(`/api/runs/${runId}/cancel`)
}

export const pauseRun = (runId: string, commandId: string) =>
  api.post('/api/runs/' + runId + '/pause', { commandId })
export const resumeRun = (runId: string, commandId: string, text: string) =>
  api.post('/api/runs/' + runId + '/resume', { commandId, text })

export function supplement(runId: string, sessionId: string, questionId: string, answer: string) {
  return api.post(`/api/sessions/${sessionId}/runs/${runId}/supplement`, { questionId, answer })
}

export async function listApprovals(runId: string): Promise<ApprovalItem[]> {
  const r = await api.get<{ approvals: ApprovalItem[] }>(`/api/runs/${runId}/approvals`)
  return r.approvals
}

export function decideApproval(approvalId: string, decision: 'approved' | 'rejected', reason?: string) {
  return api.post(`/api/approvals/${approvalId}/decision`, { decision, reason })
}

/** 产物下载：fetch 成 blob 后用 a[download] 触发保存 */
export async function downloadArtifact(objectId: string): Promise<void> {
  const res = await fetch(`${API_BASE}/api/artifacts/${objectId}/download`, {
    headers: headers(),
  })
  if (!res.ok) throw new Error(`下载失败 HTTP ${res.status}`)
  const disposition = res.headers.get('Content-Disposition') || ''
  const match = /filename\*=UTF-8''([^;]+)/i.exec(disposition) || /filename="?([^";]+)"?/i.exec(disposition)
  const name = match ? decodeURIComponent(match[1]) : `${objectId}.md`
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}
