<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "@/auth/sessionStore";
import { repairRequest, repairProblem, RepairApiError, type Repair, type RepairMutation } from "@/repair/api";

interface Place { id: string; name: string; version: number; warehouse_id?: string | null; location_type?: string }
const props=defineProps<{repair:Repair;blocked?:boolean}>();
const emit=defineEmits<{changed:[];invalidated:[];busyChange:[value:boolean]}>();
const session=useSessionStore();
const identity=computed(()=>JSON.stringify([session.user?.id,session.user?.roles,session.csrfToken,props.repair.id]));
const warehouses=ref<Place[]>([]),locations=ref<Place[]>([]),warehouse=ref(""),location=ref("");
const warehousePage=ref(1),locationPage=ref(1),warehouseTotal=ref(0),locationTotal=ref(0);
const mode=ref<"away"|"inbound">("away"),observed=ref(""),actual=ref(""),evidence=ref(""),error=ref(""),busy=ref(false);
const prepared=ref<RepairMutation|null>(null),pending=ref<RepairMutation|null>(null);
const locked=computed(()=>busy.value||!!prepared.value||!!pending.value);
const visibleLocations=computed(()=>mode.value==="away"?locations.value:locations.value.filter(row=>row.location_type==="BIN"&&row.warehouse_id===warehouse.value));
let generation=0,live=true;
const current=(epoch:number,owner:string)=>live&&epoch===generation&&identity.value===owner;
function clear(){generation++;warehouses.value=[];locations.value=[];warehouse.value="";location.value="";warehousePage.value=1;locationPage.value=1;warehouseTotal.value=0;locationTotal.value=0;observed.value="";actual.value="";evidence.value="";prepared.value=null;pending.value=null;busy.value=false;}
function failure(cause:unknown){if(cause instanceof RepairApiError&&[401,403,404].includes(cause.status)){clear();emit("invalidated");}error.value=repairProblem(cause);}
function selectMode(value:"away"|"inbound"){if(locked.value||props.blocked)return;mode.value=value;location.value="";const time=new Date();actual.value=new Date(time.getTime()-time.getTimezoneOffset()*60000).toISOString().slice(0,23);error.value="";}
async function catalog(kind:"warehouse"|"location",delta=0){if(locked.value||props.blocked)return;const page=kind==="warehouse"?warehousePage:locationPage,total=kind==="warehouse"?warehouseTotal:locationTotal,next=page.value+delta;if(next<1||delta>0&&(next-1)*20>=total.value)return;
 const epoch=++generation,owner=identity.value;busy.value=true;error.value="";
 try{const result=await repairRequest<{items:Place[];total:number}>(`/physical-catalog/${kind}?page=${next}&pageSize=20`);if(current(epoch,owner)){page.value=next;total.value=result.total;if(kind==="warehouse"){warehouses.value=result.items;warehouse.value="";}else locations.value=result.items;location.value="";}}
 catch(cause){if(current(epoch,owner))failure(cause);}finally{if(current(epoch,owner))busy.value=false;}}
function prepare(){if(locked.value||props.blocked)return;error.value="";const row=props.repair,place=visibleLocations.value.find(row=>row.id===location.value),store=warehouses.value.find(row=>row.id===warehouse.value),time=new Date(actual.value);
 if(!place||!Number.isFinite(time.getTime())||!observed.value.trim()||evidence.value.trim().length<10||row.lockVersion==null||mode.value==="inbound"&&!store){error.value="请核对实物编码、实际时间、位置及至少10字依据；回库须选择有效仓库和所属库位。";return;}
 if(mode.value==="away"&&!row.actions?.acceptAway||mode.value==="inbound"&&!row.actions?.closeInbound){error.value="当前阶段不能办理此动作，请刷新核对。";return;}
 const body:Record<string,unknown>={assetVersion:row.assetVersion,ownershipVersion:row.ownershipVersion,lockVersion:row.lockVersion,loanVersion:row.loanVersion??null,observedCode:observed.value.trim(),occurredAt:time.toISOString(),evidence:evidence.value.trim(),locationVersion:place.version};
 if(mode.value==="away")body.actualLocationId=place.id;else{body.warehouseId=store!.id;body.warehouseVersion=store!.version;body.locationId=place.id;}
 prepared.value={path:`/${row.id}/${mode.value==="away"?"accept-away":"close-inbound"}`,key:crypto.randomUUID(),version:row.version,body};}
