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
  if(input.method==="POST"){writes.push(structuredClone({role,path:input.url,body:input.data,key:input.header["Idempotency-Key"],version:input.header["If-Match-Version"]}));if(drop){drop=false;throw new Error("Actual committed repair reply dropped by adapter");}}
  return {statusCode:response.status,data};
 }});
 const page=createRepairPage(()=>({sessions,createKey:randomUUID,confirm:async()=>true}));page.data=structuredClone(page.data);page.setData=x=>Object.assign(page.data,x);page.onLoad({id});return page;
}
const field=(field,value)=>({currentTarget:{dataset:{field}},detail:{value}}), action=action=>({currentTarget:{dataset:{action}}});
const employee=pageFor("employee");await employee.onShow();assert.equal(employee.data.error,"");
employee.onAsset({currentTarget:{dataset:{id:"951020"}}});employee.onField(field("fault","现场实际设备故障，原生端报修验证"));employee.onField(field("occurredAt",new Date(Date.now()-60000).toISOString()));
await employee.onSubmit();assert.equal(employee.data.retry,true);employee.onHide();await employee.onShow();assert.equal(employee.data.total,1);await employee.onRetry();assert.equal(employee.data.retry,false);assert.deepEqual(writes[0],writes[1]);const id=employee.data.detail.id;employee.onUnload();
const admin=pageFor("admin",id);await admin.onShow();assert.equal(admin.data.detail.status,"PENDING_ADMIN_DECISION");admin.onField(field("reason","已核实实际故障，批准内部零费用原地处理"));await admin.onAction(action("approve"));assert.equal(admin.data.detail.status,"WAITING_WAREHOUSE_CLAIM");admin.onUnload();
const warehouse=pageFor("warehouse",id);await warehouse.onShow();await warehouse.onAction(action("claim"));assert.equal(warehouse.data.detail.status,"WAREHOUSE_PROCESSING");
for(const operation of ["accept","complete"]){warehouse.onField(field("observedCode","REP-ASSET-951020"));warehouse.onPhysical({detail:{value:["location","neverLeft"]}});warehouse.onField(field("evidence",operation==="accept"?"现场实际核码核位，未离开原保管方，接修记录":"现场实际修复，核对电源恢复正常，原保管方与位置不变"));await warehouse.onAction(action(operation));assert.equal(warehouse.data.error,"");}
assert.equal(warehouse.data.detail.status,"COMPLETED");assert.equal(warehouse.data.detail.historyTotal,5);warehouse.onUnload();
console.log(JSON.stringify({nativeActualRepairClosed:true,exactOriginalReplyRecovered:true,approvalAndOwnerAndPhysicalTransitions:true,repairId:id,writes:writes.length}));
