const $ = id => document.getElementById(id);
const elements = {provider: $('provider'), input: $('input-format'), output: $('output-format')};
let capabilities, contract, routes = {}, optionGroups = [], running, downloadUrl;
const create = (tag, text, className) => { const node = document.createElement(tag); if (text !== undefined) node.textContent = text; if (className) node.className = className; return node; };
const objectText = (value, name) => {
  const parsed = JSON.parse(value);
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw Error(`${name} must be a JSON object.`);
  return value.trim(); // Preserve exact decimal/integer spelling, including values outside JavaScript's safe integer range.
};
const literal = value => JSON.stringify(value);
const rawObject = fields => `{${fields.map(([name, value]) => `${literal(name)}: ${value}`).join(',\n')}}`;
const provider = () => capabilities?.providers.find(item => item.id === elements.provider.value);
const matchingProviders = () => (provider() ? [provider()] : capabilities?.providers || []).filter(item =>
  item.available.readFormats.includes(elements.input.value) && item.available.writeFormats.includes(elements.output.value));
const sharedDefinitions = (items, kind) => {
  const definitions = item => kind === 'general' ? item.generalOptions : item[`${kind}Options`][elements[kind].value] || [];
  return items.length ? definitions(items[0]).filter(definition => items.every(item => definitions(item).some(other => JSON.stringify(other) === JSON.stringify(definition)))) : [];
};
let availableFonts = [], fontSequence = 0;
const selected = (select, values, previous = select.value) => {
  select.replaceChildren(...values.map(value => { const item = create('option', value); item.value = value; return item; }));
  if (values.includes(previous)) select.value = previous;
  select.disabled = values.length === 0;
};
const schema = name => contract.components.schemas[name];
const resolve = value => value?.$ref ? schema(value.$ref.split('/').pop()) : value;
const locationFor = operationId => Object.entries(contract.paths).find(([, operations]) => Object.values(operations).some(operation => operation.operationId === operationId))?.[0];

function configureOptions(target, definitions, kind) {
  if (kind === 'output') definitions = definitions.filter(definition => definition.name !== 'fontSelection');
  const node = $(target); node.replaceChildren();
  if (!definitions.length) return;
  node.append(create('h3', `${kind === 'general' ? 'General' : kind === 'input' ? 'Input' : 'Output'} options`));
  const fields = definitions.map((definition, index) => {
    const row = create('div', undefined, 'field');
    const label = create('label', definition.name + (definition.required ? ' · required' : ''));
    const id = `${target}-${index}`; label.htmlFor = id;
    let control;
    const allowed = definition.allowedValues || [];
    if (allowed.length || definition.type === 'BOOLEAN') {
      control = create('select');
      const blank = create('option', definition.defaultValue == null ? 'Not set' : `Provider default (${literal(definition.defaultValue)})`); blank.value = ''; control.append(blank);
      (allowed.length ? allowed : [true, false]).forEach(value => { const option = create('option', String(value)); option.value = literal(value); control.append(option); });
    } else if (['OBJECT', 'ARRAY'].includes(definition.type)) {
      control = create('textarea'); control.rows = 3; control.spellcheck = false;
      control.placeholder = definition.type === 'OBJECT' ? '{ }' : '[ ]'; control.className = 'code-editor';
    } else {
      control = create('input'); control.type = 'text';
      control.placeholder = definition.defaultValue == null ? 'Not set' : `Provider default: ${literal(definition.defaultValue)}`;
      if (['INTEGER', 'NUMBER'].includes(definition.type)) control.inputMode = 'decimal';
    }
    control.id = id; control.dataset.option = definition.name;
    row.append(label, control, create('span', definition.description || definition.type, 'hint')); node.append(row);
    return {definition, control};
  });
  optionGroups.push({kind, fields});
}

