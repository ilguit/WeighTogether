#!/usr/bin/env python3
import csv, re
from collections import Counter
from pathlib import Path

OUT=Path(__file__).resolve().parent
PKGS={'A':OUT/'upstream'/'a','B':OUT/'upstream'/'b','C':OUT/'upstream'/'c'}
RU_NAMES={
    '0100169':'Манчкин',
    '0100170':'Манчкин длинношёрстный',
    '0100303':'Манчкин короткошёрстный',
}
WEIGHT_FIELDS=['breed','breedRu','vboId','sex','stage','ageDaysMin','ageDaysMax','lowerKg','medianKg','upperKg','valueKg','meanKg','sdKg','statistic','derivation','evidenceClass','sourceId','pooledGroup','notes']

def num(v):
    if v is None or str(v).strip()=='' : return ''
    try:return f'{float(v):.6f}'.rstrip('0').rstrip('.')
    except:return v
def age(v):
    if not v:return ('','')
    s=str(v)
    if '-' in s:
        a,b=s.split('-',1); return (a,b)
    return (s,s)
def sd_from(s):
    m=re.search(r'\bSD\s*([0-9]+(?:\.[0-9]+)?)',s or '',re.I)
    return num(float(m.group(1))/1000) if m else ''
def pooled(notes):
    n=(notes or '').lower()
    return 'yes' if 'pooled' in n or 'group' in n or 'covers both' in n or 'same breed as' in n else 'no'
def evidence(source_class,status):
    s=(source_class+' '+status).lower()
    if 'graph' in s or 'chart_digitized' in s:return 'graphical_only'
    if 'gap' in s or 'not_available' in s:return 'gap/conflict'
    if any(x in s for x in ['breed_club_member','open_secondary','wikipedia','fallback','proxy','unisex','secondary']):return 'provisional_low_authority'
    if any(x in s for x in ['peer_reviewed','observational','published_mean','mean_only']):return 'observational_only'
    if any(x in s for x in ['official_feline','official_breed','official_professional']):return 'ready_official'
    if any(x in s for x in ['professional','veterinary','pet_reference']):return 'ready_open_fallback'
    return 'provisional_low_authority'

# Sources: namespace package-local identifiers and preserve every supplied field.
sources=[]; srcclass={}
for pkg in 'ABC':
    p=PKGS[pkg]/'sources.csv'
    for r in csv.DictReader(p.open()):
        sid=f'{pkg}-{r["source_id"]}'; sc=r.get('source_class','')
        srcclass[sid]=sc
        sources.append({'sourceId':sid,'package':pkg,'title':r.get('title',''),'publisher':r.get('publisher',''),
          'year':r.get('year') or r.get('published_or_updated',''),'url':r.get('url',''),'location':r.get('page_table') or r.get('scope',''),
          'accessDate':r.get('access_date') or r.get('accessed',''),'authorityClass':sc,
          'methodSampleGeography':r.get('sample_geography_method') or r.get('method_sample_geography',''),
          'claimsUsed':r.get('claims_used',''),'limitations':r.get('limitations','')})

rows=[]
def add(**kw):
    row={k:'' for k in WEIGHT_FIELDS}; row.update(kw)
    row['vboId']=row['vboId'].replace('VBO:','')
    row['breedRu']=row['breedRu'] or RU_NAMES.get(row['vboId'],'')
    if row['derivation'] in ('derived_midpoint','midpoint_derived','midpoint') and row['lowerKg'] and row['upperKg']:
        row['medianKg']=num((float(row['lowerKg'])+float(row['upperKg']))/2)
    row['evidenceClass']=evidence(srcclass.get(row['sourceId'],''),row['statistic'])
    rows.append(row)

# Package A. Repair the source schema error: published means were placed in upper_kg.
for r in csv.DictReader((PKGS['A']/'data.csv').open()):
    sid='A-'+r['source_id'] if r['source_id'] else ''
    amin,amax=age(r['age_days_min']); amax=r['age_days_max'] or amax
    stat=r['statistic']; notes=r['limitations']; kw={}
    if stat=='published_mean': kw={'meanKg':num(r['upper_kg']),'sdKg':sd_from(notes)}
    elif stat=='published_upper_bound': kw={'upperKg':num(r['upper_kg'])}
    elif stat=='published_group_extreme': kw={'valueKg':num(r['median_kg'])}
    else: kw={'lowerKg':num(r['lower_kg']),'medianKg':num(r['median_kg']),'upperKg':num(r['upper_kg'])}
    add(breed=r['breed_en'],breedRu=r['breed_ru'],vboId=r['vbo_id'],sex=r['sex'],stage='birth' if r['life_stage']=='birth' else r['life_stage'],ageDaysMin=amin,ageDaysMax=amax,
        statistic=stat,derivation=r['median_status'],sourceId=sid,pooledGroup=pooled(notes),notes=notes,**kw)

