#!/usr/bin/env python3
import csv,sys
from pathlib import Path
p=Path(__file__).parent
w=list(csv.DictReader((p/'weights.csv').open())); c=list(csv.DictReader((p/'breed-coverage.csv').open())); s=list(csv.DictReader((p/'sources.csv').open()))
errors=[]
if len(c)!=48: errors.append(f'coverage rows {len(c)} != 48')
if len({r['vboId'] for r in c})!=48: errors.append('VBO IDs not unique')
if any(not r['vboId'].isdigit() or len(r['vboId'])!=7 for r in c): errors.append('invalid VBO ID')
by_id={r['vboId']:r for r in c}
canonical_ready=[r for r in c if r['implementationReadiness'] in ('ready_official','ready_open_fallback') and r['vboId']!='0100061']
canonical_official=[r for r in canonical_ready if r['implementationReadiness']=='ready_official']
canonical_fallback=[r for r in canonical_ready if r['implementationReadiness']=='ready_open_fallback']
if len(canonical_ready)!=26 or len(canonical_official)!=17 or len(canonical_fallback)!=9: errors.append('approved canonical evidence totals must be 26 = 17 official + 9 fallback')
if '0100061' not in by_id or '0100230' not in by_id: errors.append('Sphynx alias/canonical evidence missing')
if by_id.get('0100200',{}).get('implementationReadiness')!='ready_open_fallback': errors.append('Russian Blue must retain fallback evidence classification')
expected_munchkin_names={'0100169':'Манчкин','0100170':'Манчкин длинношёрстный','0100303':'Манчкин короткошёрстный'}
for vid,name in expected_munchkin_names.items():
    if by_id.get(vid,{}).get('breedRu')!=name: errors.append(f'{vid}: expected distinct Russian name {name}')
valid={'ready_official','ready_open_fallback','observational_only','provisional_low_authority','graphical_only','gap/conflict'}
for i,r in enumerate(w,2):
    nums={k:float(r[k]) for k in ('lowerKg','medianKg','upperKg','valueKg','meanKg','sdKg') if r[k]}
    if any(v<0 for v in nums.values()):errors.append(f'line {i}: negative value')
    if all(r[k] for k in ('lowerKg','medianKg','upperKg')) and not(float(r['lowerKg'])<=float(r['medianKg'])<=float(r['upperKg'])):errors.append(f'line {i}: interval order')
    if r['statistic']=='published_mean' and (r['upperKg'] or not r['meanKg']):errors.append(f'line {i}: published mean misrepresented')
    if r['derivation'] in ('derived_midpoint','midpoint_derived','midpoint') and r['lowerKg'] and r['upperKg']:
        if abs(float(r['medianKg'])-(float(r['lowerKg'])+float(r['upperKg']))/2)>0.00001:errors.append(f'line {i}: midpoint mismatch')
    if r['evidenceClass'] not in valid:errors.append(f'line {i}: evidence class')
    if r['sourceId'] and r['sourceId'] not in {x['sourceId'] for x in s}:errors.append(f'line {i}: missing source')
for r in c:
    rr=[x for x in w if x['vboId']==r['vboId']]
    for st in ('adult','birth','intermediate'):
        if not any(x['stage']==st for x in rr):errors.append(f'{r["vboId"]}: missing {st}')
    for sex in ('female','male'):
        if not any(x['stage']=='adult' and x['sex']==sex for x in rr):errors.append(f'{r["vboId"]}: missing adult {sex}')
urls=[x['url'] for x in s if x['url']]
if any(not u.startswith('http') for u in urls):errors.append('non-http source URL')
from collections import Counter
dups={u for u,n in Counter(urls).items() if n>1}
if any(not x['duplicateUrlGroup'] for x in s if x['url'] in dups):errors.append('duplicate URL not explicitly grouped')
if errors:
 print('\n'.join(errors));sys.exit(1)
print(f'PASS: {len(c)} raw VBO concepts / 47 semantic candidates, 26 new canonical profiles (17 official + 9 fallback), 31 total supported; {len(w)} evidence rows, {len(s)} sources; schema, intervals, means, midpoint derivations, stages, VBO IDs, canonicalization inputs and references valid')
