(() => {
  const U=window.FamsUI, M=window.FamsManifest;
  const el=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text)n.textContent=text;return n;};
  function card(label,node){const n=el('article','kit-card');n.append(el('h3','',label));const box=el('div','kit-sample');box.append(node);n.append(box);return n;}
  function link(label,href){const a=el('a','',label);a.href=href;return a;}
  const nav=document.querySelector('#kit-nav');nav.append(link('00 交互试验区','#sandbox'));
  const cartesian=axes=>Object.entries(axes).reduce((list,[key,values])=>list.flatMap(p=>values.map(v=>({...p,[key]:v}))),[{}]);
  const noticeCopy={Info:['已保存到本设备','2 条待上传，尚未增加已盘数。'],Warning:['仍有资产待接收','请核对实物后逐项确认。'],Error:['上传失败','本地记录保留，请检查网络后重试。'],Success:['服务端已接受','本条已入账，任务进度已更新。'],Empty:['暂无记录','当前没有待接收资产，交出后会出现在这里。'],NoAccess:['无法查看此记录','当前账号没有访问权限，请联系管理员。'],Conflict:['资产状态已变化','请刷新当前责任和实物信息，再继续处理。']};
  M.families.forEach((f,index)=>{
    const section=el('section','kit-family');section.id=f.id;section.dataset.family=f.id;
    nav.append(link(`${String(index+1).padStart(2,'0')} ${f.name.split(' / ')[1]||f.name}`,'#'+f.id));
    const head=el('div','kit-section-title');head.append(el('small','',`${String(index+1).padStart(2,'0')} / ${cartesian(f.axes).length} VARIANTS`),el('h2','',f.name),el('p','',f.rule));section.append(head);
    const grid=el('div','kit-grid');
    cartesian(f.axes).forEach((v,i)=>{
      let props={...v};
      if(f.id==='button')Object.assign(props,{label:v.state==='Loading'?'处理中…':v.state==='Disabled'?'暂不可操作':v.kind==='Danger'?'确认处置':'确认接收'});
      if(f.id==='field')Object.assign(props,{id:`sample-${f.id}-${i}`,value:v.state==='Error'?'':'产品部 / 301',required:true,hint:v.state==='Error'?'请选择实际接收位置':v.state==='Disabled'?'业务处理中，暂不可修改':v.state==='ReadOnly'?'已确认的位置，只读展示':'填写实物实际存放位置'});
      if(f.id==='status')props.label={Neutral:'尚无记录',Info:'在库',Success:'已接收',Warning:'待交接',Danger:'身份失效'}[v.tone];
      if(f.id==='notice'){props.title=noticeCopy[v.state][0];props.body=noticeCopy[v.state][1];}
      if(f.id==='quantity')props.supporting=v.state==='Unavailable'?'当前数据不可用':v.state==='Loading'?'正在获取最新数量':'已交出待接收 1';
      const n=card(Object.values(v).join(' / '),U[f.factory](props));n.dataset.variant=`${f.id}:${Object.values(v).join('/')}`;grid.append(n);
    });
    section.append(grid);const code=el('details','kit-code');code.append(el('summary','','调用方式与属性'),el('pre','',`const node = FamsUI.${f.factory}(${JSON.stringify(cartesian(f.axes)[0],null,2)});\ncontainer.append(node);\n\n可配置属性：${f.props.join(', ')}\n状态轴：${Object.keys(f.axes).join(', ')}\n源原型选择器：${f.prototypeSelectors.join(', ')}`));section.append(code);
    const pages=el('div','kit-pages');pages.append(el('span','','页面应用：'));f.pages.forEach(p=>pages.append(link(p,`prototype.html?present=1&page=${p}`)));section.append(pages);document.querySelector('#families').append(section);
  });
  const demos=document.querySelector('#demos');
  const save=el('div');save.style.width='100%';const output=el('p','demo-output','等待输入');output.setAttribute('role','status');
  const input=U.field({id:'demo-location',required:true,label:'接收位置',hint:'输入实际位置后保存到本页示例。'}),saveButton=U.button({label:'保存位置示例',onClick:()=>{
    const n=input.querySelector('input');if(!n.value.trim()){n.setAttribute('aria-invalid','true');input.dataset.state='error';input.querySelector('small').textContent='请输入实际接收位置';output.textContent='位置未保存';n.focus();return;}
    n.removeAttribute('aria-invalid');input.dataset.state='default';input.querySelector('small').textContent='位置格式正确';saveButton.disabled=true;saveButton.setAttribute('aria-busy','true');saveButton.textContent='保存中…';setTimeout(()=>{saveButton.disabled=false;saveButton.removeAttribute('aria-busy');saveButton.textContent='保存位置示例';output.textContent=`本页已保存：${n.value}（未提交服务端）`;},700);
  }});save.append(input,saveButton,output);demos.append(card('表单校验 / 加载防重',save));
  const selectBox=el('div'),selected=el('p','demo-output','已选 0 项 · 未完成接收');selected.setAttribute('role','status');selectBox.append(U.assetCard({onSelect:checked=>selected.textContent=`已选 ${checked?1:0} 项 · 未完成接收`}),selected);demos.append(card('逐项选择 / 默认不全选',selectBox));
  const navBox=el('div'),navOut=el('p','demo-output','当前：待审批');navOut.setAttribute('role','status');navBox.append(U.navigation({onChange:i=>navOut.textContent='当前：'+['待审批','待领取','我处理中'][i]}),navOut);demos.append(card('导航 / 当前入口',navBox));
  const dialogBox=el('div','fams-inline'),dialogOut=el('p','demo-output','确认动作只在本页演示');dialogOut.setAttribute('role','status');['Confirm','Danger'].forEach(state=>{
    const d=U.dialog({state,title:state==='Danger'?'确认处置资产':'确认接收资产',body:state==='Danger'?'示例资产 FA-2026-00102。处置依据：已批准单据 CZ-001。确认后该资产将标记为已报废，不能继续领用。':'示例资产 FA-2026-00102。确认后责任变更为林晓，位置为产品部 / 301。',confirmLabel:state==='Danger'?'确认处置':'确认接收',onConfirm:()=>dialogOut.textContent='已确认示例 · 未提交服务端'});const b=U.button({label:state==='Danger'?'打开处置确认':'打开接收确认',kind:state==='Danger'?'Danger':'Primary',onClick:()=>d.openFrom(b)});dialogBox.append(b);});dialogBox.append(dialogOut);demos.append(card('确认弹窗 / Escape / 焦点返回',dialogBox));
  const compositions=document.querySelector('#composition-items');
  for(const type of ['Checkbox','Radio']){const box=el('div');['Unchecked','Checked','Disabled',...(type==='Checkbox'?['Mixed']:[])].forEach(state=>box.append(U.selection({type,state,name:`${type}-${state}`,label:`${type} / ${state}`})));compositions.append(card(`${type} · 原生选择控件`,box));}
  compositions.append(card('Steps · Complete / Current / Upcoming',U.steps()),card('Timeline · Complete / Current / Upcoming',U.timeline()));
  ['Determinate','Indeterminate'].forEach(state=>compositions.append(card('Progress / '+state,U.progress({state}))));
  const fieldStress=[
    ['field-input-focus-long-value','Input · Focus / 长位置值',{id:'catalog-stress-input',control:'Input',state:'Focus',label:'位置',value:'华东研发中心 / 三层联合办公区 / 靠窗设备柜 A-03',hint:'完整值可横向查看，聚焦环不覆盖标签。',required:true}],
    ['field-select-error-two-lines','Select · Error / 两行标签与错误',{id:'catalog-stress-select',control:'Select',state:'Error',label:'资产实际所在位置（请按现场门牌选择，无法确定时返回任务）',value:'请选择',hint:'尚未选择实际位置。请核对房间后再继续；错误不会挤掉操作。',required:true,options:['请选择','产品部 / 三层 / 301','产品部 / 三层 / 302']}],
    ['field-textarea-readonly-long-value','Textarea · ReadOnly / 长值',{id:'catalog-stress-textarea',control:'Textarea',state:'ReadOnly',label:'已确认的现场说明',value:'资产位于华东研发中心三层联合办公区，靠窗设备柜 A-03 第二层；现场门牌与系统位置一致。',hint:'只读内容保留完整信息，不能编辑。'}]
  ];
  fieldStress.forEach(([id,label,props])=>{const n=card(label,U.field(props));n.dataset.d04Example=id;compositions.append(n);});
  const quantityBox=el('div','kit-grid');[['申请总量',3],['待分配',0],['待交接',1],['已完成',1],['无法满足',1],['已取消',0]].forEach(([label,value])=>quantityBox.append(U.metric({label,value,supporting:label==='待交接'?'已交出待接收 1':''})));compositions.append(card('部分履行 / 六数量守恒',quantityBox));
  const empty=el('div');empty.append(U.notice({state:'Empty',title:'没有符合筛选条件的资产',body:'试试调整状态或清除关键词，原有资产记录不会被删除。'}));compositions.append(card('筛选无结果 / 区别于无记录',empty));
  const domains=document.querySelector('#domain-items');Object.entries(M.statusDomains).forEach(([key,d])=>{const box=el('div','fams-inline');d.examples.forEach(([label,tone])=>box.append(U.status({label,tone,domain:key})));domains.append(card(d.label,box));});
  nav.append(link('09 组合模式','#compositions'),link('10 状态维度','#domains'));
})();
