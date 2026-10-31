import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import {createLossPage} from "../src/loss-pages.ts";
const event=dataset=>({currentTarget:{dataset}}),field=(name,value)=>({...event({field:name}),detail:{value}});
function fixture(){
 const calls=[];let keys=0;
 const row={id:"9",source:{id:"9007199254740993",assetCode:"L-1",name:"损坏原机",version:2},version:8,assetVersion:2,ownershipVersion:3,lockVersion:4,status:"SEARCHING",actions:{recoverOriginal:true},recoveryAsset:{version:2,serialNumber:"SERIAL-1",locationId:"9007199254740994",locationVersion:6,locationName:"原位置"},photos:[],history:[]};
 const sessions={identity:{accountId:"1",bindingId:"2"},identityRevision:0,async request(method,path,body,mutation){calls.push(structuredClone({method,path,body,mutation}));if(method==="POST")return{id:"9"};return path.includes("?")?{items:[row],total:1}:structuredClone(row);},discardInventoryPhoto(){},discardFactoryEvidence(){}};
 const page=createLossPage(()=>({sessions,createKey:()=>"damage-"+ ++keys}));page.data=structuredClone(page.data);page.setData=x=>Object.assign(page.data,x);page.onLoad({id:"9"});return{page,row,sessions,calls};
}
async function proof(f){await f.page.onShow();for(const[name,value]of Object.entries({observedAssetCode:"L-1",observedSerialNumber:"SERIAL-1",foundDate:"2026-10-30",foundTime:"10:20",recoveryEvidence:"亲见损坏原机仍由原责任人在登记位置保管，已对照身份。",repairFault:"现场原机无法开机，电源指示灯无法点亮。"}))f.page.onField(field(name,value));f.page.onDamagedChecked({detail:{value:true}});f.page.onRepairImpact(event({impact:"HIGH"}));}
const prepare=f=>f.page.onRecoveryPrepare(event({action:"recoverDamaged"})),writes=f=>f.calls.filter(x=>x.method==="POST");
test("damaged recovery freezes all versions and retries original intent after commit, refresh and hide/show",async()=>{
 const f=fixture();let lost=true;const request=f.sessions.request;
 f.sessions.request=async(...args)=>{const result=await request(...args);if(args[0]==="POST"&&lost){lost=false;Object.assign(f.row,{status:"RECOVERED",version:9,repairId:"9007199254740995",actions:{}});throw Error("committed reply lost");}return result;};
 await proof(f);prepare(f);assert.match(f.page.data.confirmation,/原锁转入维修/);assert.match(f.page.data.confirmation,/较高/);await f.page.onConfirm();assert.equal(f.page.data.retry,true);
 await f.page.onRefresh();assert.equal(f.page.data.detail.repairId,"9007199254740995");f.page.onHide();await f.page.onShow();f.page.onField(field("repairFault","不同故障不可覆盖原请求。"));await f.page.onRetry();
 assert.deepEqual(writes(f)[0],writes(f)[1]);const write=writes(f)[0];assert.match(write.path,/recover-to-repair$/);assert.equal(write.mutation.version,8);assert.deepEqual([write.body.recovery.assetVersion,write.body.recovery.ownershipVersion,write.body.recovery.lockVersion],[2,3,4]);assert.equal(write.body.recovery.facts.condition,"DAMAGED");assert.equal(write.body.recovery.facts.locationId,"9007199254740994");assert.equal(write.body.recovery.facts.locationVersion,6);assert.equal(write.body.impact,"HIGH");assert.equal(f.page.data.damagedChecked,false);assert.equal(f.page.data.repairFault,"");assert.equal(f.page.data.retry,false);
});
test("intact attestation cannot authorize damage and damage attestation cannot authorize intact",async()=>{
 const f=fixture();await proof(f);f.page.onDamagedChecked({detail:{value:false}});f.page.onRecoveryChecked({detail:{value:true}});prepare(f);assert.equal(f.page.data.confirmation,"");f.page.onRecoveryChecked({detail:{value:false}});f.page.onDamagedChecked({detail:{value:true}});f.page.onRecoveryPrepare(event({action:"recoverOriginal"}));assert.equal(f.page.data.confirmation,"");assert.equal(writes(f).length,0);
});
test("damage requires current capability, full fault, serial and registered location",async()=>{
 for(const mutate of [f=>f.row.actions={},f=>f.row.recoveryAsset.locationVersion=null,f=>f.row.recoveryAsset.locationId=null]){const f=fixture();mutate(f);await proof(f);prepare(f);assert.equal(f.page.data.confirmation,"");}
 for(const [name,value]of [["repairFault","故障"],["observedSerialNumber",""]]){const f=fixture();await proof(f);f.page.onField(field(name,value));prepare(f);assert.equal(f.page.data.confirmation,"");}
 const f=fixture();await proof(f);f.page.onRepairImpact(event({impact:"INVALID"}));assert.equal(f.page.data.repairImpact,"HIGH");prepare(f);f.page.onCancel();f.row.actions={};await f.page.onRefresh();prepare(f);assert.equal(f.page.data.confirmation,"");assert.equal(writes(f).length,0);
});
test("identity change discards prepared and pending damage evidence without another write",async()=>{
 for(const pending of [false,true]){const f=fixture();await proof(f);prepare(f);if(pending){f.sessions.request=async(method)=>{if(method==="POST")throw Error("unknown");return{};};await f.page.onConfirm();assert.equal(f.page.data.retry,true);}f.sessions.identityRevision++;await (pending?f.page.onRetry():f.page.onConfirm());assert.equal(f.page.data.detail,null);assert.equal(f.page.data.repairFault,"");assert.equal(f.page.data.damagedChecked,false);assert.equal(f.page.data.retry,false);assert.equal(f.page.data.confirmation,"");}
});
test("native template exposes damage handlers and linked repair number",()=>{
 const wxml=fs.readFileSync(new URL("../native/pages/loss-reports/index.wxml",import.meta.url),"utf8");for(const value of ['data-action="recoverDamaged"','bindchange="onDamagedChecked"','bindtap="onRepairImpact"','{{detail.repairId}}'])assert.ok(wxml.includes(value));
});


