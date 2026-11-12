const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function fixture() {
  const nodes = new Map();
  const node = id => {
    if (!nodes.has(id)) nodes.set(id, {value:'', checked:false, children:[], checkValidity:() => true});
    return nodes.get(id);
  };
  node('input-format').value = 'docx'; node('output-format').value = 'pdf';
  node('filename').value = 'document'; node('data').value = '{"integer":900719925474099312345,"decimal":0.12345678901234567890,"nested":[null,{"a":true}]}';
  node('time-zone').value = 'UTC'; node('font-mode').value = 'embed';
  const context = vm.createContext({document:{getElementById:node}, JSON, Error, Set, Boolean, Number});
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/playground/app.js'), 'utf8');
  vm.runInContext(source.slice(0, source.indexOf("$('builder').addEventListener")), context);
  const api = vm.runInContext('({buildRequest, resourceFiles, fontDeclarations, matchingProviders, sharedDefinitions, prettyJson, formatDiagnostics, showResult, conformancePolicyText, renderConformancePolicy})', context);
  const option = {name:'fonts',type:'ARRAY',description:'Fonts',required:false,defaultValue:null,allowedValues:[]};
  const providers = [
    {id:'word',available:{readFormats:['docx'],writeFormats:['docx']},inputOptions:{},outputOptions:{docx:[option]},generalOptions:[],fonts:{status:'AVAILABLE',entries:[]}},
    {id:'pdf',available:{readFormats:['docx'],writeFormats:['docx','pdf']},inputOptions:{},outputOptions:{docx:[option],pdf:[option]},generalOptions:[],fonts:{status:'AVAILABLE',entries:[]}},
  ];
  const set = (name, value) => { context.fixtureValue = value; vm.runInContext(`${name} = fixtureValue`, context); };
  set('capabilities',{providers});
  return {node,api,set,providers};
}
function upload(family='Libre Barcode 39',resource='barcode',file={name:'barcode.ttf'}) {
  const controls = {'[data-font-family]':{value:family},'[data-font-face]':{value:'regular'},'[data-font-resource]':{value:resource},'[type=file]':{files:file?[file]:[]}};
  return {querySelector:key=>controls[key]};
}

test('operator conformance policy is visible, independent of availability and never exported as an override',()=>{
  const {api,node,providers}=fixture();
  for (const enabled of [true,false]) {
    providers[1].conformancePolicies=[{outputFormat:'pdf',profiles:['pdfa-1b','pdfa-2b','pdfa-3b'],validationEnabled:enabled}];
    api.renderConformancePolicy();
    assert.equal(node('conformance-policy').hidden,false);
    assert.equal(node('conformance-policy').textContent.includes('PDF/A conformance is not checked by NuaForma'),!enabled);
    assert.equal(node('conformance-policy').textContent.includes('validation enabled'),enabled);
    assert.equal(api.matchingProviders().length,1);
    const request=api.buildRequest();
    for(const name of ['conformancePolicies','validationEnabled','pdfaValidation','NUAFORMA_PDFA_VALIDATION']) assert.equal(request.includes(name),false);
  }
  for(const metadata of [undefined,null,[],[{outputFormat:'pdf',profiles:['pdfa-3b'],validationEnabled:'false'}]]) {
    providers[1].conformancePolicies=metadata;
    api.renderConformancePolicy();
    assert.ok(node('conformance-policy').textContent.includes('policy unknown'));
    assert.equal(node('conformance-policy').textContent.includes('is not checked'),false);
  }
  node('output-format').value='docx'; api.renderConformancePolicy();
  assert.equal(node('conformance-policy').hidden,true);
});