function readOptions(kind) {
  const fields = optionGroups.filter(group => group.kind === kind).flatMap(group => group.fields);
  return rawObject(fields.flatMap(({definition: d, control}) => {
    if (kind === 'output' && $('auto-embed-fonts').checked && ['fontMode', 'fontSelection'].includes(d.name)) return [];
    if (kind === 'output' && ['fontMode', 'fonts'].includes(d.name) && fontDeclarations().length) return [];
    if (!control.value.trim()) {
      if (d.required && d.defaultValue == null) throw Error(`Set ${kind} option ${d.name}.`);
      return [];
    }
    let value;
    if (control.tagName === 'SELECT') value = control.value;
    else if (d.type === 'STRING') value = literal(control.value);
    else {
      value = control.value.trim(); const parsed = JSON.parse(value);
      const valid = d.type === 'OBJECT' ? parsed && typeof parsed === 'object' && !Array.isArray(parsed)
        : d.type === 'ARRAY' ? Array.isArray(parsed)
        : d.type === 'INTEGER' ? /^-?(?:0|[1-9][0-9]*)$/.test(value)
        : d.type === 'NUMBER' ? typeof parsed === 'number' && Number.isFinite(parsed) : false;
      if (!valid) throw Error(`Invalid ${d.type.toLowerCase()} for ${kind} option ${d.name}.`);
    }
    return [[d.name, value]];
  }));
}

function buildRequest() {
  if (!matchingProviders().length) throw Error('No usable format pair is selected.');
  if (!$('filename').checkValidity()) throw Error('Use an ASCII filename with letters, digits, underscores or hyphens, without an extension.');
  const options = [];
  if ($('locale').value) options.push(['locale', literal($('locale').value)]);
  if ($('time-zone').value) options.push(['timeZone', literal($('time-zone').value)]);
  if ($('watermark-enabled').checked) {
    if (!$('watermark-text').value.trim()) throw Error('Watermark text must not be blank.');
    const numeric = id => { const control = $(id); if (!control.value || !control.checkValidity()) throw Error(`Invalid ${id.replaceAll('-', ' ')}.`); return Number(control.value); };
    options.push(['watermark', literal({text: $('watermark-text').value, color: $('watermark-color').value,
      opacity: numeric('watermark-opacity'), rotation: numeric('watermark-rotation')})]);
  }
  const general = readOptions('general'); if (general !== '{}') options.push(['extensions', general]);
  const format = kind => {
    const fields = [['format', literal(elements[kind].value)]];
    let value = readOptions(kind);
    if (kind === 'output') {
      const fonts = fontDeclarations();
      if ($('auto-embed-fonts').checked) {
        if (!sharedDefinitions(matchingProviders(), 'output').some(option => option.name === 'fontSelection' && option.allowedValues?.includes('used'))) throw Error('Automatic font embedding is not supported by this provider/output.');
        const additions = '"fontMode":"embed","fontSelection":"used"';
        value = value === '{}' ? `{${additions}}` : value.slice(0, -1) + ',' + additions + '}';
      }
      if (fonts.length) {
        if (!sharedDefinitions(matchingProviders(), 'output').some(option => option.name === 'fonts')) throw Error('Select a provider/output that supports supplied fonts.');
        const additions = `"fonts":${literal(fonts)}` + ($('auto-embed-fonts').checked ? '' : `,"fontMode":${literal($('font-mode').value)}`);
        value = value === '{}' ? `{${additions}}` : value.slice(0, -1) + ',' + additions + '}';
      }
    }
    if (value !== '{}') fields.push(['options', value]);
    return rawObject(fields);
  };
  return rawObject([['input', format('input')], ['output', format('output')], ...(provider() ? [['providerId', literal(provider().id)]] : []),
    ['filename', literal($('filename').value)], ['data', objectText($('data').value, 'Data')], ['options', rawObject(options)]]);
}

// Reindent without parsing/reserializing values: JSON decimals and large integer literals stay unchanged.
function prettyJson(text) {
  let result = '', depth = 0, quoted = false, escaped = false;
  for (const character of text) {
    if (quoted) { result += character; if (escaped) escaped = false; else if (character === '\\') escaped = true; else if (character === '"') quoted = false; continue; }
    if (character === '"') { quoted = true; result += character; }
    else if (/\s/.test(character)) continue;
    else if (character === '{' || character === '[') { depth++; result += character + '\n' + '  '.repeat(depth); }
    else if (character === '}' || character === ']') { depth--; result += '\n' + '  '.repeat(depth) + character; }
    else if (character === ',') result += ',\n' + '  '.repeat(depth);
    else if (character === ':') result += ': ';
    else result += character;
  }
  return result;
}

