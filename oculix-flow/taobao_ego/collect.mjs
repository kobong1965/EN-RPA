// Read-only navigation of an already logged-in independent ego profile.
// Every selected date and store is checked again against the resulting table.
import {writeFile} from 'node:fs/promises';
import {dismissKnownPromotions} from './promotions.mjs';

export async function collect(req) {
  const allowed = {neutcut:['Profile 1','淘宝1号店','示例店铺A'],
    fangmianqiu:['Profile 2','淘宝2号店','示例店铺B'],amgx:['Profile 3','淘宝3号店','示例店铺C']};
  const binding=allowed[req.storeId];
  if (!binding || binding[0]!==req.profileId || binding[1]!==req.profileName || binding[2]!==req.pageStoreName || !/^\d{4}-\d{2}-\d{2}$/.test(req.targetDate)) throw Error('店铺配置或目标日期无效');
  const p=await profiles();
  if(p.filter(x=>x.id===binding[0]&&x.name===binding[1]).length!==1)throw Error('浏览器配置不唯一或已变化');
  let task;
  if (req.spaceId!==undefined) {
    if (!Number.isSafeInteger(req.spaceId) || req.spaceId<1)throw Error('任务空间编号无效');
    const spaces=await listTaskSpaces();
    const matches=spaces.filter(x=>x.id===req.spaceId&&x.ownership==='agent'&&x.profileId===req.profileId&&x.profileName===req.profileName);
    if(matches.length!==1)throw Error('现有任务空间或店铺配置不匹配，停止采集');
    task=await taskSpace(req.spaceId);
  } else task=await taskSpace(req.spaceName,{profileId:req.profileId});
  const page=task.page('p1');
  console.log('OCULIX_PROGRESS ego 空间 '+task.spaceId+'：'+req.profileName);
  if (req.spaceId!==undefined) {
    if (await page.url()!=='https://sycm.taobao.com/portal/home.htm')throw Error('现有空间不在预期生意参谋页面，停止采集');
    await page.reload();
  } else await page.goto('https://sycm.taobao.com/portal/home.htm');
  await page.waitForFunction(name=>document.body.innerText.includes('生意参谋\n'+name+'\n主店') && document.body.innerText.includes('数据概览'),req.pageStoreName,{timeout:30000});
  await dismissKnownPromotions(page);
  // Restrict the day button to the same controls row as the unique overview
  // button; never choose by screen coordinates or bypass a blocking dialog.
  await page.evaluate(()=>{
    const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'};
    const all=[...document.querySelectorAll('button')].filter(e=>visible(e)&&e.innerText.trim()==='全部指标');
    if(all.length!==1)throw Error('无法唯一定位全部指标按钮');
    const a=all[0].getBoundingClientRect();
    const days=[...document.querySelectorAll('button')].filter(e=>{const r=e.getBoundingClientRect();return visible(e)&&e.innerText.trim()==='日'&&Math.abs(r.top-a.top)<45&&r.left<a.left});
    if(days.length!==1)throw Error('无法唯一定位数据概览的日度按钮');
    days[0].setAttribute('data-dianmai-daily','1');
  });
  await page.click('loc=css:button[data-dianmai-daily="1"]',{label:'切换日度数据'});
  await page.keyboard.press('Escape');
  await page.waitForFunction(date=>document.body.innerText.includes('统计时间 '+date),req.targetDate,{timeout:20000});
  await dismissKnownPromotions(page);
  await page.click('text="全部指标"',{label:'打开全部指标'});
  await page.waitForFunction(()=>[...document.querySelectorAll('a')].some(e=>e.innerText.trim()==='表格'),undefined,{timeout:20000});
  await page.click('text="表格"',{label:'查看指标表格'});
  await page.waitForFunction(date=>[...document.querySelectorAll('table')].some(t=>t.rows.length>5&&t.rows[0]?.cells[1]?.innerText.startsWith(date)),req.targetDate,{timeout:25000});
  const data=await page.evaluate(()=>({url:location.href,pageText:document.body.innerText,matrix:[...document.querySelectorAll('table')].map(t=>Array.from(t.rows,r=>Array.from(r.cells,c=>c.innerText.trim()))).filter(t=>t[0]?.some(c=>c.includes('指标名称'))&&t[0].length>2)}));
  if(data.url!=='https://sycm.taobao.com/portal/home.htm'||!data.pageText.includes('生意参谋\n'+req.pageStoreName+'\n主店')||data.matrix.length!==1||!data.matrix[0][0][1].startsWith(req.targetDate))throw Error('表格店铺、日期或来源未通过核验');
  await writeFile(req.outputPath,JSON.stringify({...data,matrix:data.matrix[0],profileId:req.profileId,profileName:req.profileName,capturedAt:new Date().toISOString()},null,2),'utf8');
  console.log('OCULIX_PROGRESS 浏览器表格证据已保存');
  await task.finish({keep:[]});
}
