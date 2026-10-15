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
  const api = vm.runInContext('({buildRequest, resourceFiles, fontDeclarations, matchingProviders, sharedDefinitions, prettyJson})', context);
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
test('font resources cannot collide with manually added resource files',()=>{
  const {api,node}=fixture(); node('font-selections').children=[upload()];
  node('resources').children=[{querySelector:key=>key==='[data-resource-id]'?{value:'barcode'}:{files:[{name:'other.bin'}]}}];
  assert.throws(()=>api.resourceFiles(),/unique/);
});