async function send(){if(busy.value||!pending.value||props.blocked)return;const operation=pending.value,epoch=++generation,owner=identity.value;busy.value=true;error.value="";
 try{await repairRequest(operation.path,operation);await repairRequest<Repair>(`/${props.repair.id}`);if(current(epoch,owner)){pending.value=null;observed.value="";actual.value="";evidence.value="";location.value="";emit("changed");}}
 catch(cause){if(current(epoch,owner)){if(cause instanceof RepairApiError&&cause.status>=400&&cause.status<500)pending.value=null;failure(cause);}}finally{if(current(epoch,owner))busy.value=false;}}
async function confirm(){if(!prepared.value||busy.value||props.blocked)return;pending.value=prepared.value;prepared.value=null;await send();}
watch(identity,()=>{clear();error.value="";},{immediate:true,flush:"sync"});
watch(warehouse,()=>{if(!locked.value)location.value="";});
watch(locked,value=>emit("busyChange",value),{immediate:true,flush:"sync"});
onBeforeUnmount(()=>{live=false;clear();emit("busyChange",false);});
</script>
<template><section aria-label="实物接修与验收入库"><h3>实物接修与验收入库</h3>
<p v-if="repair.physicalHandling?.length">已记录实物离开原保管人的实际接收。原地关闭不可用，修复后须实际返还接收或验收入库。</p>
<p v-for="row in repair.physicalHandling" :key="row.occurred_at">实物接收 {{new Date(row.occurred_at).toLocaleString()}} · {{row.evidence}}</p>
<p v-for="row in repair.inbound" :key="row.occurred_at">已验收入库 {{new Date(row.occurred_at).toLocaleString()}} · {{row.evidence}}</p>
<p v-if="error" role="alert">{{error}}</p><button v-if="pending" :disabled="busy||blocked" @click="send">重试原实物办理</button>
<section v-if="prepared" aria-label="核对本次实物办理"><p>接修只记录实际接收，不结束维修。验收入库须确认实物及修复结果，同步结束原未结借用和维修，不能用科技建议代替实物。</p><button :disabled="busy||blocked" @click="confirm">确认保存实物办理</button><button :disabled="busy" @click="prepared=null">返回修改实物依据</button></section>
<fieldset v-if="repair.actions?.acceptAway||repair.actions?.closeInbound" :disabled="locked||blocked"><legend>按实际去向办理</legend>
<button v-if="repair.actions.acceptAway" @click="selectMode('away')">记录离开原保管人的实际接修</button><button v-if="repair.actions.closeInbound" @click="selectMode('inbound')">核对已修复并验收入库</button>
<p>{{mode==='away'?'当前：实际接修，维修与资产锁继续保留。':'当前：验收入库，并原子关闭原未结借用及维修。'}}</p>
<template v-if="mode==='inbound'"><button @click="catalog('warehouse')">查询回库仓库</button><label>回库仓库<select v-model="warehouse"><option value="">请选择</option><option v-for="row in warehouses" :key="row.id" :value="row.id">{{row.name}}</option></select></label><button :disabled="warehousePage===1" @click="catalog('warehouse',-1)">上一页回库仓库</button>共{{warehouseTotal}}个<button :disabled="warehousePage*20>=warehouseTotal" @click="catalog('warehouse',1)">下一页回库仓库</button></template>
<button @click="catalog('location')">查询实物位置</button><label>实际位置<select v-model="location"><option value="">请选择当前页有效位置</option><option v-for="row in visibleLocations" :key="row.id" :value="row.id">{{row.name}}</option></select></label><p v-if="mode==='inbound'">仅显示已选仓库的库位；当前页没有时请翻页核对。</p><button :disabled="locationPage===1" @click="catalog('location',-1)">上一页实物位置</button>共{{locationTotal}}个<button :disabled="locationPage*20>=locationTotal" @click="catalog('location',1)">下一页实物位置</button>
<label>实物资产编码<input v-model="observed" maxlength="2048"></label><label>实际接收时间<input v-model="actual" type="datetime-local" step="0.001"></label><label>实物接收及修复依据<textarea v-model="evidence" maxlength="2000"/></label><button @click="prepare">核对本次实物办理</button></fieldset>
</section></template>
<style scoped>section{margin:18px 0;}fieldset{border:1px solid #d9e0e8;padding:16px;}label{display:block;margin:12px 0;}textarea{display:block;width:min(100%,720px);min-height:70px;}button{margin:6px;}</style>
