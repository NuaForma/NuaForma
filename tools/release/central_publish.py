"""Publish one validated deployment only after independently matching all reviewed artifact bytes."""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import urllib.error
import urllib.request
import uuid

from central_portal import BASE, open_request
from verify_libraries import MODULES


def publish(identity, review, token, opener=open_request):
    assert str(uuid.UUID(identity)) == identity
    assert review["version"] == "0.1.0-alpha.1"
    headers = {"Authorization": "Bearer " + token}
    try:
        request = urllib.request.Request(BASE + "status?id=" + identity, data=b"", headers=headers, method="POST")
        with opener(request, timeout=60) as response:
            data = response.read(1024 * 1024 + 1)
            assert len(data) <= 1024 * 1024
            status = json.loads(data)
        assert status["deploymentId"] == identity and status["deploymentState"] == "VALIDATED", "Deployment is not awaiting publication"
        assert set(status["purls"]) == {"pkg:maven/dev.nuaforma/" + module + "@0.1.0-alpha.1" for module in MODULES}
        for name, expected in review["libraryHashes"].items():
            module = next(module for module in MODULES if name.startswith(module + "-0.1.0-alpha.1." ) or name.startswith(module + "-0.1.0-alpha.1-"))
            relative = "dev/nuaforma/" + module + "/0.1.0-alpha.1/" + name
            request = urllib.request.Request(BASE + "deployment/" + identity + "/download/" + relative, headers=headers)
            with opener(request, timeout=60) as response:
                data = response.read(128 * 1024 * 1024 + 1)
                assert len(data) <= 128 * 1024 * 1024 and hashlib.sha256(data).hexdigest() == expected, "Staged artifact differs from review"
        request = urllib.request.Request(BASE + "deployment/" + identity, data=b"", headers=headers, method="POST")
        with opener(request, timeout=60) as response:
            assert response.status == 204
    except urllib.error.HTTPError as error:
        raise RuntimeError("Central request failed with HTTP " + str(error.code) + "; no automatic retry") from None
    except urllib.error.URLError:
        raise RuntimeError("Central request outcome unknown; inspect Portal before retrying") from None
    return dict(deploymentId=identity, version=review["version"], publishRequested=True, published=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--confirm-publish", required=True, action="store_true")
    args = parser.parse_args()
    assert not args.output.exists()
    identity = os.environ["CENTRAL_DEPLOYMENT_ID"]
    token = base64.b64encode((os.environ["MAVEN_CENTRAL_USERNAME"] + ":" + os.environ["MAVEN_CENTRAL_PASSWORD"]).encode()).decode("ascii")
    result = publish(identity, json.loads(args.review.read_text()), token)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("Publish request accepted; public availability must still be verified")
