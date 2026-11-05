import { ref, onBeforeUnmount } from 'vue'
import { API_BASE, headers, identity } from '../services/httpClient'

export function usePresentationPreview(id: () => string) {
  const page = ref(1), url = ref(''), loading = ref(false), error = ref('')
  let generation = 0, controller: AbortController | undefined, disposed = false
  async function load(target: number) {
    const current = ++generation, artifact = id(), owner = identity.tenantId + ':' + identity.userId
    controller?.abort(); controller = new AbortController(); const active = controller
    const timer = setTimeout(() => active.abort(), 60000)
    loading.value = true; error.value = ''
    try {
      const response = await fetch(`${API_BASE}/api/file-tools/artifacts/${encodeURIComponent(artifact)}/slides/${target}`, { headers: headers(), signal: active.signal })
      if (!response.ok) throw new Error('幻灯片预览失败，请稍后重试或下载查看（HTTP ' + response.status + '）')
      const image = await response.blob()
      if (disposed || current !== generation || owner !== identity.tenantId + ':' + identity.userId) return
      const next = URL.createObjectURL(image)
      try {
        // Decode before swapping so the old page is never labeled as the newly selected page.
        if (typeof Image !== 'undefined') { const preview = new Image(); preview.src = next; await preview.decode() }
        if (disposed || current !== generation || owner !== identity.tenantId + ':' + identity.userId) { URL.revokeObjectURL(next); return }
        const previous = url.value; url.value = next; page.value = target
        if (previous) URL.revokeObjectURL(previous)
      } catch (decodeError) { URL.revokeObjectURL(next); throw decodeError }
    } catch (e) {
      if (!disposed && current === generation) error.value = active.signal.aborted ? '预览超时，请重试或下载查看' : (e as Error).message
    } finally { clearTimeout(timer); if (!disposed && current === generation) loading.value = false }
  }
  onBeforeUnmount(() => { disposed = true; generation++; controller?.abort(); if (url.value) URL.revokeObjectURL(url.value) })
  return { page, url, loading, error, load }
}