function updateRequest() {
  renderConformancePolicy();
  try {
    $('request').textContent = prettyJson(buildRequest()); $('data-status').textContent = 'JSON · object';
    $('export-json').disabled = false; $('export-curl').disabled = false;
    $('generate').disabled = Boolean(running) || (!provider() && matchingProviders().length > 1); $('assess').disabled = Boolean(running);
    return true;
  } catch (error) {
    $('request').textContent = error.message; $('data-status').textContent = 'Complete or correct the fields to preview your request';
    ['export-json', 'export-curl', 'generate', 'assess'].forEach(id => $(id).disabled = true); return false;
  }
}

function conformancePolicyText(items, output) {
  if (output !== 'pdf' || !items.length) return '';
  const descriptions = items.map(item => {
    const policies = item.conformancePolicies;
    if (!Array.isArray(policies)) return `${item.id}: PDF/A conformance policy unknown (not reported).`;
    const archival = policies.filter(policy => policy.outputFormat === output && policy.profiles?.some(profile => profile.startsWith('pdfa-')));
    if (!archival.length || archival.some(policy => typeof policy.validationEnabled !== 'boolean')) return `${item.id}: PDF/A conformance policy unknown (not reported).`;
    return archival.map(policy => `${item.id} (${policy.profiles.join(', ')}): ${policy.validationEnabled
      ? 'PDF/A conformance validation enabled.'
      : 'PDF/A conformance is not checked by NuaForma. The operator is responsible for downstream validation.'}`).join('\n');
  });
  return descriptions.join('\n') + '\nExport profile support and validation policy are separate; capability metadata is not a certificate for an individual file.';
}

function renderConformancePolicy() {
  const target = $('conformance-policy');
  const text = conformancePolicyText(matchingProviders(), elements.output.value);
  target.textContent = text; target.hidden = !text;
  target.className = text.includes('is not checked') ? 'error' : 'availability';
}

function refreshFormats() {
  const items = provider() ? [provider()] : capabilities?.providers || []; optionGroups = [];
  selected(elements.input, [...new Set(items.flatMap(item => item.available.readFormats))].sort());
  selected(elements.output, [...new Set(items.filter(item => item.available.readFormats.includes(elements.input.value)).flatMap(item => item.available.writeFormats))].sort());
  const matches = matchingProviders(), ambiguous = !provider() && matches.length > 1;
  $('profile-summary').textContent = ambiguous ? 'Choose a provider' : matches.length ? 'Available · assess to verify' : 'No usable format pair';
  $('provider-note').textContent = ambiguous ? `Multiple providers support this pair (${matches.map(item => item.id).join(', ')}). Select one above before generating. Only shared options are shown.`
    : !provider() && matches.length === 1 ? `Automatically matched: ${matches[0].id}. providerId is omitted; the server makes the final selection.`
    : items.flatMap(item => item.diagnostics).map(diagnostic => `${diagnostic.code}: ${diagnostic.message}`).join(' · ');
  configureOptions('input-options', sharedDefinitions(matches, 'input'), 'input');
  configureOptions('output-options', sharedDefinitions(matches, 'output'), 'output');
  configureOptions('general-options', sharedDefinitions(matches, 'general'), 'general');
  refreshFonts();
  renderCatalog(); fontSelectionsChanged();
}