test('automatic unique matching omits providerId and preserves exact JSON literals',()=>{
  const {api}=fixture(); const text=api.buildRequest(); const request=JSON.parse(text);
  assert.equal(Object.hasOwn(request,'providerId'),false);
  assert.equal(api.matchingProviders()[0].id,'pdf');
  assert.ok(api.prettyJson(text).includes('900719925474099312345'));
  assert.ok(text.includes('0.12345678901234567890'));
  assert.equal(request.data.nested[0],null);
});
test('explicit selection adds providerId and rejects an unavailable pair',()=>{
  const {api,node}=fixture(); node('provider').value='pdf';
  assert.equal(JSON.parse(api.buildRequest()).providerId,'pdf');
  node('provider').value='word'; assert.throws(()=>api.buildRequest(),/No usable format pair/);
});
test('ambiguous matching retains both candidates rather than silently picking one',()=>{
  const {api,node}=fixture(); node('output-format').value='docx';
  assert.equal(api.matchingProviders().length,2);
  assert.equal(Object.hasOwn(JSON.parse(api.buildRequest()),'providerId'),false);
});
test('multiple candidates expose only identical shared option definitions',()=>{
  const {api,node,providers}=fixture(); node('output-format').value='docx';
  assert.equal(api.sharedDefinitions(providers,'output').length,1);
  providers[1].outputOptions.docx[0]={...providers[1].outputOptions.docx[0],description:'Different semantics'};
  assert.equal(api.sharedDefinitions(providers,'output').length,0);
});
test('font upload generates matching declarations and binary resources',()=>{
  const {api,node}=fixture(); const row=upload(); node('font-selections').children=[row];
  const request=JSON.parse(api.buildRequest());
  assert.deepEqual(request.output.options,{fonts:[{family:'Libre Barcode 39',face:'regular',resource:'barcode'}],fontMode:'embed'});
  assert.equal(api.resourceFiles()[0].id,'barcode'); assert.equal(api.resourceFiles()[0].file.name,'barcode.ttf');
  node('font-mode').value='validate'; assert.equal(JSON.parse(api.buildRequest()).output.options.fontMode,'validate');
});
test('automatic used font embedding exports the approved options without a font list and retains explicit overrides',()=>{
  const {api,node,providers}=fixture(); node('output-format').value='docx';
  const selection={name:'fontSelection',type:'STRING',allowedValues:['declared','used']};
  providers.forEach(provider=>provider.outputOptions.docx.push(selection));
  node('auto-embed-fonts').checked=true;
  assert.deepEqual(JSON.parse(api.buildRequest()).output.options,{fontMode:'embed',fontSelection:'used'});
  node('font-selections').children=[upload()]; node('font-mode').value='validate';
  assert.deepEqual(JSON.parse(api.buildRequest()).output.options,{fontMode:'embed',fontSelection:'used',fonts:[{family:'Libre Barcode 39',face:'regular',resource:'barcode'}]});
  node('output-format').value='pdf';
  assert.throws(()=>api.buildRequest(),/Automatic font embedding is not supported/);
});
test('mounted fonts need declarations but no upload; bundled fonts need neither',()=>{
  const {api,node,set}=fixture();
  const mounted={family:'Custom',face:'regular',source:'mounted',resource:'mounted-font'};
  const bundled={family:'Bundled',face:'regular',source:'bundled',resource:null};
  set('availableFonts',[mounted,bundled]); node('font-selections').children=[{_catalogFont:mounted},{_catalogFont:bundled}];
  assert.equal(JSON.parse(api.buildRequest()).output.options.fonts.length,1);
  assert.equal(api.resourceFiles().length,0);
  node('font-selections').children=[{_catalogFont:bundled}];
  assert.equal(Object.hasOwn(JSON.parse(api.buildRequest()).output,'options'),false);
});
test('incomplete duplicate invalid and stale font declarations fail explicitly',()=>{
  const {api,node,set}=fixture();
  for(const rows of [[upload('')],[upload('Family','bad/id')],[upload('Family','a',null)],[upload(),upload()],[upload('Family','same'),upload('Other','same')]]) {
    node('font-selections').children=rows; assert.throws(()=>api.buildRequest());
  }
  node('font-selections').children=[{_catalogFont:{family:'Stale',face:'regular',resource:'stale',source:'mounted'}}];
  set('availableFonts',[]); assert.throws(()=>api.buildRequest(),/not available/);
});

