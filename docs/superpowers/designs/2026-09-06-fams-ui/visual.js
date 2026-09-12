// D0.4 visual decoration only. The D0.2 state machine, handlers, actions and page IDs stay in the inline script.
(() => {
  const qs = new URLSearchParams(location.search);
  const exactTone = new Map([
    ['在库','info'],['在用','info'],['借出','info'],['正常借出','info'],['部门公用','info'],['库管处理中','info'],
    ['已接收','success'],['本人已接收','success'],['已完成','success'],['已入账','success'],['已计入','success'],['已接受','success'],['已接受 · 原回执','success'],
    ['待交接','warning'],['领用待交接','warning'],['已交出待接收','warning'],['已交出，等待你接收','warning'],['待管理员确认','warning'],['待库管处理','warning'],['待处理','warning'],['部分完成','warning'],['无法满足','warning'],['待上传','warning'],['待核验','warning'],['维修中','warning'],['返厂维修','warning'],['迟到待复核','warning'],['时间异常待复核','warning'],['旧授权待复核','warning'],
    ['已过期','danger'],['过期失效','danger'],['明确拒绝','danger'],['身份失效','danger'],['未保存','danger'],['冲突','danger'],['已驳回','danger']
  ]);
  const patternedTone = [
    [/^(?:领用)?待交接(?:\s*[·：:]\s*.+)?$/,'warning'],[/^已交出待接收(?:\s*\d+\s*件)?$/,'warning'],[/^待上传\s*\d+\s*条$/,'warning'],
    [/^(?:迟到|时间异常|旧授权)待复核(?:\s*[·：:]\s*.+)?$/,'warning'],[/^(?:已接收|已完成|已入账|已计入)(?:\s*\d+\s*件)?$/,'success'],
    [/^(?:已过期|过期失效|明确拒绝|身份失效|未保存|冲突)(?:\s*[·：:]\s*.+)?$/,'danger']
  ];
  const toneFor = text => {const value=text.replace(/\s+/g,' ').trim();if(exactTone.has(value))return exactTone.get(value);const found=patternedTone.find(([pattern])=>pattern.test(value));return found?found[1]:'neutral';};
  const pageId = () => (typeof state !== 'undefined' && state.page) || document.querySelector('#pageMeta')?.textContent.match(/[PM]\d{2}/)?.[0] || '';
  const iconDefs = {
    M02:[['rect',{x:5,y:5,width:14,height:15,rx:2}],['rect',{x:10,y:13,width:4,height:7,rx:1}],['path',{d:'M8 9 H10 M14 9 H16'}]],
    M08:[['path',{d:'M9 4 H5 V9 M15 4 H19 V9 M4 15 V20 H9 M20 15 V20 H15'}],['path',{d:'M7 12 H17'}]],
    M14:[['circle',{cx:12,cy:8,r:3.5}],['rect',{x:5,y:14,width:14,height:6,rx:3}]],
    M13:[['rect',{x:4,y:5,width:16,height:12,rx:2}],['path',{d:'M8 17 V20 L12 17'}],['path',{d:'M8 9 H16 M8 13 H14'}]]
  };
  function makeIcon(target) {
    const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
    Object.entries({width:24,height:24,viewBox:'0 0 24 24','aria-hidden':'true',focusable:'false',fill:'none',stroke:'currentColor','stroke-width':'1.75','stroke-linecap':'round','stroke-linejoin':'round'}).forEach(([k,v])=>svg.setAttribute(k,v));
    svg.classList.add('d04-nav-icon');
    (iconDefs[target]||iconDefs.M02).forEach(([tag,props])=>{const node=document.createElementNS('http://www.w3.org/2000/svg',tag);Object.entries(props).forEach(([k,v])=>node.setAttribute(k,v));svg.append(node);});
    return svg;
  }
  const domainByLabel = new Map([
    ['在库','asset'],['在用','asset'],['借出','asset'],['正常借出','asset'],['部门公用','asset'],['维修中','asset'],['返厂维修','asset'],['已报废','asset'],
    ['待管理员确认','transaction'],['待库管处理','transaction'],['库管处理中','transaction'],['领用待交接','transaction'],['待交接','transaction'],['待处理','evidence'],['部分完成','transaction'],['无法满足','transaction'],['已完成','transaction'],['已驳回','transaction'],
    ['待交出','physical'],['已交出待接收','physical'],['已交出，等待你接收','physical'],['已接收','physical'],['本人已接收','physical'],
    ['待上传','evidence'],['待核验','evidence'],['已接受','evidence'],['已接受 · 原回执','evidence'],['迟到待复核','evidence'],['时间异常待复核','evidence'],['旧授权待复核','evidence'],['过期失效','evidence'],['明确拒绝','evidence'],['身份失效','evidence'],['异常：正常','asset']
  ]);
  const pageDomain = new Map([
    ['P03','asset'],['P04','asset'],['P05','asset'],['P06','transaction'],['P07','transaction'],['P08','asset'],
    ['P09','transaction'],['P10','transaction'],['P11','transaction'],['P12','physical'],['P13','physical'],['P14','transaction'],
    ['P15','transaction'],['P16','evidence'],['P17','asset'],['P18','transaction'],['P19','asset'],['P20','transaction'],
    ['P21','physical'],['P22','transaction'],['P23','evidence'],['P24','transaction'],['P25','transaction'],['P26','evidence'],
    ['M01','transaction'],['M02','transaction'],['M03','transaction'],['M04','transaction'],['M05','asset'],['M06','asset'],
    ['M07','asset'],['M08','physical'],['M09','physical'],['M10','evidence'],['M11','evidence'],['M12','evidence'],['M13','evidence'],['M14','transaction']
  ]);
  function domainFor(page,text) {
    const value=text.replace(/\s+/g,' ').trim();
    if(domainByLabel.has(value))return domainByLabel.get(value);
    if(/^已交出待接收\s*\d+\s*件$/.test(value))return 'physical';
    if(/^待上传\s*\d+\s*条$/.test(value))return 'evidence';
    if(/^(?:已接受|过期失效|明确拒绝|身份失效|迟到待复核|时间异常待复核|旧授权待复核)\s*[·：:].*$/.test(value))return 'evidence';
    if(/^(?:已接收|本人已接收|已完成|已入账|已计入)\s*\d+\s*件$/.test(value))return value.startsWith('已接收')||value.startsWith('本人')?'physical':'transaction';
    if(/^(?:领用)?待交接\s*[·：:].*$/.test(value))return 'transaction';
    return pageDomain.get(page)||'transaction';
  }
  function decorateTables(root) {
    root.querySelectorAll('.tablewrap').forEach(wrap=>{wrap.tabIndex=0;if(!wrap.getAttribute('aria-label'))wrap.setAttribute('aria-label','表格，可横向滚动查看完整字段');});
    root.querySelectorAll('table').forEach(table=>{const headers=[...table.querySelectorAll('thead th, tr:first-child th')];headers.forEach((th,index)=>{const label=th.textContent.trim();const kind=index===0||/编码|申请|资产|员工|角色|消息|来源/.test(label)?'identity':/数量|金额|费用|合计|小计|已盘 \/ 应盘/.test(label)?'numeric':'text';th.dataset.columnKind=kind;table.querySelectorAll(`tr > :nth-child(${index+1})`).forEach(cell=>cell.dataset.columnKind=kind);});});
  }
  function decorateNavigation(root,page) {
    const nav=root.querySelector('.bottomnav');if(!nav)return;const current=page==='M08'?'M08':page==='M13'?'M13':page==='M14'?'M14':'M02';
    nav.querySelectorAll('button[data-go]').forEach(button=>{const target=button.dataset.go,label=button.textContent.trim();if(!button.querySelector('.d04-nav-icon')){button.textContent='';button.append(makeIcon(target));const span=document.createElement('span');span.className='d04-nav-label';span.textContent=label;button.append(span);}const selected=target===current;button.classList.toggle('active',selected);if(selected)button.setAttribute('aria-current','page');else button.removeAttribute('aria-current');});
  }
  function decoratePage(root,page) {
    document.body.dataset.d04Page=page;
    root.querySelectorAll('.badge').forEach(el=>{const text=el.textContent.trim();el.dataset.tone=toneFor(text);el.dataset.domain=domainFor(page,text);});
    root.querySelectorAll('.scanbox span').forEach(el=>{if(el.textContent.includes('草图占位'))el.textContent=el.textContent.includes('连续')?'将资产码置于框内 · 连续识别':'将资产码置于框内';});
    root.querySelectorAll('.statusbar').forEach(bar=>{const items=bar.querySelectorAll('span');if(items[1]&&!items[1].classList.contains('d04-host-capsule')){items[1].textContent='';items[1].className='d04-host-capsule';items[1].setAttribute('aria-label','微信小程序宿主区域示意');items[1].append(Object.assign(document.createElement('i'),{className:'d04-host-more'}),Object.assign(document.createElement('i'),{className:'d04-host-close'}));}});
    decorateNavigation(root,page);decorateTables(root);
    if(page==='M02'){const alert=[...root.querySelectorAll('.mobilecontent>.banner')][0];if(alert){alert.classList.add('warning','d04-attention');alert.dataset.state='warning';}}
    if(page==='M05')root.querySelectorAll('.mobilecontent>.panel').forEach((panel,index)=>{panel.classList.add('d04-asset-panel');panel.querySelector('p')?.classList.add('d04-asset-code');const actions=panel.querySelector('.phone-actions');if(!actions)return;actions.classList.add('d04-asset-actions');actions.querySelectorAll('button').forEach(button=>{const action=button.dataset.action,target=button.dataset.go;if(target==='M08'||(index===1&&target==='M04'))button.classList.add('d04-detail-action');else if(action==='request-scrap')button.classList.add('d04-risk-action');else button.classList.add('d04-common-action');});});
    if(page==='M14'){const panels=root.querySelectorAll('.mobilecontent>.panel');panels[0]?.classList.add('d04-record-panel');panels[0]?.querySelector('.phone-actions')?.classList.add('d04-nav-rows');panels[1]?.classList.add('d04-account-panel');panels[1]?.querySelector('.phone-actions')?.classList.add('d04-account-actions');}
    if(page==='P04')root.querySelector('.content>.panel .kv')?.classList.add('d04-kv-two-column');
    if(page==='P23'){root.querySelectorAll('.content>.grid2>.panel').forEach(panel=>panel.classList.add('d04-border-lite'));const labels=new Map([['P09','查看批准'],['P12','查看交出'],['P11','查看接收'],['P20','查看处置']]);root.querySelectorAll('.content table button[data-go]').forEach(button=>{const label=labels.get(button.dataset.go);if(label&&button.textContent.trim()!==label)button.textContent=label;button.classList.add('d04-table-link');});}
  }
  function decorate(){const root=document.getElementById('canvas');if(!root)return;decoratePage(root,pageId());document.title=document.title.replace('固定资产草图','FAMS D0.4 视觉稿');}
  if(qs.get('canvas')==='warm')document.body.classList.add('d04-warm-canvas');if(qs.get('m02header')==='gradient')document.body.classList.add('d04-m02-gradient');if(qs.get('tableDensity')==='compact')document.body.classList.add('d04-table-compact');
  new MutationObserver(decorate).observe(document.getElementById('canvas'),{childList:true,subtree:true});
  document.querySelector('.reviewbar strong').textContent='FAMS / 视觉设计评审';document.querySelector('.reviewbar small').textContent='D0.4 · PRD V1.5 · 40 页视觉应用 · 虚构数据';
  const designLink=document.querySelector('.reviewbar a');designLink.href='index.html';designLink.textContent='视觉规范与组件库 ↗';
  const target=qs.get('page');if(qs.get('present')==='1')document.body.classList.add('presentation');if(target&&byId[target])navigate(target);
  const offline=qs.get('offline');if(target==='M12'&&['late','expired','invalid'].includes(offline)&&typeof setOfflineCase==='function'){setOfflineCase(offline);render();}
  decorate();
})();
