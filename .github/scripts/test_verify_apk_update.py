"""Release safety regression tests. No SDK, Java, APKs or signing secrets required."""

import contextlib
import io
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import verify_apk_update as guard


CERT = "a1" * 32
OTHER_CERT = "b2" * 32
STAMP = "c3" * 32


def signer_output(digest=CERT, label="V3.0 Signer:"):
    return f"{label} certificate SHA-256 digest: {digest}\n"


def badging(package="app.dkord", code=6, version="0.5.0"):
    return f"package: name='{package}' versionCode='{code}' versionName='{version}'\n"


class CertificateParserTest(unittest.TestCase):
    def test_legacy_and_sdk37_formats(self):
        for label in (
            "Signer #1",
            "Signer #2",
            "Signer:",
            "Signer #1:",
            "V1 Signer:",
            "V2 Signer:",
            "V2 Signer #1:",
            "V3.0 Signer:",
            "V3.0 Signer #2:",
            "V3.0 Signer: (minSdkVersion=28, maxSdkVersion=32)",
            "V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647)",
            "V3.1 Signer: (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647)",
            "Signer (minSdkVersion=28, maxSdkVersion=32) #1",
        ):
            with self.subTest(label=label):
                self.assertEqual(guard.certificate_digests(signer_output(label=label)), (CERT,))

    def test_uppercase_digest_and_crlf(self):
        self.assertEqual(
            guard.certificate_digests(signer_output(CERT.upper()).replace("\n", "\r\n")),
            (CERT,),
        )

    def test_duplicate_scheme_certificates_are_one_identity(self):
        output = signer_output(label="V3.0 Signer:") + signer_output(label="V3.1 Signer:")
        self.assertEqual(guard.certificate_digests(output), (CERT,))

    def test_multiple_distinct_apk_signers_are_all_preserved(self):
        output = signer_output(OTHER_CERT, "Signer #2") + signer_output(CERT, "Signer #1")
        self.assertEqual(guard.certificate_digests(output), (CERT, OTHER_CERT))

    def test_source_stamp_and_public_key_are_not_apk_signers(self):
        for stamp_label in ("Source Stamp Signer", "Source Stamp Signer:"):
            with self.subTest(stamp_label=stamp_label):
                output = (
                    signer_output()
                    + signer_output(STAMP, stamp_label)
                    + f"V3.0 Signer: public key SHA-256 digest: {OTHER_CERT}\n"
                )
                self.assertEqual(guard.certificate_digests(output), (CERT,))

    def test_no_apk_certificate_is_rejected(self):
        for output in (
            "",
            "Verified using v3 scheme: true\n",
            signer_output(STAMP, "Source Stamp Signer:"),
            f"Signer #1 public key SHA-256 digest: {CERT}\n",
        ):
            with self.subTest(output=output):
                with self.assertRaisesRegex(guard.CompatibilityError, "no verified"):
                    guard.certificate_digests(output)

    def test_malformed_or_unknown_certificate_is_rejected_even_alongside_valid_one(self):
        for output in (
            signer_output("a" * 63),
            signer_output("a" * 65),
            signer_output("z" * 64),
            signer_output()[:-1] + " extra\n",
            signer_output(label="Unknown Signer:"),
        ):
            with self.subTest(output=output):
                with self.assertRaises(guard.CompatibilityError):
                    guard.certificate_digests(signer_output(CERT, "Signer #1") + output)


