<script setup lang="ts">
import {ref,onMounted,onBeforeUnmount} from 'vue'
import {api,identity} from '../api'
const emit=defineEmits<{open:[]}>(),count=ref(0)
let timer:ReturnType<typeof setInterval>|undefined,disposed=false
const seen=new Set<string>()
let owner='',initialized=false
async function refresh(){
 const current=identity.tenantId+':'+identity.userId
 if(current!==owner){owner=current;seen.clear();initialized=false;count.value=0}
 try{const result=await api.get<{notifications:Array<{id:string;title:string;read_at:string|null}>}>('/api/operations/notifications');if(disposed||current!==owner)return
 count.value=result.notifications.filter(n=>!n.read_at).length
 for(const n of result.notifications){if(initialized&&!seen.has(n.id)&&!n.read_at)window.desktopEnv?.notify?.('AgentOps',n.title);seen.add(n.id)}initialized=true
 }catch{/* Offline polling keeps the last known badge; the app already shows backend status. */}
}
onMounted(()=>{refresh();timer=setInterval(refresh,15000);window.addEventListener('agent:identity-changed',refresh)})
onBeforeUnmount(()=>{disposed=true;clearInterval(timer);window.removeEventListener('agent:identity-changed',refresh)})
</script>
<template><button class="btn btn-ghost btn-sm notice-bell" aria-label="查看任务通知" @click="emit('open')">通知<span v-if="count" class="notice-count">{{ count>99?'99+':count }}</span></button></template>
<style scoped>.notice-bell{font-size:11px;display:flex;gap:5px;align-items:center}.notice-count{background:var(--green);color:white;font-size:10px;border-radius:10px;min-width:16px;padding:0 4px}</style>
