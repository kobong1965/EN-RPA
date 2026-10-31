import test from 'node:test';
import assert from 'node:assert/strict';
import {dismissKnownPromotions} from './promotions.mjs';

function fixture({occluded=false, duplicate=false}={}) {
  const active={celebration:true,price:true};let attrs=new Set(),clicks=[];
  const close={isConnected:true,setAttribute(k){attrs.add(k)},removeAttribute(k){attrs.delete(k)},contains(e){return e===this},getBoundingClientRect(){return {left:10,top:10,width:20,height:20}}};
  const title={innerText:'巅峰时刻'};
  const top={getBoundingClientRect:close.getBoundingClientRect,querySelector:s=>s.includes('title')?title:null,querySelectorAll:s=>s.includes('close')?[close]:[]};
  const dialog={innerText:'优惠竞争力变差提醒',getBoundingClientRect:close.getBoundingClientRect,querySelectorAll:()=>[close]};
  globalThis.getComputedStyle=()=>({display:'block',visibility:'visible'});
  globalThis.document={
    querySelectorAll(s){if(s==='[data-dianmai-promo-close]')return attrs.size?[close]:[];if(s==='.sycm-active-egg-modal-content')return active.celebration?(duplicate?[top,top]:[top]):[];if(s==='[role="dialog"]')return active.price?[dialog]:[];return []},
    querySelector(){return attrs.size?close:null},elementFromPoint(){return occluded?{}:close}
  };
  const page={evaluate:fn=>fn(),async click(sel){assert.equal(sel,'loc=css:[data-dianmai-promo-close="1"]');clicks.push(active.celebration?'celebration':'price');if(active.celebration)active.celebration=false;else active.price=false;attrs.clear()},waitForFunction:fn=>{assert.equal(fn(),true)}};
  return {page,clicks};
}
test('dismisses the top celebration before the known price dialog',async()=>{const f=fixture();await dismissKnownPromotions(f.page);assert.deepEqual(f.clicks,['celebration','price'])});
test('does not click an occluded close control',async()=>{const f=fixture({occluded:true});await assert.rejects(dismissKnownPromotions(f.page),/遮挡/);assert.equal(f.clicks.length,0)});
test('does not choose between duplicate celebrations',async()=>{const f=fixture({duplicate:true});await assert.rejects(dismissKnownPromotions(f.page),/不唯一/);assert.equal(f.clicks.length,0)});
