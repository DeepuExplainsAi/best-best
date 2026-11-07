"""Fast source/resource/release contracts; Android behavior is tested by the Kotlin suite in Actions.

These checks intentionally need only Python's standard library. They do NOT execute Android,
render Compose, or prove a two-device room session works.
"""
from collections import Counter
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def strings(folder):
    return {item.attrib["name"]: "".join(item.itertext())
            for item in ET.parse(MAIN / "res" / folder / "strings.xml").getroot()}


class AppContractsTest(unittest.TestCase):
    def test_all_xml_resources_and_manifest_are_well_formed(self):
        for path in [MAIN / "AndroidManifest.xml", *(MAIN / "res").rglob("*.xml")]:
            with self.subTest(file=str(path.relative_to(ROOT))):
                ET.parse(path)

    def test_resource_names_are_unique_in_each_language(self):
        for folder in ("values", "values-hi"):
            counts = Counter(item.attrib["name"] for item in ET.parse(MAIN / "res" / folder / "strings.xml").getroot())
            self.assertFalse({key: count for key, count in counts.items() if count != 1}, folder)

    def test_hindi_has_every_english_key_with_matching_format_arguments(self):
        english, hindi = strings("values"), strings("values-hi")
        self.assertEqual(english.keys(), hindi.keys())
        for key, value in english.items():
            with self.subTest(key=key):
                self.assertEqual(sorted(re.findall(r"%(?:\d+\$)?[sd]", value)),
                                 sorted(re.findall(r"%(?:\d+\$)?[sd]", hindi[key])))

    def test_default_interface_resources_are_english(self):
        for key, value in strings("values").items():
            self.assertIsNone(re.search(r"[\u0900-\u097f]", value), key)

    def test_all_app_string_references_resolve(self):
        english = strings("values")
        for path in (MAIN / "java").rglob("*.kt"):
            for key in re.findall(r"(?<!android\.)\bR\.string\.(\w+)", path.read_text()):
                self.assertIn(key, english, f"{path.relative_to(ROOT)}: {key}")

    def test_language_picker_matches_only_real_translations(self):
        config = ET.parse(MAIN / "res/xml/locales_config.xml").getroot()
        configured = [locale.attrib[ANDROID + "name"] for locale in config]
        policy = read("app/src/main/java/app/dkord/data/settings/AppLanguage.kt")
        supported = re.findall(r'\b[A-Z_]+\("([a-z]+)"\)', policy)
        self.assertEqual(["en", "hi"], configured)
        self.assertEqual(configured, supported)
        self.assertEqual({"values-hi"}, {p.name for p in (MAIN / "res").glob("values-*") if (p / "strings.xml").exists()})

    def test_english_default_is_applied_globally_with_matching_activity_theme(self):
        application = read("app/src/main/java/app/dkord/DkordApp.kt")
        activity = read("app/src/main/java/app/dkord/MainActivity.kt")
        settings = read("app/src/main/java/app/dkord/ui/screens/Settings.kt")
        themes = read("app/src/main/res/values/themes.xml")
        self.assertIn("AppLanguages.initialize(this)", application)
        self.assertIn("AppLanguages.deviceLocale(this)", application)
        self.assertIn(".systemLocales[0]", read("app/src/main/java/app/dkord/data/settings/AppLanguages.kt"))
        self.assertIn("class MainActivity : AppCompatActivity()", activity)
        self.assertIn("AppLanguages.initialize(newBase)", activity)
        self.assertIn("Theme.AppCompat.DayNight.NoActionBar", themes)
        self.assertIn("AppLanguages.select(ctx, it)", settings)

    def test_room_has_ordered_practical_steps_not_the_removed_technical_paragraph(self):
        ui = read("app/src/main/java/app/dkord/ui/screens/ListenTogether.kt")
        steps = ["room_step_create", "room_step_share", "room_step_join", "room_step_sync"]
        self.assertEqual(sorted(steps, key=ui.index), steps)
        for key in steps:
            self.assertIn(key, strings("values"))
            self.assertIn(key, strings("values-hi"))
        for removed in ("room_privacy_note", "room_anyone_can_join", "room_local_audio", "room_stays_connected"):
            self.assertNotIn(removed, ui)
            self.assertNotIn(removed, strings("values"))
        self.assertIn("ClipData.newPlainText", ui)

    def test_home_update_is_inline_and_only_on_home(self):
        update = read("app/src/main/java/app/dkord/ui/common/AppUpdate.kt")
        app = read("app/src/main/java/app/dkord/ui/App.kt")
        self.assertNotIn("AlertDialog", update)
        self.assertNotIn("HomeUpdatePrompt", app)
        self.assertIn("HomeUpdateNotice(visible = tab == Tab.HOME", app)
        self.assertEqual(1, app.count("ObserveAppUpdates()"))
        self.assertIn("updater::later", update)

    def test_updater_uses_latest_stable_endpoint_not_version_increment_hops(self):
        adapter = read("app/src/main/java/app/dkord/data/update/AppUpdater.kt")
        endpoints = re.findall(r'const val LATEST = "([^"]+)"', adapter)
        self.assertEqual(["https://api.github.com/repos/DeepuExplainsAi/dkord/releases/latest"], endpoints)
        self.assertIn("currentSigners.isEmpty()", adapter)
        self.assertIn("signatures(candidate) != currentSigners", adapter)
        self.assertIn("candidate.packageName != app.packageName", adapter)

    def test_choliwood_name_and_collapsed_new_releases_are_preserved(self):
        for folder in ("values", "values-hi"):
            values = strings(folder)
            for key, value in values.items():
                if "choliwood" in key:
                    self.assertIn("Choliwood", value, key)
                    self.assertNotIn("Bhojpuri", value, key)
        releases = read("app/src/main/java/app/dkord/ui/screens/NewReleases.kt")
        self.assertRegex(releases, r"expanded\s+by\s+rememberSaveable\s*\{\s*mutableStateOf\(false\)")

    def test_all_junit_annotations_have_function_declarations(self):
        for path in (ROOT / "app/src/test").rglob("*.kt"):
            source = path.read_text()
            annotations = re.findall(r"@Test(?:\([^)]*\))?\s+([A-Za-z_]\w*)", source)
            self.assertEqual(source.count("@Test"), len(annotations), str(path))
            self.assertTrue(all(token == "fun" for token in annotations), str(path))

    def test_workflows_are_manual_and_android_suite_is_not_skipped(self):
        for path in (".github/workflows/build.yml", ".github/workflows/release.yml"):
            workflow = read(path)
            self.assertIn("  workflow_dispatch:", workflow)
            self.assertNotRegex(workflow, r"(?m)^\s+(?:push|pull_request|pull_request_target|schedule):")
            self.assertIn("./gradlew testDebugUnitTest assembleDebug --stacktrace", workflow)
            self.assertNotIn("continue-on-error:", workflow)
            self.assertNotIn("-x test", workflow)
            self.assertIn("python3 -B -m unittest discover", workflow)

    def test_signing_password_is_passed_as_an_environment_value_not_shell_source(self):
        for path in (".github/workflows/build.yml", ".github/workflows/release.yml"):
            workflow = read(path)
            self.assertIn("KS_PASS: ${{ secrets.DKORD_KEYSTORE_PASS }}", workflow)
            self.assertIn("-storepass:env KS_PASS", workflow)
            self.assertNotRegex(workflow, r'(?m)^\s+(?:echo|printf).*secrets\.DKORD_KEYSTORE_PASS')
            self.assertIn('DKORD_KEYSTORE_PASS<<%s', workflow)

    def test_release_publication_is_after_tests_build_and_real_apk_signer_gate(self):
        workflow = read(".github/workflows/release.yml")
        gates = ["Validate release tag against app version", "python3 -B -m unittest discover",
                 "Require the existing stable signing key", "./gradlew testDebugUnitTest assembleDebug",
                 "Verify and prepare release APK", "python3 -B .github/scripts/verify_apk_update.py", "gh release create"]
        positions = [workflow.index(gate) for gate in gates]
        self.assertEqual(positions, sorted(positions))
        self.assertNotIn("gh release delete", workflow)
        self.assertNotIn("gh release edit", workflow)
        self.assertIn('if [ "$previous_tag" = "$TAG" ]', workflow)
        self.assertIn('--target "$GITHUB_SHA"', workflow)
        gradle = read("app/build.gradle.kts")
        self.assertIn('applicationId = "app.dkord"', gradle)
        self.assertIn('keyAlias = "dkord"', gradle)
        self.assertRegex(gradle, r'versionName\s*=\s*"\d+\.\d+\.\d+"')


if __name__ == "__main__":
    unittest.main()