# Package B. Graph readings stay approximate graphical evidence, points stay valueKg.
for r in csv.DictReader((PKGS['B']/'weights.csv').open()):
    sid='B-'+r['source_id'] if r['source_id'] else ''; stat=r['statistic_status']; notes=r['notes']
    amin,amax=age(r['age_days']); stage='birth' if r['life_stage']=='neonatal' and amin=='0' else ('intermediate' if r['life_stage'] in ('neonatal','juvenile') else r['life_stage'])
    kw={}
    if stat in ('published_point','derived_point'): kw={'valueKg':num(r['median_kg'])}
    else: kw={'lowerKg':num(r['low_kg']),'medianKg':num(r['median_kg']),'upperKg':num(r['high_kg'])}
    add(breed=r['breed'],vboId=r['breed_id'],sex=r['sex'],stage=stage,ageDaysMin=amin,ageDaysMax=amax,statistic=stat,
        derivation=r['method'],sourceId=sid,pooledGroup=pooled(notes),notes=notes,**kw)

# Package C. mean_only uses meanKg, never an interval. Graph rows remain nonnumeric.
for r in csv.DictReader((PKGS['C']/'weights.csv').open()):
    sid='C-'+r['source_id'] if r['source_id'] else ''; stat=r['status']; notes=r['notes']; amin,amax=age(r['age_days'])
    stage={'neonatal_curve':'intermediate'}.get(r['stage'],r['stage']); kw={}
    if stat in ('mean_only','secondary_mean_only'): kw={'meanKg':num(r['median_kg'])}
    else: kw={'lowerKg':num(r['low_kg']),'medianKg':num(r['median_kg']),'upperKg':num(r['high_kg'])}
    add(breed=r['breed'],vboId=r['vbo_id'],sex=r['sex'],stage=stage,ageDaysMin=amin,ageDaysMax=amax,statistic=stat,
        derivation=r['median_kind'],sourceId=sid,pooledGroup=pooled(notes),notes=notes,**kw)

# Add explicit missing stage rows; adult requires female+male, birth/intermediate at least one row.
breeds={r['vboId']:(r['breed'],r['breedRu']) for r in rows}
for vid,(breed,bru) in sorted(breeds.items()):
    for sex in ('female','male'):
        if not any(r['vboId']==vid and r['stage']=='adult' and r['sex']==sex for r in rows):
            add(breed=breed,breedRu=bru,vboId=vid,sex=sex,stage='adult',statistic='gap',derivation='not_available',pooledGroup='no',notes='No sex-specific adult datum in research packages')
    for stage in ('birth','intermediate'):
        if not any(r['vboId']==vid and r['stage']==stage for r in rows):
            add(breed=breed,breedRu=bru,vboId=vid,sex='combined',stage=stage,statistic='gap',derivation='not_available',pooledGroup='no',notes=f'No breed-specific numeric {stage} datum found')

rows.sort(key=lambda r:(r['vboId'],{'adult':0,'birth':1,'intermediate':2}.get(r['stage'],9),r['sex'],r['ageDaysMin']))
with (OUT/'weights.csv').open('w',newline='') as f:
    w=csv.DictWriter(f,fieldnames=WEIGHT_FIELDS,lineterminator="\n");w.writeheader();w.writerows(rows)
url_counts=Counter(x['url'] for x in sources if x['url'])
for x in sources: x['duplicateUrlGroup']=x['url'] if x['url'] and url_counts[x['url']]>1 else ''
SF=['sourceId','package','title','publisher','year','url','duplicateUrlGroup','location','accessDate','authorityClass','methodSampleGeography','claimsUsed','limitations']
with (OUT/'sources.csv').open('w',newline='') as f:
    w=csv.DictWriter(f,fieldnames=SF,lineterminator="\n");w.writeheader();w.writerows(sources)

