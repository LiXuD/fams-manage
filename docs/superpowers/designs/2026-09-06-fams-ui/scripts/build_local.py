"""Reproducible local UI handoff; original D0.2 remains unchanged."""
from pathlib import Path
import json, re, hashlib

root = Path(__file__).resolve().parents[1]
source = root.parents[1] / 'prototypes/2026-09-06-fixed-asset/index.html'
raw = source.read_text()
palette = dict(white='#FFFFFF',gray25='#F7F9FC',gray50='#F0F3F8',gray100='#E3E8F0',gray300='#9AA8BA',gray500='#53657D',gray700='#33445D',gray900='#18263D',blue50='#EAF0FF',blue600='#2457D6',blue700='#1943AE',blue800='#153580',green50='#E8F5EE',green700='#206A45',amber50='#FFF4DE',amber700='#8A5300',red50='#FFF0EF',red700='#B4232E')
semantic = {'bg/canvas':'gray25','bg/surface':'white','bg/subtle':'gray50','text/primary':'gray900','text/secondary':'gray500','text/inverse':'white','border/default':'gray100','border/strong':'gray500','action/primary':'blue600','action/hover':'blue700','action/pressed':'blue800','action/subtle':'blue50','status/success':'green700','status/success-bg':'green50','status/warning':'amber700','status/warning-bg':'amber50','status/danger':'red700','status/danger-bg':'red50','focus/ring':'blue600'}
tokens = {f'palette/{n}':{'type':'color','value':v} for n,v in palette.items()}
tokens.update({n:{'type':'color','alias':f'palette/{v}','value':palette[v]} for n,v in semantic.items()})
for category, values in [('space',[0,4,8,12,16,24,32,48]),('radius',[0,4,8,12]),('size',[24,32,40,44,48,56,64])]:
    tokens.update({f'{category}/{v}':{'type':'dimension','value':v,'unit':'px'} for v in values})
(root/'tokens.json').write_text(json.dumps({'version':'D0.4','mode':'Light','tokens':tokens},ensure_ascii=False,indent=2)+'\n')
css = ':root {\n' + '\n'.join(f"  --fams-{n.replace('/','-')}: " + (f"var(--fams-{v['alias'].replace('/','-')})" if 'alias' in v else str(v['value'])+('px' if v['type']=='dimension' else ''))+';' for n,v in tokens.items()) + '\n}\n'
(root/'tokens.css').write_text(css)
styles = {
  'display': [36,48,700], 'heading': [24,36,700], 'section': [18,28,500],
  'body': [14,22,400], 'body-medium': [14,22,500], 'mobile-body': [16,26,400],
  'label': [12,20,500], 'number': [32,44,600]
}
(root/'styles.json').write_text(json.dumps({'version':'D0.4','typography':{k:dict(size=v[0],lineHeight=v[1],weight=v[2]) for k,v in styles.items()},'table':{'bodyStyle':'body','supportingStyle':'label','cellPaddingY':12,'identityAlignment':'left','numericAlignment':'right','numericVariant':'tabular-nums'},'elevation':{'surface':'0 8px 28px #18263d09','dialog':'0 8px 40px #18263d24'},'motion':{'feedbackMs':120,'spinnerMs':800,'reducedMotion':'disable transitions and spinner animation'},'layout':{'pc':[1440,1024],'mobile':[390,320],'pcSidebar':190,'pcContentPadding':28,'mobilePadding':16,'mobileTargetMin':44,'safeArea':'env(safe-area-inset-bottom, 0px)'}},ensure_ascii=False,indent=2)+'\n')
(root/'styles.css').write_text('\n'.join(f'.fams-type-{k} {{font-size:{v[0]}px;line-height:{v[1]}px;font-weight:{v[2]};}}' for k,v in styles.items())+'\n.fams-type-number {font-variant-numeric:tabular-nums;}\n')
(root/'library/data.js').write_text('window.FamsManifest = '+(root/'library/manifest.json').read_text().strip()+';\n')
def versioned(name):
    return name+'?v='+hashlib.sha256((root/name).read_bytes()).hexdigest()[:12]
