<script setup lang="ts">
import {ref} from "vue";
import type {LossIntent,LossReport,LossRecovery} from "@/loss/api";
const props=defineProps<{report:LossReport;locked:boolean}>();const emit=defineEmits<{prepare:[intent:LossIntent]}>();
const observedCode=ref(""),serial=ref(""),foundAt=ref(""),evidence=ref(""),reason=ref(""),checked=ref(false),error=ref("");
const names:Record<string,string>={PENDING_ADMIN_DECISION:"待管理员批准找回",WAITING_WAREHOUSE_CLAIM:"待库管重新认领",VERIFYING:"找回实物核实中",COMPLETED:"已完成找回",REJECTED:"找回申请已驳回",CANCELLED:"找回申请已撤回"};
function prepare(action:string,attempt?:LossRecovery){
 if(props.locked)return;const row=props.report;const capability=({approve:"decide",reject:"decide",cancel:"cancel",claim:"claim",execute:"execute"}as Record<string,string>)[action];
 if(attempt?!attempt.actions[capability??""]:!row.actions?.[action])return;
 error.value="";try{let body:unknown;const source=row.recoveryAsset;let summary="";
  if(["recoverOriginal","requestRecovery","execute"].includes(action)){
   const date=new Date(foundAt.value);if(!source||!checked.value||!Number.isFinite(date.getTime())||!observedCode.value.trim()||evidence.value.trim().length<10||!source.locationId||source.locationVersion===null||source.serialNumber&&!serial.value.trim())throw Error("请对照原机编码/序列号，填写实际找回时间及至少10字证据，并确认完好、原保管与登记位置。");
   const facts={foundAt:date.toISOString(),observedAssetCode:observedCode.value.trim(),observedSerialNumber:serial.value.trim(),locationId:source.locationId,locationVersion:source.locationVersion,physicalIdentityVerified:true,condition:"INTACT",stillOriginalCustodian:true,evidence:evidence.value.trim()};
   body=action==="requestRecovery"?{assetVersion:source.version,facts}:{assetVersion:attempt?.assetVersion??row.assetVersion,ownershipVersion:attempt?.ownershipVersion??row.ownershipVersion,lockVersion:row.lockVersion,facts};
   summary=`${action==="requestRecovery"?"新建找回申请，批准认领后才能恢复":"核实后恢复原责任资产"}：${facts.observedAssetCode}，序列号${facts.observedSerialNumber||"未登记"}，找回时间${facts.foundAt}，登记位置${source.locationName}；${facts.evidence}。原借用不改期，历史遗失事实保留。`;
  }else{if(action!=="claim"){if(reason.value.trim().length<3)throw Error("请填写至少3字本次原因。");body=action==="cancel"?{reason:reason.value.trim()}:{result:action==="approve"?"APPROVE":"REJECT",reason:reason.value.trim()};}summary=action==="claim"?"取得新的唯一找回处理权，尚未恢复资产。":`${action==="approve"?"批准新找回申请，仍须库管核实执行":action==="reject"?"驳回找回申请，保留原遗失与责任跟进":"撤回新找回申请，保留原遗失与责任跟进"}；${reason.value.trim()}`;}
  const path=attempt?`/recoveries/${attempt.id}/${action==="approve"||action==="reject"?"decision":action}`:`/${row.id}/${action==="recoverOriginal"?"recover-original":"recovery-requests"}`;
  emit("prepare",Object.freeze({path,version:attempt?.version??row.version,key:crypto.randomUUID(),body,summary}));
 }catch(cause){error.value=cause instanceof Error?cause.message:"请核实当前原机与责任。";}
}
</script>
<template><section aria-label="原责任完好找回"><h3>原责任、原地、完好找回</h3><p>查找中沿原核实授权；已确认遗失须新建申请并重新批准认领。责任、位置或状况变化不能从此入口直接恢复。</p><p v-if="error" role="alert">{{error}}</p>
 <p v-if="report.recoveredAt">原实物已找回：{{report.recoveredAt}}。原遗失及后续履历继续保留。</p>
 <fieldset :disabled="locked"><template v-if="report.recoveryAsset"><p>登记位置：{{report.recoveryAsset.locationName??'缺少登记位置'}}；登记序列号：{{report.recoveryAsset.serialNumber??'未登记'}}</p>
 <label>实际原机编码<input v-model="observedCode" maxlength="64"/></label><label>实际原机序列号（登记有值时必填）<input v-model="serial" maxlength="128"/></label><label>实际找回时间<input v-model="foundAt" type="datetime-local" step="1"/></label><label>找回实物及原保管核实证据<textarea v-model="evidence" maxlength="2000"/></label><label><input v-model="checked" type="checkbox"/>已对照原机身份，亲见实物完好且仍由原责任人在登记位置保管</label></template>
 <button v-if="report.actions?.recoverOriginal" @click="prepare('recoverOriginal')">核实原机并结束查找</button><button v-if="report.actions?.requestRecovery" @click="prepare('requestRecovery')">新建找回申请</button>
 <p>近期找回申请：{{report.recoveriesTotal??0}}项（展示最近20项及当前在办项）</p><article v-for="attempt in report.recoveries" :key="attempt.id"><h4>找回申请 {{attempt.id}} · {{names[attempt.status]??attempt.status}}</h4><p>新处理库管：{{attempt.processorAccountId??'尚未认领'}}；发现时间：{{attempt.request?.facts?.foundAt}}</p><dl aria-label="本次找回申请证据"><dt>申请核实的原机编码</dt><dd>{{attempt.request?.facts?.observedAssetCode}}</dd><dt>申请核实的原机序列号</dt><dd>{{attempt.request?.facts?.observedSerialNumber||'未登记'}}</dd><dt>申请登记位置编号</dt><dd>{{attempt.request?.facts?.locationId}}</dd><dt>申请核实状况</dt><dd>{{attempt.request?.facts?.condition==='INTACT'?'完好':'需关联处理'}}；{{attempt.request?.facts?.stillOriginalCustodian===true?'仍由原责任人保管':'责任需核对'}}；{{attempt.request?.facts?.physicalIdentityVerified===true?'已对照原机身份':'身份待核对'}}</dd><dt>申请核实证据</dt><dd>{{attempt.request?.facts?.evidence}}</dd></dl><label v-if="attempt.actions.decide||attempt.actions.cancel">本次找回审批/撤回原因<textarea v-model="reason" maxlength="1000"/></label><button v-if="attempt.actions.decide" @click="prepare('approve',attempt)">批准找回申请</button><button v-if="attempt.actions.decide" @click="prepare('reject',attempt)">驳回找回申请</button><button v-if="attempt.actions.cancel" @click="prepare('cancel',attempt)">撤回找回申请</button><button v-if="attempt.actions.claim" @click="prepare('claim',attempt)">重新认领找回任务</button><button v-if="attempt.actions.execute" @click="prepare('execute',attempt)">核实原机并完成找回</button></article>
 </fieldset></section></template>
<style scoped>fieldset{border:0;padding:0}label{display:flex;flex-direction:column;gap:6px;margin:12px 0;max-width:640px}input,textarea{padding:8px;border:1px solid #cbd5e1;border-radius:5px}button{padding:8px 12px;margin:5px;border:1px solid #cbd5e1;border-radius:5px;background:#f8fafc}button:disabled{opacity:.5}[role=alert]{color:#b91c1c}article{border-top:1px solid #dbe3ee;padding-top:8px}</style>
