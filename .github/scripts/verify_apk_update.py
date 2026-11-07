#!/usr/bin/env python3
"""Fail closed unless a release APK can update the currently published APK."""

import os
import pathlib
import re
import subprocess
import sys


class CompatibilityError(Exception):
    pass


# Legacy tools print "Signer #1"; SDK 37 prints e.g. "V3.0 Signer:".
# Match only APK signer certificates, never public-key or source-stamp digests.
_SDK_RANGE = r"\(minSdkVersion=\d+(?: \(dev release=true\))?, maxSdkVersion=\d+\)"
_CERTIFICATE = re.compile(
    r"(?:(?:V1|V2|V3\.0|V3\.1) )?Signer"
    r"(?: #\d+)?(?::)?"
    rf"(?: {_SDK_RANGE})?"
    r"(?: #\d+)? certificate SHA-256 digest: ([0-9a-fA-F]{64})"
)


def certificate_digests(output):
    digests = set()
    for line in output.splitlines():
        if " certificate SHA-256 digest:" not in line:
            continue
        if line.startswith("Source Stamp Signer"):
            continue
        match = _CERTIFICATE.fullmatch(line)
        if not match:
            raise CompatibilityError("unrecognized or malformed APK signer certificate output")
        digests.add(match.group(1).lower())
    if not digests:
        raise CompatibilityError("no verified APK signing certificate was found")
    # The same certificate can be reported for multiple signature schemes/SDK ranges.
    return tuple(sorted(digests))


def run_tool(args):
    try:
        result = subprocess.run([str(x) for x in args], capture_output=True, text=True)
    except OSError as error:
        raise CompatibilityError("APK verification tool could not be started") from error
    if result.returncode:
        raise CompatibilityError("APK verification tool failed; release will not be published")
    return result.stdout


def certificates(signer, apk):
    # Parse only after apksigner has successfully verified the APK signature.
    return certificate_digests(run_tool([signer, "verify", "--print-certs", apk]))


def metadata(aapt, apk):
    output = run_tool([aapt, "dump", "badging", apk])
    match = re.search(
        r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",
        output,
        re.MULTILINE,
    )
    if not match:
        raise CompatibilityError("APK package/version metadata was not found")
    return match.group(1), int(match.group(2)), match.group(3)


def verify_update(old_apk, new_apk, signer, aapt, tag):
    if certificates(signer, old_apk) != certificates(signer, new_apk):
        raise CompatibilityError(
            "signing certificates differ; do not rotate or replace the existing signing key"
        )
    old_package, old_code, _ = metadata(aapt, old_apk)
    new_package, new_code, new_version = metadata(aapt, new_apk)
    if old_package != "app.dkord" or new_package != old_package:
        raise CompatibilityError("applicationId must remain app.dkord")
    if new_code <= old_code:
        raise CompatibilityError("versionCode must be higher than the published APK")
    if "v" + new_version != tag:
        raise CompatibilityError("built APK versionName does not match the release tag")
    return old_code, new_code


def verify_release(env):
    old_apks = list((pathlib.Path(env["RUNNER_TEMP"]) / "previous-apk").glob("*.apk"))
    if len(old_apks) != 1 or old_apks[0].stat().st_size == 0:
        raise CompatibilityError("expected exactly one nonempty APK from the previous release")
    new_apk = pathlib.Path(env["RELEASE_APK"])
    if not new_apk.is_file() or new_apk.stat().st_size == 0:
        raise CompatibilityError("expected a nonempty new release APK")
    sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise CompatibilityError("Android SDK was not found")
    tools = list((pathlib.Path(sdk) / "build-tools").glob("*/apksigner"))
    if not tools:
        raise CompatibilityError("apksigner was not found")
    signer = max(tools, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parent.name))))
    aapt = signer.parent / "aapt2"
    if not aapt.is_file():
        aapt = signer.parent / "aapt"
    if not aapt.is_file():
        raise CompatibilityError("aapt2/aapt was not found")
    print(f"Checking APK update compatibility with Android build-tools {signer.parent.name}.", flush=True)
    old_code, new_code = verify_update(old_apks[0], new_apk, signer, aapt, env["TAG"])
    print(
        f"Verified app.dkord update: versionCode {old_code} -> {new_code}; "
        "signing certificates match."
    )


if __name__ == "__main__":
    try:
        verify_release(os.environ)
    except CompatibilityError as error:
        sys.exit("Update compatibility check failed: " + str(error))
