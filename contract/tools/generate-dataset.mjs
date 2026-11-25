// Deterministic expansion of the approved directory, using the M0A example for edge cases.
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { readJson, projectPublic, root } from './lib.mjs';
const stories = {
  "en": [
    [
      "School supplies for a new term",
      "Help cover school materials and essential learning expenses for a family preparing for the new term."
    ],
    [
      "Access to essential medical care",
      "Help a household cover essential medical appointments and transportation."
    ],
    [
      "A safe place to call home",
      "Help a family cover basic household essentials and housing costs."
    ],
    [
      "Helping a family get back on track",
      "Help a household meet essential daily needs during a difficult period."
    ],
    [
      "Support after an unexpected crisis",
      "Help cover urgent everyday essentials after an unexpected disruption."
    ],
    [
      "Building a better tomorrow",
      "Help a household cover practical expenses as they work toward greater stability."
    ]
  ],
  "fr": [
    [
      "Des fournitures pour la rentrée",
      "Aider une famille à financer les fournitures et les dépenses scolaires essentielles."
    ],
    [
      "Accès aux soins essentiels",
      "Aider un foyer à financer des consultations médicales essentielles et les déplacements."
    ],
    [
      "Un logement sûr",
      "Aider une famille à financer les besoins du foyer et les frais de logement."
    ],
    [
      "Aider une famille à se relever",
      "Aider un foyer à couvrir ses besoins quotidiens essentiels pendant une période difficile."
    ],
    [
      "Un soutien après une crise imprévue",
      "Aider à couvrir les besoins essentiels urgents après un événement imprévu."
    ],
    [
      "Construire un avenir meilleur",
      "Aider un foyer à financer ses dépenses pratiques pour retrouver une situation plus stable."
    ]
  ]
};
function build(example,stories){
 const f=JSON.parse(JSON.stringify(example));
 const iso=ms=>new Date(ms).toISOString().replace(".000Z","Z");
 const epoch=Date.UTC(2026,10,24,12),day=86400000;
 const privateFor=code=>({realName:`PRIVATE-REALNAME-${code}`,contact:`PRIVATE-CONTACT-${code}`,notes:`PRIVATE-NOTES-${code}`});
 const bilingual=(en,fr)=>({en,fr});
 const original=(id,caseCode,kind,amount,date,donor)=>({id,caseCode,kind,amount,date,corrects:null,pairId:null,...(donor?{donor}:{})});
 const donor=(id,visibility="anonymous",name=null)=>({listingId:id,visibility,name,avatarConsent:false,avatarFile:null});
 const remap={"HELP-901":"HELP-101","HELP-904":"HELP-102"};
 for(const c of f.cases)if(remap[c.code]){c.code=remap[c.code];c.private=privateFor(c.code)}
 for(const e of [...f.ledger,...f.updates])e.caseCode=remap[e.caseCode]??e.caseCode;
 const school=f.cases.find(c=>c.code==="HELP-101");
 school.featuredRank=2;school.cover={file:"images/hope-window.webp",alt:bilingual("Symbolic illustration of a person looking toward sunrise","Illustration symbolique d’une personne face au lever du soleil")};school.photos=[{id:"photo-1",file:"images/new-home.webp",alt:{en:"Symbolic illustration of a sunlit room with two beds"},position:1}];
 f.ledger.find(e=>e.id==="L01").donor.avatarFile="images/hope-window.webp";
 // Private test file names remain unique and have no public assets.
 const fund=f.cases.find(c=>c.code==="FUND");
 fund.story=bilingual("A reserve for the next urgent need or gifts above a case's goal.","Une réserve pour le prochain besoin urgent ou les dons dépassant l’objectif d’un cas.");
 const main={code:"HELP-014",kind:"case",status:"active",acceptsDonations:true,category:"housing",featuredRank:1,publicName:"Mariam D.",title:bilingual("A safe start after the fire","Un nouveau départ après l’incendie"),story:bilingual("Mariam has been rebuilding a steady home for herself and her two children after an electrical fire damaged their apartment. They are safe and staying with relatives nearby.\n\nThis support will cover a deposit, two beds, and essential kitchen items so the family can settle into a new place without taking on more debt.","Mariam reconstruit un foyer stable pour elle et ses deux enfants après qu’un incendie électrique a endommagé leur appartement. Ils sont en sécurité et logent chez des proches.\n\nCe soutien couvrira une caution, deux lits et les éléments essentiels de cuisine, afin que la famille puisse s’installer sans s’endetter davantage."),goal:850000,deadline:"2027-01-18",deadlineTimezone:"America/Chicago",createdAt:"2026-10-04T15:00:00Z",cover:{file:"images/new-home.webp",alt:bilingual("Symbolic illustration of a sunlit room with two beds and a moving box","Illustration symbolique d’une chambre ensoleillée avec deux lits et un carton de déménagement")},photos:[],private:privateFor("HELP-014")};
 f.cases.push(main);
 for(const [i,amount,date,vis,name] of [[1,12500,"2026-11-21","named","Nadia K."],[2,25000,"2026-11-18","anonymous","PRIVATE-ANONYMOUS-014"],[3,7500,"2026-11-16","named","Samuel T."],[4,50000,"2026-11-11","named","Diallo family"],[5,472500,"2026-11-05","hidden","PRIVATE-HIDDEN-014"]])f.ledger.push(original(`M0${i}`,main.code,"donation",amount,date+"T12:00:00Z",donor(`DM0${i}`,vis,name)));
 f.ledger.push(original("M06",main.code,"disbursement",320000,"2026-11-09T12:00:00Z"));
 f.updates.push({id:"U014",caseCode:main.code,text:bilingual("Mariam signed the lease today. The first delivery will cover the deposit and beds; we will share the handoff here when it is complete.","Mariam a signé le bail aujourd’hui. La première remise couvrira la caution et les lits ; nous publierons la confirmation ici une fois la remise terminée."),date:"2026-11-19T17:30:00Z",author:"Aïcha",authorAvatarConsent:false,authorAvatarFile:null,photo:null});
 f.ledger.push(original("F01","FUND","donation",234000,"2026-10-01T12:00:00Z",donor("DF01","hidden","PRIVATE-FUND-DONOR")));
 // Preserve 200 current cases: two original directory slots carry the contract edge scenarios.
 for(let i=2;i<199;i++){
  const code=`HELP-${101+i}`,cat=i%6;
  let goal=(2000+(i%11)*650)*100,received=Math.floor(goal*((i*17)%100)/100),delivered=i%3===0?Math.floor(received/2):0,status="active";
  if(i===2){received=goal;status="funded"}
  if(i===3){received=goal+10000;status="funded"}
  if(i===4)goal=null;
  if(i===5||i===6){goal=200000;received=100000;delivered=0}
  const createdAt=iso(epoch-(i+1)*day),deadline=i%9===0?null:iso(epoch+(3+(i*7)%60)*day).slice(0,10);
  const c={code,kind:"case",status,acceptsDonations:i!==7,category:f.categories[cat].id,featuredRank:i===2?3:null,publicName:i===5?"Élodie (sample)":i===6?"Elodie (sample)":`Sample household ${i+1}`,title:bilingual(stories.en[cat][0],stories.fr[cat][0]),story:bilingual(stories.en[cat][1],stories.fr[cat][1]),goal,deadline,deadlineTimezone:deadline?"America/Chicago":null,createdAt,cover:null,photos:[],private:privateFor(code)};
  f.cases.push(c);
  const prefix=`G${101+i}`;
  // Preserve the fund's net $1,640, with an actual internal transfer instead of delivery.
  const transfer=i===2?110000:0;
  if(received-transfer>0)f.ledger.push(original(`${prefix}A`,code,"donation",received-transfer,createdAt,donor(`D${prefix}`)));
  if(transfer){f.ledger.push({...original("F02","FUND","transfer_out",transfer,"2026-11-23T12:00:00Z"),pairId:"TFUND"},{...original("F03",code,"transfer_in",transfer,"2026-11-23T12:00:00Z"),pairId:"TFUND"})}
  if(delivered>0)f.ledger.push(original(`${prefix}B`,code,"disbursement",delivered,createdAt));
 }
 // Additional historical and draft cases do not change the 200-current-case directory.
 const template=f.cases.find(c=>c.code==="HELP-903");
 f.cases.push({...template,code:"HELP-905",status:"closed",acceptsDonations:false,category:"other",publicName:"Sample historical case",title:bilingual("Completed sample assistance","Aide fictive terminée"),story:bilingual("A fictional closed case for financial correction testing.","Un cas fictif clôturé pour tester les corrections financières."),private:privateFor("HELP-905")});
 f.cases.push({...f.cases.find(c=>c.code==="HELP-902"),code:"HELP-906",publicName:"DRAFT-NAME-906",title:bilingual("DRAFT-TITLE-906","BROUILLON-TITRE-906"),story:bilingual("DRAFT-STORY-906","BROUILLON-HISTOIRE-906"),private:privateFor("HELP-906")});
 const add=(id,kind,amount,date,corrects=null,d)=>f.ledger.push({...original(id,"HELP-905",kind,amount,date,d),corrects});
 add("H01","donation",20000,"2026-09-02T12:00:00Z",null,donor("DH01"));
 add("H02","refund",20000,"2026-09-03T12:00:00Z","H01");
 add("H03","donation",10000,"2026-09-04T12:00:00Z",null,donor("DH03"));
 add("H04","donation_reversal",10000,"2026-09-05T12:00:00Z","H03");
 add("H05","donation",30000,"2026-09-06T12:00:00Z",null,donor("DH05"));
 add("H06","disbursement",30000,"2026-09-07T12:00:00Z");
 add("H07","disbursement_reversal",5000,"2026-09-08T12:00:00Z","H06");
 add("H08","disbursement",5000,"2026-09-09T12:00:00Z");
 return f;
}
const dataset = build(readJson('fixtures/example.json'), stories);
for (const [name,value] of [['dataset.json',dataset],['dataset.public.json',projectPublic(dataset)]]) writeFileSync(path.join(root,'fixtures',name),JSON.stringify(value)+'\n');
