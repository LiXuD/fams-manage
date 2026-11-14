<script setup lang="ts">
import { computed, ref } from "vue";
import { loanRequest } from "@/loan/api";
import type { TransferChoice, TransferTarget } from "../../../../packages/api-types/src/transfer";
import {lossRequest,type LossIntent,type LossReport,type LossRecovery} from "@/loss/api";
const props=defineProps<{report:LossReport;locked:boolean}>();const emit=defineEmits<{prepare:[intent:LossIntent]}>();
const observedCode=ref(""),serial=ref(""),foundAt=ref(""),evidence=ref(""),reason=ref(""),checked=ref(false),error=ref("");
const returnChecked=ref(false),locations=ref<{id:string;name:string;version:number}[]>([]),locationIndex=ref(""),locationPage=ref(0),locationTotal=ref(0),loadingLocations=ref(false);
async function loadLocations(){
 if(props.locked||loadingLocations.value)return;loadingLocations.value=true;
 try{const result=await lossRequest<{items:{id:string;name:string;version:number}[];total:number}>(`/recovery-locations?page=${locationPage.value+1}&pageSize=100`);if(props.locked)return;locations.value.push(...result.items);locationTotal.value=result.total;locationPage.value++;}
 catch{error.value="位置未加载，请重试。";}finally{loadingLocations.value=false;}
}
const transferChecked = ref(false), sameTransferCustodian = ref(false), transferReason = ref(""), targetCustody = ref("PERSONAL");
const targetOwner = ref(""), targetOrganization = ref(""), targetPerson = ref(""), targetLocation = ref(""), targetWarehouse = ref("");
const targetOrganizations = ref<TransferChoice[]>([]), targetPeople = ref<TransferChoice[]>([]);
const targetLocations = ref<TransferChoice[]>([]), targetWarehouses = ref<TransferChoice[]>([]), loadingTarget = ref(false);
const eligibleTargetPeople = computed(() => targetPeople.value.filter(
  person => targetCustody.value !== "SHARED" || person.sharedEligible));
const stockTransfer = computed(() => props.report.source?.lifecycle === "IN_STOCK");
const canTransfer = computed(() => stockTransfer.value || props.report.source?.lifecycle === "IN_USE");
const eligibleTargetLocations = computed(() => targetLocations.value.filter(place => stockTransfer.value
  ? place.locationType === "BIN" && place.warehouseId === targetWarehouse.value : place.locationType !== "BIN"));
const transferAttempt = computed(() => props.report.recoveries?.find(
  item => item.actions.execute && item.request?.destination === "TRANSFER"));
const showTransferTarget = computed(() => props.report.actions?.recoverOriginal || !!transferAttempt.value);

