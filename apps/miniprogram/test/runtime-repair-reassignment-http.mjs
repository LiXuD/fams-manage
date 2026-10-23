// Actual native handlers -> opaque sessions -> real Spring/MySQL. SDK confirmation and HTTPS transport hostname are explicit local test adapters.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createRepairPage } from "../src/repair-pages.ts";
const port=process.env.FAMS_TEST_API_PORT, grants=JSON.parse(process.env.FAMS_TEST_REPAIR_GRANTS);
assert.match(port??"",/^[0-9]{1,5}$/);
const writes=[];let drop=true;
function pageFor(role,id){
 const saved=grants[role];assert.ok(saved.sessionToken);
 const sessions=new MiniSessionClient("https://repair-test.invalid",{readSession:()=>saved,writeSession(){throw new Error("Unexpected login");},removeSession(){},clearIdentityData(){},async request(input){
  assert.ok(input.url.startsWith("https://repair-test.invalid/api/v1/mini/"));
  const response=await fetch(`http://127.0.0.1:${port}`+input.url.slice("https://repair-test.invalid".length),{method:input.method,headers:{"Content-Type":"application/json",...input.header},body:input.data===undefined?undefined:JSON.stringify(input.data),redirect:"error"});
  const data=await response.json();assert.equal(response.status,200,JSON.stringify(data));
  if(input.method==="POST"){writes.push(structuredClone({role,path:input.url,body:input.data,key:input.header["Idempotency-Key"],version:input.header["If-Match-Version"]}));if(drop&&input.url.endsWith("/reassignment")){drop=false;throw new Error("Actual committed repair reply dropped by adapter");}}
  return {statusCode:response.status,data};
 }});
 const page=createRepairPage(()=>({sessions,createKey:randomUUID,confirm:async()=>true}));page.data=structuredClone(page.data);page.setData=x=>Object.assign(page.data,x);page.onLoad({id});return page;
}
const event=dataset=>({currentTarget:{dataset}}),field=(field,value)=>({...event({field}),detail:{value}}), action=action=>event({action});
const employee=pageFor("employee");await employee.onShow();employee.onAsset(event({id:"951020"}));employee.onField(field("fault","实际设备故障，维修转派原生链路"));employee.onField(field("occurredAt",new Date(Date.now()-60000).toISOString()));await employee.onSubmit();assert.equal(employee.data.error,"");const id=employee.data.detail.id;employee.onUnload();
const admin=pageFor("admin",id);await admin.onShow();admin.onField(field("reason","核实实际故障批准内部零费用维修"));await admin.onAction(action("approve"));assert.equal(admin.data.error,"");
const warehouse=pageFor("warehouse",id);await warehouse.onShow();await warehouse.onAction(action("claim"));warehouse.onPhysicalMode(event({mode:"away"}));await warehouse.onPhysicalCatalog(event({kind:"location"}));warehouse.onPhysicalLocation(event({id:"951000"}));warehouse.onField(field("physicalObservedCode","REP-ASSET-951020"));warehouse.onField(field("physicalAt",new Date().toISOString()));warehouse.onField(field("physicalEvidence","本人实际接收维修设备离开原保管人，记录实物去向"));await warehouse.onPhysicalAction();assert.equal(warehouse.data.error,"");
await admin.onRefresh();assert.equal(admin.data.detail.actions.reassign,true);await admin.onReassignment();admin.onReassignTarget(event({id:"951004"}));admin.onField(field("reassignReason","原处理库管无法继续，需实际交接后转派"));admin.onField(field("reassignEvidence","已实际核对设备和未同步结果，保存本次交接凭证，原锁与责任继续保留"));admin.onReassignChecked({detail:{value:["checked"]}});await admin.onReassign();assert.equal(admin.data.retry,true);admin.onHide();await admin.onShow();assert.equal(admin.data.detail.processorAccountId,"951004");await admin.onRetry();assert.equal(admin.data.retry,false);const attempts=writes.filter(row=>row.path.endsWith("/reassignment"));assert.equal(attempts.length,2);assert.deepEqual(attempts[1],attempts[0]);admin.onUnload();
await warehouse.onRefresh();assert.equal(warehouse.data.detail.actions.closeInbound,false);warehouse.onUnload();
const next=pageFor("newWarehouse",id);await next.onShow();assert.equal(next.data.detail.actions.closeInbound,true);next.onPhysicalMode(event({mode:"inbound"}));await next.onPhysicalCatalog(event({kind:"warehouse"}));next.onPhysicalWarehouse(event({id:"951000"}));await next.onPhysicalCatalog(event({kind:"location"}));next.onPhysicalLocation(event({id:"951000"}));next.onField(field("physicalObservedCode","REP-ASSET-951020"));next.onField(field("physicalAt",new Date().toISOString()));next.onField(field("physicalEvidence","接任库管已实际核实修复设备并验收入库，保存实际接收依据"));await next.onPhysicalAction();assert.equal(next.data.error,"");assert.equal(next.data.detail.status,"COMPLETED");next.onUnload();console.log(JSON.stringify({nativeActualReassignmentClosed:true,committedOriginalReplyRecovered:true,repairId:id}));
