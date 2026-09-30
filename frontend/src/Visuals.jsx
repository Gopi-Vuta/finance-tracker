import React from 'react';
import {House,CalendarCheck,Wallet,Target,RefreshCw,ChartNoAxesCombined,Users,Settings,ChartColumn,Coins,TrendingUp,NotebookPen,CalendarDays} from 'lucide-react';
const icons={'Dashboard':House,'Monthly Check-in':CalendarCheck,'Daily Check-in':CalendarDays,'Expenses & Investments':Wallet,'Budget Goals':Target,'Reconciliation':RefreshCw,'Trends & Insights':ChartNoAxesCombined,'Family':Users,'Settings':Settings,'Income vs Expenses':ChartColumn,'This Month':CalendarDays,'Monthly check-in':CalendarCheck};
export function PageIcon({name}){const Icon=icons[name]||NotebookPen;return <Icon size={20} strokeWidth={1.8} aria-hidden="true"/>;}
export function MoneyArt({kind}){const Icon={income:Wallet,expense:Coins,invest:TrendingUp,remaining:Target}[kind];return <span className={`money-art art-${kind}`} aria-hidden="true"><span className="art-orbit"/><Icon size={48} strokeWidth={1.8}/></span>;}
