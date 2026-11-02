<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { useObjectUrlPreview } from "@/composables/useObjectUrlPreview";
import { RepairApiError, repairProblem } from "@/features/repair/api";
import { collaborationEvidenceContent, collaborationRequest } from "@/features/repair/collaboration";
import FamsMediaPreview from "@/components/FamsMediaPreview.vue";
const props=defineProps<{invitationId:string;objectId:string;kind:"fault-photos"|"attachments"|"opinion-attachments"|"staff-attachments";filename?:string;blocked?:boolean}>();
const emit=defineEmits<{invalidated:[]}>(),session=useSessionStore();
const identity=computed(()=>JSON.stringify([session.user?.id,session.user?.roles,session.csrfToken,props.invitationId,props.objectId,props.kind]));
const busy=shallowRef(false),error=shallowRef("");const preview=useObjectUrlPreview();const liveness=useRequestLiveness(()=>[identity.value]);
function clear(){liveness.invalidate();preview.close();busy.value=false;}
async function read(){if(busy.value || props.blocked)return;clear();const turn=liveness.begin();busy.value=true;error.value="";
  try{const prefix=`/api/v1/repair-collaborations/${props.invitationId}/${props.kind}/${props.objectId}/content?grant=`;
    const grant=await collaborationRequest<{url:string}>(`/repair-collaborations/${props.invitationId}/${props.kind}/${props.objectId}/download-grant`,{path:"",key:crypto.randomUUID(),version:0});if(!liveness.isCurrent(turn))return;
    if(!grant.url.startsWith(prefix)||!/^[-A-Za-z0-9_.]{1,256}$/.test(grant.url.slice(prefix.length)))throw new Error("Invalid private evidence grant");
    const signal=preview.beginDownload();const blob=await collaborationEvidenceContent(props.invitationId,props.kind,props.objectId,grant.url.slice(prefix.length),signal);
    if(!liveness.isCurrent(turn))return;if(!["image/png","image/jpeg","image/webp","application/pdf"].includes(blob.type)||blob.size<=0||blob.size>(blob.type==="application/pdf"?20:10)*1024*1024)throw new Error("Invalid evidence bytes");preview.show(blob);
  }catch(cause){if(liveness.isCurrent(turn)){if(cause instanceof RepairApiError && [401,403,404].includes(cause.status)){clear();emit("invalidated");}error.value=repairProblem(cause);}}
  finally{if(liveness.isCurrent(turn))busy.value=false;}
}
watch(identity,()=>{clear();error.value="";},{flush:"sync"});onBeforeUnmount(()=>{clear();});
const previewIsImage=computed(()=>preview.mime.startsWith('image/'));
const downloadName=computed(()=>props.filename || (preview.mime==='application/pdf'?'科技意见依据.pdf':'科技意见依据'));
</script>
<template>
  <div class="fams-inline">
    <button class="fams-button" data-kind="secondary" type="button" :disabled="busy || blocked" @click="read">读取依据 {{filename || objectId}}</button>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{error}}</strong></p>
    <FamsMediaPreview v-if="preview.url" @dismiss="clear">
      <figure><img v-if="previewIsImage" :src="preview.url" :alt="filename || '科技协助依据'"><a :href="preview.url" :download="downloadName">保存已授权依据</a><button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="clear">关闭依据并清理</button></figure>
    </FamsMediaPreview>
  </div>
</template>
