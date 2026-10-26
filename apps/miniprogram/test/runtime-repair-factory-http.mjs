// Actual Page -> opaque session -> owned Spring/MySQL. HTTPS hostname, SDK and scan outcomes are explicit test adapters, not device acceptance.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomUUID, createHash } from "node:crypto";
import { MiniSessionClient } from "../src/session.ts";
import { createWxRuntime } from "../src/wx-runtime.ts";
import { createRepairPage } from "../src/repair-pages.ts";
import { createRepairFactoryPage } from "../src/repair-factory-pages.ts";

const port=process.env.FAMS_TEST_API_PORT, grants=JSON.parse(process.env.FAMS_TEST_REPAIR_GRANTS);
assert.match(port??"",/^[0-9]{1,5}$/);
const origin="https://factory-test.invalid", root=fs.mkdtempSync(path.join(os.tmpdir(),"fams-native-factory-"));
const png=Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+afo0AAAAASUVORK5CYII=","base64");
const digest=b=>createHash("sha256").update(b).digest("hex"), uploads=[],clients=new Map();
const direct=process.env.FAMS_TEST_FACTORY_INITIAL==="1",priced=process.env.FAMS_TEST_FACTORY_PRICED==="1",decisions=[],moneyWrites=[];
let dropUpload=true,dropDecision=direct,dropCost=priced;
const dueDate=new Date(Date.now()+7*86400000).toISOString().slice(0,10);
const target=url=>{assert.ok(url.startsWith(origin+"/api/v1/mini/"));return "http://127.0.0.1:"+port+url.slice(origin.length);};
const event=dataset=>({currentTarget:{dataset}}),field=(key,value)=>({...event({field:key}),detail:{value}});
function services(role){
 if(clients.has(role))return clients.get(role);
 const dir=path.join(root,role);fs.mkdirSync(dir);const source=path.join(dir,"chosen.png"),store=new Map([["fams.session",grants[role]]]);
 const io={mkdirSync:(p,recursive)=>fs.mkdirSync(p,{recursive}),copyFileSync:fs.copyFileSync,statSync:fs.statSync,unlinkSync:fs.unlinkSync,readdirSync:fs.readdirSync,accessSync:fs.accessSync};
 const wx={env:{USER_DATA_PATH:dir},getFileSystemManager:()=>io,getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,structuredClone(v)),removeStorageSync:k=>store.delete(k),
  request:input=>{void(async()=>{const response=await fetch(target(input.url),{method:input.method,headers:{"Content-Type":"application/json",...input.header},body:input.data===undefined?undefined:JSON.stringify(input.data),redirect:"error"});
   const data=await response.json();if(input.method==="POST"&&input.url.endsWith("/decision")){decisions.push({body:structuredClone(input.data),key:input.header["Idempotency-Key"],version:input.header["If-Match-Version"]});if(dropDecision&&response.status===200){dropDecision=false;input.fail(new Error("Owned adapter dropped committed initial decision"));return;}}
   if(input.method==="POST"&&/\/(quotes|cost)$/.test(input.url)){moneyWrites.push({path:input.url,body:structuredClone(input.data),key:input.header["Idempotency-Key"],version:input.header["If-Match-Version"]});if(dropCost&&input.url.endsWith("/cost")&&response.status===200){dropCost=false;input.fail(new Error("Owned adapter dropped committed final cost"));return;}}
   input.success({statusCode:response.status,data});})().catch(input.fail);},
  uploadFile:input=>{void(async()=>{const bytes=fs.readFileSync(input.filePath),form=new FormData();
   for(const[k,v]of Object.entries(input.formData))form.append(k,v);
   // WeChat's upload API exposes no independent filename/MIME parameter. Exercise an opaque generic part.
   form.append(input.name,new Blob([bytes],{type:"application/octet-stream"}),"opaque-native-original");
   const response=await fetch(target(input.url),{method:"POST",headers:input.header,body:form,redirect:"error"}),data=await response.text();
   uploads.push({key:input.header["Idempotency-Key"],version:input.header["If-Match-Version"],form:structuredClone(input.formData),sha:digest(bytes),path:input.filePath});
   if(dropUpload&&response.status===200){dropUpload=false;input.fail(new Error("Owned adapter dropped the already committed original receipt"));return;}
   input.success({statusCode:response.status,data});})().catch(input.fail);},
  downloadFile:input=>{void(async()=>{const response=await fetch(target(input.url),{headers:input.header,redirect:"error"});fs.writeFileSync(input.filePath,Buffer.from(await response.arrayBuffer()));input.success({statusCode:response.status,filePath:input.filePath,tempFilePath:input.filePath});})().catch(input.fail);}
 };
 const sessions=new MiniSessionClient(origin,createWxRuntime(wx)),service={sessions,createKey:randomUUID,confirm:async()=>true,navigate:url=>{service.navigation=url;},
  chooseEvidence:async()=>{fs.writeFileSync(source,png);return {path:source,name:"厂家原机依据.png",size:png.length};},source};
 clients.set(role,service);return service;
}
async function page(role,id,factory=false){const p=(factory?createRepairFactoryPage:createRepairPage)(()=>services(role));p.data=structuredClone(p.data);p.setData=v=>Object.assign(p.data,v);p.onLoad({id});await p.onShow();return p;}
function okay(p){assert.equal(p.data.error,"");assert.equal(p.data.retry,false);}
async function act(p,action){p.onPrepare(event({action}));assert.ok(p.data.confirmation,p.data.error);await p.onConfirm();okay(p);}
async function round(p){for(const[k,v]of Object.entries({provider:"独立原生厂家",contact:"原生私有联系人",phone:"13800000001",due:dueDate,fault:"实际无法开机，需要原机返厂核实"}))p.onField(field(k,v));await act(p,"createRound");assert.equal(p.data.selected.roundStatus,"PENDING_DISPATCH");assert.equal(p.data.selected.expectedReturnDate,dueDate);}
async function original(p,purpose){
 if(purpose){const index=p.data.purposeKinds.indexOf(purpose);assert.ok(index>=0);p.onPurpose({detail:{value:String(index)}});}
 await p.onChoose();p.onPrepare(event({action:"upload"}));assert.ok(p.data.confirmation,p.data.error);
 fs.writeFileSync(services("warehouse").source,"selected source changed after the immutable copy");
 await p.onConfirm();
 if(p.data.retry){const frozen=uploads.at(-1);assert.equal(fs.existsSync(frozen.path),true);p.onHide();assert.equal(fs.existsSync(frozen.path),true);await p.onShow();assert.notEqual(String(p.data.detail.version),frozen.version);await p.onRetry();assert.deepEqual(uploads.at(-1),frozen);}
 okay(p);assert.equal(fs.existsSync(uploads.at(-1).path),false);
 const file=p.data.selected.files.at(-1);assert.equal(file.filename,"厂家原机依据.png");assert.equal(file.contentType,"image/png");assert.equal(file.sizeBytes,png.length);
 await p.onFile(event({id:file.objectId}));okay(p);assert.equal(digest(fs.readFileSync(p.data.previewPath)),digest(png));p.onCloseFile();return file.objectId;
}
async function physical(p,action,object,condition,unassessed=false){
 for(const[k,v]of Object.entries({observedCode:"REP-ASSET-951020",serial:"ORIGINAL-FACTORY-951020",evidence:"已实际核验本轮原机、编号及序列号，依据原件与现场交接一致"}))p.onField(field(k,v));
 p.onNow();p.onChecked({detail:{value:unassessed?["original","unassessed"]:["original","noCost"]}});p.onEvidence({detail:{value:[object]}});
 if(action==="receive"){await p.onLocations(event({delta:"0"}));assert.ok(p.data.locations.some(x=>x.id==="951001"));p.onLocation(event({id:"951001"}));p.onCondition({detail:{value:condition==="RESTORED"?"1":"2"}});}
 await act(p,action);
}
try{
 const employee=await page("employee");okay(employee);employee.onAsset(event({id:"951020"}));employee.onField(field("fault","实际设备无法开机，原生返厂闭环验证"));employee.onField(field("occurredAt",new Date(Date.now()-60000).toISOString()));await employee.onSubmit();okay(employee);const id=employee.data.detail.id;assert.equal(employee.data.detail.actions.manageFactory,undefined);employee.onUnload();
 const admin=await page("admin",id);okay(admin);assert.equal(admin.data.detail.actions.manageFactory,true);admin.onField(field("reason",direct?"直接范围私有依据，13800000001，已确认原机及无费用边界":"批准内部无费用维修，返厂范围另行独立判断"));if(direct)admin.onApprovalMethod({detail:{value:"1"}});if(priced){admin.onFactoryCostPolicy({detail:{value:"1"}});admin.onField(field("factoryCostLimit","100.00"));}await admin.onAction(event({action:"approve"}));
 if(direct){assert.equal(admin.data.retry,true);admin.onHide();await admin.onShow();assert.equal(admin.data.approvalMethod,"INTERNAL_OR_FACTORY");await admin.onRetry();assert.deepEqual(decisions[0],decisions[1]);assert.equal(admin.data.detail.factorySummary.scope.scopeStatus,"APPROVED");}okay(admin);admin.onUnload();
 const warehouse=await page("warehouse",id);okay(warehouse);await warehouse.onAction(event({action:"claim"}));okay(warehouse);assert.equal(warehouse.data.detail.actions.manageFactory,true);warehouse.onFactory();assert.equal(services("warehouse").navigation,"/pages/repair-factory/index?id="+id);
 warehouse.onPhysicalMode(event({mode:"away"}));await warehouse.onPhysicalCatalog(event({kind:"location",delta:"0"}));warehouse.onPhysicalLocation(event({id:"951001"}));
 for(const[k,v]of Object.entries({physicalObservedCode:"REP-ASSET-951020",physicalAt:new Date().toISOString(),physicalEvidence:"已核验原机实际离开原保管方，由本库管接收待修"}))warehouse.onField(field(k,v));
 await warehouse.onPhysicalAction();okay(warehouse);warehouse.onHide();
 const factory=await page("warehouse",id,true);okay(factory);
 if(direct){assert.equal(factory.data.detail.pendingScope,null);assert.ok(factory.data.detail.approvedScope.decisionReason.includes("直接范围私有依据"));assert.equal(factory.data.detail.actions.requestScope,false);}
 else{factory.onField(field("reason","维修实际诊断需返厂，申请额外无费用范围"));await act(factory,"requestScope");factory.onHide();const approval=await page("admin",id,true);okay(approval);approval.onField(field("reason","确认实际必要性，明确批准无费用返厂范围"));await act(approval,"approveScope");approval.onUnload();await factory.onShow();okay(factory);}
 await round(factory);factory.onField(field("reason","本轮尚未寄出，实际调整厂家后重新建立"));await act(factory,"cancelRound");
 async function moneyFact(action,amount,object){factory.onField(field("moneyAmount",amount));factory.onField(field("moneyReason","亲自核对本轮报价或最终费用的原始凭证"));factory.onMoneyEvidence({detail:{value:[object]}});factory.onMoneyChecked({detail:{value:["checked"]}});factory.onPrepare(event({action}));assert.ok(factory.data.confirmation,factory.data.error);await factory.onConfirm();if(factory.data.retry){const frozen=structuredClone(moneyWrites.at(-1));assert.equal(frozen.body.amount,amount);await factory.onRefresh();factory.onHide();await factory.onShow();await factory.onRetry();assert.deepEqual(moneyWrites.at(-1),frozen);}okay(factory);}
 for(const [index,condition] of ["STILL_FAULTY","RESTORED"].entries()){
  await round(factory);
  if(priced){const quote=await original(factory,"QUOTE");await moneyFact("quote",index===0?"39.99":"60.01",quote);}
  const ack=await original(factory,"FACTORY_ACK");await physical(factory,"dispatch",ack);assert.equal(factory.data.selected.roundStatus,"DISPATCHED");const receipt=await original(factory,"RECEIPT");await physical(factory,"receive",receipt,condition,priced);assert.equal(factory.data.selected.conditionResult,condition);assert.equal(factory.data.detail.status,"WAREHOUSE_PROCESSING");assert.notEqual(factory.data.detail.lockVersion,null);
  if(priced){assert.equal(factory.data.selected.actualCost,null);assert.equal(factory.data.detail.costSummary.unassessedCount,1);const cost=await original(factory,"COST_RECEIPT");await moneyFact("cost",index===0?"39.99":"65.02",cost);assert.equal(factory.data.detail.costSummary.unassessedCount,0);}
 }
 if(priced){assert.equal(factory.data.detail.costSummary.confirmedTotal,"105.01");assert.equal(factory.data.detail.costSummary.overrun,true);assert.equal(factory.data.detail.costSummary.followupOpen,true);factory.onCostPolicy({detail:{value:"1"}});factory.onField(field("costLimit","105.01"));factory.onField(field("reason","实际累计费用超出原范围，申请明确累计105.01元"));await act(factory,"requestCostScope");factory.onHide();const approval=await page("admin",id,true);approval.onField(field("reason","核对已发生费用105.01元后明确批准累计新范围"));await act(approval,"approveScope");approval.onUnload();await factory.onShow();okay(factory);assert.equal(factory.data.detail.costSummary.overrun,false);}

 assert.equal(factory.data.rows.length,3);assert.equal(factory.data.rows.filter(r=>r.roundStatus==="CANCELLED").length,1);assert.equal(factory.data.detail.summary.actualDispatchCount,2);factory.onHide();
 const publicPage=await page("employee",id);okay(publicPage);const publicJson=JSON.stringify(publicPage.data.detail);for(const word of ["13800000001","独立原生厂家","原生私有联系人","厂家原机依据.png","直接范围私有依据",...(priced?["105.01","65.02","factoryCostPolicy","costSummary"]:[])])assert.ok(!publicJson.includes(word));assert.equal(publicPage.data.detail.factorySummary.actualDispatchCount,2);publicPage.onUnload();
 const denied=await page("employee",id,true);assert.equal(denied.data.detail,null);assert.ok(denied.data.error);denied.onUnload();
 await warehouse.onShow();okay(warehouse);warehouse.onPhysicalMode(event({mode:"inbound"}));await warehouse.onPhysicalCatalog(event({kind:"warehouse",delta:"0"}));warehouse.onPhysicalWarehouse(event({id:"951000"}));await warehouse.onPhysicalCatalog(event({kind:"location",delta:"0"}));warehouse.onPhysicalLocation(event({id:"951000"}));
 for(const[k,v]of Object.entries({physicalObservedCode:"REP-ASSET-951020",physicalAt:new Date().toISOString(),physicalEvidence:"原机已实际修复，核验编号及原机真实验收入库结束维修"}))warehouse.onField(field(k,v));
 await warehouse.onPhysicalAction();okay(warehouse);assert.equal(warehouse.data.detail.status,"COMPLETED");warehouse.onUnload();
 await factory.onShow();okay(factory);assert.equal(factory.data.detail.summary.followupOpen,false);assert.equal(factory.data.rows.length,3);factory.onUnload();
 for(const service of clients.values())service.sessions.clear();
 assert.equal(uploads.length,priced?9:5);assert.ok(uploads.every(x=>x.sha===digest(png)));
 console.log(JSON.stringify({nativeFactoryClosed:true,pricedFactory:priced,committedCostOriginalReplay:priced,unknownReturnThenFinalCost:priced,cumulativeOverrunExplicitlyApproved:priced,initialSingleFactoryApproval:direct,initialDecisionOriginalReplay:direct,repairId:id,twoActualRounds:true,canceledRoundNotCounted:true,opaqueOriginalMetadataAndBytes:true,committedOriginalRetry:true,employeePrivacy:true,independentInboundClosed:true,rounds:3,actualDispatchCount:2,originals:priced?8:4}));
}finally{fs.rmSync(root,{recursive:true,force:true});}
