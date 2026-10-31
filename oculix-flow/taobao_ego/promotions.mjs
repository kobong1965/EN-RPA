// Only these known, non-transactional overlays may be dismissed. All other
// dialogs are left alone and will block later input normally.
export async function dismissKnownPromotions(page) {
  for(let i=0;i<4;i++) {
    const found=await page.evaluate(()=>{
      document.querySelectorAll('[data-dianmai-promo-close]').forEach(e=>e.removeAttribute('data-dianmai-promo-close'));
      const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'};
      const tops=[...document.querySelectorAll('.sycm-active-egg-modal-content')].filter(e=>visible(e)&&['巅峰时刻','阶段成就'].includes(e.querySelector('.sycm-active-egg-modal-content-title')?.innerText.trim()));
      const dialogs=[...document.querySelectorAll('[role="dialog"]')].filter(e=>visible(e)&&e.innerText.includes('优惠竞争力变差提醒'));
      if(tops.length>1 || dialogs.length>1)throw Error('活动提醒不唯一，停止采集');
      if(!tops.length&&!dialogs.length)return null;
      const target=tops.length ? tops[0].querySelectorAll('.sycm-active-egg-modal-content-close') : dialogs[0].querySelectorAll('button[aria-label="Close"]');
      const matches=[...target].filter(visible);
      if(matches.length!==1)throw Error('已识别提醒的关闭按钮不唯一');
      const e=matches[0],r=e.getBoundingClientRect(), hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);
      if(!hit || !(e===hit || e.contains(hit)))throw Error('提醒关闭按钮被遮挡');
      e.setAttribute('data-dianmai-promo-close','1');
      return tops.length?'celebration':'price';
    });
    if(found===null)return;
    await page.click('loc=css:[data-dianmai-promo-close="1"]',{label:'关闭已识别活动提醒'});
    await page.waitForFunction(()=>{const e=document.querySelector('[data-dianmai-promo-close="1"]');if(!e)return true;const r=e.getBoundingClientRect(),s=getComputedStyle(e);return !e.isConnected||r.width===0||r.height===0||s.display==='none'||s.visibility==='hidden'},undefined,{timeout:10000});
  }
  throw Error('活动提醒反复出现，停止采集');
}
