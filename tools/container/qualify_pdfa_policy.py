"""Real isolated policy qualification, reused by native production and Playground checks."""
import concurrent.futures
import base64
import hashlib
import io
import json
import pathlib
import re
import subprocess
import uuid
import zipfile

from qualify_service import Runtime, generate, http, multipart, request_id, template, image_fixture


def stage(runtime, source, destination):
    subprocess.run([runtime.args.docker, 'exec', '-i', runtime.name, '/bin/sh', '-c', 'cat > "$1"',
                    'synthetic-policy', destination], input=source.read_bytes(), stdout=subprocess.PIPE,
                   stderr=subprocess.PIPE, check=True, timeout=45)


def inspect(runtime, source, reference, forbidden):
    work = '/tmp/nuaforma-policy-inspection-' + uuid.uuid4().hex
    runtime.command('exec', runtime.name, 'mkdir', '-m', '700', work)
    try:
        remote = work + '/output.pdf'
        stage(runtime, source, remote)
        result = runtime.command('exec', runtime.name, '/opt/java/openjdk/bin/java', '-Xmx256m', '-cp',
                                 '/tmp/pdf-policy-inspector.jar:/opt/nuaforma/pdf-validation/*',
                                 'dev.nuaforma.server.PdfPolicyInspector', remote, reference, forbidden)
        digest = next(line.removeprefix('layoutSha256=') for line in result.splitlines() if line.startswith('layoutSha256='))
        assert re.fullmatch('[0-9a-f]{64}', digest)
        return digest
    finally:
        assert work.startswith('/tmp/nuaforma-policy-inspection-')
        runtime.command('exec', runtime.name, 'rm', '-r', '--', work)


def static_pdf(catalog='', page='', extra=()):
    """Independent minimal PDF syntax for negative safety/conformance cases."""
    objects = [f'<< /Type /Catalog /Pages 2 0 R {catalog} >>',
               '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] {page} >>', *extra]
    result = b'%PDF-1.7\n'
    offsets = []
    for index, value in enumerate(objects, 1):
        offsets.append(len(result))
        result += f'{index} 0 obj\n{value}\nendobj\n'.encode('ascii')
    xref = len(result)
    result += f'xref\n0 {len(objects) + 1}\n0000000000 65535 f \n'.encode()
    result += b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets)
    return result + f'trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()


def validate(runtime, source, mode, version='1.7', succeeds=True):
    work = '/tmp/nuaforma-policy-check-' + uuid.uuid4().hex
    runtime.command('exec', runtime.name, 'mkdir', '-m', '700', work)
    try:
        runtime.command('exec', runtime.name, 'mkdir', *[work + '/' + name for name in ('output', 'fonts', 'home', 'cache', 'profile', 'tmp')])
        stage(runtime, source, work + '/output/input.pdf')
        try:
            result = runtime.command('exec', runtime.name, '/opt/nuaforma/bin/render-pdf', work, '10485760', '30', mode, '268435456', version)
        except subprocess.CalledProcessError:
            assert not succeeds, 'Real isolated validator rejected expected output'
            return
        assert succeeds, 'Real isolated validator accepted a negative fixture'
        if mode.startswith('validate-pdfa-'):
            expected = f'NuaForma PDF/A valid {mode.removeprefix("validate-")} {hashlib.sha256(source.read_bytes()).hexdigest()}'
            assert result == expected, 'Conformance attestation did not bind exact profile and final bytes'
    finally:
        assert work.startswith('/tmp/nuaforma-policy-check-')
        runtime.command('exec', runtime.name, 'rm', '-r', '--', work)