function renderCatalog() {
  const target = $('catalog-content'); target.replaceChildren();
  capabilities.diagnostics.forEach(d => target.append(create('p', `${d.code}: ${d.message}`, 'hint')));
  for (const item of capabilities.providers) {
    const section = create('details'); section.open = item.id === elements.provider.value;
    const summary = create('summary', `${item.id} · ${item.releaseVersion}`); section.append(summary);
    section.append(create('p', `Available: ${item.available.readFormats.join(', ') || 'none'} → ${item.available.writeFormats.join(', ') || 'none'}. Declared: ${item.declared.readFormats.join(', ')} → ${item.declared.writeFormats.join(', ')}.`, 'hint'));
    if (item.declared.writeFormats.includes('pdf')) section.append(create('p', conformancePolicyText([item], 'pdf'), 'availability'));
    section.append(create('h3', `Fonts · ${item.fonts.status.toLowerCase()} (${item.fonts.entries.length})`));
    const fonts = create('div', undefined, 'chip-list');
    item.fonts.entries.forEach(font => fonts.append(create('span', `${font.family} · ${font.face} · ${font.source}`, 'chip'))); section.append(fonts);
    for (const [key, title] of [['valuePipes', 'Value pipes · chainable'], ['contentPipes', 'Content pipes · not chainable']]) {
      const catalog = item[key]; section.append(create('h3', `${title} · ${(catalog?.status || 'unsupported').toLowerCase()}`));
      for (const pipe of catalog?.entries || []) {
        const details = create('details'); details.append(create('summary', `${pipe.name}(${pipe.arguments.map(argument => argument.name).join(', ')})`));
        details.append(create('p', pipe.description, 'hint'), create('pre', JSON.stringify(pipe, null, 2))); section.append(details);
      }
      if (!catalog?.entries.length) section.append(create('p', 'No installed extensions reported. Built-in number/date formatters are language features, not extension entries.', 'hint'));
    }
    const raw = create('details'); raw.append(create('summary', 'Inspect complete provider metadata'), create('pre', JSON.stringify(item, null, 2))); section.append(raw); target.append(section);
  }
}

const fontKey = font => JSON.stringify([font.family, font.face, font.source, font.resource]);
function refreshFonts() {
  const matches = matchingProviders();
  availableFonts = matches.length ? (matches[0].fonts?.entries || []).filter(font => matches.every(item =>
    item.fonts?.status === 'AVAILABLE' && item.fonts.entries.some(other => fontKey(other) === fontKey(font)))) : [];
  const select = $('available-font'); select.replaceChildren();
  const placeholder = create('option', availableFonts.length ? 'Choose a family and style…' : 'No shared font catalog available'); placeholder.value = ''; select.append(placeholder);
  availableFonts.forEach((font, index) => { const option = create('option', `${font.family} · ${font.face} · ${font.source}`); option.value = String(index); select.append(option); });
  select.disabled = availableFonts.length === 0; $('select-font').disabled = availableFonts.length === 0;
  const supportsFonts = sharedDefinitions(matches, 'output').some(option => option.name === 'fonts');
  const supportsAutomatic = sharedDefinitions(matches, 'output').some(option => option.name === 'fontSelection' && option.allowedValues?.includes('used'));
  $('auto-embed-fonts').disabled = !supportsAutomatic;
  $('auto-embed-fonts-field').hidden = !supportsAutomatic;
  if (!supportsAutomatic) $('auto-embed-fonts').checked = false;
  $('upload-font').disabled = !supportsFonts;
  $('font-note').textContent = supportsFonts ? 'Automatic embedding selects used supported faces from IMAGE/MOUNT catalogs. Uploads override matching faces; missing faces and unsupported formatting fail clearly. Embedding permissions always apply. For uploads, enter the actual family and style.' : 'Uploaded font declarations are not supported by this selection. Choose a specific provider for its options.';
}