test('recovered catalog exports surviving mounted font and rejects a selection lost after restart',()=>{
  const {api,node,set}=fixture();
  const surviving={family:'Surviving',face:'regular',resource:'nf-mounted-surviving',source:'mounted'};
  const rejected={family:'Rejected',face:'regular',resource:'nf-mounted-rejected',source:'mounted'};
  set('availableFonts',[surviving]);
  node('font-selections').children=[{_catalogFont:surviving}];
  assert.deepEqual(JSON.parse(api.buildRequest()).output.options.fonts,[{family:'Surviving',face:'regular',resource:'nf-mounted-surviving'}]);
  assert.equal(api.resourceFiles().length,0);
  node('font-selections').children=[{_catalogFont:rejected}];
  assert.throws(()=>api.buildRequest(),/not available/);
  node('font-selections').children=[{_catalogFont:surviving}];
  set('availableFonts',[]);
  assert.throws(()=>api.buildRequest(),/not available/);
});
test('font resources cannot collide with manually added resource files',()=>{
  const {api,node}=fixture(); node('font-selections').children=[upload()];
  node('resources').children=[{querySelector:key=>key==='[data-resource-id]'?{value:'barcode'}:{files:[{name:'other.bin'}]}}];
  assert.throws(()=>api.resourceFiles(),/unique/);
});

test('rejection advice and coordinates render literally and older restricted errors remain readable',()=>{
  const {api,node}=fixture();
  Object.defineProperty(node('result'),'innerHTML',{set(){throw Error('HTML injection');}});
  const text=api.formatDiagnostics([{code:'INVALID_TEMPLATE',message:'Unsupported <script>template</script>',part:'word/document.xml',construct:'attachedTemplate',location:'xml:4:8',remediation:'Remove the linked template.'}]);
  api.showResult(text);
  assert.equal(node('result').textContent,'INVALID_TEMPLATE: Unsupported <script>template</script>\nPart: word/document.xml\nConstruct: attachedTemplate\nLocation: xml:4:8\nTry: Remove the linked template.');
  assert.equal(node('result').hidden,false);
  assert.equal(api.formatDiagnostics([{code:'INVALID_TEMPLATE',message:'Restricted diagnostic'}]),'INVALID_TEMPLATE: Restricted diagnostic');
  assert.equal(api.formatDiagnostics(Array(1000).fill({code:'INVALID_TEMPLATE',message:'Bounded'})).split('\n\n').length,64);
});

test('syntax excerpts show a literal expression and bounded error marker',()=>{
  const {api,node}=fixture();
  Object.defineProperty(node('result'),'innerHTML',{set(){throw Error('HTML injection');}});
  const text=api.formatDiagnostics([{code:'INVALID_TEMPLATE',message:'Missing operand',part:'word/header1.xml',
    documentLocation:'paragraph:2',expression:'{trim(name,)}',expressionPosition:'11',expressionStart:'0'}]);
  api.showResult(text);
  assert.match(node('result').textContent,/Document location: paragraph:2/);
  assert.match(node('result').textContent,/Expression:\n\{trim\(name,\)\}\n {11}\^/);
  assert.match(node('result').textContent,/Error position: 11/);
  assert.doesNotThrow(()=>api.formatDiagnostics([{code:'INVALID_TEMPLATE',message:'Bounded',expression:'x',expressionPosition:'999999999'}]));
});

