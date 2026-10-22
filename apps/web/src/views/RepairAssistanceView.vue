<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { RepairApiError, repairProblem, repairStates, type RepairMutation } from "@/repair/api";
import { collaborationRequest, opinionResults, type AssistedRepair } from "@/repair/collaboration";
import RepairTechnicalEvidence from "./RepairTechnicalEvidence.vue";
const session=useSessionStore(),route=useRoute();
const identity=computed(()=>JSON.stringify([session.user?.id,session.user?.roles,session.csrfToken,route.params.id]));
const rows=ref<AssistedRepair[]>([]),total=ref(0),page=ref(1),detail=ref<AssistedRepair|null>(null),busy=ref(false),error=ref(""),notice=ref("");
const judgement=ref(""),method=ref(""),result=ref("NEEDS_WORK"),occurredAt=ref(""),file=ref<File|null>(null),attachmentIds=ref<string[]>([]);
const prepared=ref<RepairMutation|null>(null),pending=ref<RepairMutation|null>(null),locked=computed(()=>busy.value||!!prepared.value||!!pending.value);
let generation=0,live=true;
const current=(epoch:number,owner:string)=>live&&generation===epoch&&identity.value===owner;
function reset(){generation++;rows.value=[];detail.value=null;total.value=0;page.value=1;judgement.value="";method.value="";result.value="NEEDS_WORK";occurredAt.value="";file.value=null;attachmentIds.value=[];prepared.value=null;pending.value=null;busy.value=false;}
function failure(cause:unknown){if(cause instanceof RepairApiError&&[401,403,404].includes(cause.status))reset();error.value=repairProblem(cause);}
async function read(){if(busy.value||prepared.value)return;const epoch=++generation,owner=identity.value,selected=detail.value?.invitationId ?? (/^[1-9][0-9]*$/.test(String(route.params.id ?? ""))?String(route.params.id):"");busy.value=true;error.value="";
 try{const board=await collaborationRequest<{items:AssistedRepair[];total:number}>(`/repair-collaborations?page=${page.value}&pageSize=20`);const item=selected?await collaborationRequest<AssistedRepair>(`/repair-collaborations/${selected}`):null;if(current(epoch,owner)){rows.value=board.items;total.value=board.total;detail.value=item;}}
 catch(cause){if(current(epoch,owner))failure(cause);}finally{if(current(epoch,owner))busy.value=false;}}
async function open(row:AssistedRepair){if(locked.value)return;const epoch=++generation,owner=identity.value;busy.value=true;error.value="";detail.value=null;judgement.value="";method.value="";occurredAt.value="";attachmentIds.value=[];file.value=null;
 try{const value=await collaborationRequest<AssistedRepair>(`/repair-collaborations/${row.invitationId}`);if(current(epoch,owner))detail.value=value;}catch(cause){if(current(epoch,owner))failure(cause);}finally{if(current(epoch,owner))busy.value=false;}}
function choose(event:Event){if(!locked.value)file.value=(event.target as HTMLInputElement).files?.[0] ?? null;}
function prepare(upload=false){if(locked.value||!detail.value)return;error.value="";let body:unknown,selectedFile:File|undefined;
 if(upload){const chosen=file.value;if(!chosen||chosen.size<=0||!(/\.(png|jpe?g|webp|pdf)$/i.test(chosen.name))||chosen.size>(/\.pdf$/i.test(chosen.name)?20:10)*1024*1024){error.value="请选PNG、JPEG、WebP图片（10MB以内）或PDF依据（20MB以内）。";return;}selectedFile=chosen;}
 else{const at=new Date(occurredAt.value);if(judgement.value.trim().length<3||method.value.trim().length<3||!Number.isFinite(at.getTime())){error.value="请填写实际判断、处理方式和发生时间。";return;}body={judgement:judgement.value.trim(),method:method.value.trim(),result:result.value,occurredAt:at.toISOString(),attachmentIds:[...attachmentIds.value]};}
 prepared.value={path:`/repair-collaborations/${detail.value.invitationId}/${upload?'attachments':'opinions'}`,key:crypto.randomUUID(),version:detail.value.version,body,file:selectedFile};}
async function send(){if(busy.value||!pending.value||!detail.value)return;const operation=pending.value,id=detail.value.invitationId,epoch=++generation,owner=identity.value;busy.value=true;error.value="";
 try{const reply=await collaborationRequest<{objectId?:string}>(operation.path,operation);const item=await collaborationRequest<AssistedRepair>(`/repair-collaborations/${id}`);if(current(epoch,owner)){detail.value=item;pending.value=null;file.value=null;if(!reply.objectId){attachmentIds.value=[];judgement.value="";method.value="";occurredAt.value="";}notice.value=reply.objectId?"依据已保存，请在本次意见中明确选用。":"意见已追加，维修仍由合法处理库管实际办理。";}}
 catch(cause){if(current(epoch,owner)){if(cause instanceof RepairApiError&&cause.status>=400&&cause.status<500)pending.value=null;failure(cause);}}finally{if(current(epoch,owner))busy.value=false;}}
