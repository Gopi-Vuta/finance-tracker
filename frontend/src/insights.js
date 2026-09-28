import {shiftMonth, monthLabel, totals} from './model.js';
export const categories={fixed:'Fixed expenses',cards:'Credit cards',oneoffs:'Variable expenses',investments:'Investments'};
export const defaultBudget=()=>({preset:'Balanced',targets:{Needs:50,Wants:30,Savings:20},mapping:{fixed:'Needs',cards:'Wants',oneoffs:'Wants',investments:'Savings'}});
export const presets={Balanced:[50,30,20],'Debt payoff':[50,20,30],'Aggressive saver':[35,15,50]};
export function validBudget(b){return b&&Object.keys(presets).concat('Custom').includes(b.preset)&&['Needs','Wants','Savings'].every(k=>Number.isFinite(b.targets[k])&&b.targets[k]>=0&&b.targets[k]<=100)&&Math.abs(Object.values(b.targets).reduce((a,v)=>a+v,0)-100)<.001&&Object.keys(categories).every(k=>['Needs','Wants','Savings'].includes(b.mapping[k]));}
const steps={cards:2,fixed:3,investments:4,oneoffs:5};
export function recorded(m,g){if(!m)return false;if(g==='income')return m.income!==0||m.entered?.includes('income')||m.completed.includes(0);return m.completed.includes(steps[g])||(m[g]||[]).some(r=>r.estimated!==true);}
export function monthlyData(state,ids,key){
 const records=ids.map(id=>state.months[id]?.[key]);
 const amount=g=>records.every(m=>recorded(m,g))?records.reduce((sum,m)=>sum+(g==='income'?Number(m.income):m[g].filter(r=>!r.estimated||m.completed.includes(steps[g])).reduce((s,r)=>s+Number(r.amount),0)),0):null;
 const income=amount('income'),invest=amount('investments');
 // An expense series is present once an expense is entered or reviewed. Unreviewed groups remain partial.
 const expensePresent=records.every(m=>['fixed','cards','oneoffs'].some(g=>recorded(m,g)));
 const expense=expensePresent?records.reduce((s,m)=>s+['fixed','cards','oneoffs'].reduce((n,g)=>n+(m[g]||[]).filter(r=>!r.estimated||m.completed.includes(steps[g])).reduce((a,r)=>a+Number(r.amount),0),0),0):null;
 const complete=records.every(m=>m&&['income','cards','fixed','investments','oneoffs'].every(g=>recorded(m,g)));
 const remaining=complete?income-expense-invest:null;
 const byCategory={};if(expensePresent)for(const m of records)for(const g of ['fixed','cards','oneoffs'])for(const r of m[g])if(!r.estimated||m.completed.includes(steps[g])){const label=r.category?.trim()||categories[g];byCategory[label]=(byCategory[label]||0)+Number(r.amount);}
 return {key,income,expense,invest,remaining,byCategory,investRate:income>0&&invest!==null?invest/income:null,uncommittedRate:income>0&&remaining!==null?remaining/income:null,complete};
}
export function delta(now,previous){return now===null||previous===null||previous<=0?null:(now-previous)/previous;}
export function insight(now,previous){
 const swings=Object.entries(now.byCategory||{}).flatMap(([label,amount])=>{const before=previous.byCategory?.[label],d=delta(amount,before??null);return d!==null&&Math.abs(amount-before)>=1000&&Math.abs(d)>=.1?[{label,d}]:[];}).sort((a,b)=>Math.abs(b.d)-Math.abs(a.d));
 if(swings.length){const top=swings[0];return `${top.label} spend ${top.d<0?'dropped':'rose'} ${Math.round(Math.abs(top.d)*100)}% from last month.`;}
 const candidates=[['Expenses','expense'],['Income','income'],['Investments','invest']].flatMap(([label,key])=>{const d=delta(now[key],previous[key]);return d!==null&&Math.abs(now[key]-previous[key])>=1000&&Math.abs(d)>=.1?[{label,d,change:Math.abs(now[key]-previous[key])}]:[];});
 candidates.sort((a,b)=>b.change-a.change);const top=candidates[0];return top?`${top.label} ${top.d<0?'dropped':'rose'} ${Math.round(Math.abs(top.d)*100)}% from last month.`:null;
}
export function streakInfo(state,uid,selected){
 const months=state.months[uid]||{},qualified=k=>{const m=months[k];return !!m&&new Set(m.completed).size===8&&totals(m).tallied;};
 let key=qualified(selected)?selected:shiftMonth(selected,-1),count=0;
 while(key>='1900-01'&&qualified(key)){count++;key=shiftMonth(key,-1);}
 const hadEarlier=Object.keys(months).some(k=>k<key&&qualified(k));
 return {count,reset:count===0&&hadEarlier?`Streak reset — ${monthLabel(key)} wasn't fully reconciled.`:null};
}
export function budgetActual(m,b){const values={Needs:0,Wants:0,Savings:0};for(const g of Object.keys(categories))for(const r of m?.[g]||[])if(!r.estimated||m.completed.includes(steps[g]))values[b.mapping[g]]+=Number(r.amount);return values;}
export function budgetStatus(bucket,actual,target){const excess=bucket==='Savings'?target-actual:actual-target;return excess<=0?'good':excess<=5?'caution':'over';}