test('diagnostic download reuses the failed upload and preserves the original error on success or unsupported rendering',async()=>{
  for(const succeeds of [true,false]) {
    const nodes=new Map();
    const make=()=>({children:[],events:{},textContent:'',append(...children){this.children.push(...children);},
      addEventListener(name,callback){this.events[name]=callback;},click(){this.clicked=true;}});
    const node=id=>{if(!nodes.has(id))nodes.set(id,make());return nodes.get(id);};
    node('result').textContent='Original parser error: {broken';
    const failedUpload={originalFile:'snapshot-before-builder-edits'};
    let sent;
    const context=vm.createContext({document:{getElementById:node,createElement:make},AbortController,
      URL:{createObjectURL:()=> 'blob:diagnostic',revokeObjectURL:()=>{}},
      fetch:async(url,options)=>{sent={url,options};return {ok:succeeds,status:succeeds?200:422,
        headers:{get:name=>({'X-Request-ID':'diagnostic-request','NuaForma-Diagnostic-Artifact':'template-error','Content-Type':'application/pdf'}[name])},
        blob:async()=>({size:1024}),text:async()=>JSON.stringify({diagnostics:[{code:'UNSUPPORTED_INPUT',message:'Unsupported source drawing'}]})};},
      JSON,Error,Set,Boolean,Number});
    const source=fs.readFileSync(path.join(__dirname,'../../main/resources/playground/app.js'),'utf8');
    vm.runInContext(source.slice(0,source.indexOf("$('builder').addEventListener")),context);
    vm.runInContext('routes={diagnoseTemplate:"/api/v1/diagnostics/template"}; updateRequest=()=>true;',context);
    const offer=vm.runInContext('offerTemplateDiagnostic',context);
    offer(failedUpload);
    const button=node('result').children.find(child=>child.type==='button');
    await button.events.click();
    assert.equal(sent.url,'/api/v1/diagnostics/template');
    assert.equal(sent.options.body,failedUpload);
    assert.equal(sent.options.credentials,'same-origin');
    assert.equal(node('result').textContent,'Original parser error: {broken');
    const status=node('result').children.at(-1);
    assert.match(status.textContent,succeeds?/generation remains unsuccessful/:/Diagnostic PDF unavailable/);
    if(succeeds) {
      const link=status.children.find(child=>child.download==='template-diagnostic.pdf');
      assert.equal(link.clicked,true);
    } else assert.match(status.textContent,/Unsupported source drawing/);
    assert.equal(button.disabled,false);
    assert.equal(node('cancel').hidden,true);
  }
});


test('native source diagnostics show the concrete construct path and excerpt as literal text',()=>{
  const {api}=fixture();
  const text=api.formatDiagnostics([{code:'UNSUPPORTED_INPUT',message:'Native profile',sourceExplanation:'Paragraph borders are unsupported',
    sourceConstruct:'w:pBdr',sourcePath:'/w:document[1]/w:body[1]/w:p[2]/w:pPr[1]/w:pBdr[1]',sourceExcerpt:'Nearby <private> text'}]);
  assert.match(text,/Cause: Paragraph borders are unsupported/);
  assert.match(text,/Unsupported element: w:pBdr/);
  assert.match(text,/XML path \(table\/row\/cell\): \/w:document/);
  assert.match(text,/Nearby text: Nearby <private> text/);
});

test('PDF render failures display their concrete cause without inventing source coordinates',()=>{
  const {api}=fixture();
  for (const cause of ['CONTENT_OVERFLOW: Paragraph indentation exceeds the physical page',
    'CONTENT_OVERFLOW: Word and leading spaces exceed line width; required=900.1234 pt; available=468.0 pt',
    'MISSING_GLYPH: The supplied font cannot encode requested text']) {
    const text=api.formatDiagnostics([{code:'UNSUPPORTED_INPUT',reason:'NATIVE_PDF_RENDER_REJECTED',
      message:'The native PDF engine rejected content, fonts or the requested output',sourceExplanation:cause}]);
    assert.ok(text.includes(`Cause: ${cause}`));
    assert.ok(!text.includes('Document location:'));
    assert.ok(!text.includes('Unsupported element:'));
    assert.ok(!text.includes('Part:'));
  }
});

