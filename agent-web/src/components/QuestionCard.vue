<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
const props=defineProps<{ questionId: string; question: string; options: string[]; busy: boolean }>()
const emit=defineEmits<{ answer: [text: string] }>()
const choice=ref(''),custom=ref('')
const footer=ref<HTMLElement|null>(null)
const answer=computed(()=>choice.value==='__custom__'?custom.value.trim():choice.value)
watch(()=>props.questionId,()=>{choice.value='';custom.value=''})
watch(choice,async value=>{if(value!=='__custom__')return;await nextTick();footer.value?.scrollIntoView({block:'nearest'})})
</script>
<template>
  <form class="question-card" aria-label="补充信息" @submit.prevent="answer && emit('answer',answer)">
    <div class="question-heading"><AppIcon name="chat" :size="18" /><span>需要你的补充</span></div>
    <h3>{{ question || '请选择或填写继续任务所需的信息' }}</h3>
    <fieldset :disabled="busy"><label v-for="(option,index) in options" :key="option" class="question-option" :class="{ selected: choice===option }"><input v-model="choice" type="radio" :name="questionId" :value="option" /><span class="option-number">{{ index+1 }}</span><span>{{ option }}</span></label>
      <label class="question-option" :class="{ selected: choice==='__custom__' }"><input v-model="choice" type="radio" :name="questionId" value="__custom__" /><AppIcon name="plus" :size="15" /><span>自行补充</span></label>
      <textarea v-if="choice==='__custom__' || !options.length" v-model="custom" class="field question-answer" rows="3" maxlength="8000" placeholder="在这里填写你的补充要求…" aria-label="自行补充内容" @focus="choice='__custom__'"></textarea>
    </fieldset><div ref="footer" class="question-footer"><span>回答会补充到当前任务</span><button class="btn btn-primary btn-sm question-submit" :disabled="busy || !answer">{{ busy ? '提交中…' : '提交补充' }}</button></div>
  </form>
</template>
<style scoped>
.question-card { margin-top:18px; padding:20px; border:1px solid var(--line-strong); border-radius:12px; background:var(--ink-2); }.question-heading { display:flex; gap:8px; align-items:center; color:var(--green); font-size:11px; }.question-card h3 { font-size:14px; line-height:1.8; font-weight:550; margin:13px 0 16px; }.question-card fieldset { border:0; padding:0; margin:0; }.question-option { display:flex; align-items:center; gap:10px; padding:12px; margin:7px 0; border:1px solid var(--line); border-radius:9px; cursor:pointer; font-size:12px; color:var(--txt-2); }.question-option:hover,.question-option.selected { background:var(--amber-soft); border-color:var(--amber-deep); color:var(--txt-1); }.question-option input { accent-color:var(--green); }.option-number { color:var(--txt-3); font-size:10px; }.question-answer { margin-top:10px; width:100%; resize:vertical; min-height:90px; font-size:12px; }.question-footer { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-top:17px; }.question-footer>span { font-size:10px; color:var(--txt-3); }
</style>
