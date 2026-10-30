export interface ModelProfile {
  id: string
  versionId?: string
  name: string
  url: string
  model: string
  keyConfigured: boolean
  keyUnavailable?: boolean
  provider?: string
}
export interface ModelCatalog {
  selectedProfileId: string
  profileId: string
  profiles: ModelProfile[]
  defaultProfile: ModelProfile
  savedProfileId?: string
}
export function profileHost(profile: ModelProfile): string {
  try { return new URL(profile.url).host } catch { return profile.provider === 'mock' ? '演示模型' : '系统服务' }
}
export function selectedProfile(catalog: ModelCatalog): ModelProfile {
  return catalog.profiles.find(p => p.id === catalog.selectedProfileId) ?? catalog.defaultProfile
}