async function transferCatalog(kind: string, organization?: string): Promise<TransferChoice[]> {
  const items: TransferChoice[] = [];
  let page = 1;
  while (true) {
    const scope = organization ? `&organizationId=${encodeURIComponent(organization)}` : "";
    const result = await loanRequest<{ items: TransferChoice[]; total: number }>(
      `/transfers/catalog/${kind}?page=${page++}&pageSize=200${scope}`);
    items.push(...result.items);
    if (items.length >= result.total || result.items.length === 0) return items;
  }
}
async function loadTransferTarget() {
  if (props.locked || loadingTarget.value) return;
  loadingTarget.value = true;
  error.value = "";
  try {
    const [organizations, locations, warehouses] = await Promise.all([
      transferCatalog("organization"), transferCatalog("location"),
      stockTransfer.value ? transferCatalog("warehouse") : Promise.resolve([]),
    ]);
    targetOrganizations.value = organizations;
    targetLocations.value = locations;
    targetWarehouses.value = warehouses;
  } catch { error.value = "调拨目标目录未加载，请重试。"; }
  finally { loadingTarget.value = false; }
}
async function loadTransferPeople() {
  targetPerson.value = "";
  targetPeople.value = [];
  const organization = targetOrganization.value;
  if (!organization) return;
  loadingTarget.value = true;
  error.value = "";
  try {
    const people = await transferCatalog("employee", organization);
    if (targetOrganization.value === organization) targetPeople.value = people;
  } catch { error.value = "目标责任人员未加载，请重新选择部门。"; }
  finally { loadingTarget.value = false; }
}
function transferTarget(): TransferTarget {
  const organization = targetOrganizations.value.find(item => item.id === targetOrganization.value);
  const owner = targetOrganizations.value.find(item => item.id === targetOwner.value);
  const person = eligibleTargetPeople.value.find(item => item.id === targetPerson.value);
  const place = eligibleTargetLocations.value.find(item => item.id === targetLocation.value);
  if (!place || (targetOwner.value && !owner) || !transferReason.value.trim()) {
    throw Error("请选择有效的目标位置，并填写调拨原因。");
  }
  const referenceVersions: Record<string, number> = { location: place.version };
  if (owner) referenceVersions.owningOrganization = owner.version;
  if (stockTransfer.value) {
    const warehouse = targetWarehouses.value.find(item => item.id === targetWarehouse.value);
    if (!warehouse) throw Error("请选择目标仓库和仓位。");
    referenceVersions.warehouse = warehouse.version;
    return { owningOrganizationId: owner?.id ?? null, custodyType: "STORAGE",
      usingOrganizationId: null, employeeId: null, warehouseId: warehouse.id, locationId: place.id, referenceVersions };
  }
  if (!organization || !person) throw Error("请选择有效的调拨目标部门和责任人。");
  referenceVersions.usingOrganization = organization.version;
  referenceVersions.employee = person.version;
  return { owningOrganizationId: owner?.id ?? null, custodyType: targetCustody.value,
    usingOrganizationId: organization.id, employeeId: person.id, warehouseId: null,
    locationId: place.id, referenceVersions };
}
const factoryChecked = ref(false), factoryCondition = ref("INTACT");
const internalRepair = computed(() => !!props.report.source?.internalRepairSource);
const retainedDestination = computed(() => internalRepair.value ? "INTERNAL_REPAIR" : "FACTORY_REPAIR");
const recoverRetainedAction = computed(() => internalRepair.value ? "recoverInternalRepair" : "recoverFactory");
const requestRetainedAction = computed(() => internalRepair.value ? "requestInternalRepairRecovery" : "requestFactoryRecovery");
const factoryAttempt = computed(() => props.report.recoveries?.find(
  item => item.actions.execute && item.request?.destination === retainedDestination.value));