# One-row-per-VBO implementation coverage.
cov=[]; conflicts=[]
for vid,(breed,bru) in sorted(breeds.items()):
    rr=[r for r in rows if r['vboId']==vid]; adult=[r for r in rr if r['stage']=='adult']; numeric=lambda r:any(r[x] for x in ('lowerKg','upperKg','valueKg','meanKg'))
    sexranges={s:next((r for r in adult if r['sex']==s and r['lowerKg'] and r['upperKg']),None) for s in ('female','male')}
    adult_class='full_both_sex_ranges' if all(sexranges.values()) else ('partial_or_combined' if any(numeric(r) for r in adult) else 'gap')
    classes={r['evidenceClass'] for r in adult if numeric(r)}
    has_conflict=any('conflict' in r['notes'].lower() or 'contradict' in r['notes'].lower() for r in adult)
    if has_conflict: readiness='gap/conflict'
    elif adult_class=='full_both_sex_ranges' and classes and classes <= {'ready_official'}: readiness='ready_official'
    elif adult_class=='full_both_sex_ranges' and classes and classes <= {'ready_official','ready_open_fallback'}: readiness='ready_open_fallback'
    elif 'provisional_low_authority' in classes: readiness='provisional_low_authority'
    elif 'observational_only' in classes: readiness='observational_only'
    else: readiness='gap/conflict'
    birth=[r for r in rr if r['stage']=='birth' and numeric(r)]
    inter=[r for r in rr if r['stage']=='intermediate' and numeric(r)]
    graph=[r for r in rr if r['evidenceClass']=='graphical_only']
    cov.append({'breed':breed,'breedRu':bru,'vboId':vid,'adultCoverage':adult_class,'implementationReadiness':readiness,
      'birthCoverage':'numeric' if birth else 'gap','intermediateCoverage':'tabulated_or_point' if inter and not all(r['evidenceClass']=='graphical_only' for r in inter) else ('graphical_only' if graph else 'gap'),
      'pooledEvidence':'yes' if any(r['pooledGroup']=='yes' and numeric(r) for r in rr) else 'no','notes':' | '.join(sorted(set(r['notes'] for r in adult if 'conflict' in r['notes'].lower() or 'proxy' in r['notes'].lower() or 'only' in r['notes'].lower())))})
    for r in rr:
        if r['statistic']=='gap' or r['evidenceClass'] in ('provisional_low_authority','gap/conflict') or 'conflict' in r['notes'].lower():
            conflicts.append({'vboId':vid,'breed':breed,'stage':r['stage'],'sex':r['sex'],'classification':r['evidenceClass'],'issue':r['notes'],'sourceId':r['sourceId'],'requiredAction':'Keep excluded from production model until stronger evidence or explicit product decision.'})
CF=['breed','breedRu','vboId','adultCoverage','implementationReadiness','birthCoverage','intermediateCoverage','pooledEvidence','notes']
with (OUT/'breed-coverage.csv').open('w',newline='') as f:
    w=csv.DictWriter(f,fieldnames=CF,lineterminator="\n");w.writeheader();w.writerows(cov)
GF=['vboId','breed','stage','sex','classification','issue','sourceId','requiredAction']
with (OUT/'conflicts-gaps.csv').open('w',newline='') as f:
    w=csv.DictWriter(f,fieldnames=GF,lineterminator="\n");w.writeheader();w.writerows(conflicts)