class CompatibilityTest(unittest.TestCase):
    def verify(self, outputs, codes=None, tag="v0.5.0"):
        if codes is None:
            codes = [0] * len(outputs)
        results = [
            subprocess.CompletedProcess([], code, stdout=output, stderr="")
            for code, output in zip(codes, outputs)
        ]
        with patch.object(guard.subprocess, "run", side_effect=results) as tool:
            result = guard.verify_update("old.apk", "new.apk", "apksigner", "aapt2", tag)
        return result, tool

    def matching(self):
        return [
            signer_output(CERT, "Signer #1"),
            signer_output(),
            badging(code=5, version="0.4.0"),
            badging(),
        ]

    def test_same_identity_with_different_tool_labels_is_accepted(self):
        result, tool = self.verify(self.matching())
        self.assertEqual(result, (5, 6))
        self.assertEqual(tool.call_args_list[0].args[0], ["apksigner", "verify", "--print-certs", "old.apk"])
        self.assertEqual(tool.call_args_list[1].args[0], ["apksigner", "verify", "--print-certs", "new.apk"])
        self.assertEqual(tool.call_count, 4)

    def test_invalid_old_or_new_signature_is_rejected_even_with_printed_digest(self):
        for codes in ([1, 0, 0, 0], [0, 1, 0, 0]):
            with self.subTest(codes=codes):
                with self.assertRaisesRegex(guard.CompatibilityError, "tool failed"):
                    self.verify(self.matching(), codes=codes)

    def test_changed_key_is_rejected(self):
        outputs = self.matching()
        outputs[1] = signer_output(OTHER_CERT)
        with self.assertRaisesRegex(guard.CompatibilityError, "certificates differ"):
            self.verify(outputs)

    def test_additional_apk_signer_is_rejected(self):
        outputs = self.matching()
        outputs[1] += signer_output(OTHER_CERT, "V3.0 Signer #2:")
        with self.assertRaisesRegex(guard.CompatibilityError, "certificates differ"):
            self.verify(outputs)

    def test_different_source_stamps_do_not_change_apk_signing_identity(self):
        outputs = self.matching()
        outputs[0] += signer_output(OTHER_CERT, "Source Stamp Signer")
        outputs[1] += signer_output(STAMP, "Source Stamp Signer:")
        self.assertEqual(self.verify(outputs)[0], (5, 6))

    def test_matching_source_stamp_cannot_hide_changed_apk_signer(self):
        outputs = self.matching()
        outputs[0] += signer_output(CERT, "Source Stamp Signer:")
        outputs[1] = signer_output(OTHER_CERT) + signer_output(CERT, "Source Stamp Signer:")
        with self.assertRaisesRegex(guard.CompatibilityError, "certificates differ"):
            self.verify(outputs)

    def test_wrong_old_or_new_package_is_rejected(self):
        for index in (2, 3):
            with self.subTest(index=index):
                outputs = self.matching()
                outputs[index] = badging(package="other.app")
                with self.assertRaisesRegex(guard.CompatibilityError, "applicationId"):
                    self.verify(outputs)

    def test_same_or_lower_version_code_is_rejected(self):
        for code in (5, 4):
            with self.subTest(code=code):
                outputs = self.matching()
                outputs[3] = badging(code=code)
                with self.assertRaisesRegex(guard.CompatibilityError, "versionCode"):
                    self.verify(outputs)

    def test_wrong_version_name_is_rejected(self):
        with self.assertRaisesRegex(guard.CompatibilityError, "versionName"):
            self.verify(self.matching(), tag="v0.5.1")

    def test_missing_package_metadata_is_rejected(self):
        outputs = self.matching()
        outputs[3] = ""
        with self.assertRaisesRegex(guard.CompatibilityError, "metadata"):
            self.verify(outputs)

    def test_metadata_tool_failure_is_rejected(self):
        with self.assertRaisesRegex(guard.CompatibilityError, "tool failed"):
            self.verify(self.matching(), codes=[0, 0, 1, 0])

    def test_missing_verification_tool_is_rejected(self):
        with patch.object(guard.subprocess, "run", side_effect=FileNotFoundError):
            with self.assertRaisesRegex(guard.CompatibilityError, "could not be started"):
                guard.certificates("missing", "old.apk")


class ReleaseEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.previous = self.root / "previous-apk"
        self.previous.mkdir()
        (self.previous / "old.apk").write_bytes(b"test fixture, not an actual APK")
        self.new = self.root / "new.apk"
        self.new.write_bytes(b"test fixture, not an actual APK")
        self.tools = self.root / "sdk" / "build-tools" / "37.0.0"
        self.tools.mkdir(parents=True)
        (self.tools / "apksigner").touch()
        (self.tools / "aapt2").touch()
        self.env = dict(RUNNER_TEMP=str(self.root), RELEASE_APK=str(self.new),
                        ANDROID_HOME=str(self.root / "sdk"), TAG="v0.5.0")

    def test_release_wires_downloaded_apks_and_prints_success_only_after_checks(self):
        with patch.object(guard, "verify_update", return_value=(5, 6)) as verify:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                guard.verify_release(self.env)
        verify.assert_called_once_with(
            self.previous / "old.apk", self.new, self.tools / "apksigner",
            self.tools / "aapt2", "v0.5.0",
        )
        self.assertIn("signing certificates match", output.getvalue())

    def test_missing_empty_or_multiple_previous_apks_are_rejected(self):
        old = self.previous / "old.apk"
        for condition in ("missing", "empty", "multiple"):
            with self.subTest(condition=condition):
                if condition == "missing":
                    old.unlink()
                elif condition == "empty":
                    old.touch()
                else:
                    old.write_bytes(b"fixture")
                    (self.previous / "extra.apk").write_bytes(b"fixture")
                with self.assertRaisesRegex(guard.CompatibilityError, "exactly one nonempty"):
                    guard.verify_release(self.env)

    def test_missing_or_empty_new_apk_is_rejected(self):
        self.new.unlink()
        for condition in ("missing", "empty"):
            with self.subTest(condition=condition):
                if condition == "empty":
                    self.new.touch()
                with self.assertRaisesRegex(guard.CompatibilityError, "nonempty new"):
                    guard.verify_release(self.env)

    def test_missing_sdk_is_rejected(self):
        del self.env["ANDROID_HOME"]
        with self.assertRaisesRegex(guard.CompatibilityError, "Android SDK"):
            guard.verify_release(self.env)

    def test_missing_apksigner_or_aapt_is_rejected(self):
        for name in ("aapt2", "apksigner"):
            with self.subTest(name=name):
                (self.tools / name).unlink()
                with self.assertRaises(guard.CompatibilityError):
                    guard.verify_release(self.env)

    def test_cli_accepts_matching_certificates_and_fails_closed_with_fake_tools(self):
        # Exercise the real entry point/subprocess wiring, not actual APK cryptography.
        signer = self.tools / "apksigner"
        signer.write_text(
            "#!/usr/bin/env python3\n"
            "import os, pathlib, sys\n"
            "mode = os.environ['FAKE_MODE']\n"
            "is_new = pathlib.Path(sys.argv[-1]).name == 'new.apk'\n"
            "if mode == 'missing_certificate': sys.exit(0)\n"
            f"digest = '{OTHER_CERT}' if mode == 'changed_key' and is_new else '{CERT}'\n"
            "print('V3.0 Signer: certificate SHA-256 digest: ' + digest)\n"
            "sys.exit(1 if mode == 'invalid_signature' else 0)\n"
        )
        aapt = self.tools / "aapt2"
        aapt.write_text(
            "#!/usr/bin/env python3\n"
            "import pathlib, sys\n"
            "is_new = pathlib.Path(sys.argv[-1]).name == 'new.apk'\n"
            "code, version = (6, '0.5.0') if is_new else (5, '0.4.0')\n"
            "print(f\"package: name='app.dkord' versionCode='{code}' versionName='{version}'\")\n"
        )
        signer.chmod(0o700)
        aapt.chmod(0o700)
        for mode in ("match", "changed_key", "invalid_signature", "missing_certificate"):
            with self.subTest(mode=mode):
                result = subprocess.run(
                    [sys.executable, "-B", guard.__file__],
                    env={**os.environ, **self.env, "FAKE_MODE": mode},
                    capture_output=True, text=True,
                )
                self.assertEqual(result.returncode, 0 if mode == "match" else 1, result.stderr)
                self.assertEqual("signing certificates match" in result.stdout, mode == "match")

    def test_android_sdk_root_and_aapt_fallback_remain_supported(self):
        self.env["ANDROID_SDK_ROOT"] = self.env.pop("ANDROID_HOME")
        (self.tools / "aapt2").unlink()
        (self.tools / "aapt").touch()
        with patch.object(guard, "verify_update", return_value=(5, 6)) as verify:
            with contextlib.redirect_stdout(io.StringIO()):
                guard.verify_release(self.env)
        self.assertEqual(verify.call_args.args[3], self.tools / "aapt")


if __name__ == "__main__":
    unittest.main()
