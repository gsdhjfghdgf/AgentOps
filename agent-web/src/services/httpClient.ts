/** Shared transport only: identity, timeouts and concurrent GET coalescing. No business routes. */
const DEV = import.meta.env.DEV
const injected = typeof window !== 'undefined' ? window.__AGENT_DESKTOP__?.apiBase : undefined
export const API_BASE = injected || (DEV ? '' : 'http://127.0.0.1:8080')
export const identity = {
  tenantId: localStorage.getItem('agent.tenantId') || '11111111-1111-1111-1111-111111111111',
  userId: localStorage.getItem('agent.userId') || '22222222-2222-2222-2222-222222222222',
}
const pending = new Map<string, Promise<unknown>>()
let revision = 0
const ownerKey = () => identity.tenantId + ':' + identity.userId
export function saveIdentity(tenantId: string, userId: string) {
  identity.tenantId = tenantId
  identity.userId = userId
  localStorage.setItem('agent.tenantId', tenantId)
  localStorage.setItem('agent.userId', userId)
  revision++
  pending.clear()
}
export function headers(extra?: Record<string, string>): Record<string, string> {
  return { 'X-Tenant-Id': identity.tenantId, 'X-User-Id': identity.userId, ...extra }
}
export async function parse<T>(response: Response): Promise<T> {
  const text = await response.text()
  let body: unknown
  try { body = text ? JSON.parse(text) : null } catch { body = { message: text } }
  if (!response.ok) {
    const error = body as { message?: string; error?: string } | null
    throw new Error(error?.message || error?.error || 'HTTP ' + response.status)
  }
  return body as T
}
export interface RequestOptions { timeoutMs?: number }
async function request<T>(path: string, method: string, data?: unknown, options?: RequestOptions): Promise<T> {
  const owner = ownerKey(), controller = new AbortController()
  const timeout = options?.timeoutMs ?? (method === 'GET' ? 15000 : path.startsWith('/api/model-settings') ? 120000 : 60000)
  const timer = setTimeout(() => controller.abort(), timeout)
  try {
    const value = await fetch(API_BASE + path, { method, signal: controller.signal,
      headers: headers(data === undefined ? undefined : { 'Content-Type': 'application/json' }),
      ...(data === undefined ? {} : { body: JSON.stringify(data) }),
    }).then(parse<T>)
    if (owner !== ownerKey()) throw new Error('工作空间已切换，已忽略旧连接的返回结果')
    return value
  } catch (error) {
    if (controller.signal.aborted) throw new Error('连接超时，请检查后端及虚拟机连接')
    throw error
  } finally { clearTimeout(timer) }
}
async function mutate<T>(path: string, method: string, value?: unknown, options?: RequestOptions): Promise<T> {
  revision++
  try { return await request<T>(path, method, value, options) } finally { revision++ }
}
export const api = {
  get<T>(path: string, options?: RequestOptions): Promise<T> {
    // In-flight requests only. No response TTL: permissions, statuses and edits stay fresh.
    const key = ownerKey() + ':' + revision + ':' + (options?.timeoutMs ?? 15000) + ':' + path
    let task = pending.get(key)
    if (!task) {
      task = request<T>(path, 'GET', undefined, options)
      pending.set(key, task)
      const current = task
      void task.finally(() => { if (pending.get(key) === current) pending.delete(key) }).catch(() => {})
    }
    // Consumers may edit their local form data; never share mutable response objects.
    return task.then(value => structuredClone(value) as T)
  },
  post<T>(path: string, data?: unknown, options?: RequestOptions) { return mutate<T>(path, 'POST', data ?? {}, options) },
  put<T>(path: string, data: unknown, options?: RequestOptions) { return mutate<T>(path, 'PUT', data, options) },
  del<T>(path: string, options?: RequestOptions) { return mutate<T>(path, 'DELETE', undefined, options) },
  upload<T>(path: string, file: File, progress?: (percent: number) => void): Promise<T> {
    revision++
    const owner = ownerKey()
    return new Promise<T>((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      xhr.open('POST', API_BASE + path)
      for (const [key, value] of Object.entries(headers())) xhr.setRequestHeader(key, value)
      xhr.timeout = 300000
      xhr.upload.onprogress = event => { if (event.lengthComputable) progress?.(Math.round(event.loaded * 100 / event.total)) }
      xhr.onload = () => {
        if (owner !== ownerKey()) { reject(new Error('工作空间已切换，请在原工作空间查看导入结果')); return }
        parse<T>(new Response(xhr.responseText, { status: xhr.status })).then(resolve, reject)
      }
      xhr.onerror = () => reject(new Error('连接中断，请检查后端状态'))
      xhr.ontimeout = () => reject(new Error('导入超时，请刷新文档列表确认结果'))
      const form = new FormData(); form.append('file', file); xhr.send(form)
    }).finally(() => { revision++ })
  },
}
