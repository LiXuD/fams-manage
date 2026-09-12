/* FAMS UI: native DOM components. All text props are textContent, never HTML. */
(() => {
  let serial = 0;
  const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text != null) n.textContent = text; return n; };
  const attrs = (n, values) => { Object.entries(values).forEach(([k,v]) => n.setAttribute(k,v)); return n; };
  const tone = s => String(s || 'Neutral').toLowerCase();
  const iconPaths = {
    home: '<rect x="5" y="5" width="14" height="15" rx="2"></rect><rect x="10" y="13" width="4" height="7" rx="1"></rect><path d="M8 9 H10 M14 9 H16"></path>',
    scan: '<path d="M9 4 H5 A1 1 0 0 0 4 5 V9 M15 4 H19 A1 1 0 0 1 20 5 V9 M4 15 V19 A1 1 0 0 0 5 20 H9 M20 15 V19 A1 1 0 0 1 19 20 H15"></path><path d="M7 12 H17"></path>',
    mine: '<circle cx="12" cy="8" r="3.5"></circle><rect x="5" y="14" width="14" height="6" rx="3"></rect>',
    message: '<path d="M5 5 H19 A1 1 0 0 1 20 6 V16 A1 1 0 0 1 19 17 H11 L7 20 V17 H5 A1 1 0 0 1 4 16 V6 A1 1 0 0 1 5 5 Z"></path><path d="M8 9 H16 M8 13 H14"></path>'
  };
  function icon(name) {
    const n=attrs(document.createElementNS('http://www.w3.org/2000/svg','svg'),{width:'24',height:'24',viewBox:'0 0 24 24','aria-hidden':'true',focusable:'false'});
    n.classList.add('fams-nav-icon');n.setAttribute('fill','none');n.setAttribute('stroke','currentColor');n.setAttribute('stroke-width','1.75');n.setAttribute('stroke-linecap','round');n.setAttribute('stroke-linejoin','round');n.innerHTML=iconPaths[name]||iconPaths.home;return n;
  }
  function button({label='确认',kind='Primary',size='PC',state='Default',icon,onClick}={}) {
    const n = el('button','fams-button',label); n.type='button'; Object.assign(n.dataset,{kind:kind.toLowerCase(),size:size.toLowerCase(),state:state.toLowerCase()});
    if (icon) { const i=el('span','fams-icon',icon); i.setAttribute('aria-hidden','true'); n.prepend(i); }
    if (state==='Loading') { n.prepend(attrs(el('span','fams-spinner'),{'aria-hidden':'true'})); n.setAttribute('aria-busy','true'); }
    n.disabled = ['Disabled','Loading'].includes(state); if(onClick)n.addEventListener('click',onClick); return n;
  }
  function field({id='fams-field-'+(++serial),label='接收位置',value='',hint='',required=false,options=['请选择','产品部 / 301','一号仓库 / A-03'],control='Input',state='Default',onChange}={}) {
    const wrap=el('div','fams-field'), lab=el('label','',label+(required?' *':''));lab.htmlFor=id;wrap.append(lab);wrap.dataset.state=tone(state);
    let n;
    if(state==='ReadOnly' && control==='Select'){ n=el('div','fams-readonly',value||'—');attrs(n,{id,role:'textbox','aria-readonly':'true','aria-label':label}); }
    else { n=el(control==='Select'?'select':control==='Textarea'?'textarea':'input','fams-control');n.id=id;
      if(control==='Select') options.forEach(v=>{const o=el('option','',v);o.value=v;n.append(o);});
      n.value=value;n.required=required;n.disabled=state==='Disabled';n.readOnly=state==='ReadOnly';if(control==='Textarea')n.rows=3;
      if(onChange)n.addEventListener('input',()=>onChange(n.value));
    }
    if(state==='Error')n.setAttribute('aria-invalid','true');
    if(hint){n.setAttribute('aria-describedby',id+'-hint');wrap.append(n,attrs(el('small','fams-field-hint',hint),{id:id+'-hint'}));}else wrap.append(n);
    return wrap;
  }
  function status({label='待交接',tone:color='Warning',domain}={}){const n=el('span','fams-status',label);n.dataset.tone=tone(color);if(domain)n.dataset.domain=domain;return n;}
  function navigation({id='nav-'+(++serial),mode='Tabs',items=['待审批','待领取','我处理中'],selected=0,itemState,onChange}={}) {
    const n=attrs(el('nav','fams-navigation'),{'aria-label':id}); n.dataset.mode=tone(mode);
    if(mode==='Mobile')items=['首页','扫码','我的','消息'];
    const buttons=items.map((item,i)=>{const label=typeof item==='string'?item:item.label,b=button({kind:'Secondary',label,size:mode==='Mobile'?'Mobile':'PC'});if(mode==='Mobile'){b.textContent='';b.append(icon(['home','scan','mine','message'][i]),el('span','fams-nav-label',label));}b.disabled=(typeof item==='object'&&item.disabled)||(itemState==='Disabled'&&i===0);n.append(b);return b;});
    const select=i=>buttons.forEach((b,j)=>{if(i===j)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current');});
    select(itemState==='Default'||itemState==='Disabled'?-1:selected);
    buttons.forEach((b,i)=>b.onclick=()=>{select(i);if(onChange)onChange(i);});return n;
  }
  function table({columns=['资产 / 编码','生命周期','正式责任','实物阶段'],rows=[['办公笔记本 · FA-2026-00102','在库','一号仓库 / A-03','已交出待接收']],caption='资产明细',state='Default',onSelect}={}) {
    const wrap=attrs(el('div','fams-table-scroll'),{tabindex:'0','aria-label':caption+'，可横向滚动'}),n=el('table','fams-table');n.append(el('caption','',caption));
    const columnKind=c=>/金额|费用|单价|数量|合计|小计|数目/.test(c)?'numeric':null;
    const head=el('thead'),hr=el('tr');columns.forEach((c,i)=>{const th=attrs(el('th','',c),{scope:'col'});th.dataset.columnKind=i===0?'identity':columnKind(c)||'text';hr.append(th);});head.append(hr);n.append(head);const body=el('tbody');
    if(state!=='Header')rows.forEach(row=>{const tr=el('tr');tr.dataset.state=tone(state);row.forEach((c,i)=>{const td=el(i===0?'th':'td','',c);if(i===0)td.scope='row';td.dataset.columnKind=i===0?'identity':columnKind(columns[i])||'text';tr.append(td);});
      if(onSelect&&state!=='ReadOnly'){const td=el('td');td.append(button({label:'查看',kind:'Secondary',onClick:()=>onSelect(row)}));tr.append(td);}body.append(tr);});
    if(onSelect&&state!=='ReadOnly')hr.append(attrs(el('th','','操作'),{scope:'col'}));n.append(body);wrap.append(n);return wrap;
  }
  function notice({state='Info',title='已保存到本设备',body='还有 2 条待上传，当前不增加已盘数。',actionLabel,onAction}={}) {
    const n=el('section','fams-notice');n.dataset.state=tone(state);n.append(el('strong','',title),el('p','',body));if(actionLabel)n.append(button({label:actionLabel,kind:'Secondary',onClick:onAction}));return n;
  }
  function metric({label='待交接',value=1,supporting='已交出待接收 1',state='Default'}={}) {
    const n=el('div','fams-metric');n.append(el('span','',label),el('strong','',state==='Default'?value:state==='Loading'?'加载中…':'—'),el('small','',supporting));if(state==='Loading')n.setAttribute('aria-busy','true');return n;
  }
  function selection({type='Checkbox',state='Unchecked',label='已核对编号与实物',name,id='select-'+(++serial),onChange}={}) {
    const wrap=el('label','fams-selection'),n=el('input');n.type=type.toLowerCase();n.id=id;n.name=name||id;n.checked=state==='Checked';n.disabled=state==='Disabled';n.indeterminate=type==='Checkbox'&&state==='Mixed';wrap.append(n,el('span','',label));if(onChange)n.onchange=()=>onChange(n.checked);return wrap;
  }
  function assetCard({state='Selectable',code='FA-2026-00102',name='办公笔记本 · 商务型',assetState='在库',physicalState='已交出待接收',responsibility='一号仓库 / A-03',target='林晓 · 产品部 / 301',onSelect}={}) {
    const n=el('article','fams-asset');n.dataset.state=tone(state);n.append(el('small','',code),el('h3','',name));const tags=el('div','fams-inline');tags.append(status({label:assetState,tone:'Info',domain:'asset'}),status({label:state==='Received'?'已接收':physicalState,tone:state==='Received'?'Success':'Warning',domain:'physical'}));n.append(tags);
    const dl=el('dl');[['正式责任',responsibility],['接收后',target]].forEach(([k,v])=>dl.append(el('dt','',k),el('dd','',v)));n.append(dl);
    if(['Selectable','Selected'].includes(state))n.append(selection({state:state==='Selected'?'Checked':'Unchecked',label:'已核对并确实收到此资产',onChange:checked=>{n.dataset.state=checked?'selected':'selectable';if(onSelect)onSelect(checked);}}));
    if(state==='Conflict')n.append(notice({state:'Conflict',title:'资产状态已变化',body:'请重新核对当前责任和实物，再继续处理。'}));
    if(state==='ReadOnly')n.append(el('small','','只读 · 当前无接收权限'));return n;
  }
  function steps({items=[{label:'管理员确认',state:'Complete',detail:'已批准'},{label:'库管交出',state:'Current',detail:'等待库管'},{label:'本人接收',state:'Upcoming',detail:'逐项核对后确认'}]}={}) {
    const n=el('ol','fams-steps');items.forEach((v,i)=>{const li=el('li');li.dataset.state=tone(v.state);if(v.state==='Current')li.setAttribute('aria-current','step');li.append(el('b','',`${v.state==='Complete'?'✓':i+1}　${v.label}`),el('small','',v.detail));n.append(li);});return n;
  }
  function progress({value=28,max=40,state='Determinate',pending=2}={}) {
    const n=el('div','fams-progress'),p=el('progress');p.max=max;if(state==='Determinate')p.value=value;p.setAttribute('aria-label',state==='Determinate'?`已盘 ${value} / ${max}`:'正在获取进度');n.append(el('b','',state==='Determinate'?`已盘 ${value} / ${max}`:'正在获取进度…'),p,el('small','',`待上传 ${pending} 条 · 尚未计入已盘`));return n;
  }
  function timeline({items=[{label:'申请已批准',state:'Complete',actor:'管理员 · 陈明',time:'09-06 09:20'},{label:'等待实物交出',state:'Current',actor:'库管 · 王芳',time:'尚未交出'},{label:'本人核对接收',state:'Upcoming',actor:'申请人 · 林晓',time:'尚未接收'}]}={}) {
    const n=el('ol','fams-timeline');items.forEach(v=>{const li=el('li');li.dataset.state=tone(v.state);li.append(el('strong','',v.label),el('span','',v.actor),el('small','',v.time));n.append(li);});return n;
  }
  function dialog({state='Confirm',title='确认接收资产',body='办公笔记本 · FA-2026-00102。确认后正式责任将变更为林晓，位置为产品部 / 301。',confirmLabel='确认接收',onConfirm}={}) {
    const n=el('dialog','fams-dialog'),id='dialog-'+(++serial);n.append(attrs(el('h2','',title),{id}),el('p','',body));n.setAttribute('aria-labelledby',id);const actions=el('div','fams-dialog-actions');actions.append(button({label:'取消',kind:'Secondary',onClick:()=>n.close('cancel')}),button({label:confirmLabel,kind:state==='Danger'?'Danger':'Primary',onClick:()=>{n.close('confirm');if(onConfirm)onConfirm();}}));n.append(actions);let trigger;
    n.openFrom=element=>{trigger=element||document.activeElement;if(!n.isConnected)document.body.append(n);n.showModal();actions.firstChild.focus();};n.addEventListener('close',()=>{if(trigger?.isConnected)trigger.focus();});return n;
  }
  window.FamsUI={button,field,status,navigation,table,notice,metric,selection,assetCard,steps,dialog,progress,timeline,icon};
})();
