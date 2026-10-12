"""Explicit USER_MANAGED Central upload; no automatic retries or publication."""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import urllib.error
import urllib.parse
import urllib.request
import uuid

from prepare_central_bundle import release_version

BASE = "https://central.sonatype.com/api/v1/publisher/"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, url):
        raise urllib.error.HTTPError(request.full_url, code, "Redirect denied", headers, file)


def open_request(request, timeout):
    return urllib.request.build_opener(NoRedirect()).open(request, timeout=timeout)


def upload(bundle, version, expected_sha256, token, opener=open_request):
    release_version(version)
    assert version == "0.1.0-alpha.1", "Only the reviewed first alpha is enabled"
    assert 0 < bundle.stat().st_size <= 512 * 1024 * 1024
    data = bundle.read_bytes()
    assert hashlib.sha256(data).hexdigest() == expected_sha256, "Bundle identity changed"
    boundary = "nuaforma-" + uuid.uuid4().hex
    body = ("--" + boundary + '\r\nContent-Disposition: form-data; name="bundle"; filename="nuaforma-central.zip"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode("ascii") + data + ("\r\n--" + boundary + "--\r\n").encode("ascii")
    query = urllib.parse.urlencode({"name": "NuaForma " + version, "publishingType": "USER_MANAGED"})
    request = urllib.request.Request(BASE + "upload?" + query, data=body, method="POST",
        headers={"Authorization": "Bearer " + token, "Content-Type": "multipart/form-data; boundary=" + boundary})
    try:
        with opener(request, timeout=120) as response:
            identity = response.read(4097).decode("ascii").strip()
            assert response.status == 201 and len(identity) <= 4096
    except urllib.error.HTTPError as error:
        raise RuntimeError("Central upload failed with HTTP " + str(error.code) + "; no automatic retry") from None
    except urllib.error.URLError:
        raise RuntimeError("Central upload outcome unknown; inspect the Portal before retrying") from None
    assert str(uuid.UUID(identity)) == identity.lower(), "Unexpected Central deployment identity; inspect Portal before retrying"
    return {"deploymentId": identity, "version": version, "bundleSha256": expected_sha256,
            "publishingType": "USER_MANAGED", "published": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=pathlib.Path, required=True)
    parser.add_argument("--record", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--confirm-upload", action="store_true", required=True)
    args = parser.parse_args()
    assert not args.output.exists(), "Retain the prior deployment record; do not blindly retry"
    record = json.loads(args.record.read_text(encoding="utf-8"))
    username = os.environ.get("MAVEN_CENTRAL_USERNAME")
    password = os.environ.get("MAVEN_CENTRAL_PASSWORD")
    assert username and password, "Central credentials are required"
    token = base64.b64encode((username + ":" + password).encode("utf-8")).decode("ascii")
    result = upload(args.bundle, record["version"], record["sha256"], token)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("Central deployment recorded in USER_MANAGED mode; no publish request sent")


if __name__ == "__main__": main()