function fontDeclarations() {
  const declarations = [], identities = new Set(), resources = new Set();
  for (const row of $('font-selections').children) {
    if (row._catalogFont) {
      const font = row._catalogFont;
      if (!availableFonts.some(other => fontKey(other) === fontKey(font))) throw Error(`Font ${font.family} is not available for this provider selection. Remove or reselect it.`);
      if (!font.resource) continue;
      declarations.push({family: font.family, face: font.face, resource: font.resource});
    } else {
      const family = row.querySelector('[data-font-family]').value.trim(), face = row.querySelector('[data-font-face]').value;
      const resource = row.querySelector('[data-font-resource]').value.trim();
      if (!family || family.length > 128 || /[\u0000-\u001f\u007f]/.test(family)) throw Error('Enter the actual family name for every uploaded font (at most 128 characters, no controls).');
      if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(resource)) throw Error('Every uploaded font needs a valid logical resource ID.');
      if (!row.querySelector('[type=file]').files[0]) throw Error(`Choose a font file for ${family}.`);
      declarations.push({family, face, resource});
    }
  }
  for (const font of declarations) {
    const identity = JSON.stringify([font.family.toLowerCase(), font.face]);
    if (identities.has(identity) || resources.has(font.resource)) throw Error('Font family/style combinations and font resource IDs must be unique.');
    identities.add(identity); resources.add(font.resource);
  }
  if (declarations.length > 64) throw Error('At most 64 supplied font faces are supported.');
  return declarations;
}

function fontSelectionsChanged() {
  const hasDeclarations = [...$('font-selections').children].some(row => !row._catalogFont || row._catalogFont.resource);
  const automatic = $('auto-embed-fonts').checked;
  $('font-mode-field').hidden = !hasDeclarations || automatic;
  for (const group of optionGroups.filter(group => group.kind === 'output')) for (const {definition, control} of group.fields) {
    if (['fonts', 'fontMode', 'fontSelection'].includes(definition.name)) {
      control.disabled = definition.name === 'fonts' ? hasDeclarations : hasDeclarations || automatic;
      control.title = hasDeclarations ? 'Configured by Fonts for this document. Remove supplied fonts to edit manually.' : '';
    }
  }
  updateRequest();
}

function appendFontRow(font) {
  if ($('font-selections').children.length >= 64) { showResult('At most 64 font selections can be added.'); return; }
  const row = create('fieldset');
  const remove = create('button', 'Remove font', 'text-button'); remove.type = 'button';
  remove.addEventListener('click', () => { row.remove(); fontSelectionsChanged(); });
  if (font) {
    row._catalogFont = font;
    row.append(create('legend', `${font.family} · ${font.face}`), create('p', font.resource ? `Mounted resource: ${font.resource}. No file upload is required.` : 'Bundled font: already available for rendering. No declaration or upload is needed; use this family in your template.', 'hint'), remove);
  } else {
    const number = ++fontSequence;
    row.append(create('legend', `Uploaded font ${number}`));
    const field = (labelText, control) => { const label = create('label', labelText), wrapper = create('div', undefined, 'field'); control.id = `font-${number}-${labelText.toLowerCase().replaceAll(' ', '-')}`; label.htmlFor = control.id; wrapper.append(label, control); row.append(wrapper); };
    const family = create('input'); family.dataset.fontFamily = ''; family.maxLength = 128; family.placeholder = 'e.g. Libre Barcode 39'; field('Font family', family);
    const face = create('select'); face.dataset.fontFace = ''; for (const style of ['regular', 'bold', 'italic', 'boldItalic']) { const option = create('option', style); option.value = style; face.append(option); } field('Font style', face);
    const id = create('input'); id.dataset.fontResource = ''; id.value = `font-${number}`; id.maxLength = 128; field('Font resource ID', id);
    const file = create('input'); file.type = 'file'; file.accept = '.ttf,.otf'; field('Font file', file);
    file.addEventListener('change', fontSelectionsChanged); row.append(remove);
  }
  $('font-selections').append(row); fontSelectionsChanged();
}

async function getJson(url) {
  const response = await fetch(url, {cache: 'no-store', credentials: 'same-origin', signal: AbortSignal.timeout(20000)});
  if (!response.ok) throw Error(`Could not load ${url}: HTTP ${response.status}`);
  return response.json();
}

