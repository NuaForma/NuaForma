"""Qualify real isolated PDF/A concurrency, probe reuse and per-document validation locally."""
import argparse
import concurrent.futures
import io
import json
import pathlib
import re
import time

from pypdf import PdfReader
from qualify_service import Runtime, generate, http, template
from verify_isolation_config import verify as verify_isolation


def operation_count(logs, event, operation):
    return sum('event=' + event in line and 'operation="' + operation + '"' in line
               for line in logs.splitlines())


def process_peak(logs):
    active = 0
    peak = 0
    for line in logs.splitlines():
        if 'event=SANDBOX_PROCESS_START ' in line:
            active += 1
            peak = max(peak, active)
        elif 'event=SANDBOX_PROCESS_COMPLETED ' in line:
            active -= 1
            assert active >= 0, 'Incomplete or reordered process lifecycle evidence'
    assert active == 0, 'A sandbox operation did not complete'
    return peak


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--docker', default='docker')
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    results = []
    for parallelism in [1, 2]:
        runtime = Runtime(args, '--env', 'NUAFORMA_PDF_PARALLELISM=' + str(parallelism))
        try:
            base = runtime.connect()
            config = json.loads(runtime.command('inspect', runtime.name))[0]
            verify_isolation(config)
            assert http(base, '/health/ready', timeout=5)[0] == 200
            status, _, payload = http(base, '/api/v1/capabilities')
            assert status == 200
            assert any('pdf' in provider['available']['writeFormats'] for provider in json.loads(payload)['providers'])
            for round_number in [1, 2]:
                previous = runtime.command('logs', runtime.name)
                if round_number == 2:
                    time.sleep(31)
                started = time.monotonic()
                def document(index):
                    name = f'NF-PAR-{parallelism}-{round_number}-{index}'
                    status, _, body = generate(base, 'pdf', template(), {'name': name}, {'profile': 'pdfa-3b'})
                    assert status == 200, 'Concurrent PDF/A request failed: ' + str(status)
                    pdf = PdfReader(io.BytesIO(body))
                    text = '\n'.join(page.extract_text() or '' for page in pdf.pages)
                    assert name in text
                    assert set(re.findall(r'NF-PAR-[0-9]+-[0-9]+-[0-9]+', text)) == {name}
                    (args.output / (name + '.pdf')).write_bytes(body)
                    return len(body)
                health_samples = 0
                with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                    futures = [pool.submit(document, index) for index in range(4)]
                    pending = set(futures)
                    while pending:
                        _, pending = concurrent.futures.wait(pending, timeout=1)
                        assert http(base, '/health/live', timeout=5)[0] == 200
                        assert http(base, '/health/ready', timeout=5)[0] == 200
                        health_samples += 1
                    sizes = [future.result() for future in futures]
                elapsed = time.monotonic() - started
                logs = runtime.command('logs', runtime.name)
                delta = lambda operation: operation_count(logs, 'SANDBOX_PROCESS_START', operation) - operation_count(previous, 'SANDBOX_PROCESS_START', operation)
                probes = delta('probe')
                assert probes <= 2, 'Runtime probes were not deduplicated'
                assert round_number != 2 or probes >= 1, 'Expired verification was not refreshed'
                for operation in ['convert-pdfa-3b', 'validate', 'validate-pdfa-3b']:
                    assert delta(operation) == 4, 'Per-document operation was skipped: ' + operation
                peak = process_peak(logs)
                assert peak == parallelism, 'Configured process ceiling was exceeded or parallelism was not exercised'
                assert http(base, '/health/live', timeout=5)[0] == 200
                assert http(base, '/health/ready', timeout=5)[0] == 200
                runtime.clean()
                state = json.loads(runtime.command('inspect', runtime.name))[0]
                assert not state['State']['OOMKilled'] and state['RestartCount'] == 0
                result = dict(parallelism=parallelism, round=round_number, documents=4,
                              seconds=round(elapsed, 3), probeStarts=probes, pdfBytes=sizes,
                              validations=4, conformanceValidations=4, privateCleanup=True,
                              liveAndReadySamples=health_samples)
                result['observedProcessPeak'] = peak
                results.append(result)
                print(json.dumps(result), flush=True)
        finally:
            runtime.close()
    (args.output / 'qualification.json').write_text(json.dumps(dict(image=args.image, results=results), indent=2))
    print('Real isolated PDF/A concurrency, expiry, tenant references and all validators: PASS', flush=True)


if __name__ == '__main__':
    main()
