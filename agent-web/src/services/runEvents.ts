import { API_BASE, identity } from './httpClient'
import type { RunEvent } from '../api'

export function openRunEvents(
  runId: string,
  lastEventSeq: number,
  onEvent: (e: RunEvent) => void,
  onDone: (reason: string) => void,
  onConnection?: (connected: boolean) => void,
): () => void {
  const url =
    `${API_BASE}/api/runs/${runId}/events?lastEventSeq=${lastEventSeq}` +
    `&tenantId=${identity.tenantId}&userId=${identity.userId}`
  const es = new EventSource(url)
  let closed = false
  let cursor = lastEventSeq

  const handle = (e: MessageEvent, fallbackType: string) => {
    if (closed) return
    let data: RunEvent
    try {
      data = JSON.parse(e.data)
    } catch {
      return
    }
    const type = data.type || (e as unknown as { type?: string }).type || fallbackType
    data.type = type
    if (type === 'heartbeat') return
    if (type === 'run.snapshot') {
      if (data.reset === true) cursor = data.seq
    } else {
      if (!Number.isSafeInteger(data.seq) || data.seq <= cursor) return
      cursor = data.seq
    }
    onEvent(data)
    if (
      ['run.succeeded', 'run.failed', 'run.cancelled', 'run.timed_out', 'run.budget_exceeded'].includes(type) ||
      (type === 'run.snapshot' && ['succeeded', 'failed', 'cancelled', 'timed_out', 'budget_exceeded'].includes(String(data.status)))
    ) {
      closed = true
      es.close()
      onDone(type)
    }
  }

  es.onmessage = (e) => handle(e, 'message')
  // 命名事件：后端每条事件都带 type 字段，统一走 data 解析
  for (const t of [
    'run.pause_requested', 'run.paused', 'run.resumed',
    'run.created', 'run.queued', 'run.snapshot', 'message.start','message.delta','message.committed','message.interrupted', 'run.command_received', 'run.command_applied',
    'run.phase', 'run.status', 'run.succeeded', 'run.failed', 'run.cancelled',
    'run.cancel_requested', 'run.timed_out', 'run.budget_exceeded',
    'tool.started', 'tool.finished', 'approval.requested', 'approval.decided',
    'run.waiting_user', 'run.waiting_reconcile', 'run.retry_wait',
    'tool.completed', 'run.validation', 'artifact.created', 'heartbeat', 'error',
  ]) {
    es.addEventListener(t, (e) => handle(e as MessageEvent, t))
  }
  es.onopen = () => onConnection?.(true)
  es.onerror = () => {
    onConnection?.(false)
    if (closed) return
    // EventSource 自动重连；终态由服务端 complete 关闭，此处仅兜底
  }
  return () => {
    closed = true
    es.close()
  }
}

// ==================== 业务操作 ====================