test("closed damage request uses a separate route and does not masquerade as completed recovery",async()=>{
 const f=fixture();Object.assign(f.row,{status:"CONFIRMED_LOST",actions:{requestRecovery:true}});
 await proof(f);f.page.onField(field("repairFault",""));
 f.page.onRecoveryPrepare(event({action:"requestDamaged"}));assert.match(f.page.data.confirmation,/新建损坏找回申请/);
 await f.page.onConfirm();const write=writes(f)[0];assert.match(write.path,/repair-recovery-requests$/);
 assert.equal(write.body.assetVersion,2);assert.equal(write.body.facts.condition,"DAMAGED");
 assert.equal(write.body.recovery,undefined);
});
test("closed damage execution freezes the new recovery versions and cannot use an intact approval",async()=>{
 const f=fixture();Object.assign(f.row,{status:"CONFIRMED_LOST",actions:{},recoveries:[{id:"22",version:7,assetVersion:2,ownershipVersion:6,actions:{execute:true},request:{facts:{condition:"DAMAGED"}}}]});
 await proof(f);f.page.onRecoveryPrepare(event({action:"execute",id:"22"}));assert.equal(f.page.data.confirmation,"");
 f.page.onRecoveryPrepare(event({action:"executeDamaged",id:"22"}));assert.ok(f.page.data.confirmation);await f.page.onConfirm();
 const write=writes(f)[0];assert.match(write.path,/recoveries\/22\/execute-to-repair$/);assert.equal(write.mutation.version,7);assert.equal(write.body.recovery.ownershipVersion,6);
 const g=fixture();Object.assign(g.row,{actions:{},recoveries:[{id:"22",version:7,assetVersion:2,ownershipVersion:6,actions:{execute:true},request:{facts:{condition:"INTACT"}}}]});await proof(g);
 g.page.onRecoveryPrepare(event({action:"executeDamaged",id:"22"}));assert.equal(g.page.data.confirmation,"");
 assert.equal(writes(g).length,0);
});