test('DOCX diagnostic buttons keep the failed request and separate preview from normal generation',async()=>{
  for(const preview of [false,true]) {
    const nodes=new Map();
    const make=()=>({children:[],events:{},textContent:'',append(...children){this.children.push(...children);},
      replaceChildren(...children){this.children=children;},addEventListener(name,callback){this.events[name]=callback;}});
    const node=id=>{if(!nodes.has(id))nodes.set(id,make());return nodes.get(id);};
    node('result').textContent='Original unsupported document error';
    const original={originalFile:'captured-upload'};
    let sent; const revoked=[];
    const context=vm.createContext({document:{getElementById:node,createElement:make},AbortController,
      URL:{createObjectURL:()=> 'blob:docx',revokeObjectURL:url=>revoked.push(url)},
      fetch:async(url,options)=>{sent={url,options};return {ok:true,
        headers:{get:name=>({'NuaForma-Diagnostic-Artifact':'document-error','Content-Type':'application/vnd.openxmlformats-officedocument.wordprocessingml.document'}[name])},
        blob:async()=>({size:1024})};},JSON,Error,Set,Boolean,Number});
    const source=fs.readFileSync(path.join(__dirname,'../../main/resources/playground/app.js'),'utf8');
    vm.runInContext(source.slice(0,source.indexOf("$('builder').addEventListener")),context);
    vm.runInContext('downloadUrl="blob:existing-pdf"; routes={diagnoseDocument:"/api/v1/diagnostics/document"}; updateRequest=()=>true;',context);
    vm.runInContext('offerDocumentDiagnostic',context)(original);
    const buttons=node('result').children.filter(child=>child.type==='button');
    await buttons[preview?1:0].events.click();
    assert.equal(sent.url,`/api/v1/diagnostics/document?preview=${preview}`);
    assert.equal(sent.options.body,original);
    assert.equal(sent.options.credentials,'same-origin');
    assert.equal(node('result').textContent,'Original unsupported document error');
    const status=node('result').children[0];
    assert.equal(status.children.at(-1).download,preview?'diagnostic-preview.docx':'document-diagnostic.docx');
    assert.equal(node('cancel').hidden,true);
    assert.deepEqual(revoked,[]);
    vm.runInContext('showResult("Next request")',context);
    assert.deepEqual(revoked,['blob:docx']);
  }
});


function cleanupFixture(respond) {
  const nodes = new Map(), windowEvents = {}, revoked = [], requests = [];
  let downloads = 0;
  const make = () => ({children: [], events: {}, textContent: '', files: [],
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = children; this.textContent = ''; },
    addEventListener(name, callback) { this.events[name] = callback; }});
  const node = id => { if (!nodes.has(id)) nodes.set(id, make()); return nodes.get(id); };
  node('template').files = [{name: 'synthetic.docx'}];
  node('result').textContent = 'Existing generated document';
  const context = vm.createContext({document: {getElementById: node, createElement: make, documentElement: {dataset: {}}},
    window: {addEventListener(name, callback) { windowEvents[name] = callback; }}, matchMedia: () => ({matches: false}),
    AbortController, FormData: class { constructor() { this.parts = []; } append(...part) { this.parts.push(part); } },
    URL: {createObjectURL: () => `blob:cleanup-${++downloads}`, revokeObjectURL: url => revoked.push(url)},
    fetch: async (url, options) => { requests.push({url, options}); return respond ? respond(options) : {
      ok: true, headers: {get: name => ({'X-Request-ID': 'cleanup-request', 'NuaForma-Template-Cleanup': 'native-structure', 'Content-Type': 'application/zip'}[name])},
      blob: async () => ({size: 123})}; }, JSON, Error, Set, Boolean, Number});
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/playground/app.js'), 'utf8');
  const boundary = source.indexOf("$('builder').addEventListener");
  vm.runInContext(source.slice(0, boundary), context);
  vm.runInContext('updateRequest=()=>true; bootstrap=()=>{}; downloadUrl="blob:generated-document";', context);
  vm.runInContext(source.slice(boundary), context);
  return {node, revoked, requests, windowEvents, context, run: () => vm.runInContext('cleanTemplate()', context)};
}

