export function sessionStatus(status?: string): { label: string; spinning: boolean; kind: string } | null {
  if (!status) return null
  if (['queued', 'running', 'retry_wait'].includes(status))
    return { label: status === 'queued' ? '排队中' : status === 'retry_wait' ? '等待重试' : '正在运行', spinning: true, kind: 'running' }
  const waiting: Record<string, string> = {
    paused: '任务已暂停', pause_requested: '正在安全暂停',
    waiting_user: '等待你的补充', waiting_approval: '等待你授权', waiting_reconcile: '等待核对结果',
    cancel_requested: '正在停止',
  }
  return waiting[status] ? { label: waiting[status], spinning: false, kind: status === 'paused' ? 'paused' : status === 'cancel_requested' ? 'stopping' : 'waiting' } : null
}
