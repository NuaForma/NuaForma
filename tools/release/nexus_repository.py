"""Publish exact reviewed prerelease bundle bytes to Nexus. No rebuild or write retry.

Keep this dependency-free protocol implementation in sync across both NuaForma repositories.
Snapshots use Gradle's native Maven publisher, not this immutable-bundle uploader.
"""
import base64
import datetime
import hashlib
import io
import json
import pathlib
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

from maven_destination import PRERELEASES, SNAPSHOTS, destination, require

SUFFIXES = (".pom", ".jar", "-sources.jar", "-javadoc.jar")
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")
LIMIT = 128 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, url):
        raise urllib.error.HTTPError(request.full_url, code, "Redirect denied", headers, file)


def open_request(request, timeout):
    return urllib.request.build_opener(NoRedirect()).open(request, timeout=timeout)


def token(username, password):
    require(bool(username and password) and ":" not in username and not any(c in username + password for c in "\r\n"),
            "Nexus username/password are required as process environment variables")
    return base64.b64encode((username + ":" + password).encode()).decode("ascii")


def request(relative, method="GET", data=None, authorization=None, headers=None, opener=open_request, missing=False, repository=PRERELEASES):
    require(repository in (PRERELEASES, SNAPSHOTS), "Untrusted Nexus destination")
    require(not relative.startswith("/") and not any(c in relative for c in ("..", "?", "#", "%", "\\")), "Unsafe Maven repository path")
    fields = {"Cache-Control": "no-cache", "Content-Type": "application/octet-stream", **(headers or {})}
    if authorization:
        fields["Authorization"] = "Basic " + authorization
    operation = urllib.request.Request(repository + relative, data=data, method=method, headers=fields)
    try:
        with opener(operation, timeout=60) as response:
            require(response.status in ((200, 201, 204) if method == "PUT" else (200,)), "Unexpected Nexus response status")
            body = response.read(LIMIT + 1)
            require(len(body) <= LIMIT, "Nexus response exceeds its bound")
            return body, response.headers
    except urllib.error.HTTPError as error:
        if missing and error.code == 404:
            return None, {}
        raise RuntimeError("Nexus operation failed with HTTP " + str(error.code) + "; inspect the receipt before any new publication") from None
    except (urllib.error.URLError, OSError):
        raise RuntimeError("Nexus operation outcome is uncertain; inspect the receipt, never automatically retry a write") from None


def entries(bundle, version, modules, approved_hashes, bundle_sha256):
    require(destination(version)["kind"] == "prerelease", "Immutable Nexus uploads require a prerelease version")
    require(bundle.is_file() and not bundle.is_symlink() and 0 < bundle.stat().st_size <= 512 * 1024 * 1024, "Unsafe Maven bundle")
    reviewed_bytes = bundle.read_bytes()
    require(hashlib.sha256(reviewed_bytes).hexdigest() == bundle_sha256, "Reviewed bundle bytes changed")
    bases = {f"dev/nuaforma/{module}/{version}/{module}-{version}{suffix}" for module in modules for suffix in SUFFIXES}
    require(set(approved_hashes) == {pathlib.PurePosixPath(name).name for name in bases}, "Require every approved Maven artifact exactly once")
    expected = {name + suffix for name in bases for suffix in ("", ".asc", *("." + c for c in CHECKSUMS))}
    with zipfile.ZipFile(io.BytesIO(reviewed_bytes)) as archive:
        infos = archive.infolist()
        require(len(infos) == len(expected) and {i.filename for i in infos} == expected, "Unexpected, missing or duplicate Maven bundle entries")
        require(all(not i.is_dir() and 0 < i.file_size <= LIMIT for i in infos)
                and sum(i.file_size for i in infos) <= 512 * 1024 * 1024, "Unsafe expanded bundle size")
        result = {i.filename: archive.read(i) for i in infos}
    for name in bases:
        data = result[name]
        require(hashlib.sha256(data).hexdigest() == approved_hashes[pathlib.PurePosixPath(name).name], "Artifact differs from the reviewed bytes")
        require(result[name + ".asc"].startswith(b"-----BEGIN PGP SIGNATURE-----"), "Require the previously verified detached signature")
        for algorithm in CHECKSUMS:
            require(result[name + "." + algorithm].decode("ascii").strip().lower() == hashlib.new(algorithm, data).hexdigest(), "Bundle checksum differs from artifact bytes")
    return result


