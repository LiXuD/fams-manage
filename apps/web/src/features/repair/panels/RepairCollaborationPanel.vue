<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { RepairApiError, repairProblem, type RepairMutation } from "@/features/repair/api";
import { collaborationRequest, opinionResults, type CollaborationBoard } from "@/features/repair/collaboration";
import { useBusy } from "@/features/repair/busyRegistry";
import RepairTechnicalEvidence from "./RepairTechnicalEvidence.vue";
const props = defineProps<{ repairId: string; requestVersion: number }>();
const emit = defineEmits<{ changed: []; invalidated: [] }>();
/** 页内互斥：claimed 写本面板自身的占用位，blocked 读「任一其它面板在忙」。 */
const { busy: claimed, blocked } = useBusy("collaboration");
const session = useSessionStore(), board = ref<CollaborationBoard | null>(null), technicians = ref<{id:string; username:string; name:string}[]>([]);
const techPage = shallowRef(1), techTotal = shallowRef(0), technician = shallowRef(""), reason = shallowRef(""), error = shallowRef(""), busy = shallowRef(false);
const prepared = ref<RepairMutation | null>(null), pending = ref<RepairMutation | null>(null);
const identity = computed(()=>JSON.stringify([session.user?.id,session.user?.roles,session.csrfToken,props.repairId]));
const locked = computed(()=>busy.value || !!prepared.value || !!pending.value);
const refreshDisabled = computed(()=>busy.value || !!prepared.value || !!blocked.value);
const retryDisabled = computed(()=>busy.value || !!blocked.value);
const confirmDisabled = computed(()=>busy.value || !!blocked.value);
const inviteFieldsetShown = computed(()=>!!board.value?.canInvite);
const techPrevDisabled = computed(()=>techPage.value===1);
const techNextDisabled = computed(()=>techPage.value*20>=techTotal.value);
const invitationRows = computed(()=>(board.value?.invitations ?? []).map(row=>({...row,statusText:row.invitation_status==='ACTIVE'?'有效邀请':row.invitation_status==='REVOKED'?'已撤销':'已结束',revocable:!!board.value?.canInvite && row.invitation_status==='ACTIVE',revokeText:`撤销 ${row.technician_username} 的协助邀请`})));
const opinionRows = computed(()=>(board.value?.opinions ?? []).map(row=>({...row,occurredText:new Date(row.occurred_at).toLocaleString(),resultText:opinionResults[row.opinion_result] || '待核实意见'})));
const liveness = useRequestLiveness(()=>[identity.value]);
function clear() { liveness.invalidate();board.value=null;technicians.value=[];techPage.value=1;techTotal.value=0;technician.value="";reason.value="";prepared.value=null;pending.value=null;busy.value=false; }
function failure(cause:unknown) { if (cause instanceof RepairApiError && [401,403,404].includes(cause.status)) { clear(); emit("invalidated"); } error.value=repairProblem(cause); }
async function read() {
  if (busy.value || prepared.value) return;
  const turn = liveness.begin();busy.value=true;error.value="";
  try { const value=await collaborationRequest<CollaborationBoard>(`/repairs/${props.repairId}/collaborations`);if(liveness.isCurrent(turn))board.value=value; }
  catch(cause){if(liveness.isCurrent(turn))failure(cause);}finally{if(liveness.isCurrent(turn))busy.value=false;}
}
async function candidates(delta=0) {
  if(locked.value || blocked.value || !board.value?.canInvite)return;
  const next=techPage.value+delta;if(next<1 || delta>0 && (next-1)*20>=techTotal.value)return;
  const turn = liveness.begin();busy.value=true;error.value="";
  try { const value=await collaborationRequest<{items:{id:string;username:string;name:string}[];total:number}>(`/repairs/${props.repairId}/eligible-technicians?page=${next}&pageSize=20`);
    if(liveness.isCurrent(turn)){technicians.value=value.items;techTotal.value=value.total;techPage.value=next;technician.value="";}
  }catch(cause){if(liveness.isCurrent(turn))failure(cause);}finally{if(liveness.isCurrent(turn))busy.value=false;}
}
function prepare(invitation?:string) {
  if(locked.value || blocked.value || !board.value?.canInvite)return;
  if(reason.value.trim().length<3){error.value="请填写邀请或撤销的明确原因。";return;}
  if(!invitation && !technicians.value.some(row=>row.id===technician.value)){error.value="请选择当前启用且有科技协助角色的人员。";return;}
  prepared.value={path:invitation?`/repair-collaborations/${invitation}/revoke`:`/repairs/${props.repairId}/collaborations`,key:crypto.randomUUID(),version:board.value.requestVersion,
    body:invitation?{reason:reason.value.trim()}:{technicianAccountId:technician.value,reason:reason.value.trim()}};
}
async function send() {
  if(busy.value || !pending.value || blocked.value)return;
  const operation=pending.value,turn = liveness.begin();busy.value=true;error.value="";
  try { await collaborationRequest(operation.path,operation);const result=await collaborationRequest<CollaborationBoard>(`/repairs/${props.repairId}/collaborations`);
    if(liveness.isCurrent(turn)){board.value=result;pending.value=null;reason.value="";technician.value="";emit("changed");}
  }catch(cause){if(liveness.isCurrent(turn)){if(cause instanceof RepairApiError && cause.status>=400 && cause.status<500)pending.value=null;failure(cause);}}
  finally{if(liveness.isCurrent(turn))busy.value=false;}
}
async function confirm(){if(!prepared.value || busy.value || blocked.value)return;pending.value=prepared.value;prepared.value=null;await send();}
watch(identity,()=>{clear();error.value="";void read();},{immediate:true,flush:"sync"});
watch(()=>props.requestVersion,()=>{if(!busy.value && !prepared.value)void read();});
watch(locked,value=>{claimed.value=value;},{immediate:true,flush:"sync"});
onBeforeUnmount(()=>{clear();});
</script>
<template>
  <section class="fams-card fams-stack" aria-label="受邀科技协助">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">受邀科技协助</h3>
        <p class="fams-panel-subtitle">科技意见只提供判断和建议，由合法处理库管核实实物后办理；返厂和报废需要相应真实后续。</p>
      </div>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="refreshDisabled" @click="read">刷新协助记录</button>
        <button v-if="pending" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="retryDisabled" @click="send">用原请求重试协助操作</button>
      </div>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{error}}</strong></p>
    <div v-if="prepared" class="fams-card fams-stack" role="group" aria-label="确认邀请或撤销">
      <p>确认本次人员和原因，仅开放此维修必要资料；邀请不授予库管处理权，撤销后不能继续读取或提交。</p>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="confirmDisabled" @click="confirm">确认保存协助操作</button>
        <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="prepared=null">返回修改协助操作</button>
      </div>
    </div>
    <template v-if="board">
      <fieldset v-if="inviteFieldsetShown" class="fams-card fams-stack" :disabled="locked || blocked">
        <legend class="fams-panel-title">邀请或撤销</legend>
        <button class="fams-button" data-kind="secondary" type="button" @click="candidates()">查找可邀请科技人员</button>
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">科技人员</span><select class="fams-control" v-model="technician"><option value="">请选择</option><option v-for="row in technicians" :key="row.id" :value="row.id">{{row.username}} · {{row.name}}</option></select></label>
        </div>
        <div class="fams-pagination">
          <span class="fams-pagination-summary">共{{techTotal}}人</span>
          <div class="fams-pagination-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="techPrevDisabled" @click="candidates(-1)">上一页科技人员</button>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="techNextDisabled" @click="candidates(1)">下一页科技人员</button>
          </div>
        </div>
        <div class="fams-field-grid">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">邀请或撤销原因</span><textarea class="fams-control" v-model="reason" maxlength="1000"/></label>
        </div>
        <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="button" @click="prepare()">核对邀请科技协助</button></div>
      </fieldset>
      <p>最近100条邀请，共{{board.invitationTotal}}条</p>
      <ul class="fams-timeline">
        <li v-for="row in invitationRows" :key="row.id">{{row.technician_username}} · {{row.statusText}}<small>{{row.reason}}</small><button v-if="row.revocable" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="locked || blocked" @click="prepare(row.id)">{{row.revokeText}}</button></li>
      </ul>
      <p>最近100条意见，共{{board.opinionTotal}}条</p>
      <ul class="fams-timeline">
        <li v-for="row in opinionRows" :key="row.id">{{row.actor_username}} · {{row.occurredText}} · {{row.resultText}}<small>{{row.judgement}}</small><small>{{row.method}}</small><RepairTechnicalEvidence v-for="id in row.attachment_ids_json" :key="id" :invitation-id="row.invitation_id" :object-id="id" kind="staff-attachments" :blocked="locked || blocked" @invalidated="emit('invalidated')"/></li>
      </ul>
    </template>
  </section>
</template>
