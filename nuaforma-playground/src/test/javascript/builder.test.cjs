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