cc=Counter(r['implementationReadiness'] for r in cov); ac=Counter(r['adultCoverage'] for r in cov); ic=Counter(r['intermediateCoverage'] for r in cov)
ready=[r['breed'] for r in cov if r['implementationReadiness'] in ('ready_official','ready_open_fallback')]
official=[r['breed'] for r in cov if r['implementationReadiness']=='ready_official']
fallback=[r['breed'] for r in cov if r['implementationReadiness']=='ready_open_fallback']
canonical_official=[r['breed'] for r in cov if r['implementationReadiness']=='ready_official' and r['vboId']!='0100061']
canonical_fallback=[r['breed'] for r in cov if r['implementationReadiness']=='ready_open_fallback']
batch1=canonical_official+['Russian Blue']
batch2=[name for name in canonical_fallback if name!='Russian Blue']
excluded=[r['breed'] for r in cov if r['implementationReadiness'] not in ('ready_official','ready_open_fallback')]
birth_exact={r['vboId'] for r in rows if r['stage']=='birth' and any(r[x] for x in ('lowerKg','upperKg','valueKg','meanKg')) and r['evidenceClass']!='graphical_only'}
birth_graph={r['vboId'] for r in rows if r['stage']=='birth' and r['evidenceClass']=='graphical_only' and any(r[x] for x in ('lowerKg','upperKg','valueKg','meanKg'))}
inter_exact={r['vboId'] for r in rows if r['stage']=='intermediate' and any(r[x] for x in ('lowerKg','upperKg','valueKg','meanKg')) and r['evidenceClass']!='graphical_only'}
graph_breeds={r['vboId'] for r in rows if r['evidenceClass']=='graphical_only'}
refs='\n'.join(f'{i}. [{x["title"]}]({x["url"]}) — `{x["sourceId"]}`, {x["authorityClass"]}; {x["limitations"]}' for i,x in enumerate(sources,1))
report=f'''# Integrated weight evidence for issue #90

Research integration date: 2026-09-09. Scope: the 48 VBO concepts outside the five already implemented breeds. The package is self-contained under `docs/design/90/`; raw inputs used by this script are in `upstream/`.

## Result

There are **{ac['full_both_sex_ranges']}** raw VBO concepts with female and male adult ranges, **{ac['partial_or_combined']}** with only points, means, bounds, or combined-sex evidence, and **{ac['gap']}** with no numeric adult evidence. Raw readiness is **{len(official)} official + {len(fallback)} professional fallback**, but `0100061` Canadian Sphynx canonicalizes to `0100230` Sphynx. The approved production evidence total is therefore **{len(canonical_official)} official + {len(canonical_fallback)} professional fallback = 26 new profiles**, or **31 total** with the five already implemented. The 48 raw VBO concepts represent **47 semantic candidates**.

Numeric birth evidence exists for **{len(birth_exact)+len(birth_graph)}** concepts: **{len(birth_exact)}** exact/tabulated published records and **{len(birth_graph)}** approximate graph-digitized records. Only **{len(inter_exact)}** concepts have exact/tabulated intermediate-age points. **{len(graph_breeds)}** concepts have official graphical neonatal evidence somewhere in birth/intermediate coverage, but graph-only or digitized values remain research evidence, not production values.

## Canonical interpretation rules

- `lowerKg`, `medianKg`, and `upperKg` are used only for intervals. If a source publishes bounds but no median, the arithmetic midpoint is stored with `derivation=derived_midpoint`, `midpoint_derived`, or `midpoint`; it is never described as an observed median.
- Published single observations use `valueKg`. Published means use `meanKg`, with `sdKg` only when the source states an SD. A mean is never placed in `upperKg`.
- Pounds use exactly `1 lb = 0.45359237 kg`. The verifier checks nonnegative values, interval ordering, midpoint arithmetic, source references, exact VBO format, and mandatory adult/birth/intermediate coverage.
- Sex-combined, breed-group, coat-group, and proxy evidence remains explicit in `sex`, `pooledGroup`, `statistic`, and `notes`; it is not silently duplicated into an implementation-ready sex profile.
- Descriptive breed ranges are orientation data, not clinical healthy-weight limits. The existing top-five method may normalize the same-sex general kitten P50 shape and scale it to adult lower/midpoint/upper only for the 26 canonical ready profiles. The generated curve remains modelled, not breed-observed, and unsupported early ages remain unavailable. When a source gives no maturity age, the approved fallback adult knot is day 730.

## Staged implementation

1. **Batch 1 — 17 official + Russian Blue fallback ({len(batch1)}):** {', '.join(batch1)}.
2. **Batch 2 — remaining professional fallbacks ({len(batch2)}):** {', '.join(batch2)}. All fallback claims ship with explicit provenance/limitations and the same model disclaimer.
3. **Research-only:** birth means/ranges and the two isolated exact intermediate records. Do not connect birth to day 56 or synthesize longitudinal breed curves. Graphical evidence needs calibrated digitization plus independent verification or underlying tables.
4. **Excluded pending resolution ({len(excluded)}):** {', '.join(excluded)}. Reasons include sex-combined evidence, point/mean only, missing bounds, proxies, low authority, source contradictions, or a taxonomy defect.

Ocicat is excluded despite two sex ranges because its TICA page contains contradictory ranges. Asian Leopard Cat (`Prionailurus bengalensis`) is a wild species and must not appear as a domestic-breed weight profile without a taxonomy/product decision. Savannah requires a filial-generation policy. Donskoy has no numeric adult range. Sacred Birman has identical sex points conflicting with prose. Kurilian males have only upper bounds. Experimental/proxy concepts remain provisional.

## Artifact contract

- `weights.csv`: canonical evidence rows plus explicit gaps. It is the only numeric table.
- `breed-coverage.csv`: exactly 48 raw VBO rows and evidence-readiness classification; production canonicalization is specified above and in `design-specification.md`.
- `sources.csv`: namespaced source inventory; package-local source IDs cannot collide.
- `conflicts-gaps.csv`: every gap, provisional record, and conflict that must stay excluded.
- `verify.py`: deterministic structural and semantic checks; `build.py` reproducibly integrates the preserved inputs under `upstream/`.

## Sources

The numbered list below preserves every source URL supplied by the three independent searches. Claim-level provenance is linked by `sourceId` in `weights.csv`; complete locations, samples, geography, claims, and limitations are in `sources.csv`.

Repeated URLs are retained as namespaced evidence records because the agents used them for different VBO concepts or scopes. `duplicateUrlGroup` identifies them explicitly; they are not independent corroboration. The repeated URLs are: {', '.join(sorted(u for u,n in url_counts.items() if n>1))}.

{refs}
'''
(OUT/'comprehensive-report.md').write_text(report)
print(f'rows={len(rows)} breeds={len(breeds)} sources={len(sources)} conflicts={len(conflicts)}')
