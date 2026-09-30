import {receiptSummary,roundMoney} from './money.js';
export const STORAGE_KEY = 'finance-tracker:v2';
export const GROUPS = { accounts: 'Accounts & Assets', cards: 'Credit Cards', fixed: 'Fixed Expenses', investments: 'Investments / SIPs', oneoffs: 'One-off Expenses', remarks: 'Remarks' };
export const STEPS = ['Income & Money Received', 'Accounts & Assets', 'Credit Cards', 'Fixed Expenses', 'Investments', 'One-off Expenses', 'Remarks', 'Reconciliation'];
export const id = () => crypto.randomUUID();
export const currentMonth = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}`; };
export const validMonth = value => /^\d{4}-(0[1-9]|1[0-2])$/.test(value) && value >= '1900-01' && value <= '9999-12';
export function shiftMonth(key, delta) { const [y,m] = key.split('-').map(Number); const n = y*12+m-1+delta; return `${Math.floor(n/12)}`.padStart(4,'0')+`-${String((n%12+12)%12+1).padStart(2,'0')}`; }
export function monthLabel(key, short=false) { const [y,m] = key.split('-').map(Number); return new Date(y,m-1,1).toLocaleDateString('en-IN',{month:short?'short':'long',year:'numeric'}); }
export const sum = (rows, field='amount') => rows.reduce((a,r)=>a+Number(r[field]||0),0);
export function newUser(name,email) { return {id:id(),name,email,timezone:'Asia/Kolkata',recurring:{accounts:[],cards:[],fixed:[],investments:[]},reminders:{enabled:true,onlyIfIncomplete:true,stopWhenTallied:true,includeMissing:true,schedules:[{id:id(),day:25,time:'19:00',purpose:'Start monthly check-in',enabled:true}]}}; }
export function monthFor(state,userId,key) {
 const saved=state.months[userId]?.[key]; if(saved) return {...saved,receipts:saved.receipts||[],assets:saved.assets||[]};
 const user=state.users.find(u=>u.id===userId); const m={income:0,accounts:[],cards:[],fixed:[],investments:[],oneoffs:[],remarks:[],receipts:[],assets:[],completed:[],step:0};
 const previousKey=Object.keys(state.months[userId]||{}).filter(month=>month<key).sort().at(-1),previous=previousKey?state.months[userId][previousKey]:null;
 m.assets=(previous?.assets||[]).map(a=>({...a,value:a.value??null,carried:true}));
 for(const group of Object.keys(user.recurring)) m[group]=user.recurring[group].filter(r=>r.start<=key && (!r.end||r.end>=key)).map(r=>{
  const prior=group==='fixed'?previous?.fixed?.find(item=>item.sourceId===r.id):null;
  return {...r,id:id(),sourceId:r.id,estimated:true,...(prior?{amount:prior.amount,...(prior.note!==undefined?{note:prior.note}:{})}:{}) ,...(group==='accounts'?{opening:0,closing:null,balanceDate:''}:{})};
 });
 if(previous){
  const pastAccountKeys=new Set(Object.entries(state.months[userId]||{}).filter(([k])=>k<key).flatMap(([,mm])=>mm.accounts.map(a=>a.sourceId||a.id)));
  const fresh=m.accounts.filter(a=>!pastAccountKeys.has(a.sourceId||a.id));
  m.accounts=[...previous.accounts.map(a=>({...a,opening:a.closing??a.opening??0,carried:true,estimated:true})),...fresh];
 }
 return m;
}
export function ensureMonth(state,userId,key) { if(!validMonth(key)) throw new Error('Choose a valid month.'); if(state.months[userId]?.[key]) return state; return {...state,months:{...state.months,[userId]:{...state.months[userId],[key]:monthFor(state,userId,key)}}}; }
export function updateMonth(state,userId,key,fn) { const next=ensureMonth(state,userId,key); const m=structuredClone(monthFor(next,userId,key)); fn(m); m.completed=m.completed.filter(x=>x!==7); return {...next,months:{...next.months,[userId]:{...next.months[userId],[key]:m}}}; }
export function totals(m) {
 const receipts=receiptSummary(m),income=roundMoney(Number(m.income)+receipts.income),regular=sum(m.cards)+sum(m.fixed),oneoff=sum(m.oneoffs),refund=receipts.refund,otherReceipts=receipts.other,invest=sum(m.investments),remaining=roundMoney(income-regular-oneoff+refund-invest),opening=sum(m.accounts,'opening');
 const actual=m.accounts.length && m.accounts.every(a=>!a.carried && a.closing!==null && a.closing!=='')?sum(m.accounts,'closing'):null;
 const netCashFlow=roundMoney(remaining+otherReceipts),expected=roundMoney(opening+netCashFlow),difference=actual===null?null:Math.round((actual-expected)*100)/100;
 return {income,regular,oneoff,refund,otherReceipts,netCashFlow,invest,remaining,opening,expected,actual,difference,investRate:income?invest/income:0,uncommittedRate:income?remaining/income:0,rate:income?(income-regular-oneoff+refund)/income:0,tallied:difference===0&&m.completed.includes(7),done:m.completed.length};
}
export function aggregate(records) { const rows=records.map(totals),out={}; for(const k of ['income','regular','oneoff','refund','otherReceipts','netCashFlow','invest','remaining','opening','expected','done']) out[k]=sum(rows,k);out.actual=rows.every(r=>r.actual!==null)?sum(rows,'actual'):null;out.difference=out.actual===null?null:Math.round((out.actual-out.expected)*100)/100;out.investRate=out.income?out.invest/out.income:0;out.uncommittedRate=out.income?out.remaining/out.income:0;out.rate=out.income?(out.income-out.regular-out.oneoff+out.refund)/out.income:0;out.tallied=rows.length>0&&rows.every(r=>r.tallied);return out; }
export function dateRange(state,userIds) { const keys=userIds.flatMap(uid=>Object.keys(state.months[uid]||{}));keys.push(currentMonth());keys.sort();return {first:keys[0],last:keys.at(-1)}; }
export function familyFor(state,uid) { return state.families.find(f=>f.memberIds.includes(uid)); }
export function talliedStreak(state,uid,through){let key=through,count=0;for(;;){const month=state.months[uid]?.[key];if(!month||!totals(month).tallied||month.completed.length!==8)break;count++;key=shiftMonth(key,-1);}return count;}
export function createFamily(state,uid,name) { if(familyFor(state,uid)) throw new Error('Leave your current family before creating another.'); const family={id:id(),name,code:id().slice(0,8).toUpperCase(),memberIds:[uid]};return {...state,families:[...state.families,family]}; }
export function joinFamily(state,uid,code) { if(familyFor(state,uid)) throw new Error('Leave your current family before joining another.');const f=state.families.find(f=>f.code===code.trim().toUpperCase());if(!f) throw new Error('No family with that code exists in this browser.');return {...state,families:state.families.map(x=>x.id===f.id?{...x,memberIds:[...x.memberIds,uid]}:x)}; }
export function seedState() {
 const candy=newUser('Candy','candy@example.com'),popcorn=newUser('PopCorn','popcorn@example.com');const now=currentMonth(),start=shiftMonth(now,-3);
 const state={version:2,activeUserId:candy.id,users:[candy,popcorn],families:[{id:id(),name:'Candy & PopCorn',code:'FAMILY-DEMO',memberIds:[candy.id,popcorn.id]}],months:{}};
 for(const [i,u] of state.users.entries()) {
  u.recurring.accounts=[{id:id(),name:'Savings account',start}];u.recurring.cards=[{id:id(),name:'HDFC Credit Card',amount:i?12000:8000,start},{id:id(),name:'ICICI Credit Card',amount:4000,start}];u.recurring.fixed=[{id:id(),name:'Rent / Home',amount:25000,start},{id:id(),name:'Utilities',amount:5000,start}];u.recurring.investments=[{id:id(),name:'Mutual Fund SIP',amount:i?40000:20000,start}];
  state.months[u.id]={};for(let n=0;n<4;n++){const key=shiftMonth(start,n),m=monthFor(state,u.id,key);m.income=i?145257:128000;m.accounts[0].opening=50000;m.oneoffs=n===1?[{id:id(),name:'Travel',amount:6500,date:`${key}-12`,note:'Weekend trip'}]:[];if(n<3){m.accounts[0].closing=totals(m).expected;delete m.accounts[0].carried;m.completed=[0,1,2,3,4,5,6,7];m.step=7;}state.months[u.id][key]=m;}
 }
 return state;
}
export function loadState(storage=localStorage) { const raw=storage.getItem(STORAGE_KEY); if(!raw)return seedState(); const s=JSON.parse(raw); if(s.version!==2||!Array.isArray(s.users)||!s.users.length||!s.months||!Array.isArray(s.families)||!s.users.some(u=>u.id===s.activeUserId))throw new Error('The saved data could not be loaded. It has not been overwritten.');return s; }