async function confirm(){if(!prepared.value||busy.value)return;pending.value=prepared.value;prepared.value=null;await send();}
async function paginate(delta:number){const next=page.value+delta;if(locked.value||next<1||(next-1)*20>=total.value)return;page.value=next;await read();}
watch(identity,()=>{reset();error.value="";notice.value="";void read();},{immediate:true,flush:"sync"});onBeforeUnmount(()=>{live=false;reset();});
</script>
<template><AppChrome><section aria-label="我协助的报修"><h1>我协助的报修</h1><p>只显示明确邀请的必要资料。判断和建议保留历史；实际维修、返厂或报废由相应合法业务办理。</p><p v-if="error" role="alert">{{error}}</p><p v-if="notice" role="status">{{notice}}</p><button :disabled="busy || !!prepared" @click="read">刷新受邀维修</button><button v-if="pending" :disabled="busy" @click="send">重试原协助请求</button>
<section v-if="prepared" aria-label="确认本次科技意见"><p>请核对实际判断、发生时间、结果及附件。此操作仅追加意见或依据，不代表维修完成或取得库管权限。</p><button :disabled="busy" @click="confirm">确认追加本次协助依据</button><button :disabled="busy" @click="prepared=null">返回修改科技意见</button></section>
<ul><li v-for="row in rows" :key="row.invitationId"><button :disabled="locked" @click="open(row)">{{row.asset.assetCode}} · {{row.asset.name}} · {{repairStates[row.status]}}</button></li></ul><p v-if="!rows.length && !busy">当前没有有效的协助邀请。</p><button :disabled="locked || page===1" @click="paginate(-1)">上一页受邀维修</button>共{{total}}项<button :disabled="locked || page*20>=total" @click="paginate(1)">下一页受邀维修</button>
<section v-if="detail" aria-label="当前受邀故障"><h2>{{detail.asset.assetCode}} · {{detail.asset.name}}</h2><p>{{detail.fault}} · 故障发生 {{new Date(detail.faultOccurredAt).toLocaleString()}}</p><p>当前维修阶段：{{repairStates[detail.status]}}</p><h3>必要故障照片</h3><p v-if="!detail.photosAvailable">证据服务暂不可用。</p><RepairTechnicalEvidence v-for="item in detail.faultPhotos" :key="item.objectId" :invitation-id="detail.invitationId" :object-id="item.objectId" kind="fault-photos" :filename="item.filename" :blocked="locked" @invalidated="reset"/>
<fieldset :disabled="locked"><legend>追加实际意见</legend><label>故障判断<textarea v-model="judgement" maxlength="2000"/></label><label>处理方式与依据<textarea v-model="method" maxlength="2000"/></label><label>实际发生时间<input v-model="occurredAt" type="datetime-local"></label><label>判断结果<select v-model="result"><option v-for="(label,key) in opinionResults" :key="key" :value="key">{{label}}</option></select></label><label v-for="item in detail.attachments" :key="item.objectId"><input v-model="attachmentIds" type="checkbox" :value="item.objectId">本次选用 {{item.filename}}</label><label>必要图片或PDF<input type="file" accept=".png,.jpg,.jpeg,.webp,.pdf" @change="choose"></label><button @click="prepare(true)">核对上传协助附件</button><button @click="prepare()">核对追加科技意见</button></fieldset>
<h3>已保存的本邀请依据</h3><RepairTechnicalEvidence v-for="item in detail.attachments" :key="item.objectId" :invitation-id="detail.invitationId" :object-id="item.objectId" kind="attachments" :filename="item.filename" :blocked="locked" @invalidated="reset"/>
<h3>最近100条既有意见 / 共{{detail.opinionTotal || 0}}条</h3><ul><li v-for="row in detail.opinions" :key="row.id">{{row.actor_username}} · {{new Date(row.occurred_at).toLocaleString()}} · {{opinionResults[row.opinion_result]}}<p>{{row.judgement}}</p><p>{{row.method}}</p><RepairTechnicalEvidence v-for="id in row.attachment_ids_json" :key="id" :invitation-id="detail.invitationId" :object-id="id" kind="opinion-attachments" :blocked="locked" @invalidated="reset"/></li></ul>
</section></section></AppChrome></template>
<style scoped>section{margin:18px 0;}fieldset{padding:16px;border:1px solid #d9e0e8;}label{display:block;margin:10px 0;}textarea{display:block;width:min(100%,720px);min-height:70px;}button{margin:6px;}</style>
