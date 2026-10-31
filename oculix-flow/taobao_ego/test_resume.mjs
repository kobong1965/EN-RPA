import test from 'node:test';
import assert from 'node:assert/strict';
import {collect} from './collect.mjs';

const req={storeId:'neutcut',profileId:'Profile 1',profileName:'淘宝1号店',pageStoreName:'示例店铺A',targetDate:'2026-10-30',spaceName:'test',outputPath:'unused',spaceId:26};
globalThis.profiles=async()=>[{id:'Profile 1',name:'淘宝1号店'}];
test('wrong existing profile is rejected before selecting a space',async()=>{
  let opened=0;globalThis.listTaskSpaces=async()=>[{id:26,ownership:'agent',profileId:'Profile 2',profileName:'淘宝2号店'}];
  globalThis.taskSpace=async()=>{opened++};
  await assert.rejects(collect(req),/配置不匹配/);assert.equal(opened,0);
});
test('existing space is reused and reloaded without goto or new task',async()=>{
  const calls=[];globalThis.listTaskSpaces=async()=>[{id:26,ownership:'agent',profileId:'Profile 1',profileName:'淘宝1号店'}];
  globalThis.taskSpace=async(...args)=>{calls.push(args);return {spaceId:26,page:()=>({url:async()=> 'https://sycm.taobao.com/portal/home.htm',reload:async()=>{calls.push('reload')},goto:async()=>{throw Error('must not navigate')},waitForFunction:async()=>{throw Error('stop after reload')}})}};
  await assert.rejects(collect(req),/stop after reload/);assert.deepEqual(calls,[[26],'reload']);
});
test('unexpected URL fails without reloading or navigating',async()=>{
  let reload=0;globalThis.listTaskSpaces=async()=>[{id:26,ownership:'agent',profileId:'Profile 1',profileName:'淘宝1号店'}];
  globalThis.taskSpace=async()=>({spaceId:26,page:()=>({url:async()=> 'https://unexpected.invalid/',reload:async()=>{reload++}})});
  await assert.rejects(collect(req),/不在预期/);assert.equal(reload,0);
});