async function bootstrap() {
  $('refresh').disabled = true; $('bootstrap-error').hidden = true;
  try {
    [contract, capabilities] = await Promise.all([getJson('/openapi.json'), getJson('/api/v1/capabilities')]);
    routes = Object.fromEntries(['generateDocument', 'assessGeneration', 'diagnoseTemplate'].map(operation => [operation, locationFor(operation)]));
    if (!routes.generateDocument || !routes.assessGeneration) throw Error('The running API does not describe generation and assessment operations.');
    const envelope = schema('GenerationEnvelope');
    $('filename').pattern = envelope.properties.filename.pattern;
    const watermark = schema('TextWatermark');
    for (const [name, id] of [['opacity', 'watermark-opacity'], ['rotation', 'watermark-rotation']]) {
      const definition = watermark.properties[name]; $(id).min = definition.minimum; $(id).max = definition.maximum;
    }
    const names = capabilities.providers.map(item => item.id); selected(elements.provider, ['', ...names]);
    elements.provider.options[0].textContent = 'Automatically choose a provider';
    if (!names.length) $('bootstrap-error').textContent = 'This instance has no registered providers. Check the server configuration and internal logs.';
    $('bootstrap-error').hidden = names.length !== 0;
    $('connection').textContent = 'Connected to your instance'; refreshFormats();
    getJson('/api/v1/version').then(version => $('version').textContent = `v${version.version} · contract ${version.contractVersion}`).catch(() => $('version').textContent = 'Version information unavailable');
  } catch (error) { $('connection').textContent = 'Connection needs attention'; $('bootstrap-error').textContent = error.message; $('bootstrap-error').hidden = false; }
  finally { $('refresh').disabled = false; }
}

function resourceFiles() {
  const seen = new Set();
  const files = [...$('font-selections').children].filter(row => !row._catalogFont).map(row => ({
    id: row.querySelector('[data-font-resource]').value.trim(), file: row.querySelector('[type=file]').files[0],
  }));
  for (const font of fontDeclarations()) {
    if (seen.has(font.resource)) throw Error('Duplicate font resource ID.'); seen.add(font.resource);
  }
  return files.concat([...$('resources').children].map(row => {
    const id = row.querySelector('[data-resource-id]').value, file = row.querySelector('[type=file]').files[0];
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(id) || seen.has(id) || !file) throw Error('Every resource needs a unique logical ID and a file.');
    seen.add(id); return {id, file};
  }));
}

