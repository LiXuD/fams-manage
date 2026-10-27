<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { RepairApiError, repairProblem, type RepairMutation } from "@/repair/api";
import { collaborationRequest, opinionResults, type CollaborationBoard } from "@/repair/collaboration";
import RepairTechnicalEvidence from "./RepairTechnicalEvidence.vue";
const props = defineProps<{ repairId: string; requestVersion: number; blocked?: boolean }>();
const emit = defineEmits<{ changed: []; invalidated: []; busyChange: [value: boolean] }>();
const session = useSessionStore(), board = ref<CollaborationBoard | null>(null), technicians = ref<{id:string; username:string; name:string}[]>([]);
const techPage = ref(1), techTotal = ref(0), technician = ref(""), reason = ref(""), error = ref(""), busy = ref(false);
const prepared = ref<RepairMutation | null>(null), pending = ref<RepairMutation | null>(null);
const identity = computed(()=>JSON.stringify([session.user?.id,session.user?.roles,session.csrfToken,props.repairId]));
const locked = computed(()=>busy.value || !!prepared.value || !!pending.value);
let generation = 0, live = true;
const current = (epoch:number,owner:string)=>live && generation===epoch && identity.value===owner;
function clear() { generation++;board.value=null;technicians.value=[];techPage.value=1;techTotal.value=0;technician.value="";reason.value="";prepared.value=null;pending.value=null;busy.value=false; }
function failure(cause:unknown) { if (cause instanceof RepairApiError && [401,403,404].includes(cause.status)) { clear(); emit("invalidated"); } error.value=repairProblem(cause); }
async function read() {
  if (busy.value || prepared.value) return;
  const epoch=++generation,owner=identity.value;busy.value=true;error.value="";
  try { const value=await collaborationRequest<CollaborationBoard>(`/repairs/${props.repairId}/collaborations`);if(current(epoch,owner))board.value=value; }
  catch(cause){if(current(epoch,owner))failure(cause);}finally{if(current(epoch,owner))busy.value=false;}
}
async function candidates(delta=0) {
  if(locked.value || props.blocked || !board.value?.canInvite)return;
  const next=techPage.value+delta;if(next<1 || delta>0 && (next-1)*20>=techTotal.value)return;
  const epoch=++generation,owner=identity.value;busy.value=true;error.value="";
  try { const value=await collaborationRequest<{items:{id:string;username:string;name:string}[];total:number}>(`/repairs/${props.repairId}/eligible-technicians?page=${next}&pageSize=20`);
    if(current(epoch,owner)){technicians.value=value.items;techTotal.value=value.total;techPage.value=next;technician.value="";}
  }catch(cause){if(current(epoch,owner))failure(cause);}finally{if(current(epoch,owner))busy.value=false;}
}
function prepare(invitation?:string) {
  if(locked.value || props.blocked || !board.value?.canInvite)return;
  if(reason.value.trim().length<3){error.value="请填写邀请或撤销的明确原因。";return;}
  if(!invitation && !technicians.value.some(row=>row.id===technician.value)){error.value="请选择当前启用且有科技协助角色的人员。";return;}
  prepared.value={path:invitation?`/repair-collaborations/${invitation}/revoke`:`/repairs/${props.repairId}/collaborations`,key:crypto.randomUUID(),version:board.value.requestVersion,
    body:invitation?{reason:reason.value.trim()}:{technicianAccountId:technician.value,reason:reason.value.trim()}};
}
async function send() {
  if(busy.value || !pending.value || props.blocked)return;
  const operation=pending.value,epoch=++generation,owner=identity.value;busy.value=true;error.value="";
  try { await collaborationRequest(operation.path,operation);const result=await collaborationRequest<CollaborationBoard>(`/repairs/${props.repairId}/collaborations`);
    if(current(epoch,owner)){board.value=result;pending.value=null;reason.value="";technician.value="";emit("changed");}
  }catch(cause){if(current(epoch,owner)){if(cause instanceof RepairApiError && cause.status>=400 && cause.status<500)pending.value=null;failure(cause);}}
  finally{if(current(epoch,owner))busy.value=false;}
}
async function confirm(){if(!prepared.value || busy.value || props.blocked)return;pending.value=prepared.value;prepared.value=null;await send();}
watch(identity,()=>{clear();error.value="";void read();},{immediate:true,flush:"sync"});
watch(()=>props.requestVersion,()=>{if(!busy.value && !prepared.value)void read();});
watch(locked,value=>emit("busyChange",value),{immediate:true,flush:"sync"});
onBeforeUnmount(()=>{live=false;clear();emit("busyChange",false);});
</script>
<template>
  <section class="fams-card fams-stack" aria-label="受邀科技协助">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">受邀科技协助</h3>
        <p class="fams-panel-subtitle">科技意见只提供判断和建议，由合法处理库管核实实物后办理；返厂和报废需要相应真实后续。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || !!prepared || blocked" @click="read">刷新协助记录</button>
        <button v-if="pending" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy || blocked" @click="send">用原请求重试协助操作</button>
      </div>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{error}}</strong></p>
    <div v-if="prepared" class="fams-card fams-stack" role="group" aria-label="确认邀请或撤销">
      <p>确认本次人员和原因，仅开放此维修必要资料；邀请不授予库管处理权，撤销后不能继续读取或提交。</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy || blocked" @click="confirm">确认保存协助操作</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="prepared=null">返回修改协助操作</button>
      </div>
    </div>
    <template v-if="board">
      <fieldset v-if="board.canInvite" class="fams-card fams-stack" :disabled="locked || blocked">
        <legend class="fams-panel-title">邀请或撤销</legend>
        <button class="fams-button" data-kind="secondary" type="button" @click="candidates()">查找可邀请科技人员</button>
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">科技人员</span><select class="fams-control" v-model="technician"><option value="">请选择</option><option v-for="row in technicians" :key="row.id" :value="row.id">{{row.username}} · {{row.name}}</option></select></label>
        </div>
        <div class="fams-pagination">
          <span class="fams-pagination-summary">共{{techTotal}}人</span>
          <div class="fams-pagination-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="techPage===1" @click="candidates(-1)">上一页科技人员</button>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="techPage*20>=techTotal" @click="candidates(1)">下一页科技人员</button>
          </div>
        </div>
        <div class="fams-field-grid">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">邀请或撤销原因</span><textarea class="fams-control" v-model="reason" maxlength="1000"/></label>
        </div>
        <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button" @click="prepare()">核对邀请科技协助</button></div>
      </fieldset>
      <p>最近100条邀请，共{{board.invitationTotal}}条</p>
      <ul class="fams-timeline">
        <li v-for="row in board.invitations" :key="row.id">{{row.technician_username}} · {{row.invitation_status==='ACTIVE'?'有效邀请':row.invitation_status==='REVOKED'?'已撤销':'已结束'}}<small>{{row.reason}}</small><button v-if="board.canInvite && row.invitation_status==='ACTIVE'" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || blocked" @click="prepare(row.id)">撤销 {{row.technician_username}} 的协助邀请</button></li>
      </ul>
      <p>最近100条意见，共{{board.opinionTotal}}条</p>
      <ul class="fams-timeline">
        <li v-for="row in board.opinions" :key="row.id">{{row.actor_username}} · {{new Date(row.occurred_at).toLocaleString()}} · {{opinionResults[row.opinion_result] || '待核实意见'}}<small>{{row.judgement}}</small><small>{{row.method}}</small><RepairTechnicalEvidence v-for="id in row.attachment_ids_json" :key="id" :invitation-id="row.invitation_id" :object-id="id" kind="staff-attachments" :blocked="locked || blocked" @invalidated="emit('invalidated')"/></li>
      </ul>
    </template>
  </section>
</template>