const damagedChecked=ref(false),fault=ref(""),impact=ref("MEDIUM");
const names:Record<string,string>={PENDING_ADMIN_DECISION:"待管理员批准找回",WAITING_WAREHOUSE_CLAIM:"待库管重新认领",VERIFYING:"找回实物核实中",COMPLETED:"已完成找回",REJECTED:"找回申请已驳回",CANCELLED:"找回申请已撤回"};
function prepare(action:string,attempt?:LossRecovery){
 if(props.locked)return;const row=props.report;const capability=({approve:"decide",reject:"decide",cancel:"cancel",claim:"claim",execute:"execute",executeDamaged:"execute",executeReturn:"execute",executeTransfer:"execute",executeFactory:"execute"}as Record<string,string>)[action];
 if(attempt?!attempt.actions[capability??""]:!row.actions?.[["recoverDamaged","recoverReturn","recoverTransfer"].includes(action)?"recoverOriginal":["requestDamaged","requestReturn","requestTransfer"].includes(action)?"requestRecovery":action==="recoverFactory"?recoverRetainedAction.value:action==="requestFactoryRecovery"?requestRetainedAction.value:action])return;
 error.value="";try{let body:unknown;const source=row.recoveryAsset;let summary="";
  if (["recoverFactory", "requestFactoryRecovery", "executeFactory"].includes(action)) {
   const place = locations.value.find(item => item.id === locationIndex.value);
   const date = new Date(foundAt.value);
   if (!source || !factoryChecked.value || !place || !Number.isFinite(date.getTime())
       || !observedCode.value.trim() || (source.serialNumber && !serial.value.trim()) || evidence.value.trim().length < 10) {
    throw Error("请核对找回原机、实际位置、时间及至少10字证据。");
   }
   const facts = { foundAt: date.toISOString(), observedAssetCode: observedCode.value.trim(),
     observedSerialNumber: serial.value.trim(), locationId: place.id, locationVersion: place.version,
     physicalIdentityVerified: true, condition: factoryCondition.value, stillOriginalCustodian: false,
     evidence: evidence.value.trim() };
   body = action === "requestFactoryRecovery" ? { assetVersion: source.version, facts }
     : { assetVersion: attempt?.assetVersion ?? row.assetVersion,
       ownershipVersion: attempt?.ownershipVersion ?? row.ownershipVersion, lockVersion: row.lockVersion, facts };
   summary = action === "requestFactoryRecovery" ? "提交原维修续办的找回申请，待批准和重新认领。"
     : internalRepair.value ? "找回并把锁交回原内部维修，后续继续按原维修办理。"
       : "找回并把锁交回原维修。实际收回仍须在原返厂轮次办理。";
  } else if (["recoverTransfer", "requestTransfer", "executeTransfer"].includes(action)) {
   const place = locations.value.find(item => item.id === locationIndex.value);
   const date = new Date(foundAt.value);
   if (!canTransfer.value || !source || !transferChecked.value || !place || !Number.isFinite(date.getTime())
       || !observedCode.value.trim() || (source.serialNumber && !serial.value.trim()) || evidence.value.trim().length < 10) {
    throw Error("请核对完好原机、实际位置、找回时间和至少10字证据，并确认实际保管或位置变化。");
   }
   const facts = { foundAt: date.toISOString(), observedAssetCode: observedCode.value.trim(),
     observedSerialNumber: serial.value.trim(), locationId: place.id, locationVersion: place.version,
     physicalIdentityVerified: true, condition: "INTACT", stillOriginalCustodian: sameTransferCustodian.value, evidence: evidence.value.trim() };
   if (action === "requestTransfer") {
    body = { assetVersion: source.version, facts };
    summary = `申请找回后调拨 ${facts.observedAssetCode}，须重新批准并认领找回。`;
   } else {
    const target = transferTarget();
    body = { recovery: { assetVersion: attempt?.assetVersion ?? row.assetVersion,
      ownershipVersion: attempt?.ownershipVersion ?? row.ownershipVersion, lockVersion: row.lockVersion, facts },
      target, reason: transferReason.value.trim() };
    const person = targetPeople.value.find(item => item.id === target.employeeId);
    const destination = targetLocations.value.find(item => item.id === target.locationId);
    const warehouse = targetWarehouses.value.find(item => item.id === target.warehouseId);
    summary = `找回并提交调拨 ${facts.observedAssetCode}，实际位置 ${place.name}，目标 ${warehouse?.name ?? person?.name} / ${destination?.name}。原责任和位置保留至实物履行。`;
   }
  } else if(["recoverReturn","requestReturn","executeReturn"].includes(action)){
   const place=locations.value.find(item=>item.id===locationIndex.value),date=new Date(foundAt.value);
   if(!source||!returnChecked.value||!place||!Number.isFinite(date.getTime())||!observedCode.value.trim()||source.serialNumber&&!serial.value.trim()||evidence.value.trim().length<10)throw Error("请核对完好原机、实际位置、找回时间及至少10字变更保管证据，并确认关联归还/退库。");
   const facts={foundAt:date.toISOString(),observedAssetCode:observedCode.value.trim(),observedSerialNumber:serial.value.trim(),locationId:place.id,locationVersion:place.version,physicalIdentityVerified:true,condition:"INTACT",stillOriginalCustodian:false,evidence:evidence.value.trim()};
   body=action==="requestReturn"?{assetVersion:source.version,facts}:{assetVersion:attempt?.assetVersion??row.assetVersion,ownershipVersion:attempt?.ownershipVersion??row.ownershipVersion,lockVersion:row.lockVersion,facts};
   summary=`${action==="requestReturn"?"新建归还/退库找回申请，须新批准并认领":"找回并建立正式归还/退库"}：${facts.observedAssetCode}；实际位置${place.name}；${facts.evidence}。原责任和借用保留至退库审批及实物验收，当前锁继续保留，尚未成为可用库存。`;
  }else if(["recoverDamaged","requestDamaged","executeDamaged"].includes(action)){
   const date=new Date(foundAt.value);if(!source||!damagedChecked.value||!Number.isFinite(date.getTime())||!observedCode.value.trim()||source.serialNumber&&!serial.value.trim()||evidence.value.trim().length<10||action!=="requestDamaged"&&fault.value.trim().length<10||!source.locationId||source.locationVersion===null)throw Error("请核对原机身份、实际找回时间、至少10字证据及故障，并确认原责任原地损坏实物。");
   body={recovery:{assetVersion:attempt?.assetVersion??row.assetVersion,ownershipVersion:attempt?.ownershipVersion??row.ownershipVersion,lockVersion:row.lockVersion,facts:{foundAt:date.toISOString(),observedAssetCode:observedCode.value.trim(),observedSerialNumber:serial.value.trim(),locationId:source.locationId,locationVersion:source.locationVersion,physicalIdentityVerified:true,condition:"DAMAGED",stillOriginalCustodian:true,evidence:evidence.value.trim()}},fault:fault.value.trim(),impact:impact.value};
   if(action==="requestDamaged") body={assetVersion:source.version,facts:(body as {recovery:{facts:unknown}}).recovery.facts};
   summary=action==="requestDamaged"?`新建损坏找回申请：${observedCode.value.trim()}；${evidence.value.trim()}。须管理员批准并重新认领核实后才能转正式维修，当前不恢复正常，原遗失事实和责任保留。`:`原机损坏找回并建立正式维修：${observedCode.value.trim()}，故障：${fault.value.trim()}；资产继续由维修单锁定，须管理员批准及库管实际处理，原责任和借用日期保留。`;
  }else if(["recoverOriginal","requestRecovery","execute"].includes(action)){
   const date=new Date(foundAt.value);if(!source||!checked.value||!Number.isFinite(date.getTime())||!observedCode.value.trim()||evidence.value.trim().length<10||!source.locationId||source.locationVersion===null||source.serialNumber&&!serial.value.trim())throw Error("请对照原机编码/序列号，填写实际找回时间及至少10字证据，并确认完好、原保管与登记位置。");
   const facts={foundAt:date.toISOString(),observedAssetCode:observedCode.value.trim(),observedSerialNumber:serial.value.trim(),locationId:source.locationId,locationVersion:source.locationVersion,physicalIdentityVerified:true,condition:"INTACT",stillOriginalCustodian:true,evidence:evidence.value.trim()};
   body=action==="requestRecovery"?{assetVersion:source.version,facts}:{assetVersion:attempt?.assetVersion??row.assetVersion,ownershipVersion:attempt?.ownershipVersion??row.ownershipVersion,lockVersion:row.lockVersion,facts};
   summary=`${action==="requestRecovery"?"新建找回申请，批准认领后才能恢复":"核实后恢复原责任资产"}：${facts.observedAssetCode}，序列号${facts.observedSerialNumber||"未登记"}，找回时间${facts.foundAt}，登记位置${source.locationName}；${facts.evidence}。原借用不改期，历史遗失事实保留。`;
  }else{if(action!=="claim"){if(reason.value.trim().length<3)throw Error("请填写至少3字本次原因。");body=action==="cancel"?{reason:reason.value.trim()}:{result:action==="approve"?"APPROVE":"REJECT",reason:reason.value.trim()};}summary=action==="claim"?"取得新的唯一找回处理权，尚未恢复资产。":`${action==="approve"?"批准新找回申请，仍须库管核实执行":action==="reject"?"驳回找回申请，保留原遗失与责任跟进":"撤回新找回申请，保留原遗失与责任跟进"}；${reason.value.trim()}`;}
  if (attempt && ["execute", "executeDamaged", "executeReturn", "executeTransfer", "executeFactory"].includes(action)) {
   const destination = attempt.request?.destination;
   if ((destination === "ASSET_RETURN") !== (action === "executeReturn")
       || (destination === "TRANSFER") !== (action === "executeTransfer")
       || (["FACTORY_REPAIR", "INTERNAL_REPAIR"].includes(destination ?? "")) !== (action === "executeFactory")) return;
  }
  if(attempt&&["execute","executeDamaged"].includes(action)&&((attempt.request?.facts?.condition==="DAMAGED")!==(action==="executeDamaged")))return;
  const path=attempt?`/recoveries/${attempt.id}/${action==="approve"||action==="reject"?"decision":action==="executeDamaged"?"execute-to-repair":action==="executeReturn"?"execute-to-return":action==="executeTransfer"?"execute-to-transfer":action==="executeFactory"?(internalRepair.value?"execute-to-internal-repair":"execute-to-factory"):action}`:`/${row.id}/${action==="recoverFactory"?(internalRepair.value?"recover-to-internal-repair":"recover-to-factory"):action==="requestFactoryRecovery"?(internalRepair.value?"internal-repair-recovery-requests":"factory-recovery-requests"):action==="recoverOriginal"?"recover-original":action==="recoverDamaged"?"recover-to-repair":action==="recoverReturn"?"recover-to-return":action==="requestReturn"?"return-recovery-requests":action==="recoverTransfer"?"recover-to-transfer":action==="requestTransfer"?"transfer-recovery-requests":action==="requestDamaged"?"repair-recovery-requests":"recovery-requests"}`;
  emit("prepare",Object.freeze({path,version:attempt?.version??row.version,key:crypto.randomUUID(),body,summary}));
 }catch(cause){error.value=cause instanceof Error?cause.message:"请核实当前原机与责任。";}
}
</script>
<template><section aria-label="原责任完好找回"><h3>找回核实与实际去向</h3><p>查找中沿原核实授权；已确认遗失须新建申请并重新批准认领。责任或位置变化须关联后续；损坏找回通过对应申请与核实转入正式维修。</p><p v-if="error" role="alert">{{error}}</p>
 <p v-if="report.recoveredAt">原实物已找回：{{report.recoveredAt}}。原遗失及后续履历继续保留。</p>
 <p v-if="report.repairId &amp;&amp; report.source?.internalRepairSource">已交回原内部维修 {{report.repairId}}，请在原维修单继续办理。</p>
 <p v-if="report.repairId &amp;&amp; report.source?.factorySource">已交回原返厂维修 {{report.repairId}}，请在原轮次登记实际收回。</p>
 <p v-else-if="report.repairId">已关联正式维修 {{report.repairId}}，请前往维修管理查看审批和实际办理进度。</p>
 <fieldset :disabled="locked"><template v-if="report.recoveryAsset"><p>登记位置：{{report.recoveryAsset.locationName??'缺少登记位置'}}；登记序列号：{{report.recoveryAsset.serialNumber??'未登记'}}</p>
 <label>实际原机编码<input v-model="observedCode" maxlength="64"/></label><label>实际原机序列号（登记有值时必填）<input v-model="serial" maxlength="128"/></label><label>实际找回时间<input v-model="foundAt" type="datetime-local" step="1"/></label><label>找回实物及原保管核实证据<textarea v-model="evidence" maxlength="2000"/></label><label><input v-model="checked" type="checkbox"/>已对照原机身份，亲见实物完好且仍由原责任人在登记位置保管</label></template>
 <button v-if="report.actions?.recoverOriginal" @click="prepare('recoverOriginal')">核实原机并结束查找</button><button v-if="report.actions?.requestRecovery" @click="prepare('requestRecovery')">新建找回申请</button>
 <section v-if="report.actions?.recoverOriginal || report.actions?.requestRecovery || report.recoveries?.some(item=>item.actions.execute &amp;&amp; item.request?.facts?.condition==='DAMAGED' &amp;&amp; item.request?.destination!=='FACTORY_REPAIR')" aria-label="损坏找回转维修"><h4>原责任原地损坏找回</h4><p>填写上方原机身份、实际找回时间及证据，另核实以下损坏事实。</p><label>发现的故障<textarea v-model="fault" maxlength="2000"/></label><label>使用影响<select v-model="impact"><option value="LOW">较低</option><option value="MEDIUM">中等</option><option value="HIGH">较高</option></select></label><label><input v-model="damagedChecked" type="checkbox"/>已亲见损坏原机仍由原责任人在登记位置保管，申请正式维修</label><button v-if="report.actions?.recoverOriginal" @click="prepare('recoverDamaged')">损坏找回并申请维修</button><button v-if="report.actions?.requestRecovery" @click="prepare('requestDamaged')">新建损坏找回申请</button></section>
 <section v-if="report.actions?.recoverOriginal || report.actions?.requestRecovery || report.recoveries?.some(item=>item.actions.execute &amp;&amp; item.request?.destination==='ASSET_RETURN')" aria-label="找回关联归还退库"><h4>保管或位置变化，关联归还/退库</h4><button :disabled="loadingLocations || locked" @click="loadLocations">{{locationPage?'继续加载位置':'加载实际位置'}}</button><p v-if="locationPage">已加载{{locations.length}}/{{locationTotal}}个位置</p><label>实物实际位置<select v-model="locationIndex"><option value="">请选择实际位置</option><option v-for="place in locations" :key="place.id" :value="place.id">{{place.name}}</option></select></label><label><input v-model="returnChecked" type="checkbox"/>已亲见完好原机，保管已变化；按上述实际位置和证据关联归还/退库，原责任待实物验收后移交</label><button v-if="report.actions?.recoverOriginal" @click="prepare('recoverReturn')">找回并申请归还退库</button><button v-if="report.actions?.requestRecovery" @click="prepare('requestReturn')">新建归还退库找回申请</button></section>
 <section v-if="canTransfer && (showTransferTarget || report.actions?.requestRecovery)" aria-label="找回关联调拨">
  <h4>完好找回后调拨</h4>
  <p>沿用上方填写的原机身份、实际找回时间、实际位置和证据。调拨单须批准，实际收件后才更新责任。</p>
  <button :disabled="loadingLocations || locked" @click="loadLocations">{{locationPage?'继续加载实际位置':'加载实物实际位置'}}</button>
  <label>找回实物位置<select v-model="locationIndex"><option value="">请选择实际位置</option><option v-for="place in locations" :key="place.id" :value="place.id">{{place.name}}</option></select></label>
  <label><input v-model="transferChecked" type="checkbox"/>已核实完好原机，现场保管或位置已变化，需要正式调拨</label>
  <label><input v-model="sameTransferCustodian" type="checkbox"/>现场仍由原责任人保管，仅位置变化</label>
  <template v-if="showTransferTarget">
   <button :disabled="loadingTarget" @click="loadTransferTarget">加载调拨目标目录</button>
   <fieldset :disabled="loadingTarget">
    <label>目标所属组织<select v-model="targetOwner"><option value="">未指定</option><option v-for="org in targetOrganizations" :key="org.id" :value="org.id">{{org.name}}</option></select></label>
    <label v-if="stockTransfer">目标仓库<select v-model="targetWarehouse" @change="targetLocation = ''"><option value="">请选择仓库</option><option v-for="warehouse in targetWarehouses" :key="warehouse.id" :value="warehouse.id">{{warehouse.name}}</option></select></label>
    <template v-else>
    <label>目标使用部门<select v-model="targetOrganization" @change="loadTransferPeople"><option value="">请选择部门</option><option v-for="org in targetOrganizations" :key="org.id" :value="org.id">{{org.name}}</option></select></label>
    <label>目标保管方式<select v-model="targetCustody" @change="targetPerson = ''"><option value="PERSONAL">个人</option><option value="SHARED">部门公用</option></select></label>
    <label>目标责任人<select v-model="targetPerson"><option value="">请选择责任人</option><option v-for="person in eligibleTargetPeople" :key="person.id" :value="person.id">{{person.name}}</option></select></label>
    </template>
    <label>目标位置<select v-model="targetLocation"><option value="">请选择目标位置</option><option v-for="place in eligibleTargetLocations" :key="place.id" :value="place.id">{{place.name}}</option></select></label>
    <label>调拨原因<textarea v-model="transferReason" maxlength="1000"/></label>
    <button v-if="report.actions?.recoverOriginal" @click="prepare('recoverTransfer')">找回并提交调拨</button>
    <button v-if="transferAttempt" @click="prepare('executeTransfer', transferAttempt)">核实找回并提交调拨</button>
   </fieldset>
  </template>
  <button v-if="report.actions?.requestRecovery" @click="prepare('requestTransfer')">新建调拨找回申请</button>
 </section>
 <section v-if="report.actions?.[recoverRetainedAction] || report.actions?.[requestRetainedAction] || factoryAttempt" :aria-label="internalRepair ? '内部维修实物找回' : '返厂原机找回续修'">
  <h4>{{internalRepair ? '内部维修实物找回' : '返厂原机找回续修'}}</h4>
  <p>填写上方原机编码、序列号、找回时间及证据。原维修继续办理实际收回。</p>
  <button :disabled="loadingLocations || locked" @click="loadLocations">加载找回实物位置</button>
  <label>原机找回位置<select v-model="locationIndex"><option value="">请选择实际位置</option><option v-for="place in locations" :key="place.id" :value="place.id">{{place.name}}</option></select></label>
  <label>找回时原机状况<select v-model="factoryCondition"><option value="INTACT">完好</option><option value="DAMAGED">损坏</option></select></label>
  <label><input v-model="factoryChecked" type="checkbox"/>已亲见并对照{{internalRepair ? '原维修实物' : '返厂原机'}}</label>
  <button v-if="report.actions?.[recoverRetainedAction]" @click="prepare('recoverFactory')">找回并交回原维修</button>
  <button v-if="report.actions?.[requestRetainedAction]" @click="prepare('requestFactoryRecovery')">新建{{internalRepair ? '内部维修' : '返厂原机'}}找回申请</button>
  <button v-if="factoryAttempt" @click="prepare('executeFactory', factoryAttempt)">核实找回并交回原维修</button>
 </section>
 <p v-if="report.transferId">已关联正式调拨 {{report.transferId}}，请在调拨管理继续审批和实物交接。</p>
 <p v-if="report.returnId">已关联正式归还/退库 {{report.returnId}}，请在借用与退库管理继续审批及实物验收。</p>
 <p>近期找回申请：{{report.recoveriesTotal??0}}项（展示最近20项及当前在办项）</p><article v-for="attempt in report.recoveries" :key="attempt.id"><h4>找回申请 {{attempt.id}} · {{names[attempt.status]??attempt.status}}</h4><p>新处理库管：{{attempt.processorAccountId??'尚未认领'}}；发现时间：{{attempt.request?.facts?.foundAt}}</p><dl aria-label="本次找回申请证据"><dt>申请核实的原机编码</dt><dd>{{attempt.request?.facts?.observedAssetCode}}</dd><dt>申请核实的原机序列号</dt><dd>{{attempt.request?.facts?.observedSerialNumber||'未登记'}}</dd><dt>申请登记位置编号</dt><dd>{{attempt.request?.facts?.locationId}}</dd><dt>本次申请去向</dt><dd>{{attempt.request?.destination==='ASSET_RETURN'?'关联归还/退库，原责任待实物验收后移交':attempt.request?.destination==='TRANSFER'?'关联正式调拨':attempt.request?.destination==='FACTORY_REPAIR'?'交回原返厂维修':attempt.request?.destination==='INTERNAL_REPAIR'?'交回原内部维修':'原责任恢复或维修'}}</dd><dt>申请核实状况</dt><dd>{{attempt.request?.facts?.condition==='INTACT'?'完好':attempt.request?.facts?.condition==='DAMAGED'?'损坏，批准后转正式维修':'需关联处理'}}；{{attempt.request?.facts?.stillOriginalCustodian===true?'仍由原责任人保管':'责任需核对'}}；{{attempt.request?.facts?.physicalIdentityVerified===true?'已对照原机身份':'身份待核对'}}</dd><dt>申请核实证据</dt><dd>{{attempt.request?.facts?.evidence}}</dd></dl><label v-if="attempt.actions.decide||attempt.actions.cancel">本次找回审批/撤回原因<textarea v-model="reason" maxlength="1000"/></label><button v-if="attempt.actions.decide" @click="prepare('approve',attempt)">批准找回申请</button><button v-if="attempt.actions.decide" @click="prepare('reject',attempt)">驳回找回申请</button><button v-if="attempt.actions.cancel" @click="prepare('cancel',attempt)">撤回找回申请</button><button v-if="attempt.actions.claim" @click="prepare('claim',attempt)">重新认领找回任务</button><button v-if="attempt.actions.execute &amp;&amp; attempt.request?.facts?.condition!=='DAMAGED' &amp;&amp; attempt.request?.destination!=='ASSET_RETURN' &amp;&amp; attempt.request?.destination!=='TRANSFER' &amp;&amp; attempt.request?.destination!=='FACTORY_REPAIR' &amp;&amp; attempt.request?.destination!=='INTERNAL_REPAIR'" @click="prepare('execute',attempt)">核实原机并完成找回</button><button v-if="attempt.actions.execute &amp;&amp; attempt.request?.facts?.condition==='DAMAGED' &amp;&amp; attempt.request?.destination!=='FACTORY_REPAIR' &amp;&amp; attempt.request?.destination!=='INTERNAL_REPAIR'" @click="prepare('executeDamaged',attempt)">核实损坏原机并转正式维修</button><button v-if="attempt.actions.execute &amp;&amp; attempt.request?.destination==='ASSET_RETURN'" @click="prepare('executeReturn',attempt)">核实完好原机并关联归还退库</button></article>
 </fieldset></section></template>
<style scoped>fieldset{border:0;padding:0}label{display:flex;flex-direction:column;gap:6px;margin:12px 0;max-width:640px}input,textarea{padding:8px;border:1px solid #cbd5e1;border-radius:5px}button{padding:8px 12px;margin:5px;border:1px solid #cbd5e1;border-radius:5px;background:#f8fafc}button:disabled{opacity:.5}[role=alert]{color:#b91c1c}article{border-top:1px solid #dbe3ee;padding-top:8px}</style>
