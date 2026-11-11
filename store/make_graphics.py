# Draws the Google Play store graphics from vector shapes. Needs rsvg-convert and the Inter font.
# Run from this folder: python3 make_graphics.py
import subprocess
BG="#0f7f96"; WATER="#0c6a7e"; BODY="#eef2f4"; TOP="#8aa4b4"; FIN="#c9d3da"; LINE="#9fb3c0"; PLAY="#203e4d"
def fish(tx,ty,s,cid):
    # fish drawn in the banner's 640x360 space: body from x=-30 to nose x=236, centred y=172
    return f'''<g transform="translate({tx},{ty}) scale({s})">
  <defs><clipPath id="{cid}"><path d="M-40,120 C60,112 190,118 236,172 C190,226 60,232 -40,224 Z"/></clipPath></defs>
  <path d="M38,120 L72,86 L104,118 Z" fill="{FIN}"/>
  <path d="M-40,120 C60,112 190,118 236,172 C190,226 60,232 -40,224 Z" fill="{BODY}"/>
  <rect x="-40" y="100" width="300" height="38" fill="{TOP}" clip-path="url(#{cid})"/>
  <path d="M-40,176 C40,174 110,174 150,176" stroke="{LINE}" stroke-width="2" fill="none" clip-path="url(#{cid})"/>
  <path d="M120,128 C130,150 130,196 118,220" stroke="{LINE}" stroke-width="3" fill="none" clip-path="url(#{cid})"/>
  <circle cx="170" cy="172" r="37" fill="#ffffff"/>
  <circle cx="170" cy="172" r="32" fill="{PLAY}"/>
  <path d="M159,154 L159,190 L188,172 Z" fill="#ffffff"/>
</g>'''
def water(w,h,top):
    return f'<path d="M0,{top} Q{w/2},{top-h*0.04} {w},{top} L{w},{h} L0,{h} Z" fill="{WATER}"/>'
FONT="font-family='Inter' "
def banner(w,h,name):
    s=w/640
    svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">
<rect width="{w}" height="{h}" fill="{BG}"/>{water(w,h,h*0.73)}
{fish(0,(h-360*s)/2,s,'c')}
<text x="{262*s}" y="{(h/2)+12*s}" {FONT}font-weight="700" font-size="{64*s}" fill="#ffffff">Pilchard</text>
<text x="{266*s}" y="{(h/2)+56*s}" {FONT}font-weight="600" font-size="{19*s}" letter-spacing="{6*s}" fill="#ffffff">PLAYER</text>
</svg>'''
    open(name+'.svg','w').write(svg)
def icon(n,name):
    s=n/250
    svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="{n}" height="{n}" viewBox="0 0 {n} {n}">
<rect width="{n}" height="{n}" fill="{BG}"/>{water(n,n,n*0.80)}
{fish(-10*s,(n/2)-172*s*1.0+8*s,s,'c')}
</svg>'''
    open(name+'.svg','w').write(svg)
def feature(w,h,name):
    s=1.45
    fy=h/2-172*s+6
    svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">
<rect width="{w}" height="{h}" fill="{BG}"/>{water(w,h,h*0.76)}
{fish(-10,fy,s,'c')}
<text x="420" y="232" {FONT}font-weight="700" font-size="96" fill="#ffffff">Pilchard</text>
<text x="426" y="290" {FONT}font-weight="600" font-size="28" letter-spacing="9" fill="#ffffff">PLAYER</text>
<text x="426" y="340" {FONT}font-weight="500" font-size="26" fill="#d4f1f7">A video player made for TV</text>
</svg>'''
    open(name+'.svg','w').write(svg)
icon(512,'icon-512'); feature(1024,500,'feature-1024x500'); banner(1280,720,'tv-banner-1280x720')
for n in ['icon-512','feature-1024x500','tv-banner-1280x720']:
    subprocess.run(['rsvg-convert',n+'.svg','-o',n+'.png'],check=True)
