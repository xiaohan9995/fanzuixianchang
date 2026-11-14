#!/usr/bin/env python3
"""Reproduce the compact server catalog from the unmodified official USDA SR2018 ZIP."""
import argparse,csv,hashlib,io,json,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('zip');a=p.parse_args()
root=Path(__file__).resolve().parents[1]
raw=Path(a.zip).read_bytes();archive=zipfile.ZipFile(io.BytesIO(raw))
foods=json.loads(archive.read('FoodData_Central_sr_legacy_food_json_2018-04.json'))['SRLegacyFoods']
rows=[]
for f in foods:
 ns={str(x['nutrient']['id']):x.get('amount') for x in f.get('foodNutrients',[])}
 kcal=ns.get('1008')
 if kcal is None and ns.get('1062') is not None:kcal=ns['1062']/4.184
 if kcal is None:continue
 rows.append([str(f['fdcId']),f['description'].replace('\t',' ').replace('\n',' '),str(kcal),*[str(ns.get(k,'')) for k in ['1003','1004','1005']]])
rows.sort(key=lambda row:int(row[0]))
out=root/'src/main/resources/nutrition/usda-sr2018.tsv'
out.parent.mkdir(parents=True,exist_ok=True)
with out.open('w',newline='',encoding='utf-8') as stream:
 writer=csv.writer(stream,delimiter='\t',lineterminator='\n',quoting=csv.QUOTE_NONE,escapechar='\\');writer.writerow(['fdcId','description','energyKcalPer100','proteinGPer100','fatGPer100','carbGPer100']);writer.writerows(rows)
metadata={'title':'USDA FoodData Central SR Legacy','version':'2018-04','sourceUrl':'https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_json_2018-04.zip','license':'CC0-1.0 / public domain','licenseUrl':'https://fdc.nal.usda.gov/api-guide/','archiveSha256':hashlib.sha256(raw).hexdigest(),'catalogSha256':hashlib.sha256(out.read_bytes()).hexdigest(),'sourceRecords':len(foods),'calculableRecords':len(rows),'basis':'100g edible food; not100mL; original English preparation description retained','nutrients':{'1008':'Energy kcal','1062':'Energy kJ fallback divided by4.184','1003':'Protein g','1004':'Fat g','1005':'Carbohydrate g'}}
(root/'src/main/resources/nutrition/provenance.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(metadata,ensure_ascii=False))
