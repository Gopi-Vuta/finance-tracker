const columns=[3,5,7,9,11,13,15];
const id=(month,group,row)=>`xlsx-${month}-${group}-${row}`;
const reminder={enabled:true,onlyIfIncomplete:true,stopWhenTallied:true,includeMissing:true,schedules:[{id:'xlsx-reminder',day:25,time:'19:00',purpose:'Start monthly check-in',enabled:true}]};

async function unzip(file){
 const bytes=new Uint8Array(await file.arrayBuffer()),view=new DataView(bytes.buffer);let end=-1;
 for(let i=bytes.length-22;i>=Math.max(0,bytes.length-65557);i--)if(view.getUint32(i,true)===0x06054b50){end=i;break;}
 if(end<0)throw new Error('This does not look like a valid .xlsx workbook.');
 const count=view.getUint16(end+10,true),offset=view.getUint32(end+16,true),out=new Map();let pos=offset;
 for(let i=0;i<count;i++){
  if(view.getUint32(pos,true)!==0x02014b50)throw new Error('The workbook ZIP directory is invalid.');
  const method=view.getUint16(pos+10,true),size=view.getUint32(pos+20,true),nameLength=view.getUint16(pos+28,true),extraLength=view.getUint16(pos+30,true),commentLength=view.getUint16(pos+32,true),local=view.getUint32(pos+42,true),name=new TextDecoder().decode(bytes.slice(pos+46,pos+46+nameLength));
  const localName=view.getUint16(local+26,true),localExtra=view.getUint16(local+28,true),raw=bytes.slice(local+30+localName+localExtra,local+30+localName+localExtra+size);
  const content=method===0?raw:method===8?new Uint8Array(await new Response(new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer()):null;
  if(!content)throw new Error('The workbook uses an unsupported compression format.');out.set(name,new TextDecoder().decode(content));pos+=46+nameLength+extraLength+commentLength;
 }
 return out;
}
const xml=text=>new DOMParser().parseFromString(text,'application/xml');
const tags=(node,name)=>Array.from(node.getElementsByTagNameNS('*',name));
const columnNumber=ref=>{let n=0;for(const char of ref.match(/[A-Z]+/)[0])n=n*26+char.charCodeAt(0)-64;return n;};
const value=(cell,strings)=>{const type=cell.getAttribute('t'),raw=tags(cell,'v')[0]?.textContent;if(type==='s')return strings[Number(raw)]??'';if(type==='inlineStr')return tags(cell,'t').map(t=>t.textContent).join('');return raw==null?'':Number.isFinite(Number(raw))?Number(raw):raw;};

export async function parseLegacyWorkbook(file,worksheetName){
 if(!file?.name?.toLowerCase().endsWith('.xlsx'))throw new Error('Choose the Expense tracker .xlsx file.');
 const sheetName=String(worksheetName||'').trim();if(!sheetName)throw new Error('Enter the worksheet name to import.');
 const files=await unzip(file),workbook=xml(files.get('xl/workbook.xml')||''),rels=xml(files.get('xl/_rels/workbook.xml.rels')||''),available=tags(workbook,'sheet').map(s=>s.getAttribute('name')).filter(Boolean),sheet=tags(workbook,'sheet').find(s=>s.getAttribute('name')===sheetName);
 if(!sheet)throw new Error(`Could not find “${sheetName}”. Available worksheets: ${available.join(', ')}.`);
 const profile=sheetName.replace(/\s+\d{4}$/,'').trim()||sheetName;
 const relId=sheet.getAttribute('r:id'),relationship=tags(rels,'Relationship').find(r=>r.getAttribute('Id')===relId),target=relationship?.getAttribute('Target');
 if(!target)throw new Error('Could not read the selected profile worksheet.');
 const sheetPath=`xl/${target.replace(/^\/?/, '')}`,shared=files.get('xl/sharedStrings.xml'),strings=shared?tags(xml(shared),'si').map(si=>tags(si,'t').map(t=>t.textContent).join('')):[],cells=new Map();
 for(const cell of tags(xml(files.get(sheetPath)||''),'c')){const ref=cell.getAttribute('r');cells.set(`${Number(ref.match(/\d+/)[0])}:${columnNumber(ref)}`,value(cell,strings));}
 const at=(row,column)=>cells.get(`${row}:${column}`),number=(row,column)=>typeof at(row,column)==='number'&&Number.isFinite(at(row,column))?at(row,column):null;
 const monthKey=value=>{const match=String(value||'').trim().match(/^([A-Za-z]{3})-(\d{2}|\d{4})$/);if(!match)return null;const index=['jan','feb','mar','apr','may','jun','jul','aug','sep','oct','nov','dec'].indexOf(match[1].toLowerCase());if(index<0)return null;const year=match[2].length===2?2000+Number(match[2]):Number(match[2]);return `${year}-${String(index+1).padStart(2,'0')}`;};
 const importedMonths=columns.map(column=>({column,key:monthKey(at(65,column))})).filter(item=>item.key);
 if(!importedMonths.length)throw new Error('Could not find monthly headings such as Jan-26 in row 65 of the selected worksheet.');
 const entries=(month,column)=>({
  income:number(24,column)||0,accounts:[],
  cards:[[11,'CC Bill: AmazonPAY ICICI'],[12,'CC Bill - ICICI Others'],[13,'CC Bill - HDFC Swiggy']].flatMap(([row,name])=>number(row,column)===null?[]:[{id:id(month,'card',row),name,amount:number(row,column)}]),
  fixed:[[28,'Rent and other expenses to DAD'],[29,'To Aunt'],[30,'Parents Medical Insurance'],[31,'Airtel Postpaid']].flatMap(([row,name])=>number(row,column)===null?[]:[{id:id(month,'fixed',row),name,amount:number(row,column),...(at(row,column+1)?{note:String(at(row,column+1))}:{})}]),
  investments:[[40,'SIP in Mutual Fund'],[41,'SIP in Gold scheme']].flatMap(([row,name])=>number(row,column)===null?[]:[{id:id(month,'investment',row),name,amount:number(row,column),...(at(row,column+1)?{note:String(at(row,column+1))}:{})}]),
  oneoffs:Array.from({length:10},(_,i)=>66+i).flatMap(row=>{const amount=number(row,column),note=at(row,column+1);if(amount===null||amount<0)return [];return [{id:id(month,'oneoff',row),name:`One-off ${row-65}`,amount:amount,date:`${month}-01`,...(note?{note:String(note)}:{})}];}),
  remarks:[],completed:[],step:0
 });
 const months={};importedMonths.forEach(({key:month,column})=>{
  const m=entries(month,column),negative=Array.from({length:10},(_,i)=>66+i).filter(row=>(number(row,column)||0)<0);
  negative.forEach(row=>m.remarks.push({id:id(month,'remark',row),text:`${at(row,column+1)||`One-off ${row-65}`}: ₹${number(row,column)}. Kept as a remark because expense entries cannot be negative.`}));
  [[16,'Other savings account balance'],[17,'Mutual Funds'],[18,'Unused Funds in Zerodha'],[19,'Stocks'],[20,'PPF'],[22,'Amount people owe to me']].forEach(([row,name])=>{
   const amount=number(row,column);if(amount===null)return;const note=at(row,column+1);m.remarks.push({id:id(month,'remark',row),text:note?`${name}: ₹${amount}. ${note}`:`${name}: ₹${amount}`});
  });
  if(m.income||m.cards.length||m.fixed.length||m.investments.length||m.oneoffs.length||m.remarks.length)months[month]=m;
 });
 const firstMonth=Object.keys(months)[0],first=months[firstMonth];if(!first)throw new Error('The selected worksheet has no finance entries to import.');const recurring={accounts:[],cards:[],fixed:first.fixed.map((r,i)=>({id:`xlsx-recurring-fixed-${i}`,name:r.name,amount:r.amount,start:firstMonth})),investments:first.investments.map((r,i)=>({id:`xlsx-recurring-investment-${i}`,name:r.name,amount:r.amount,start:firstMonth}))};
 const uid=`legacy-${profile.toLowerCase().replace(/[^a-z0-9]+/g,'-')||'profile'}-2026`;
 return {version:2,activeUserId:uid,users:[{id:uid,name:profile,email:'profile@legacy.local',timezone:'Asia/Kolkata',recurring,reminders:reminder}],families:[],months:{[uid]:months}};
}