function saveFile(bytes, filename, type) {
  const url = URL.createObjectURL(new Blob([bytes], {type})), link = create('a');
  link.href = url; link.download = filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function curlInstructions() {
  const request = buildRequest(); const resources = resourceFiles();
  // Export instructions rather than injecting caller-controlled filenames into a shell command.
  const placeholders = resources.map((resource, index) => `  -F 'resource.${resource.id}=@RESOURCE_FILE_${index + 1};type=application/octet-stream'`);
  return '# NuaForma Playground\n# Save the separately exported request JSON as request.json.\n' +
    '# Replace TEMPLATE_FILE and RESOURCE_FILE_n with your local paths. Keep shell quoting appropriate for your shell.\n' +
    `# API origin used by this page: ${location.origin}\n# Request JSON bytes: ${new TextEncoder().encode(request).length}\n` +
    `curl --fail-with-body '${location.origin}${routes.generateDocument}' \\\n  -F 'request=@request.json;type=application/json' \\\n  -F 'template=@TEMPLATE_FILE;type=application/octet-stream'` +
    (placeholders.length ? ' \\\n' + placeholders.join(' \\\n') : '') + ` \\\n  --output '${$('filename').value}.${elements.output.value === 'pdf' ? 'pdf' : elements.output.value === 'docx' ? 'docx' : 'bin'}'\n`;
}

async function send(generate) {
  if (!updateRequest()) return;
  if (generate && !provider() && matchingProviders().length > 1) { showResult('Multiple providers match. Choose a provider before generating.'); return; }
  let body;
  try {
    const request = buildRequest();
    if (generate) {
      const template = $('template').files[0]; if (!template) throw Error('Choose a template before generating.');
      body = new FormData(); body.append('request', new Blob([request], {type: 'application/json'}), 'request.json');
      body.append('template', template, template.name);
      resourceFiles().forEach(({id, file}) => body.append(`resource.${id}`, file, file.name));
    } else body = request;
  } catch (error) { showResult(error.message); return; }
  running = new AbortController(); $('cancel').hidden = false; updateRequest();
  showResult(generate ? 'Generating your document…' : 'Checking requirements without uploading the template…');
  try {
    const response = await fetch(generate ? routes.generateDocument : routes.assessGeneration,
      {method: 'POST', body, headers: generate ? {} : {'Content-Type': 'application/json'}, signal: running.signal, credentials: 'same-origin'});
    const requestId = response.headers.get('X-Request-ID');
    if (!response.ok) {
      const text = (await response.text()).slice(0, 128000); let details, diagnostics;
      try { diagnostics = JSON.parse(text).diagnostics; details = formatDiagnostics(diagnostics); } catch { details = `HTTP ${response.status}`; }
      showResult(`${details}\nRequest ID: ${requestId || 'not available'}`);
      if (generate && routes.diagnoseTemplate && Array.isArray(diagnostics) && diagnostics.some(item => item.code === 'INVALID_TEMPLATE')) offerTemplateDiagnostic(body);
      return;
    }
    if (!generate) { const assessment = await response.json(); showResult(`Requirements supported by ${assessment.providerId}.\nThis does not validate the template or reserve availability.\nRequest ID: ${requestId}`); return; }
    const bytes = await response.blob(); if (downloadUrl) URL.revokeObjectURL(downloadUrl); downloadUrl = URL.createObjectURL(bytes);
    const disposition = response.headers.get('Content-Disposition') || '';
    const filename = /filename="([A-Za-z0-9_.-]+)"/.exec(disposition)?.[1] || 'document.bin';
    showResult(`Document generated · ${(bytes.size / 1024).toFixed(1)} KB\nRequest ID: ${requestId}`);
    const link = create('a', `Download ${filename}`); link.href = downloadUrl; link.download = filename; $('result').append(create('br'), link);
    const warnings = response.headers.get('NuaForma-Warning-Codes'); if (warnings) $('result').append(create('p', `Warnings: ${warnings}`));
  } catch (error) { showResult(error.name === 'AbortError' ? 'Request cancelled. No output is available.' : error.message); }
  finally { running = null; $('cancel').hidden = true; updateRequest(); }
}
function offerTemplateDiagnostic(failedRequest) {
  const button = create('button', 'Download diagnostic PDF'); button.type = 'button';
  const status = create('p', 'Development only: creates a marked, literal copy of the failed template. Document generation remains unsuccessful.', 'hint');
  $('result').append(create('br'), button, status);
  button.addEventListener('click', async () => {
    if (running) return;
    button.disabled = true; running = new AbortController(); $('cancel').hidden = false; updateRequest();
    status.textContent = 'Creating the diagnostic PDF from the original failed request…';
    try {
      const response = await fetch(routes.diagnoseTemplate, {method: 'POST', body: failedRequest, signal: running.signal, credentials: 'same-origin'});
      const identity = response.headers.get('X-Request-ID') || 'not available';
      if (!response.ok) {
        let details = `HTTP ${response.status}`;
        try { details = formatDiagnostics(JSON.parse((await response.text()).slice(0, 128000)).diagnostics); } catch { /* Retain bounded status fallback. */ }
        throw Error(`Diagnostic PDF unavailable. The source layout or fonts may not be supported.\n${details}\nRequest ID: ${identity}`);
      }
      if (response.headers.get('NuaForma-Diagnostic-Artifact') !== 'template-error' || !response.headers.get('Content-Type')?.startsWith('application/pdf'))
        throw Error('The server did not return a template diagnostic PDF.');
      const bytes = await response.blob();
      if (downloadUrl) URL.revokeObjectURL(downloadUrl);
      downloadUrl = URL.createObjectURL(bytes);
      const link = create('a', 'Download diagnostic PDF'); link.href = downloadUrl; link.download = 'template-diagnostic.pdf';
      status.textContent = `Diagnostic copy ready; generation remains unsuccessful. Request ID: ${identity}`;
      status.append(create('br'), link); link.click();
    } catch (error) { status.textContent = error.name === 'AbortError' ? 'Diagnostic request cancelled. Original parser details remain above.' : error.message; }
    finally { running = null; button.disabled = false; $('cancel').hidden = true; updateRequest(); }
  });
}
function formatDiagnostics(diagnostics) {
  return diagnostics.slice(0, 64).map(item => [
    `${item.code}: ${item.message}`, item.part && `Part: ${item.part}`, item.construct && `Construct: ${item.construct}`,
    item.documentLocation && `Document location: ${item.documentLocation}`,
    item.location && `Location: ${item.location}`,
    item.expression && `Expression${Number(item.expressionStart) > 0 ? ' (excerpt)' : ''}:\n${item.expression}`,
    item.expression && /^\d{1,3}$/.test(item.expressionPosition || '') && Number(item.expressionPosition) <= 256
      && `${' '.repeat(Number(item.expressionPosition))}^`,
    item.expressionPosition && `Error position: ${Number(item.expressionStart || 0) + Number(item.expressionPosition)} (zero-based, within the expression/marker)`,
    item.remediation && `Try: ${item.remediation}`
  ].filter(Boolean).join('\n')).join('\n\n');
}
function showResult(message) { $('result').textContent = message; $('result').hidden = false; }

$('builder').addEventListener('input', updateRequest);
$('builder').addEventListener('submit', event => { event.preventDefault(); send(true); });
$('assess').addEventListener('click', () => send(false));
$('cancel').addEventListener('click', () => running?.abort());
$('provider').addEventListener('change', refreshFormats);
$('input-format').addEventListener('change', refreshFormats);
$('output-format').addEventListener('change', refreshFormats);
$('select-font').addEventListener('click', () => {
  const index = $('available-font').value;
  if (index !== '') appendFontRow(availableFonts[Number(index)]);
});
$('upload-font').addEventListener('click', () => appendFontRow(null));
$('font-mode').addEventListener('change', fontSelectionsChanged);
$('auto-embed-fonts').addEventListener('change', fontSelectionsChanged);
$('template').addEventListener('change', () => $('template-name').textContent = $('template').files[0]?.name || 'Select a file from your computer');
$('watermark-enabled').addEventListener('change', () => $('watermark').hidden = !$('watermark-enabled').checked);
$('refresh').addEventListener('click', bootstrap);
$('sample').addEventListener('click', () => { $('data').value = JSON.stringify({customer: {name: 'Ada Lovelace', email: null}, reference: 'NF-001', tasks: [{name: 'Review agreement', done: false}, {name: 'Check document', done: true}]}, null, 2); updateRequest(); });
$('add-resource').addEventListener('click', () => {
  const row = create('div', undefined, 'resource-row'), id = create('input'), file = create('input'), remove = create('button', 'Remove', 'secondary');
  id.dataset.resourceId = ''; id.placeholder = 'Logical resource ID'; id.setAttribute('aria-label', 'Logical resource ID'); id.maxLength = 128;
  file.type = 'file'; file.setAttribute('aria-label', 'Resource file'); remove.type = 'button'; remove.addEventListener('click', () => row.remove());
  row.append(id, file, remove); $('resources').append(row); id.focus();
});
$('export-json').addEventListener('click', () => { try { saveFile(prettyJson(buildRequest()) + '\n', 'request.json', 'application/json'); } catch (error) { showResult(error.message); } });
$('export-curl').addEventListener('click', () => { try { saveFile(curlInstructions(), 'request-curl.txt', 'text/plain'); } catch (error) { showResult(error.message); } });
let dark = matchMedia('(prefers-color-scheme: dark)').matches;
function setTheme() { document.documentElement.dataset.theme = dark ? 'dark' : 'light'; $('theme').textContent = dark ? 'Switch to light mode' : 'Switch to dark mode'; }
$('theme').addEventListener('click', () => { dark = !dark; setTheme(); }); setTheme();
window.addEventListener('pagehide', () => { running?.abort(); if (downloadUrl) URL.revokeObjectURL(downloadUrl); });
bootstrap();
