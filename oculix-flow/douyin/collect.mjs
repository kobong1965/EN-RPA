import {mkdir, writeFile} from 'node:fs/promises';
import {dirname} from 'node:path';

/** Read only the observed 抖店经营数据 DOM; never call private website APIs. */
export async function collect(request) {
  const task = request.spaceId ? await taskSpace(request.spaceId) : await taskSpace(request.spaceName);
  const page = task.page(request.page ?? 'p1');
  if (!request.spaceId) {
    try { await page.goto(request.sourceUrl); }
    catch (error) { if (!String(error).includes('navigation committed')) throw error; }
  } else {
    // A reused page may still show yesterday's cached reporting period.
    // Reload before checking the account and the report period.
    await page.reload();
  }
  await page.waitForFunction(name=>document.body.innerText.split('\n').some(s=>s.trim()===name),request.storeName,{timeout:20000});
  // The account name can render before the reporting controls. Wait for the
  // control itself rather than querying it immediately after navigation.
  await page.waitForFunction(()=>[...document.querySelectorAll('[role="tab"]')].filter(e=>e.innerText.trim()==='近1天').length===1,undefined,{timeout:20000});
  await page.evaluate(()=>{
    const tabs=[...document.querySelectorAll('[role="tab"]')].filter(e=>e.innerText.trim()==='近1天');
    if(tabs.length!==1)throw Error('未能唯一定位近1天数据');
    if(tabs[0].getAttribute('aria-selected')!=='true')tabs[0].click();
  });
  await page.waitForFunction(()=>{
    const text=document.body.innerText, i=text.indexOf('经营数据');
    return i>=0&&/20\d{2}\/\d{1,2}\/\d{1,2}-20\d{2}\/\d{1,2}\/\d{1,2}/.test(text.slice(i,i+700));
  },undefined,{timeout:20000});
  const observed = await page.evaluate(name=>{
    const body=document.body.innerText;
    if(!body.slice(0,1800).split('\n').some(s=>s.trim()===name))throw Error('当前抖店店名不匹配');
    const start=body.indexOf('经营数据'),end=body.indexOf('新商近30天动销成交任务',start);
    const text=body.slice(start,end<0?start+12000:end);
    const range=text.slice(0,700).match(/(20\d{2}\/\d{1,2}\/\d{1,2})-(20\d{2}\/\d{1,2}\/\d{1,2})/);
    if(!range)throw Error('页面没有明确的日度日期范围');
    const normalize=s=>s.split('/').map((v,i)=>i?v.padStart(2,'0'):v).join('-');
    const selected=[...document.querySelectorAll('[role=tab]')].find(e=>e.getAttribute('aria-selected')==='true');
    if(selected?.innerText.trim()!=='近1天')throw Error('当前不是近1天数据');
    const cards=[...document.querySelectorAll('[class*="compassCard-"]')].map(card=>{
      const title=card.querySelector('[class*="compassTitle-"]');
      const value=card.querySelector('[class*="compassVal-"]');
      if(!title||!value)return null;
      return {label:title.innerText.trim(),rawValue:[...value.children].filter(e=>!String(e.className).includes('chartDataText')).map(e=>e.innerText).join('').trim(),text:card.innerText};
    }).filter(Boolean);
    if(cards.length<7)throw Error('经营数据卡片不完整');
    return {storeName:name,startDate:normalize(range[1]),endDate:normalize(range[2]),selectedPeriod:'近1天',text,cards};
  }, request.storeName);
  const output={platform:'douyin',storeId:request.storeId,...observed,sourceUrl:await page.url(),capturedAt:new Date().toISOString(),taskSpaceId:task.spaceId};
  output.status=observed.startDate===request.targetDate&&observed.endDate===request.targetDate?'captured':'waiting_for_data';
  output.requestedDate=request.targetDate;
  await mkdir(dirname(request.outputPath),{recursive:true});
  await writeFile(request.outputPath,JSON.stringify(output,null,2),'utf8');
  console.log({status:output.status,requestedDate:request.targetDate,sourceDate:observed.endDate,cards:observed.cards.length,outputPath:request.outputPath});
  if(request.finish!==false)await task.finish({keep:[]});
  return output;
}
