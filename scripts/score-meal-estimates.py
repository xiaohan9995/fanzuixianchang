#!/usr/bin/env python3
"""Score manually adjudicated provider runs; never invent references or accuracy."""
import argparse,json,statistics
from collections import defaultdict
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('results',type=Path,help='JSONL rows: id, category, usable, foodCorrect, latencyMs, questions, prediction {min,max}, referenceKcal optional');a=p.parse_args()
groups=defaultdict(list);seen=set()
for line in a.results.read_text().splitlines():
 if not line.strip():continue
 r=json.loads(line)
 if r['id'] in seen:raise ValueError('Duplicate sample: '+r['id'])
 seen.add(r['id'])
 if not isinstance(r.get('usable'),bool) or not isinstance(r.get('foodCorrect'),bool):raise ValueError('Manual usable/foodCorrect labels required')
 groups[r['category']].append(r)
def summarize(rows):
 errors=[];covered=[]
 for r in rows:
  ref=r.get('referenceKcal');pred=r.get('prediction')
  if ref is not None and ref>0 and pred and r['usable']:
   lo,hi=pred['min'],pred['max']
   if lo<0 or hi<lo:raise ValueError('Invalid prediction interval')
   errors.append(abs((lo+hi)/2-ref)/ref);covered.append(lo<=ref<=hi)
 timings=sorted(r['latencyMs'] for r in rows if r.get('latencyMs') is not None)
 return {'samples':len(rows),'usableRate':sum(r['usable'] for r in rows)/len(rows),'foodCorrectRate':sum(r['foodCorrect'] for r in rows)/len(rows),'referenceComparableSamples':len(errors),'medianRelativeError':statistics.median(errors) if errors else None,'intervalCoverage':sum(covered)/len(covered) if covered else None,'latencyP95Ms':timings[min(len(timings)-1,int(len(timings)*.95))] if timings else None,'atMostOneQuestionRate':sum(r.get('questions',0)<=1 for r in rows)/len(rows)}
print(json.dumps({key:summarize(rows) for key,rows in groups.items()},ensure_ascii=False,indent=2))
