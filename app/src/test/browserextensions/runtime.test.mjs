import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { ExtensionHost } from '../../main/assets/browser-runtime/extension-host.mjs';
import { createChromeAPI } from '../../main/assets/browser-runtime/chrome-api.mjs';
import { matches, safeFile } from '../../main/assets/browser-runtime/browser-api.mjs';

const tick = () => new Promise(r => setTimeout(r, 30));
test('Flow returns from editors to the canvas and waits for frame picker readiness',async()=>{
  const context={setTimeout,crypto,Date};
  vm.runInNewContext(fs.readFileSync(path.resolve('extensions/google-flow/flow-client.js'),'utf8').replace(/^import .*\n/,'').replaceAll('export ','')+'\nglobalThis.FlowClient=FlowClient;',context);
  const client=new context.FlowClient({});const navigations=[];
  client.js=async()=>({projectId:'project-a'});client.page=async(...args)=>navigations.push(args);
  assert.equal(await client.project(1), 'project-a');assert.equal(client.currentProjectId,'project-a');assert.deepEqual(navigations,[[1,'project-a']]);
  let ready=false,ids=[],started=false;
  const helper={chipIds:()=>ids,closeAddMenu(){},escape(){},byText:()=>({}),clickEl(){setTimeout(()=>{ready=true;},50);return {ok:true};},ui:()=>({pickerOpen:ready}),attachStart(){assert.equal(ready,true);started=true;ids=['new-frame'];},result:()=>({done:true,value:{success:true}})};
  client.evaluate=async(tabId,fn,args)=>{context.window={__agFlow:helper};return vm.runInNewContext('('+fn.toString()+')',context)(...args);};
  await client.attach(1,'new-frame','Frame title','Start');assert.equal(started,true);
});
test('Flow guard decodes Request bodies and blocks mismatched references before fetch',async()=>{
  const sent=[];
  class XHR { open(){} send(){} }
  const page={URL,URLSearchParams,Request,XMLHttpRequest:XHR,location:{href:'https://flow.google.com/project/test'},fetch:async(...args)=>{sent.push(args);return new Response('ok');}};
  page.window=page;
  vm.runInNewContext(fs.readFileSync(path.resolve('extensions/google-flow/page-helpers.js'),'utf8'),page);
  const helper=page.__agFlow;
  const body=refs=>new URLSearchParams({'f.req':JSON.stringify([[['ogiZ0b',JSON.stringify([null,[[null,null,refs.map(id=>[id]),0,3,'BELUGA']]]),null,'generic']]])}).toString();
  const url='https://flow.google.com/_/data/batchexecute?rpcids=ogiZ0b';
  const expect={rpcs:['ogiZ0b'],count:1,modelKey:'BELUGA',aspect:3,refs:['reference-a','reference-b']};
  helper.expect(JSON.stringify(expect));
  const original=new Request(url,{method:'POST',body:body(expect.refs)});
  await page.fetch(original);
  assert.equal(sent.length,1);assert.equal(sent[0][0],original);assert.equal(original.bodyUsed,false);
  assert.equal(helper.capSent(0).sent.refs,2);assert.equal(helper.capSent(0).blocked,false);
  helper.expect(JSON.stringify(expect));
  await assert.rejects(page.fetch(new Request(url,{method:'POST',body:body(['wrong-reference'])})),/send guard/);
  assert.equal(sent.length,1);assert.equal(helper.capSent(1).blocked,true);
  helper.expect(JSON.stringify({...expect,dryRun:true}));
  await assert.rejects(page.fetch(url,{method:'POST',body:body(expect.refs)}),/send guard/);
  assert.equal(sent.length,1);assert.deepEqual(Array.from(helper.capSent(2).problems),['dry-run']);
});
async function fixture(t, code = '') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(),'antigem-extension-')); const dir = path.join(root,'example'); fs.mkdirSync(dir);
  fs.writeFileSync(path.join(dir,'manifest.json'),JSON.stringify({manifest_version:3,name:'Fixture',version:'1',permissions:['storage','alarms','tabs'],background:{service_worker:'worker.js'},mcp_tools:[{name:'echo',inputSchema:{type:'object'}}]})); fs.writeFileSync(path.join(dir,'worker.js'),code);
  const host = new ExtensionHost({ root, stateRoot:path.join(root,'state') }); const ext = await host.loadExtension(dir);
  t.after(async()=>{await host.close();fs.rmSync(root,{recursive:true,force:true});});return {host,ext,root,dir};
}
test('callbacks, delayed sendResponse, and cross-realm Promise listeners',async t=>{
  const {host,ext}=await fixture(t,`chrome.runtime.onMessage.addListener((m,s,reply)=>{if(m.tool==='echo'){setTimeout(()=>reply({value:m.args.value}),10);return true}})`);
  assert.deepEqual(await host.callTool('echo',{value:42},ext.id),{value:42});
  ext.background.client.chrome.runtime.onMessage.addListener(async m=>m.type==='promise'?{ok:true}:undefined);
  const ctx=host.context(ext,'content');assert.deepEqual(await host.message(ctx,{type:'promise'},{extensionId:ext.id}),{ok:true});
});
test('local persists, session stays in memory, callbacks and change events work',async t=>{
  const {host,ext}=await fixture(t);const chrome=ext.background.client.chrome;const changes=[];chrome.storage.onChanged.addListener((value,area)=>changes.push({value,area}));
  await new Promise(resolve=>chrome.storage.local.set({foo:'bar'},resolve));assert.deepEqual(await chrome.storage.local.get({foo:'default',missing:7}),{foo:'bar',missing:7});
  assert.deepEqual(await chrome.storage.local.get('absent'),{});await chrome.storage.session.set({transient:1});assert.deepEqual(await chrome.storage.local.get('transient'),{});
  await tick();assert.equal(changes.length,2);await host.reload(ext.id);const next=host.extensions.get(ext.id).background.client.chrome;
  assert.deepEqual(await next.storage.local.get('foo'),{foo:'bar'});assert.deepEqual(await next.storage.session.get(),{});
});
test('ports carry messages once and disconnect when a context closes',async t=>{
  const {host,ext}=await fixture(t);const remote=host.context(ext,'content');remote.client=createChromeAPI({id:ext.id,manifest:ext.manifest,contextId:remote.id,resourceBase:'http://localhost/',rpc:(m,a)=>host.request(remote,m,a)});
  const chrome=ext.background.client.chrome;chrome.runtime.onConnect.addListener(p=>p.onMessage.addListener(m=>p.postMessage({echo:m})));
  const p=remote.client.chrome.runtime.connect({name:'test'});const result=new Promise(resolve=>p.onMessage.addListener(resolve));p.postMessage('hello');assert.deepEqual(await result,{echo:'hello'});
  const disconnected=new Promise(resolve=>p.onDisconnect.addListener(resolve));host.dropContext(ext.background);await disconnected;
});
test('lastError exists during callback only; unsupported methods reject',async t=>{
  const {host,ext}=await fixture(t);let message;await new Promise(resolve=>ext.background.client.chrome.windows.remove(1,()=>{message=ext.background.client.chrome.runtime.lastError?.message;resolve();}));assert.match(message,/not supported/);assert.equal(ext.background.client.chrome.runtime.lastError,undefined);
});
test('worker failure cannot register tools; load is idempotent',async t=>{
  const {host,ext,dir}=await fixture(t);await host.loadExtension(dir);assert.equal(host.contexts.size,1);await host.unload(ext.id);fs.writeFileSync(path.join(dir,'worker.js'),'throw new Error("bad worker")');await assert.rejects(host.loadExtension(dir),/bad worker/);assert.equal(host.listTools().length,0);assert.equal(host.contexts.size,0);
});
test('match patterns use host boundaries and resource paths cannot escape',async t=>{
  assert.equal(matches('https://*.google.com/*','https://google.com/x'),true);assert.equal(matches('https://*.google.com/*','https://x.google.com/x'),true);assert.equal(matches('https://*.google.com/*','https://evilgoogle.com/x'),false);
  const {root,dir}=await fixture(t);fs.writeFileSync(path.join(root,'secret'),'x');assert.throws(()=>safeFile(dir,'../secret'),/escapes/);
});
test('unmodified flow_mcp worker loads with its callback storage and lifecycle APIs',async t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'antigem-flow-smoke-'));const host=new ExtensionHost({stateRoot:root});t.after(async()=>{await host.close();fs.rmSync(root,{recursive:true,force:true});});
  host.browser.request = async (context, method) => { if (method === 'tabs.query' || method === 'windows.getAll') return []; return {}; };
  const ext=await host.loadExtension(path.resolve('flow_mcp'));await tick();assert.equal(ext.manifest.name,'DeepX - Antigravity Flow Bridge');assert.equal(ext.background.client.chrome.storage.onChanged.hasListeners(),true);
});
test('bundled Flow registers all tools including every Chrome MCP name',async t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'antigem-tool-sets-'));const host=new ExtensionHost({stateRoot:root});t.after(async()=>{await host.close();fs.rmSync(root,{recursive:true,force:true});});
  host.browser.request=async(context,method)=>method==='tabs.query'?[]:{};
  await host.loadExtension(path.resolve('extensions/google-flow'));await tick();
  const tools=host.listTools();assert.equal(tools.filter(t=>t.name.startsWith('flow_')).length,42);assert.equal(new Set(tools.map(t=>t.name)).size,65);
  const originals=fs.readdirSync('flow_mcp/deepx-mcp/schemas').filter(n=>n.endsWith('.json')).map(n=>JSON.parse(fs.readFileSync(path.join('flow_mcp/deepx-mcp/schemas',n),'utf8')).name).filter(n=>n?.startsWith('deepx_'));for(const name of originals)assert.ok(tools.some(t=>t.name===name),name);
  const invalid=await host.callTool('flow_generate_image',{prompt:'test',count:10},'google-flow');assert.equal(invalid.isError,true);assert.match(invalid.content[0].text,/count/);
});
test('separate MCP servers isolate lists and calls, including identical tool names',async t=>{
  const worker=`chrome.runtime.onMessage.addListener((m,s,reply)=>{if(m.type==='mcp_tool_call')reply({extension:chrome.runtime.id,tool:m.tool})})`;
  const {host,ext,root}=await fixture(t,worker);
  const other=path.join(root,'instagram');fs.mkdirSync(other);
  fs.writeFileSync(path.join(other,'manifest.json'),JSON.stringify({manifest_version:3,name:'Instagram fixture',version:'1',background:{service_worker:'worker.js'},mcp_tools:[{name:'echo'},{name:'instagram_only'}]}));fs.writeFileSync(path.join(other,'worker.js'),worker);
  await host.loadExtension(other);
  host.options.deviceId='test-device';host.options.hostPort=0;host.browser.poll=async()=>{};await host.startHttp();host.ready=true;
  const headers={'Content-Type':'application/json','X-Device-Id':'test-device'};
  const rpc=async(id,method,params)=>{const response=await fetch(host.baseUrl+'/mcp'+(id===undefined?'':'?extension='+encodeURIComponent(id)),{method:'POST',headers,body:JSON.stringify({jsonrpc:'2.0',id:1,method,params})});return {status:response.status,body:await response.json()};};
  assert.equal((await fetch(host.baseUrl+'/mcp',{method:'POST',body:'{}'})).status,401);
  assert.equal((await fetch(host.baseUrl+'/mcp?extension='+ext.id,{headers})).status,405);
  const notification=await fetch(host.baseUrl+'/mcp?extension='+ext.id,{method:'POST',headers,body:JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})});assert.equal(notification.status,202);assert.equal(await notification.text(),'');
  assert.equal((await rpc(undefined,'tools/list')).status,400);assert.equal((await rpc('','tools/list')).status,400);assert.equal((await rpc('missing','tools/list')).status,404);
  assert.deepEqual((await rpc(ext.id,'tools/list')).body.result.tools.map(t=>t.name),['echo']);
  assert.deepEqual((await rpc('instagram','tools/list')).body.result.tools.map(t=>t.name),['echo','instagram_only']);
  for(const id of [ext.id,'instagram']){
    assert.equal((await rpc(id,'initialize',{})).body.result.serverInfo.name,id);
    const response=(await rpc(id,'tools/call',{name:'echo'})).body;assert.equal(JSON.parse(response.result.content[0].text).extension,id);
  }
  const denied=(await rpc(ext.id,'tools/call',{name:'instagram_only'})).body;assert.equal(denied.result.isError,true);assert.match(denied.result.content[0].text,/Tool not found/);
  assert.equal((await host.mcp({id:1,method:'tools/list'})).error.code,-32602);
  await host.unload('instagram');assert.equal((await rpc('instagram','tools/list')).status,404);assert.equal((await rpc(ext.id,'tools/list')).body.result.tools.length,1);
  await host.loadExtension(other);assert.equal((await rpc('instagram','tools/list')).body.result.tools.length,2);
});
test('extension UI is unavailable while content events and resource files still work',async t=>{
  const {host,ext,dir}=await fixture(t);
  host.options.deviceId='test-device';host.options.hostPort=0;host.browser.poll=async()=>{};await host.startHttp();
  fs.writeFileSync(path.join(dir,'popup.html'),'<html><head></head><body>Unused popup</body></html>');
  assert.equal((await fetch(host.resourceBase(ext)+'popup.html')).status,404);
  assert.equal([...host.contexts.values()].filter(c=>c.type==='popup').length,0);
  const headers={'Content-Type':'application/json','X-Device-Id':'test-device'};
  assert.equal((await fetch(host.baseUrl+'/extension/page',{method:'POST',headers,body:JSON.stringify({id:ext.id,path:'popup.html'})})).status,404);
  for(const action of [()=>ext.background.client.chrome.action.openPopup(),()=>ext.background.client.chrome.runtime.openOptionsPage(),()=>ext.background.client.chrome.sidePanel.open({})])await assert.rejects(action,/Extension UI is not supported/);
  assert.equal((await fetch(host.resourceBase(ext)+'worker.js')).status,200);
  const ctx=host.context(ext,'content');
  const waiting=fetch(host.baseUrl+'/events?after=0',{headers:{'X-Extension-Context':ctx.id,'X-Extension-Token':ctx.token}}).then(r=>r.json());setTimeout(()=>host.send(ctx,'storage.onChanged',[{key:{newValue:1}},'local']),20);const events=await waiting;assert.equal(events[0].name,'storage.onChanged');
  const unloaded=await(await fetch(host.baseUrl+'/extension/unload',{method:'POST',headers,body:JSON.stringify({id:ext.id})})).json();assert.equal(unloaded.success,true);assert.equal(host.listTools().length,0);
});
test('published documentation covers every public facade member and exact Flow schemas',()=>{
  const {chrome}=createChromeAPI({id:'doc-test',manifest:{},contextId:'doc-test',resourceBase:'http://localhost/',rpc:async()=>{}});
  const docs=['chrome-api.md','antigem-api.md'].map(f=>fs.readFileSync('extension_docs/'+f,'utf8')).join('\n');
  const walk=(object,prefix)=>{for(const [name,value]of Object.entries(object)){
    if(name.startsWith('_')||name==='browserAction')continue;
    const full=prefix?prefix+'.'+name:name;
    if(typeof value==='function'||value?.addListener)assert.ok(docs.includes(full),'Missing docs: '+full);
    else if(value&&typeof value==='object')walk(value,full);
  }};walk(chrome,'');
  assert.deepEqual(JSON.parse(fs.readFileSync('extension_docs/flow-tools.json','utf8')),JSON.parse(fs.readFileSync('extensions/google-flow/manifest.json','utf8')).mcp_tools);
});
test('documented example handles an actual MCP call and persists state through reload',async t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'antigem-doc-example-'));const host=new ExtensionHost({stateRoot:root});t.after(async()=>{await host.close();fs.rmSync(root,{recursive:true,force:true});});
  const ext=await host.loadExtension(path.resolve('extension_docs/examples/hello-mcp'));
  assert.deepEqual(await host.callTool('hello',{name:'Ada'},ext.id),{greeting:'Hello Ada',count:1});await host.reload(ext.id);
  assert.equal((await host.callTool('hello',{name:'Lin'},ext.id)).count,2);
});
test('native file inventory and copy are available to worker APIs with permission checks',async t=>{
  const {host,ext}=await fixture(t);const dir=fs.mkdtempSync(path.join(os.homedir(),'.antigem-api-test-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  fs.writeFileSync(path.join(dir,'source.txt'),'hello');
  await assert.rejects(ext.background.client.chrome.antigem.listFiles({path:dir}),/antigemFiles/);
  ext.manifest.permissions.push('antigemFiles');const api=ext.background.client.chrome.antigem;
  assert.equal((await api.listFiles({path:dir}))[0].name,'source.txt');await api.copyFile({from:path.join(dir,'source.txt'),to:path.join(dir,'copy','target.txt')});assert.equal(await api.readFile({path:path.join(dir,'copy','target.txt')}),'hello');
});
test('Flow warm settings skip project lookup and settings traversal',async()=>{
  const context={RPC:{GENERATE_IMAGES:'ogiZ0b'},setTimeout,crypto,Date,requestAnimationFrame:fn=>setTimeout(fn,0)};
  vm.runInNewContext(fs.readFileSync('extensions/google-flow/flow-client.js','utf8').replace(/^import .*\n/,'').replaceAll('export ','')+'\nglobalThis.FlowClient=FlowClient;',context);
  const client=new context.FlowClient({});let insertions=0,armed=0,evaluations=0;
  const s={ui:()=>({projectId:'p',path:'/project/p'}),chipIds:()=>[],cachedSettings:()=>({matches:true,cost:2}),fastInsertPrompt:()=>{insertions++;return{success:true}},submitInfo:()=>({canTap:true,disabled:false,fx:.5,fy:.5}),expect:()=>armed++,capMark:()=>({n:1})};context.window={__agFlow:s};
  client.tab=async()=>1;client.begin=async()=>{};client.project=async()=>{throw new Error('Should skip project lookup')};client.settings=async()=>{throw new Error('Should skip settings traversal')};client.send=async(tab,expect,trigger,prepared)=>{assert.equal(prepared.n,1);assert.equal(expect.dryRun,true);return {dryRun:true}};
  client.evaluate=async(tab,fn,args)=>{evaluations++;return vm.runInNewContext('('+fn.toString()+')',context)(...args)};
  for(let i=0;i<10;i++){const job={abort:new AbortController(),request:{prompt:'item '+i,video:false,model:'Nano Banana 2.1',aspect:'16:9',resolution:'720p',duration:8,count:1,references:[],dry_run:true}};await client.submit(job);assert.equal(job.fast_path,true);}
  assert.equal(evaluations,10);assert.equal(insertions,10);assert.equal(armed,10);
});
test('ported analyzer runs through the real worker with multilingual scenes and entity cards',async t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'antigem-analyze-'));const host=new ExtensionHost({stateRoot:root});t.after(async()=>{await host.close();fs.rmSync(root,{recursive:true,force:true});});
  await host.loadExtension(path.resolve('extensions/google-flow'));
  const analysis=await host.callTool('deepx_analyze_and_breakdown_script',{raw_input:'Scene 1: A Tesla Roadster drives through a neon city.\n\nScene 2: The Tesla Roadster parks beside a lake.',style_preset:'cinematic'},'google-flow');
  assert.equal(analysis.success,true);assert.equal(analysis.totalScenes,2);assert.ok(analysis.entities.some(e=>e.type==='vehicle'&&e.referencePrompt));assert.ok(analysis.storyboard.every(s=>s.prompt&&s.rawConcept));
  const hindi=await host.callTool('flow_analyze_script',{raw_input:'दृश्य 1: एक योद्धा जंगल में चलता है।\n\nदृश्य 2: वह नदी के पास पहुँचता है।'},'google-flow');assert.equal(hindi.totalScenes,2);assert.equal(hindi.language,'hindi');
});
test('Flow upload supports memory files, detached inputs, trusted chooser CDP and unchanged-file reuse',async()=>{
  for(const mode of ['memory','detached','cdp','trusted']){
    const fallback=mode==='cdp';let opened=false,created=false,uploaded=0,reads=0,cdpBound=0;const listeners=new Set();
    class Input{constructor(){this.type='file';}click(){}dispatchEvent(e){if(e.type==='change')uploaded++;}}
    const originalClick=Input.prototype.click,input=new Input();
    const upload={classList:{contains:()=>false},getBoundingClientRect:()=>({left:10,top:20,width:50,height:30}),querySelector:()=>({textContent:'Upload'}),getAttribute:()=>null,innerText:'upload\nUpload',click(){if(mode==='detached')input.click();else if(mode!=='trusted')created=true;}};
    const add={click(){opened=true;}};
    const document={querySelector:()=>add,querySelectorAll(selector){if(selector==='input[type=file]')return created?[input]:[];return opened?[upload]:[];}};
    const context={HTMLInputElement:Input,RPC:{},setTimeout,crypto,Date,document,window:{},visualViewport:{width:100,height:100,offsetLeft:0,offsetTop:0},Event:class{constructor(type){this.type=type;}},atob,File:class{constructor(bytes,name,options){this.name=name;this.type=options.type;}},DataTransfer:class{constructor(){if(fallback)throw new Error('Unavailable');this.files=[];this.items={add:file=>this.files.push(file)};}}};
    vm.runInNewContext(fs.readFileSync('extensions/google-flow/flow-client.js','utf8').replace(/^import .*\n/,'').replaceAll('export ','')+'\nglobalThis.FlowClient=FlowClient;',context);
    const chrome={antigem:{stat:async()=>({path:'/fixture/asset.png',size:3,modified:1}),readFile:async()=>{reads++;return btoa('png');},automation:async p=>{assert.equal(mode,'trusted');assert.ok(p.tap.fx>0&&p.tap.fx<1);for(const fn of listeners)fn({tabId:1},'Page.fileChooserOpened',{backendNodeId:1234});}},debugger:{onEvent:{addListener:fn=>listeners.add(fn),removeListener:fn=>listeners.delete(fn)},sendCommand:async(target,method,params)=>{if(method==='Runtime.evaluate')return {result:{objectId:'input'}};if(method==='DOM.setFileInputFiles'){if(mode==='trusted')assert.equal(params.backendNodeId,1234);cdpBound++;uploaded++;}return {};}}};
    const client=new context.FlowClient(chrome);client.evaluate=async(tab,fn,args)=>vm.runInNewContext('('+fn.toString()+')',context)(...args);client.js=async(tab,source)=>source==='s.capMark().n'?0:source.startsWith('s.uploadsAfter')?{ids:uploaded?['uploaded-media-id']:[]}:{};client.media=async()=>({mediaId:'uploaded-media-id'});
    assert.equal(await client.resolveReference(1,'~/asset.png'),'uploaded-media-id');assert.equal(uploaded,1);assert.equal(reads,1);assert.equal(cdpBound,fallback||mode==='trusted'?1:0);assert.equal(Input.prototype.click,originalClick);assert.equal(listeners.size,0);
    assert.equal(await client.resolveReference(1,'~/asset.png'),'uploaded-media-id');assert.equal(uploaded,1);assert.equal(reads,1);
    client.currentProjectId='another-project';await client.resolveReference(1,'~/asset.png');assert.equal(uploaded,2,'A different project must get its own upload');
  }
});
test('developer logs survive reload and failed starts, enforce bounds and require native authentication',async t=>{
  const {host,ext,dir}=await fixture(t,`console.info('worker started');`);
  host.options.deviceId='test-device';host.options.hostPort=0;host.browser.poll=async()=>{};await host.startHttp();
  const headers={'Content-Type':'application/json','X-Device-Id':'test-device'};
  const get=async(id,limit=50)=>{const r=await fetch(host.baseUrl+'/extension/logs',{method:'POST',headers,body:JSON.stringify({id,limit})});return {status:r.status,value:await r.json()};};
  assert.equal((await fetch(host.baseUrl+'/extension/logs',{method:'POST',body:'{}'})).status,401);
  assert.ok((await get(ext.id)).value.logs.some(e=>e.message==='worker started'));
  await host.reload(ext.id);assert.equal((await get(ext.id)).value.logs.filter(e=>e.message==='worker started').length,2);
  assert.equal((await get('another-extension')).value.logs.length,0);
  assert.equal((await get(ext.id,1)).value.logs.length,1);assert.equal((await get(ext.id,201)).status,500);
  await host.unload(ext.id);fs.writeFileSync(path.join(dir,'worker.js'),'throw new Error("bad developer update")');await assert.rejects(host.loadExtension(dir),/bad developer update/);
  assert.ok((await get(ext.id)).value.logs.some(e=>e.level==='error'&&e.message.includes('bad developer update')));
});
test('skills folder adds get_skills, get_skill and get_skill_file, even without a worker or mcp_tools',async t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'antigem-skills-'));const dir=path.join(root,'yt');fs.mkdirSync(path.join(dir,'skills','youtube_upload','more'),{recursive:true});
  fs.writeFileSync(path.join(dir,'manifest.json'),JSON.stringify({manifest_version:3,id:'yt',name:'YouTube'}));
  fs.writeFileSync(path.join(dir,'skills','youtube_upload.md'),'# Upload a video\n\nUploads a video to YouTube Studio.\n\nSee youtube_upload/thumbnails.md.');
  fs.writeFileSync(path.join(dir,'skills','search.md'),'---\nname: Search\ndescription: "Find videos"\n---\nBody');
  fs.writeFileSync(path.join(dir,'skills','youtube_upload','thumbnails.md'),'Thumbnail rules');fs.writeFileSync(path.join(dir,'skills','youtube_upload','more','x.md'),'x');
  fs.writeFileSync(path.join(dir,'skills','notes.txt'),'not a skill');
  const host=new ExtensionHost({root,stateRoot:path.join(root,'state')});await host.loadExtension(dir);
  t.after(async()=>{await host.close();fs.rmSync(root,{recursive:true,force:true});});
  const call=async(method,params)=>(await host.mcp({jsonrpc:'2.0',id:1,method,params},'yt')).result;
  assert.match((await call('initialize',{})).instructions,/- youtube_upload: Uploads a video to YouTube Studio\./);
  const tools=(await call('tools/list')).tools;assert.deepEqual(tools.map(x=>x.name),['get_skills','get_skill','get_skill_file']);assert.match(tools[0].description,/Skills: search, youtube_upload\./);
  const skills=JSON.parse((await call('tools/call',{name:'get_skills'})).content[0].text).skills;
  assert.deepEqual(skills.map(s=>[s.name,s.title,s.description,s.detail_files]),[['search','Search','Find videos',0],['youtube_upload','Upload a video','Uploads a video to YouTube Studio.',2]]);
  const skill=JSON.parse((await call('tools/call',{name:'get_skill',arguments:{name:'youtube_upload'}})).content[0].text);
  assert.match(skill.content,/See youtube_upload/);assert.deepEqual(skill.detail_files.map(f=>f.file),['more/x.md','thumbnails.md']);
  assert.equal(JSON.parse((await call('tools/call',{name:'get_skill_file',arguments:{name:'youtube_upload',file:'thumbnails.md'}})).content[0].text).content,'Thumbnail rules');
  assert.equal((await call('tools/call',{name:'get_skill_file',arguments:{name:'youtube_upload',file:'../search.md'}})).isError,true);
  assert.equal((await call('tools/call',{name:'get_skill',arguments:{name:'nope'}})).isError,true);
});
test('a tool the extension declares wins over the built-in skill tool of the same name',async t=>{
  const {host,ext,dir}=await fixture(t,`chrome.runtime.onMessage.addListener((m,s,reply)=>{reply('own')})`);
  fs.mkdirSync(path.join(dir,'skills'));fs.writeFileSync(path.join(dir,'skills','a.md'),'# A');
  ext.manifest.mcp_tools.push({name:'get_skill'});host.toolRegistry.set(JSON.stringify([ext.id,'get_skill']),{spec:{name:'get_skill'},extension:ext});
  assert.deepEqual(host.listTools(ext.id).map(x=>x.name),['echo','get_skill','get_skills','get_skill_file']);
  assert.equal(await host.callTool('get_skill',{name:'a'},ext.id),'own');
  assert.equal(host.listTools(ext.id).filter(x=>x.name==='get_skill').length,1);
});
test('chrome.antigem.uploadFiles needs antigemFiles, checks paths and asks the app to answer the file chooser',async t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'antigem-upload-'));const make=(id,permissions)=>{const dir=path.join(root,id);fs.mkdirSync(dir);
    fs.writeFileSync(path.join(dir,'manifest.json'),JSON.stringify({manifest_version:3,id,name:id,permissions,host_permissions:['https://*/*'],background:{service_worker:'w.js'},mcp_tools:[{name:'x'}]}));fs.writeFileSync(path.join(dir,'w.js'),'');return dir;};
  const host=new ExtensionHost({root,stateRoot:path.join(root,'state')});t.after(async()=>{await host.close();fs.rmSync(root,{recursive:true,force:true});});
  const calls=[];host.browser.tab=async id=>({id});host.browser.checkAccess=async()=>{};host.browser.native=id=>'native-'+id;host.browser.bridge=async(endpoint,body)=>{calls.push([endpoint,body]);return {success:true,method:'chooser',given:body.file_paths.length};};
  const allowed=await host.loadExtension(make('poster',['antigemFiles']));const denied=await host.loadExtension(make('plain',[]));
  const home=path.resolve(process.env.HOME);
  const result=await host.nativeRequest(allowed.background,'antigem.uploadFiles',{tabId:3,files:['~/photo.png'],selector:'input[type=file]',point:{fx:0.5,fy:0.4},timeoutMs:5000});
  assert.equal(result.method,'chooser');
  assert.deepEqual(calls[0],['upload',{tab_id:'native-3',file_paths:[path.join(home,'photo.png')],selector:'input[type=file]',fx:0.5,fy:0.4,method:undefined,timeout_ms:5000}]);
  await assert.rejects(host.nativeRequest(denied.background,'antigem.uploadFiles',{tabId:3,files:['~/photo.png']}),/antigemFiles/);
  await assert.rejects(host.nativeRequest(allowed.background,'antigem.uploadFiles',{tabId:3,files:['/etc/passwd']}),/inside the app home/);
  await assert.rejects(host.nativeRequest(allowed.background,'antigem.uploadFiles',{tabId:3,files:[]}),/files is required/);
});