html = raw.replace('</head>',f'<link rel="stylesheet" href="{versioned("tokens.css")}"><link rel="stylesheet" href="{versioned("visual.css")}"></head>')
html = html.replace('</body>',f'<script src="{versioned("visual.js")}"></script></body>')
(root/'prototype.html').write_text(html)
pages = re.findall(r"\['([PM]\d{2})','([^']+)','([^']+)','([^']+)','([^']+)'\]",raw)
families=json.loads((root/'library/manifest.json').read_text())['families']
(root/'page-inventory.json').write_text(json.dumps([dict(id=p[0],name=p[1],category=p[2],rule=p[3],role=p[4],localVisual='prototype.html?page='+p[0],presentation='prototype.html?present=1&page='+p[0],screenshot='screens/'+p[0]+'.png',componentExamples=[f['id'] for f in families if p[0] in f['pages']]) for p in pages],ensure_ascii=False,indent=2)+'\n')
(root/'inventory.js').write_text("document.getElementById('inventory').innerHTML=" + json.dumps(''.join(f'<a href="prototype.html?page={p[0]}"><small>{p[0]}</small>{p[1]}</a>' for p in pages),ensure_ascii=False) + ';\n')
gallery=(root/'index.html').read_text()
for name in ['tokens.css','visual.css','inventory.js']:
    gallery=re.sub(r'((?:href|src)=")'+re.escape(name)+r'(?:\?v=[a-f0-9]+)?(")',lambda m:m[1]+versioned(name)+m[2],gallery)
(root/'index.html').write_text(gallery)
catalog=(root/'component-library.html').read_text()
for name in ['tokens.css','library/components.css','library/catalog.css','library/data.js','library/components.js','library/catalog.js']:
    catalog=re.sub(r'((?:href|src)=")'+re.escape(name)+r'(?:\?v=[a-f0-9]+)?(")',lambda m:m[1]+versioned(name)+m[2],catalog)
(root/'component-library.html').write_text(catalog)
screen_cards=''.join(f'<article><a href="prototype.html?present=1&page={p[0]}"><img loading="lazy" src="screens/{p[0]}.png" alt="{p[0]} {p[1]}"><h2>{p[0]} · {p[1]}</h2></a><p>{p[4]}</p></article>' for p in pages)
(root/'screens.html').write_text('<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="icon" href="data:"><title>FAMS · 40 页视觉画廊</title><link rel="stylesheet" href="tokens.css"><style>*{box-sizing:border-box}body{margin:0;padding:32px;background:var(--fams-bg-canvas);color:var(--fams-text-primary);font:14px/1.6 system-ui}a{color:inherit;text-decoration:none}header{margin-bottom:32px}header a{color:var(--fams-action-primary)}main{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:24px}article{border:1px solid var(--fams-border-default);border-radius:12px;padding:16px;background:white;min-width:0}img{display:block;width:100%;height:280px;object-fit:contain;object-position:top;background:var(--fams-bg-canvas)}h2{font-size:16px;margin-bottom:4px}p{color:var(--fams-text-secondary);margin:0}@media(max-width:1000px){main{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){body{padding:20px 16px}main{grid-template-columns:1fr}}</style></head><body><header><a href="index.html">← 返回设计系统</a><h1>40 页视觉画廊</h1><p>PC 26 页 / 小程序 14 页 · 点击图片打开独立画布；截图为虚构数据的默认状态。</p></header><main>'+screen_cards+'</main></body></html>')
(root/'source-baseline.json').write_text(json.dumps({'source':str(source),'sha256':hashlib.sha256(raw.encode()).hexdigest(),'pages':len(pages),'inlineBusinessScript':'unchanged'},indent=2)+'\n')
print(json.dumps({'tokens':len(tokens),'pages':len(pages),'sourceSha256':hashlib.sha256(raw.encode()).hexdigest()}))