def qualify(args):
    output = args.output / 'pdfa-policy'
    output.mkdir()
    font = pathlib.Path('nuaforma-docx/src/test/resources/fonts/LibreBarcode39-Regular.ttf').resolve()
    classes = output / 'inspector-classes'
    classes.mkdir()
    subprocess.check_call(['javac', '--release', '17', '-d', str(classes),
                           'nuaforma-server/src/test/java/dev/nuaforma/server/PdfPolicyInspector.java'])
    inspector = (output / 'inspector.jar').resolve()
    with zipfile.ZipFile(inspector, 'w') as archive:
        for file in classes.rglob('*.class'):
            archive.write(file, file.relative_to(classes).as_posix())
    source = template(text='Tenant {name}')
    buffer = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as incoming, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as outgoing:
        for name in incoming.namelist():
            data = incoming.read(name)
            if name == 'word/document.xml':
                data = data.replace(b'<w:sectPr/>', b'<w:p><w:r><w:rPr><w:rFonts w:ascii="Libre Barcode 39" w:hAnsi="Libre Barcode 39"/><w:sz w:val="48"/></w:rPr><w:t>*NF001*</w:t></w:r></w:p><w:sectPr/>')
            outgoing.writestr(name, data)
    source = buffer.getvalue()
    results = []
    layouts = {}
    for enabled in (True, False):
        label = 'enabled' if enabled else 'disabled'
        environment = ['--env', 'NUAFORMA_PDFA_VALIDATION=' + str(enabled).lower(),
                       '--mount', f'type=bind,source={font},target=/opt/nuaforma/custom-fonts/barcode.ttf,readonly',
                       '--mount', f'type=bind,source={inspector},target=/tmp/pdf-policy-inspector.jar,readonly']
        runtime = Runtime(args, *environment)
        try:
            base = runtime.connect()
            _, _, raw = http(base, '/api/v1/capabilities')
            providers = json.loads(raw)['providers']
            provider = next(p for p in providers if p['id'] == 'nuaforma-docx-pdf')
            assert 'pdf' in provider['available']['writeFormats']
            assert provider['conformancePolicies'] == [{'outputFormat': 'pdf', 'profiles': ['pdfa-1b', 'pdfa-2b', 'pdfa-3b'], 'validationEnabled': enabled}]
            logs = runtime.command('logs', runtime.name)
            assert ('PDF/A conformance is not checked by NuaForma' in logs) == (not enabled)
            # The probe still checks the genuine veraPDF runtime even with per-document validation off.
            assert 'operation="probe"' in logs
            for profile, version in [('standard', '1.7'), ('pdfa-1b', '1.4'), ('pdfa-2b', '1.7'), ('pdfa-3b', '1.7')]:
                status, headers, body = generate(base, 'pdf', source, {'name': 'POLICY-REFERENCE'}, {'profile': profile})
                assert status == 200 and body.startswith(('%PDF-' + version).encode()), (label, profile, status)
                assert re.search(rb'/BaseFont\s*/[^\s/]*LibreBarcode39', body) and b'/FontFile2' in body
                destination = output / f'{label}-{profile}.pdf'
                destination.write_bytes(body)
                identity = request_id(headers)
                request_logs = '\n'.join(line for line in runtime.command('logs', runtime.name).splitlines() if 'requestId=' + identity in line)
                assert 'operation="validate"' in request_logs
                assert ('operation="validate-' + profile + '"' in request_logs) == (enabled and profile != 'standard')
                # Independent downstream verification is explicit QA, never a fabricated API attestation.
                validate(runtime, destination, 'validate', version)
                if profile != 'standard':
                    validate(runtime, destination, 'validate-' + profile, version)
                layout = inspect(runtime, destination, 'POLICY-REFERENCE', 'OTHER-TENANT')
                if enabled:
                    layouts[profile] = layout
                else:
                    assert layouts[profile] == layout, 'Operator policy changed page rendering'
            for name in ['pdfaValidation', 'validationEnabled', 'NUAFORMA_PDFA_VALIDATION']:
                assert generate(base, 'pdf', source, {}, {name: not enabled})[0] == 422
                envelope = {'input': {'format': 'docx'}, 'output': {'format': 'pdf'}, 'options': {'extensions': {name: not enabled}}}
                boundary, data = multipart(envelope, source)
                assert http(base, '/api/v1/documents', data, 'multipart/form-data; boundary=' + boundary)[0] == 422
            assert generate(base, 'pdf', source, {}, {'profile': 'pdfa-1b', 'version': '1.7'})[0] == 422
            assert generate(base, 'pdf', source, {}, {'profile': 'pdfa-3a'})[0] == 422
            assert generate(base, 'pdf', template(text='{picture | image()}'), {'picture': base64.b64encode(image_fixture(True)).decode()}, {'profile': 'pdfa-1b'})[0] == 422
            negatives = {'structure': b'%PDF-1.7\nbroken\n%%EOF\n',
                         'active': static_pdf(catalog='/OpenAction 4 0 R', extra=['<< /S /JavaScript /JS (danger) >>']),
                         'font': static_pdf(page='/Resources << /Font << /F1 4 0 R >> >>', extra=['<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'])}
            for name, data in negatives.items():
                destination = output / f'negative-{name}.pdf'
                destination.write_bytes(data)
                validate(runtime, destination, 'validate', succeeds=False)
            safe = output / 'nonconforming.pdf'
            safe.write_bytes(static_pdf())
            validate(runtime, safe, 'validate')
            validate(runtime, safe, 'validate-pdfa-3b', succeeds=False)
            def tenant(reference):
                status, headers, body = generate(base, 'pdf', source, {'name': reference})
                assert status == 200, status
                (output / f'{label}-{reference}.pdf').write_bytes(body)
                layout = inspect(runtime, output / f'{label}-{reference}.pdf', reference, 'TENANT-B' if reference == 'TENANT-A' else 'TENANT-A')
                if enabled:
                    layouts[reference] = layout
                else:
                    assert layouts[reference] == layout, 'Operator policy changed tenant page rendering'
                lines = '\n'.join(line for line in runtime.command('logs', runtime.name).splitlines() if 'requestId=' + request_id(headers) in line)
                assert 'operation="validate"' in lines
                assert ('operation="validate-pdfa-3b"' in lines) == enabled
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
                list(executor.map(tenant, ['TENANT-A', 'TENANT-B']))
            assert generate(base, 'docx', template(), {'name': 'UNCHANGED-DOCX'})[0] == 200
            assert http(base, '/health/ready')[0] == 200
            runtime.clean()
            results.append({'validationEnabled': enabled, 'profiles': 4, 'concurrentDocuments': 2,
                            'mandatoryPdfBox': True, 'realNegativeChecks': list(negatives), 'exactDownstreamDigestProfile': True, 'cleanup': True})
        finally:
            runtime.close()
        timed = Runtime(args, *environment, '--env', 'JAVA_TOOL_OPTIONS=-Dnuaforma.server.timeoutMillis=1000')
        try:
            base = timed.connect()
            assert generate(base, 'pdf', template(2000), {'name': 'POLICY-DEADLINE'})[0] == 408
            timed.clean()
        finally:
            timed.close()
    (output / 'qualification.json').write_text(json.dumps({'policies': results, 'identicalPolicyRasters': len(layouts)}, indent=2))
    print('Real PDF/A operator policies, mandatory PDFBox, fonts, concurrency, deadlines, identical page rasters and downstream profile/digest: PASS')