test('cleanup status and download remain next to the cleanup button and preserve generated downloads', async () => {
  const {node, run, requests, revoked, context, windowEvents} = cleanupFixture();
  await run();
  assert.equal(requests[0].url, '/playground/template-cleanup');
  assert.equal(requests[0].options.credentials, 'same-origin');
  assert.deepEqual(requests[0].options.body.parts.map(part => part[0]), ['template']);
  assert.equal(node('result').textContent, 'Existing generated document');
  assert.equal(node('result').children.length, 0);
  assert.equal(node('cleanup-result').hidden, false);
  assert.match(node('cleanup-result').textContent, /Request ID: cleanup-request/);
  assert.equal(node('cleanup-result').children.at(-1).download, 'cleaned-template.zip');
  assert.equal(node('cleanup-result').children.at(-1).href, 'blob:cleanup-1');
  vm.runInContext('showResult("Another generation result")', context);
  assert.deepEqual(revoked, []);
  await run();
  assert.deepEqual(revoked, ['blob:cleanup-1']);
  windowEvents.pagehide();
  assert.deepEqual(revoked, ['blob:cleanup-1', 'blob:generated-document', 'blob:cleanup-2']);
  const html = fs.readFileSync(path.join(__dirname, '../../main/resources/playground/index.html'), 'utf8');
  assert.match(html, /<details>[^]*id="clean-template"[^]*id="cleanup-result"[^]*<\/details>/);
});

test('cleanup errors cancellation and malformed response stay local and do not offer an old archive', async () => {
  for (const mode of ['http', 'cancel', 'wrong-type']) {
    const fixture = cleanupFixture(async () => {
      if (mode === 'cancel') throw Object.assign(new Error('cancelled'), {name: 'AbortError'});
      return {ok: mode !== 'http', status: 422, headers: {get: () => null},
        text: async () => JSON.stringify({diagnostics: [{code: 'UNSUPPORTED_INPUT', message: '<script>unsupported</script>'}]})};
    });
    Object.defineProperty(fixture.node('cleanup-result'), 'innerHTML', {set() { throw Error('HTML injection'); }});
    await fixture.run();
    assert.equal(fixture.node('result').textContent, 'Existing generated document');
    assert.equal(fixture.node('cleanup-result').hidden, false);
    assert.equal(fixture.node('cleanup-result').children.length, 0);
    assert.match(fixture.node('cleanup-result').textContent, mode === 'cancel' ? /cancelled/ : mode === 'http' ? /<script>unsupported<\/script>/ : /archive/);
  }
});

test('changing the template revokes cleanup downloads and ignores an old in-flight response', async () => {
  let release;
  const fixture = cleanupFixture(async () => ({ok: true, headers: {get: name => ({'NuaForma-Template-Cleanup': 'native-structure', 'Content-Type': 'application/zip'}[name])},
    blob: () => new Promise(resolve => { release = resolve; })}));
  const pending = fixture.run();
  await new Promise(resolve => setImmediate(resolve));
  fixture.node('template').files = [{name: 'replacement.docx'}];
  fixture.node('template').events.change();
  release({size: 123});
  await pending;
  assert.equal(fixture.node('cleanup-result').hidden, true);
  assert.equal(fixture.node('cleanup-result').children.length, 0);
  assert.deepEqual(fixture.revoked, []);
  const completed = cleanupFixture();
  await completed.run();
  completed.node('template').events.change();
  assert.deepEqual(completed.revoked, ['blob:cleanup-1']);
  assert.equal(completed.node('cleanup-result').hidden, true);
});
