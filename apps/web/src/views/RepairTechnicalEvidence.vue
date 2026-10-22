<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { RepairApiError, repairProblem } from "@/repair/api";
import { collaborationRequest } from "@/repair/collaboration";
const props=defineProps<{invitationId:string;objectId:string;kind:"fault-photos"|"attachments"|"opinion-attachments"|"staff-attachments";filename?:string;blocked?:boolean}>();
const emit=defineEmits<{invalidated:[]}>(),session=useSessionStore();
const identity=computed(()=>JSON.stringify([session.user?.id,session.user?.roles,session.csrfToken,props.invitationId,props.objectId,props.kind]));
const busy=ref(false),error=ref(""),url=ref(""),mime=ref("");let generation=0,live=true,abort:AbortController|null=null;
function clear(){generation++;abort?.abort();abort=null;if(url.value)URL.revokeObjectURL(url.value);url.value="";mime.value="";busy.value=false;}
async function read(){if(busy.value || props.blocked)return;clear();const epoch=generation,owner=identity.value;busy.value=true;error.value="";
  const current=()=>live && generation===epoch && identity.value===owner;
  try{const prefix=`/api/v1/repair-collaborations/${props.invitationId}/${props.kind}/${props.objectId}/content?grant=`;
    const grant=await collaborationRequest<{url:string}>(`/repair-collaborations/${props.invitationId}/${props.kind}/${props.objectId}/download-grant`,{path:"",key:crypto.randomUUID(),version:0});if(!current())return;
    if(!grant.url.startsWith(prefix)||!/^[-A-Za-z0-9_.]{1,256}$/.test(grant.url.slice(prefix.length)))throw new Error("Invalid private evidence grant");
    abort=new AbortController();const response=await fetch(grant.url,{credentials:"include",cache:"no-store",signal:abort.signal});if(!response.ok)throw new RepairApiError(response.status,"REPAIR_EVIDENCE_READ_FAILED",{});
    const blob=await response.blob();if(!current())return;if(!["image/png","image/jpeg","image/webp","application/pdf"].includes(blob.type)||blob.size<=0||blob.size>(blob.type==="application/pdf"?20:10)*1024*1024)throw new Error("Invalid evidence bytes");mime.value=blob.type;url.value=URL.createObjectURL(blob);
  }catch(cause){if(current()){if(cause instanceof RepairApiError && [401,403,404].includes(cause.status)){clear();emit("invalidated");}error.value=repairProblem(cause);}}
  finally{if(current())busy.value=false;}
}
watch(identity,()=>{clear();error.value="";},{flush:"sync"});onBeforeUnmount(()=>{live=false;clear();});
</script>
<template><div class="technical-evidence"><button :disabled="busy || blocked" @click="read">读取依据 {{filename || objectId}}</button><span v-if="error" role="alert">{{error}}</span><figure v-if="url"><img v-if="mime.startsWith('image/')" :src="url" :alt="filename || '科技协助依据'"><a :href="url" :download="filename || (mime==='application/pdf'?'科技意见依据.pdf':'科技意见依据')">保存已授权依据</a><button @click="clear">关闭依据并清理</button></figure></div></template>
<style scoped>img{display:block;max-width:min(100%,720px);max-height:400px;}button{margin:6px;}a{margin:8px;}</style>
