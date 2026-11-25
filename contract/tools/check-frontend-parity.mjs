import assert from 'node:assert/strict';
import { createMockModel } from '../../src/api/mock-model.ts';
import { caseDetail, caseDonors, caseLedger, caseUpdates, listCases, readJson, sameValue, site, stats } from './lib.mjs';
const full=readJson('fixtures/dataset.json');
const model=createMockModel(readJson('fixtures/dataset.public.json'));
const mask=x=>JSON.parse(JSON.stringify(x).replace(/\/media\/public\/[A-Za-z0-9_-]+\//g,'/media/public/TOKEN/'));
let count=0;
function compare(actual,expected,label){assert.ok(sameValue(mask(actual),mask(expected)),label);count++;}
compare(model.site(),site(full),'site');
compare(model.stats(),stats(full),'stats');
for(const locale of ['en','fr']){
 for(const status of ['active','completed'])for(const sort of ['newest','deadline','closest','least','featured']){
  const first=listCases(full,{status,sort,locale});
  for(let page=1;page<=first.totalPages+1;page++){const q={status,sort,page};compare(model.list(q,locale),listCases(full,{...q,locale}),JSON.stringify({q,locale}));}
 }
 for(const c of full.cases){
  if(c.status==='draft')continue;
  compare(model.detail(c.code,locale),caseDetail(full,c.code,locale),c.code+' detail '+locale);
  compare(model.updates(c.code,locale,{}),caseUpdates(full,c.code,locale),c.code+' updates '+locale);
  compare(model.donors(c.code,{}),caseDonors(full,c.code),c.code+' donors');
  compare(model.ledger(c.code,locale,{}),caseLedger(full,c.code,locale),c.code+' ledger '+locale);
 }
 for(const {query} of readJson('fixtures/golden/acceptance.json').directory)compare(model.list(query,locale),listCases(full,{...query,locale}),JSON.stringify(query));
}
console.log('independent frontend mock parity: '+count+' responses matched');
