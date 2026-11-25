import type { Case, CaseDetail, CaseFinancials, CasePhoto, CaseQuery, CaseUpdate, Category, LedgerEntry, LedgerKind, Locale, Page, PageQuery, PublicDonor, Site, Stats } from './public-types';

type Text = Partial<Record<Locale,string>>;
type Picture = {file:string; alt:Text};
type InputCase = {code:string; kind:Case['kind']; status:Case['status']; acceptsDonations:boolean; category:string|null; featuredRank:number|null; publicName:string; title:Text; story:Text; goal:number|null; deadline:string|null; deadlineTimezone:string|null; createdAt:string; cover:Picture|null; photos:(Picture & {id:string;position:number})[]};
type InputEntry = {id:string;caseCode:string;kind:LedgerKind;amount:number;date:string;corrects:string|null;pairId:string|null;donor?:{listingId:string;name:string|null;avatarFile:string|null}};
type InputUpdate = {id:string;caseCode:string;text:Text;date:string;author:string;authorAvatarFile:string|null;photo:Picture|null};
export interface PublicFixture {clock:string;timezone:string;categories:Category[];ledgerLabels:Record<LedgerKind,Record<Locale,string>>;cases:InputCase[];ledger:InputEntry[];updates:InputUpdate[]}
export class ModelError extends Error { code:string; status:number; constructor(code:string,status:number,message:string){super(message);this.code=code;this.status=status;} }
const cmp=(a:string,b:string)=>a<b?-1:a>b?1:0;
const newest=<T extends {date:string;id:string}>(a:T,b:T)=>cmp(b.date,a.date)||cmp(b.id,a.id);
const local=(text:Text,locale:Locale):[string,Locale]=>text[locale]!==undefined?[text[locale]!,locale]:[text[locale==='en'?'fr':'en']!,locale==='en'?'fr':'en'];
const optional=(text:Text|undefined,locale:Locale):[string|null,Locale|null]=>text?local(text,locale):[null,null];
export const normalize=(s:string)=>s.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().trim();
export function cutoff(date:string|null,zone:string|null) {
  if(!date||!zone)return null;
  const [year,month,day]=date.split('-').map(Number);
  const target=Date.UTC(year,month-1,day+1);
  const fmt=new Intl.DateTimeFormat('en-US',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
  let instant=target;
  for(let i=0;i<4;i++){
    const parts=Object.fromEntries(fmt.formatToParts(instant).map(p=>[p.type,p.value]));
    const represented=Date.UTC(+parts.year,+parts.month-1,+parts.day,+parts.hour,+parts.minute,+parts.second);
    const next=instant+target-represented;
    if(next===instant)break;
    instant=next;
  }
  return new Date(instant).toISOString().replace('.000Z','Z');
}
const effects:Record<LedgerKind,[number,number]>={donation:[1,0],donation_reversal:[-1,0],refund:[-1,0],transfer_in:[1,0],transfer_out:[-1,0],transfer_in_reversal:[-1,0],transfer_out_reversal:[1,0],disbursement:[0,1],disbursement_reversal:[0,-1]};
export const mockTokens:Record<string,string>={'images/hope-window.webp':'MockHopeWindowArtwork0001','images/new-home.webp':'MockNewHomeArtwork000001'};
function media(file:string|null|undefined,variant:string){if(!file)return null;const token=mockTokens[file];if(!token)throw new Error('Unknown public fixture media: '+file);return '/media/public/'+token+'/'+variant+'.webp';}
function page<T>(items:T[],query:PageQuery={}):Page<T>{
 const {page=1,pageSize=12}=query;
 if(!Number.isInteger(page)||page<1||page>100000||!Number.isInteger(pageSize)||pageSize<1||pageSize>48)throw new ModelError('validation_error',400,'Invalid pagination');
 return {items:items.slice((page-1)*pageSize,page*pageSize),page,pageSize,totalItems:items.length,totalPages:Math.max(1,Math.ceil(items.length/pageSize))};
}
export function createMockModel(fixture:PublicFixture){
 const entries=new Map<string,InputEntry[]>();
 const totals=new Map<string,CaseFinancials>();
 const corrections=new Map<string,InputEntry[]>();
 for(const c of fixture.cases){entries.set(c.code,[]);totals.set(c.code,{externalDonations:0,transfersIn:0,transfersOut:0,allocated:0,delivered:0,available:0,hasShortfall:false});}
 for(const e of fixture.ledger){
  entries.get(e.caseCode)!.push(e);
  const f=totals.get(e.caseCode)!;
  if(e.kind==='donation')f.externalDonations+=e.amount;
  if(e.kind==='refund'||e.kind==='donation_reversal')f.externalDonations-=e.amount;
  if(e.kind==='transfer_in')f.transfersIn+=e.amount;
  if(e.kind==='transfer_in_reversal')f.transfersIn-=e.amount;
  if(e.kind==='transfer_out')f.transfersOut+=e.amount;
  if(e.kind==='transfer_out_reversal')f.transfersOut-=e.amount;
  f.allocated+=effects[e.kind][0]*e.amount;
  f.delivered+=effects[e.kind][1]*e.amount;
  if(e.corrects)corrections.set(e.corrects,[...(corrections.get(e.corrects)??[]),e]);
 }
 for(const f of totals.values()){f.available=f.allocated-f.delivered;f.hasShortfall=f.available<0;}
 function find(code:string){const c=fixture.cases.find(c=>c.code===code.toUpperCase());if(!c)throw new ModelError('case_not_found',404,'Case not found');return c;}
 function toCase(c:InputCase,locale:Locale):Case{
  const [title,titleLanguage]=local(c.title,locale),[story,storyLanguage]=local(c.story,locale),[photoAlt,photoAltLanguage]=optional(c.cover?.alt,locale);
  const deadlineEndsAt=cutoff(c.deadline,c.deadlineTimezone);
  const f=totals.get(c.code)!;
  return {code:c.code,kind:c.kind,status:c.status,acceptingDonations:c.acceptsDonations&&['active','funded'].includes(c.status)&&(!deadlineEndsAt||fixture.clock<deadlineEndsAt),category:c.category,featuredRank:c.featuredRank,title,titleLanguage,publicName:c.publicName,story,storyLanguage,photoUrl:media(c.cover?.file,'display'),photoThumbnailUrl:media(c.cover?.file,'thumb'),photoAlt,photoAltLanguage,goal:c.goal,received:f.allocated,delivered:f.delivered,deadline:c.deadline,deadlineTimezone:c.deadlineTimezone,deadlineEndsAt,createdAt:c.createdAt};
 }
 const corrected=(id:string)=>(corrections.get(id)??[]).slice().sort((a,b)=>cmp(a.date,b.date)||cmp(a.id,b.id));
 const net=(e:InputEntry)=>e.amount-corrected(e.id).reduce((s,x)=>s+x.amount,0);
 function ledger(code:string,locale:Locale):LedgerEntry[]{return entries.get(find(code).code)!.slice().sort(newest).map(e=>({id:e.id,kind:e.kind,amount:e.amount,allocatedDelta:effects[e.kind][0]*e.amount,deliveredDelta:effects[e.kind][1]*e.amount,date:e.date,label:fixture.ledgerLabels[e.kind][locale],corrects:e.corrects,correctedBy:corrected(e.id).map(x=>x.id),correctedAmount:e.amount-net(e),netAmount:net(e),counterpartCaseCode:e.pairId?fixture.ledger.find(x=>x.pairId===e.pairId&&x.id!==e.id)?.caseCode??null:null}));}
 function donors(code:string):PublicDonor[]{return entries.get(find(code).code)!.filter(e=>e.donor&&net(e)>0).map(e=>({id:e.donor!.listingId,name:e.donor!.name,amount:net(e),date:e.date,avatarUrl:e.donor!.name?media(e.donor!.avatarFile,'thumb'):null})).sort(newest);}
 function updates(code:string,locale:Locale):CaseUpdate[]{return fixture.updates.filter(u=>u.caseCode===find(code).code).sort(newest).map(u=>{const [text,textLanguage]=local(u.text,locale),[photoAlt,photoAltLanguage]=optional(u.photo?.alt,locale);return {id:u.id,caseCode:u.caseCode,text,textLanguage,photoUrl:media(u.photo?.file,'display'),photoAlt,photoAltLanguage,date:u.date,author:u.author,authorAvatarUrl:media(u.authorAvatarFile,'thumb')};});}
 function ratio(a:Case,b:Case){const x=BigInt(Math.max(a.received,0))*BigInt(b.goal!),y=BigInt(Math.max(b.received,0))*BigInt(a.goal!);return x<y?-1:x>y?1:0;}
 function nullLast<T>(a:T|null,b:T|null,compare:(x:T,y:T)=>number){return a===null?(b===null?0:1):b===null?-1:compare(a,b);}
 function list(query:CaseQuery,locale:Locale){
  const {status='active',q='',category,sort='newest'}=query;
  if([...q].length>200||[...q.trim()].length>80)throw new ModelError('validation_error',400,'Search too long');
  if(category&&!fixture.categories.some(c=>c.id===category))throw new ModelError('validation_error',400,'Unknown category');
  if(!['active','completed'].includes(status)||!['newest','deadline','closest','least','featured'].includes(sort))throw new ModelError('validation_error',400,'Invalid filter');
  const group=status==='active'?['active','funded']:['delivered','closed'],needle=normalize(q);
  const result=fixture.cases.filter(c=>c.kind==='case'&&group.includes(c.status)&&(!category||c.category===category)&&[c.title.en!,c.title.fr!,c.publicName,c.code].some(s=>normalize(s).includes(needle))).map(c=>toCase(c,locale));
  const closestGroup=(c:Case)=>c.goal===null?2:c.received<c.goal?0:1;
  result.sort((a,b)=>{
   let order=0;
   if(sort==='deadline')order=nullLast(a.deadlineEndsAt,b.deadlineEndsAt,cmp);
   if(sort==='featured')order=nullLast(a.featuredRank,b.featuredRank,(x,y)=>x-y);
   if(sort==='least')order=nullLast(a.goal,b.goal,()=>ratio(a,b));
   if(sort==='closest'){const g=closestGroup(a),h=closestGroup(b);order=g!==h?g-h:g===0?ratio(b,a):g===1?ratio(a,b):0;}
   return order||cmp(b.createdAt,a.createdAt)||cmp(a.code,b.code);
  });
  return page(result,query);
 }
 return {
  site:():Site=>({timezone:fixture.timezone,currency:'USD',locales:['en','fr'],defaultLocale:'en',categories:fixture.categories.slice().sort((a,b)=>a.position-b.position||cmp(a.id,b.id)),fundCode:'FUND',pagination:{defaultPageSize:12,maxPageSize:48}}),
  stats:():Stats=>{const all=[...totals.values()];const received=all.reduce((s,f)=>s+f.externalDonations,0),delivered=all.reduce((s,f)=>s+f.delivered,0);return {received,delivered,available:received-delivered,peopleHelped:fixture.cases.filter(c=>c.kind==='case'&&totals.get(c.code)!.delivered>0).length,asOf:fixture.clock};},
  list,
  detail:(code:string,locale:Locale):CaseDetail=>{const c=find(code),u=updates(code,locale),d=donors(code),l=ledger(code,locale);const photos:CasePhoto[]=c.photos.slice().sort((a,b)=>a.position-b.position).map(p=>{const [alt,altLanguage]=local(p.alt,locale);return {id:p.id,url:media(p.file,'display')!,thumbnailUrl:media(p.file,'thumb')!,alt,altLanguage,position:p.position};});return {case:toCase(c,locale),financials:totals.get(c.code)!,photos,updates:u.slice(0,10),donors:d.slice(0,10),ledger:l.slice(0,10),counts:{updates:u.length,donors:d.length,ledger:l.length,photos:photos.length},asOf:fixture.clock};},
  updates:(code:string,locale:Locale,q:PageQuery)=>page(updates(code,locale),q),
  donors:(code:string,q:PageQuery)=>page(donors(code),q),
  ledger:(code:string,locale:Locale,q:PageQuery)=>page(ledger(code,locale),q),
 };
}
