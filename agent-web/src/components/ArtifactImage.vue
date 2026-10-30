<script setup lang="ts">
import { ref,watch,onBeforeUnmount } from 'vue'
import {artifactBlob} from '../api'
const props=defineProps<{id:string;name:string}>();const url=ref(''),error=ref(''),open=ref(false);let generation=0
watch(()=>props.id,async id=>{const current=++generation;if(url.value)URL.revokeObjectURL(url.value);url.value='';try{const blob=await artifactBlob(id);if(current!==generation)return;if(!blob.type.startsWith('image/'))return;url.value=URL.createObjectURL(blob)}catch(e){error.value=(e as Error).message}},{immediate:true})
onBeforeUnmount(()=>{generation++;if(url.value)URL.revokeObjectURL(url.value)})
</script>
<template><button v-if="url" class="artifact-preview" @click="open=true"><img :src="url" :alt="name" /></button><small v-else-if="error">{{ error }}</small><div v-if="open" class="image-overlay" @click.self="open=false"><button class="btn" aria-label="关闭截图预览" @click="open=false">关闭</button><img :src="url" :alt="name" /></div></template>
<style scoped>.artifact-preview{display:block;margin-top:12px;width:100%;padding:0;border:0;background:transparent;cursor:zoom-in}.artifact-preview img{display:block;max-width:100%;max-height:440px;object-fit:contain;border:1px solid var(--line);border-radius:10px}.image-overlay{position:fixed;inset:0;z-index:55;display:grid;place-items:center;padding:50px 24px 24px;background:#101913dd}.image-overlay>button{position:absolute;right:24px;top:14px;background:var(--ink-2)}.image-overlay>img{max-width:96vw;max-height:88vh;object-fit:contain}</style>
