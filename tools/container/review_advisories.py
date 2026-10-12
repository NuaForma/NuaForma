"""Query public package coordinates only; retain dated advisory metadata, not a security guarantee."""
import argparse
import concurrent.futures
import datetime
import json
import pathlib
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packages", required=True, type=pathlib.Path)
    parser.add_argument("--application", required=True, type=pathlib.Path)
    parser.add_argument("--validator", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    coordinates = set()
    for record in json.loads(args.packages.read_text())["packages"]:
        if not record["source"].startswith(("libobasis", "libreoffice")):
            coordinates.add(("Debian:13", record["source"], record["sourceVersion"]))
    components = json.loads(args.application.read_text())["components"] + json.loads(args.validator.read_text())
    for record in components:
        if record["group"] != "dev.nuaforma":
            coordinates.add(("Maven", record["group"] + ":" + record["artifact"], record["version"]))
    queries = [dict(package=dict(ecosystem=ecosystem, name=name), version=version) for ecosystem, name, version in sorted(coordinates)]
    request = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(dict(queries=queries)).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(16 * 1024 * 1024 + 1)
    assert len(data) <= 16 * 1024 * 1024
    results = json.loads(data)["results"]
    assert len(results) == len(queries)
    records = [dict(query=query, result=result) for query, result in zip(queries, results)]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    assert not args.output.exists()
    args.output.write_text(json.dumps(dict(checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                         records=records, approved=False,
                                         limitation="Coordinate-based OSV results do not cover unindexed or hidden components; review renderer/JVM advisories separately."), indent=2), encoding="utf-8")
    identifiers = sorted({finding["id"] for record in records for finding in record["result"].get("vulns", [])})
    def detail(identity):
        assert identity.startswith("DEBIAN-CVE-") and all(char.isalnum() or char == "-" for char in identity)
        with urllib.request.urlopen("https://api.osv.dev/v1/vulns/" + identity, timeout=45) as response:
            value = response.read(2 * 1024 * 1024 + 1)
        assert len(value) <= 2 * 1024 * 1024
        return json.loads(value)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        details = list(executor.map(detail, identifiers))
    args.output.with_suffix(".details.json").write_text(json.dumps(details, indent=2), encoding="utf-8")
    print(json.dumps(dict(queries=len(records), advisoryIds=len(identifiers), affectedPackages=sum(bool(record["result"].get("vulns")) for record in records))))


if __name__ == "__main__":
    main()
