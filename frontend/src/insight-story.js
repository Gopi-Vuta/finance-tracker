import {currentMonth,shiftMonth,monthLabel} from './model.js';
import {monthlyData,categories} from './insights.js';
import {accountKey,wealthSnapshot,wealthChange,roundMoney} from './money.js';

const money=n=>new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',maximumFractionDigits:0}).format(n);
const reviewSections=[0,2,3,4,5];
export function reviewedMonth(state,ids,key){return ids.length>0&&ids.every(uid=>{const m=state.months[uid]?.[key];return m&&reviewSections.every(i=>m.completed.includes(i));});}
export function spendingEntries(state,ids,key){
 return ids.flatMap(uid=>{
  const m=state.months[uid]?.[key];if(!m)return [];
  const owner=state.users.find(u=>u.id===uid)?.name||'Member';
  const entries=['fixed','cards','oneoffs'].flatMap(group=>(m[group]||[]).filter(r=>!r.estimated||m.completed.includes({fixed:3,cards:2,oneoffs:5}[group])).map(r=>({...r,owner,uid,group,category:r.category?.trim()||categories[group],amount:Number(r.amount)})));
  return [...entries,...(m.receipts||[]).filter(r=>r.type==='refund'&&r.refundPeriod==='current').map(r=>({...r,owner,uid,group:'receipts',category:r.category?.trim()||'Refunds',amount:-Number(r.amount)}))];
 });
}
export function insightStory(state,ids,key,today=currentMonth()){
 const reviewed=reviewedMonth(state,ids,key),closed=key<today,ready=reviewed&&closed;
 const previousKey=shiftMonth(key,-1),previousReady=reviewedMonth(state,ids,previousKey)&&previousKey<today;
 const now=monthlyData(state,ids,key),before=monthlyData(state,ids,previousKey);
 const entries=spendingEntries(state,ids,key),grouped={};for(const r of entries)grouped[r.category]=(grouped[r.category]||0)+r.amount;
 const baselineKeys=[-3,-2,-1].map(n=>shiftMonth(key,n));
 const baselineReady=baselineKeys.every(k=>k<today&&reviewedMonth(state,ids,k));
 const baseline=baselineReady?baselineKeys.map(k=>monthlyData(state,ids,k)):[];
 const names=new Set([...Object.keys(grouped),...(ready&&previousReady?Object.keys(before.byCategory):[])]);
 const drivers=[...names].map(category=>{const amount=roundMoney(grouped[category]||0),prior=ready&&previousReady?before.byCategory[category]||0:null,average=ready&&baselineReady?roundMoney(baseline.reduce((s,b)=>s+(b.byCategory[category]||0),0)/3):null;return {category,amount,prior,change:prior===null?null:roundMoney(amount-prior),average,step:entries.find(r=>r.category===category)?.group==='fixed'?3:entries.find(r=>r.category===category)?.group==='cards'?2:entries.find(r=>r.category===category)?.group==='receipts'?0:5};}).sort((a,b)=>Math.abs(b.change??b.amount)-Math.abs(a.change??a.amount));
 const rhythm=Array.from({length:6},(_,i)=>{const month=shiftMonth(key,i-5),data=monthlyData(state,ids,month);return {month,ready:month<today&&reviewedMonth(state,ids,month),...data};});
 let retainedStreak=0,contributionStreak=0;
 for(let k=closed?key:previousKey;k>='1900-01';k=shiftMonth(k,-1)){if(!reviewedMonth(state,ids,k))break;const d=monthlyData(state,ids,k);if(d.remaining<=0)break;retainedStreak++;}
 for(let k=closed?key:previousKey;k>='1900-01';k=shiftMonth(k,-1)){if(!reviewedMonth(state,ids,k)||monthlyData(state,ids,k).invest<=0)break;contributionStreak++;}
 const commitments=ids.flatMap(uid=>{const user=state.users.find(u=>u.id===uid);return ['fixed','investments'].flatMap(group=>(user?.recurring?.[group]||[]).filter(r=>r.start<=key&&(!r.end||r.end>=key)).map(r=>({...r,owner:user.name,group,amount:Number(r.amount)})));}).sort((a,b)=>b.amount-a.amount);
 const fixed=roundMoney(commitments.filter(r=>r.group==='fixed').reduce((s,r)=>s+r.amount,0)),investments=roundMoney(commitments.filter(r=>r.group==='investments').reduce((s,r)=>s+r.amount,0));
 const income=reviewed&&now.income>0?now.income:null,commitmentRate=income===null?null:fixed/income;
 const cards=[];
 if(!ready)cards.push({tone:'context',title:closed?'Your picture is still taking shape':'Give this month room to unfold',message:closed?'Review the remaining sections before comparing spending with earlier months. A smaller partial total can look like an improvement.':'Keep adding entries at your own pace. Full-month spending comparisons will appear after this month ends and its sections are reviewed.',evidence:`${monthLabel(key,true)} · ${reviewed?'Cash-flow sections reviewed':'Income, expenses and investments need review'}`,action:'Continue check-in',step:0});
 if(ready&&previousReady){
  const change=roundMoney(now.expense-before.expense),top=drivers.find(d=>d.change!==null&&Math.sign(d.change)===Math.sign(change));
  if(Math.abs(change)>=1000&&(before.expense<=0||Math.abs(change)/before.expense>=.1))cards.push({tone:change>0?'context':'progress',title:change>0?'Here’s what moved your spending':'More breathing room this month',message:change>0?`${top?.category||'Spending'} was the largest upward category change. Check whether those entries were one-offs before adjusting your regular plan.`:'Your reviewed spending was lower than last month. That leaves more flexibility—check the categories behind the change to see what is repeatable.',evidence:`Spending ${change>0?'up':'down'} ${money(Math.abs(change))} vs ${monthLabel(previousKey,true)}${top?` · ${top.category}: ${top.change>=0?'+':'−'}${money(Math.abs(top.change))}`:''}`,action:'Explore spending',category:top?.category,step:top?.step||5});
  if(now.remaining<0)cards.push({tone:'caution',title:'More went out than came in',message:'Expenses and investment contributions exceeded earned income. This may be intentional if you used existing cash; review the entries before making next month’s plan.',evidence:`${money(Math.abs(now.remaining))} beyond income after contributions`,action:'Review reconciliation',step:7});
 }
 if(commitmentRate!==null&&commitmentRate>=.6)cards.push({tone:'caution',title:'Your regular commitments leave less room',message:'Your configured fixed expenses use a large share of reviewed income. Start with the biggest commitments when looking for flexibility.',evidence:`${(commitmentRate*100).toFixed(0)}% of ${monthLabel(key,true)} income · ${money(fixed)} fixed defaults`,action:'Review commitments',target:'commitments'});
 if(retainedStreak>=2)cards.push({tone:'progress',title:'You’re building a consistent buffer',message:`You had income left after expenses and contributions for ${retainedStreak} consecutive reviewed, finished months. That consistency gives your bigger plans more room.`,evidence:`Through ${monthLabel(closed?key:previousKey,true)} · not a bank-balance measure`,action:'See your rhythm',target:'rhythm'});
 else if(contributionStreak>=2)cards.push({tone:'progress',title:'A steady investing habit',message:`You recorded investment contributions in ${contributionStreak} consecutive reviewed, finished months. Consistency is worth recognising, even when market values fluctuate.`,evidence:`Through ${monthLabel(closed?key:previousKey,true)} · contributions, not returns`,action:'See your rhythm',target:'rhythm'});
 if(!cards.length)cards.push({tone:'context',title:'A useful baseline starts here',message:'Your records are ready to explore. As you review more months, meaningful changes and consistent habits will stand out here.',evidence:`${monthLabel(key,true)} · reviewed records`,action:'Explore spending',target:'spending'});
 const wealth=ids.map(uid=>({uid,now:wealthSnapshot(state.months[uid]?.[key]),before:wealthSnapshot(state.months[uid]?.[previousKey])}));
 const wealthComparable=wealth.every(w=>wealthChange(w.now,w.before)!==null);
 const wealthMoves=wealthComparable?ids.flatMap(uid=>{const a=state.months[uid][key],b=state.months[uid][previousKey],owner=state.users.find(u=>u.id===uid)?.name;return ['accounts','assets'].flatMap(group=>(a[group]||[]).map(r=>{const old=(b[group]||[]).find(x=>group==='accounts'?accountKey(x)===accountKey(r):x.id===r.id);return {name:r.name,owner,change:roundMoney(Number(group==='accounts'?r.closing:r.value)-Number(group==='accounts'?old.closing:old.value))};}));}).sort((a,b)=>Math.abs(b.change)-Math.abs(a.change)):[];
 return {reviewed,closed,ready,now,previousKey,previousReady,baselineKeys,baselineReady,drivers,entries,rhythm,commitments,fixed,investments,commitmentRate,cards:cards.slice(0,3),wealthComparable,wealthMoves};
}
export const scenarioAnnual=monthlyReduction=>roundMoney(Math.max(0,Number(monthlyReduction)||0)*12);