def updated_metadata(data, module, version):
    if data is None:
        root = ET.Element("metadata")
        ET.SubElement(root, "groupId").text = "dev.nuaforma"
        ET.SubElement(root, "artifactId").text = module
    else:
        require(len(data) <= 1024 * 1024 and b"<!DOCTYPE" not in data.upper() and b"<!ENTITY" not in data.upper(), "Unsafe existing Maven metadata")
        root = ET.fromstring(data)
        require(root.tag == "metadata" and root.findtext("groupId") == "dev.nuaforma"
                and root.findtext("artifactId") == module, "Existing Maven metadata has different coordinates")
    versioning = root.find("versioning")
    if versioning is None:
        versioning = ET.SubElement(root, "versioning")
    versions = versioning.find("versions")
    if versions is None:
        versions = ET.SubElement(versioning, "versions")
    existing = [item.text for item in versions.findall("version")]
    require(version not in existing, "Version is already listed in Maven metadata; inspect instead of retrying")
    ET.SubElement(versions, "version").text = version
    for field, value in (("latest", version), ("release", version),
                         ("lastUpdated", datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M%S"))):
        child = versioning.find(field)
        if child is None:
            child = ET.SubElement(versioning, field)
        child.text = value
    # Emit Maven's canonical field order even when creating a new module index.
    ordered = [versioning.find(field) for field in ("latest", "release", "snapshot", "versions", "lastUpdated")]
    for child in ordered:
        if child is not None:
            versioning.remove(child)
            versioning.append(child)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def publish_bundle(bundle, version, modules, approved_hashes, bundle_sha256, authorization, receipt, opener=open_request):
    payloads = entries(bundle, version, modules, approved_hashes, bundle_sha256)
    # Preflight every path and all metadata before the first mutation. Anonymous reads
    # also ensure the promised download access is available; only a real 404 is absence.
    for name in sorted(payloads):
        data, _ = request(name, method="HEAD", opener=opener, missing=True)
        require(data is None, "Immutable Maven version already exists; do not overwrite or blindly retry")
    metadata = {}
    for module in modules:
        name = f"dev/nuaforma/{module}/maven-metadata.xml"
        data, headers = request(name, opener=opener, missing=True)
        conditional = {"If-None-Match": "*"} if data is None else ({"If-Match": headers["ETag"]} if headers.get("ETag") else {})
        metadata[name] = (updated_metadata(data, module, version), conditional)
    indexes = {}
    for name, (data, conditional) in metadata.items():
        indexes[name] = (data, conditional)
        for algorithm in CHECKSUMS:
            indexes[name + "." + algorithm] = ((hashlib.new(algorithm, data).hexdigest() + "\n").encode(), {})
    record = {"version": version, "repository": PRERELEASES, "bundleSha256": bundle_sha256,
              "state": "publishing", "uploaded": [], "pending": None, "publicBytesMatch": False}
    receipt.parent.mkdir(parents=True, exist_ok=True)
    with receipt.open("x", encoding="utf-8") as target:
        target.write(json.dumps(record, indent=2) + "\n")

    def save():
        receipt.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")

    for name, (data, conditional) in [(name, (data, {})) for name, data in sorted(payloads.items())] + list(indexes.items()):
        record["pending"] = name
        save()
        request(name, method="PUT", data=data, authorization=authorization, headers=conditional, opener=opener)
        record["uploaded"].append({"file": name, "sha256": hashlib.sha256(data).hexdigest()})
        record["pending"] = None
        save()
    record["state"] = "verifying"
    save()
    for name, expected in payloads.items():
        data, _ = request(name, opener=opener)
        require(data == expected, "Anonymous Nexus bytes differ from the reviewed bundle; inspect, do not upload again")
    for name, (expected, _) in indexes.items():
        data, _ = request(name, opener=opener)
        require(data == expected, "Published Maven index/checksum differs from the uploaded metadata; inspect before retrying")
    record.update(state="published", publicBytesMatch=True, artifacts=len(approved_hashes))
    save()
    return record
