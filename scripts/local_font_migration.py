from pathlib import Path
import re, subprocess

root=Path('.')
font_link='<link rel="stylesheet" href="/pelak-font.css?v=1">'
def edit(path, transform):
    p=Path(path)
    original=p.read_text(encoding='utf-8')
    updated=transform(original)
    assert original != updated, f'No change: {p}'
    p.write_text(updated,encoding='utf-8')

def exact(s, a, b):
    assert s.count(a)==1, a
    return s.replace(a,b,1)

def home(s):
    p=r'<link rel="preconnect" href="https://fonts.googleapis.com" />\s*<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />\s*<link rel="stylesheet" href="https://fonts.googleapis.com/css2\?family=Vazirmatn[^"]+" media="print" onload="this.media=\'all\'" />\s*<noscript><link rel="stylesheet" href="https://fonts.googleapis.com/css2\?family=Vazirmatn[^"]+" /></noscript>'
    s,n=re.subn(p,font_link,s)
    assert n==1, n
    return s
edit('index.html',home)
def price(s):
    p=r'<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin><link rel="stylesheet" href="https://fonts.googleapis.com/css2\?family=Vazirmatn[^"]+">'
    s,n=re.subn(p,font_link,s)
    assert n==1
    return s.replace('pelak-car-price.css?v=3','pelak-car-price.css?v=4')
edit('car-price.html',price)
edit('discovery.css',lambda s:exact(s,'font:16px/1.95 Tahoma,Arial,sans-serif','font:16px/1.95 var(--pelak-persian-font)'))
edit('pelak-car-price.css',lambda s:exact(s,'font:16px/1.9 Vazirmatn,-apple-system,BlinkMacSystemFont,"Segoe UI",Tahoma,Arial,sans-serif','font:16px/1.9 var(--pelak-persian-font)'))
for p in ['admin.html','android.html']:
    edit(p,lambda s:exact(s,'</head>',font_link+'</head>').replace('font-family:Tahoma,Arial,sans-serif','font-family:var(--pelak-persian-font)'))
pages=[]
for name in ['about','ads','buying-guides','car-guide','comparisons','guides','import-cost','prices']:
    pages.extend(Path(name).rglob('index.html'))
assert len(pages)==27, len(pages)
for p in pages:
    edit(p,lambda s:exact(s,'<link rel="stylesheet" href="/discovery.css?v=1">',font_link+'<link rel="stylesheet" href="/discovery.css?v=2">'))
for p in ['scripts/build_discovery.py','pelak-public-ads.mjs']:
    edit(p,lambda s:exact(s,'<link rel="stylesheet" href="/discovery.css?v=1">',font_link+'<link rel="stylesheet" href="/discovery.css?v=2">'))
edit('pelak-sw.js',lambda s:exact(s,"'pelak-public-shell-v2'","'pelak-public-shell-v3'").replace("'/pelak-icon-192.png', '/pelak-icon-512.png'","'/pelak-icon-192.png', '/pelak-icon-512.png',\n  '/pelak-font.css', '/fonts/Vazirmatn-variable.woff2', '/discovery.css', '/pelak-car-price.css'"))
print(f'Updated {len(pages)+4} live HTML pages, generator, dynamic ads, styles and public-only cache.')
