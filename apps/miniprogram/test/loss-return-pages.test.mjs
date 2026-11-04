import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import {createLossPage} from "../src/loss-pages.ts";
const event=dataset=>({currentTarget:{dataset}}),field=(name,value)=>({...event({field:name}),detail:{value}});
function fixture(){
 const calls=[];let keys=0;
 const row={id:"9",source:{id:"9007199254740993",assetCode:"L-1",name:"损坏原机",version:2},version:8,assetVersion:2,ownershipVersion:3,lockVersion:4,status:"SEARCHING",actions:{recoverOriginal:true},recoveryAsset:{version:2,serialNumber:"SERIAL-1",locationId:"9007199254740994",locationVersion:6,locationName:"原位置"},photos:[],history:[]};
 const sessions={identity:{accountId:"1",bindingId:"2"},identityRevision:0,async request(method,path,body,mutation){calls.push(structuredClone({method,path,body,mutation}));if(method==="POST")return{id:"9"};if(path.includes("/recovery-locations"))return{items:[{id:"9007199254740995",name:"新实际位置",version:8}],total:1};return path.includes("?")?{items:[row],total:1}:structuredClone(row);},discardInventoryPhoto(){},discardFactoryEvidence(){}};
 const page=createLossPage(()=>({sessions,createKey:()=>"damage-"+ ++keys}));page.data=structuredClone(page.data);page.setData=x=>Object.assign(page.data,x);page.onLoad({id:"9"});return{page,row,sessions,calls};
}
async function proof(f){await f.page.onShow();for(const[name,value]of Object.entries({observedAssetCode:"L-1",observedSerialNumber:"SERIAL-1",foundDate:"2026-10-30",foundTime:"10:20",recoveryEvidence:"亲见损坏原机仍由原责任人在登记位置保管，已对照身份。",repairFault:"现场原机无法开机，电源指示灯无法点亮。"}))f.page.onField(field(name,value));f.page.onDamagedChecked({detail:{value:true}});f.page.onRepairImpact(event({impact:"HIGH"}));}
const writes=f=>f.calls.filter(x=>x.method==="POST");
test("linked return freezes actual location and replays original intent through committed reply loss, hide and refresh",async()=>{
 const f=fixture();let lost=true;const request=f.sessions.request;f.sessions.request=async(...args)=>{const result=await request(...args);if(args[0]==="POST"&&lost){lost=false;Object.assign(f.row,{status:"RECOVERED",version:9,returnId:"9007199254740996",actions:{}});throw Error("committed reply lost");}return result;};
 await proof(f);await f.page.onLoadLocations();f.page.onReturnLocation({detail:{value:"0"}});
 f.page.onRecoveryPrepare(event({action:"recoverReturn"}));assert.equal(f.page.data.confirmation,"");
 f.page.onReturnChecked({detail:{value:true}});f.page.onRecoveryPrepare(event({action:"recoverReturn"}));assert.match(f.page.data.confirmation,/原责任与借用保留至审批及实物验收/);await f.page.onConfirm();assert.equal(f.page.data.retry,true);
 await f.page.onRefresh();f.page.onHide();await f.page.onShow();await f.page.onRetry();
 assert.deepEqual(writes(f)[0],writes(f)[1]);const first=writes(f)[0];assert.match(first.path,/recover-to-return$/);assert.deepEqual([first.body.assetVersion,first.body.ownershipVersion,first.body.lockVersion],[2,3,4]);assert.equal(first.body.facts.stillOriginalCustodian,false);assert.equal(first.body.facts.locationId,"9007199254740995");assert.equal(first.body.facts.locationVersion,8);assert.equal(f.page.data.returnChecked,false);assert.equal(f.page.data.selectedLocation,"");assert.equal(f.page.data.detail.returnId,"9007199254740996");
});
test("closed return requires its own purpose and new ownership rather than an intact original approval",async()=>{
 const f=fixture();Object.assign(f.row,{status:"CONFIRMED_LOST",actions:{requestRecovery:true}});await proof(f);await f.page.onLoadLocations();f.page.onReturnLocation({detail:{value:"0"}});f.page.onReturnChecked({detail:{value:true}});f.page.onRecoveryPrepare(event({action:"requestReturn"}));await f.page.onConfirm();assert.match(writes(f)[0].path,/return-recovery-requests$/);
 Object.assign(f.row,{actions:{},recoveries:[{id:"44",version:7,assetVersion:2,ownershipVersion:9,actions:{execute:true},request:{destination:"ASSET_RETURN",facts:{condition:"INTACT"}}}]});await proof(f);await f.page.onLoadLocations();f.page.onReturnLocation({detail:{value:"0"}});f.page.onReturnChecked({detail:{value:true}});
 f.page.onRecoveryPrepare(event({action:"execute",id:"44"}));assert.equal(f.page.data.confirmation,"");f.page.onRecoveryPrepare(event({action:"executeReturn",id:"44"}));await f.page.onConfirm();assert.match(writes(f)[1].path,/recoveries\/44\/execute-to-return$/);assert.equal(writes(f)[1].mutation.version,7);assert.equal(writes(f)[1].body.ownershipVersion,9);
});

test("native recovery transfer keeps target references and original write after a lost reply", async () => {
 const f = fixture();
 f.row.source.lifecycle = "IN_USE";
 const request = f.sessions.request;
 let lost = true;
 f.sessions.request = async (method, path, body, mutation) => {
  if (path.includes("/transfers/catalog/organization")) return {items:[{id:"9007199254741001",name:"使用部门",version:5}],total:1};
  if (path.includes("/transfers/catalog/location")) return {items:[{id:"9007199254741002",name:"目标位置",version:9,locationType:"ROOM"}],total:1};
  if (path.includes("/transfers/catalog/employee")) {
   assert.match(path, /organizationId=9007199254741001$/);
   return {items:[{id:"9007199254741003",name:"目标责任人",version:6}],total:1};
  }
  const result = await request(method,path,body,mutation);
  if (method === "POST" && lost) {
   lost = false;
   Object.assign(f.row,{status:"RECOVERED",version:9,transferId:"9007199254741004",actions:{}});
   throw Error("committed reply lost");
  }
  return result;
 };
 await proof(f);
 await f.page.onLoadLocations();
 f.page.onReturnLocation({detail:{value:"0"}});
 f.page.onTransferChecked({detail:{value:true}});
 await f.page.onLoadTransferTargets();
 await f.page.onTransferTarget(field("organization","0"));
 await f.page.onTransferTarget(field("person","0"));
 await f.page.onTransferTarget(field("place","0"));
 f.page.onTransferReason({detail:{value:"找回原机后需调拨给现场接收人员"}});
 f.page.onRecoveryPrepare(event({action:"recoverTransfer"}));
 assert.match(f.page.data.confirmation,/目标责任人/);
 await f.page.onConfirm();
 assert.equal(f.page.data.retry,true);
 f.page.onHide();
 await f.page.onShow();
 await f.page.onRetry();
 assert.deepEqual(writes(f)[0],writes(f)[1]);
 const {path,body} = writes(f)[0];
 assert.match(path,/recover-to-transfer$/);
 assert.deepEqual(body.target.referenceVersions,{usingOrganization:5,employee:6,location:9});
 assert.equal(body.target.employeeId,"9007199254741003");
 assert.equal(body.recovery.facts.locationId,"9007199254740995");
 assert.equal(f.page.data.detail.transferId,"9007199254741004");
});
