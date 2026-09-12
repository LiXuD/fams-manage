from pathlib import Path
import json,re,hashlib,math

root=Path(__file__).resolve().parents[1]
source=(root.parents[1]/'prototypes/2026-09-06-fixed-asset/index.html').read_text()
local=(root/'prototype.html').read_text()
tokens=json.loads((root/'tokens.json').read_text())['tokens']
def lum(h):
    rgb=[int(h[i:i+2],16)/255 for i in (1,3,5)]
    return sum(w*(v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4) for w,v in zip([.2126,.7152,.0722],rgb))
pairs=[('text/primary','bg/surface',4.5),('text/secondary','bg/canvas',4.5),('text/inverse','action/primary',4.5),('status/success','status/success-bg',4.5),('status/warning','status/warning-bg',4.5),('status/danger','status/danger-bg',4.5),('border/strong','bg/surface',3)]
contrast=[]
for a,b,limit in pairs:
    aa,bb=lum(tokens[a]['value']),lum(tokens[b]['value'])
    ratio=(max(aa,bb)+.05)/(min(aa,bb)+.05)
    contrast.append(dict(foreground=a,background=b,ratio=round(ratio,2),minimum=limit,passed=ratio>=limit))
links=[]
for f in [root/'index.html',root/'prototype.html',root/'component-library.html',root/'screens.html']:
    for href in re.findall(r'(?:href|src)="([^"]+)"',f.read_text()):
        if href.startswith(('http:','https:','data:','#')):continue
        target=href.split('?')[0].split('#')[0]
        if target and not (f.parent/target).exists():links.append(dict(file=f.name,target=target))
manifest=json.loads((root/'library/manifest.json').read_text())
report=dict(sourceSha256=hashlib.sha256(source.encode()).hexdigest(),businessScriptEqual=re.findall(r'<script>(.*?)</script>',source,re.S)==re.findall(r'<script>(.*?)</script>',local,re.S),tokens=len(tokens),pages=len(json.loads((root/'page-inventory.json').read_text())),contrast=contrast,brokenLocalLinks=links,componentFamilies=len(manifest['families']),variants=sum(math.prod(len(v) for v in f['axes'].values()) for f in manifest['families']),typographyStyles=len(json.loads((root/'styles.json').read_text())['typography']),screenshots=len(list((root/'screens').glob('[PM][0-9][0-9].png'))))
out=root.parents[3]/'output/playwright/fams-ui/static-audit.json'
out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False,indent=2))
assert report['businessScriptEqual'] and report['tokens']==56 and report['pages']==40
assert report['variants']==85 and report['componentFamilies']==8 and report['typographyStyles']==8 and report['screenshots']==40
assert all(x['passed'] for x in contrast) and not links
