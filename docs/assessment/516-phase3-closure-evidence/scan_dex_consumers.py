# Diagnostic evidence tooling; not a production module or CI lane.
import zipfile,struct,json,re,collections
import argparse
from pathlib import Path
args_parser = argparse.ArgumentParser(description="Bounded DEX method-reference and instruction-aligned consumer inventory")
args_parser.add_argument("apk", help="App or instrumentation APK to inspect")
args_parser.add_argument("output", help="Output directory (raw binaries are never copied)")
args = args_parser.parse_args()
apk = args.apk
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)
width=[1]*256
for x in [0x02,0x05,0x08,0x13,0x15,0x16,0x19,0x1a,0x1c,0x1f,0x20,0x22,0x23,0x29]:width[x]=2
for x in [0x03,0x06,0x09,0x14,0x17,0x1b,0x24,0x25,0x26,0x2a,0x2b,0x2c]:width[x]=3
width[0x18]=5
for a,b,w in [(0x2d,0x3d,2),(0x44,0x6d,2),(0x6e,0x72,3),(0x74,0x78,3),(0x90,0xaf,2),(0xd0,0xe2,2)]:
 for x in range(a,b+1):width[x]=w
for x in [0xfa,0xfb]:width[x]=4
for x in [0xfc,0xfd]:width[x]=3
for x in [0xfe,0xff]:width[x]=2
allout=[];summ=[]
with zipfile.ZipFile(apk) as z:
 for name in sorted([n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex',n)],key=lambda n:int(re.search(r'\d+',n)[0]) if re.search(r'\d+',n) else 1):
  d=z.read(name);u32=lambda o:struct.unpack_from('<I',d,o)[0];u16=lambda o:struct.unpack_from('<H',d,o)[0]
  def uleb(o):
   v=0;s=0
   while True:
    b=d[o];o+=1;v|=(b&127)<<s
    if b<128:return v,o
    s+=7
  ss,so=u32(56),u32(60);strings=[]
  for i in range(ss):
   o=u32(so+4*i);_,o=uleb(o);end=d.index(b'\0',o);strings.append(d[o:end].decode('utf8','replace'))
  ts,to=u32(64),u32(68);types=[strings[u32(to+4*i)] for i in range(ts)]
  ps,po=u32(72),u32(76);protos=[]
  for i in range(ps):
   o=po+12*i;p=u32(o+8);args=[]
   if p:args=[types[u16(p+4+2*j)] for j in range(u32(p))]
   protos.append('('+''.join(args)+')'+types[u32(o+4)])
  ms,mo=u32(88),u32(92);methods=[]
  for i in range(ms):
   o=mo+i*8;methods.append(types[u16(o)]+'.'+strings[u32(o+4)]+protos[u16(o+2)])
  interesting={i:m for i,m in enumerate(methods) if (m.startswith('Ljava/lang/reflect/Field;.set') or (m.startswith('Ljava/lang/System;.load') or m.startswith('Ljava/lang/Runtime;.load')) or 'DexClassLoader;' in m or 'InMemoryDexClassLoader;' in m or ('org/chickenhook/' in m and ('setReflective' in m or 'invoke' in m)) or ('MessageQueue;' in m) or ('MediaStore;.getVersion' in m))}
  refs=[m for m in interesting.values() if '.setAccessible' not in m]
  cs,co=u32(96),u32(100);calls=[];code_count=0; contexts=[];all_invokes=[]
  for ci in range(cs):
   cd=u32(co+ci*32+24)
   if not cd:continue
   sizes=[]
   for _ in range(4):v,cd=uleb(cd);sizes.append(v)
   for _ in range(sizes[0]+sizes[1]):_,cd=uleb(cd);_,cd=uleb(cd)
   for n in sizes[2:]:
    mi=0
    for _ in range(n):
     v,cd=uleb(cd);mi+=v;flags,cd=uleb(cd);code,cd=uleb(cd)
     if not code:continue
     method_context=[]; method_calls=[];code_count+=1;cnt=u32(code+12);units=struct.unpack_from('<'+'H'*cnt,d,code+16);pc=0
     while pc<cnt:
      unit=units[pc];op=unit&255;w=width[op]
      if op==0 and unit:
       kind=unit>>8
       if kind==1:w=4+units[pc+1]*2
       elif kind==2:w=2+units[pc+1]*4
       elif kind==3:w=4+(units[pc+1]*(units[pc+2]|units[pc+3]<<16)+1)//2
       else:raise ValueError((name,methods[mi],pc,hex(unit)))
      if op==0x1a: method_context.append('STRING '+strings[units[pc+1]])
      if op==0x1b: method_context.append('STRING '+strings[units[pc+1]|units[pc+2]<<16])
      if op==0x1c: method_context.append('CLASS '+types[units[pc+1]])
      if op in list(range(0x6e,0x73))+list(range(0x74,0x79))+[0xfa,0xfb] and pc+1<cnt and units[pc+1] in interesting:
       method_calls.append(interesting[units[pc+1]]);calls.append({'caller':methods[mi],'target':interesting[units[pc+1]],'code_offset':hex(code),'pc':hex(pc)})
      pc+=w
     if method_calls:contexts.append({'caller':methods[mi],'literals':method_context,'calls':method_calls})
     if pc!=cnt:raise ValueError((name,methods[mi],pc,cnt))
  row={'dex':name,'methods':ms,'code_methods':code_count,'references':refs,'calls':calls,'contexts':contexts,'relevant_strings':[s for s in strings if s in ['mMessages','mSystem','mViews','STATIC','FINAL']]}
  allout.append(row);summ.append({'dex':name,'methods':ms,'code_methods':code_count,'calls':len(calls),'refs':refs,'strings':row['relevant_strings']})
json.dump(allout,open(output/'inventory.json','w'),indent=2)
json.dump(summ,open(output/'summary.json','w'),indent=2)
for r in allout:
 print(r['dex'],r['methods'],r['code_methods'])
 for c in r['calls']:
  if '.setAccessible' not in c['target']:print(c['caller'],'=>',c['target'],c['code_offset'],c['pc'])
 print('strings',r['relevant_strings'])
 for ctx in r['contexts']:
  if any('.set' in t and '.setAccessible' not in t for t in ctx['calls']):print('CONTEXT',ctx)
