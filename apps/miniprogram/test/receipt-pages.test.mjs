import assert from 'node:assert/strict';
import test from 'node:test';
import { createReceiptPage } from '../src/receipt-pages.ts';
const detail={requestId:'1',requestNo:'REQ1',requestVersion:4,allocationId:'2',allocationVersion:1,
    allocationStatus:'DELIVERED_WAITING_RECEIPT',asset:{assetCode:'A1',name:'Computer'},handover:{deliveredAt:'now',receiptDeadlineAt:'later',destinationText:'Office'}};
function fixture(request) {
    const services={sessions:{identity:{accountId:'1',bindingId:'1'},request},confirm:async()=>true,createKey:()=> 'receipt-key-000001',navigate() {}};
    const page=createReceiptPage(()=>services);page.data=structuredClone(page.data);page.setData=value=>Object.assign(page.data,value);
    return {page,services};
}
test('lost receipt reply preserves key, grant and original evidence; refresh does not imply receipt',async()=>{
    let lost=true;const writes=[];const {page}=fixture(async(method,path,body,options)=>{
        if(path.includes('/pending'))return {items:[],total:0};
        if(path.endsWith('/receipt'))return detail;
        if(path.endsWith('/grant'))return {grant:'x'.repeat(43)};
        writes.push({body:structuredClone(body),key:options.key,version:options.version});
        if(lost){lost=false;throw Error('lost after commit');}return {result:'RECEIVED'};
    });
    await page.onShow();await page.onOpen({currentTarget:{dataset:{request:'1',id:'2'}}});
    assert.equal(JSON.stringify(page.data).includes('x'.repeat(43)),false);
    page.onCode({detail:{value:'A1'}});page.onCheck({detail:{value:['checked']}});
    await page.onReceive();assert.equal(page.data.retryable,true);
    page.onCode({detail:{value:'CHANGED'}});await page.onRetry();assert.deepEqual(writes[0],writes[1]);
    assert.match(page.data.notice,/正式责任已更新/);assert.equal(page.data.selected,null);
});
test('identity switch and page hiding suppress late grant and clear receipt evidence',async()=>{
    let resolve;const {page,services}=fixture(async(method,path)=>{
        if(path.includes('/pending'))return {items:[],total:0};
        if(path.endsWith('/receipt'))return detail;
        return new Promise(r=>resolve=r);
    });
    await page.onShow();const opened=page.onOpen({currentTarget:{dataset:{request:'1',id:'2'}}});
    await new Promise(r=>setImmediate(r));page.onHide();services.sessions.identity={accountId:'other',bindingId:'other'};
    await page.onShow();resolve({grant:'x'.repeat(43)});await opened;
    assert.equal(page.data.selected,null);assert.equal(page.data.checked,false);assert.equal(page.data.busy,false);
});
test('receipt needs physical check and refusal reason; scanner failure allows manual code',async()=>{
    let writes=0;const {page}=fixture(async(method,path)=>{
        if(path.includes('/pending'))return {items:[],total:0};if(path.endsWith('/receipt'))return detail;
        if(path.endsWith('/grant'))return {grant:'x'.repeat(43)};writes++;return {result:'REFUSED'};
    });
    await page.onShow();await page.onOpen({currentTarget:{dataset:{request:'1',id:'2'}}});
    await page.onReceive();assert.equal(writes,0);await page.onScan();assert.match(page.data.error,/手工/);
    page.onCode({detail:{value:'A1'}});page.onCheck({detail:{value:['checked']}});await page.onRefuse();assert.equal(writes,0);
    page.onReason({detail:{value:'Wrong physical asset'}});await page.onRefuse();assert.equal(writes,1);assert.match(page.data.notice,/仍待交接/);
});
test('confirmation failure releases busy state and writes no receipt',async()=>{
    let writes=0;const {page,services}=fixture(async(method,path)=>{
        if(path.includes('/pending'))return {items:[],total:0};if(path.endsWith('/receipt'))return detail;
        if(path.endsWith('/grant'))return {grant:'x'.repeat(43)};writes++;return {result:'RECEIVED'};
    });
    services.confirm=async()=>{throw Error('modal unavailable');};
    await page.onShow();await page.onOpen({currentTarget:{dataset:{request:'1',id:'2'}}});
    page.onCode({detail:{value:'A1'}});page.onCheck({detail:{value:['checked']}});await page.onReceive();
    assert.equal(page.data.busy,false);assert.equal(writes,0);assert.match(page.data.error,/确认未完成/);
    services.confirm=async()=>true;await page.onReceive();assert.equal(writes,1);
});
