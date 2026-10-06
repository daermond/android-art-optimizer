"""Execute release workflow shell steps with offline signing/source/GitHub fixtures."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]


def workflow_step(name):
    workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
    section = workflow.split(f"      - name: {name}\n", 1)[1].split("\n      - ", 1)[0]
    return textwrap.dedent(section.split("        run: |\n", 1)[1])


class ReleaseWorkflowTests(unittest.TestCase):
    def setUp(self):
        # Git Bash supplies the same shell/coreutils as the Ubuntu release runner.
        if os.name == "nt":
            git = shutil.which("git")
            candidates = []
            if git:
                git_dir = Path(git).resolve().parent
                candidates.extend([git_dir / "bash.exe", git_dir.parent / "bin/bash.exe"])
            candidates.append(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
            self.bash = next((str(path) for path in candidates if path.is_file()), None)
        else:
            self.bash = shutil.which("bash")
        if not self.bash or not Path(self.bash).is_file():
            self.fail("Bash is required for release workflow regression tests (Git Bash on Windows).")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for folder in ["bin", "sdk/build-tools/36.0.0", "runner", "server", "app/build/outputs/apk/release"]:
            (self.root / folder).mkdir(parents=True)
        self.apk = self.root / "app/build/outputs/apk/release/app-release.apk"
        self.apk.write_bytes(b"signed APK fixture\x00\xff")
        self.env = os.environ.copy()
        self.env.update(GITHUB_REF_NAME="v0.3.0", GITHUB_REPOSITORY="daermond/android-art-optimizer",
                        ANDROID_HOME="sdk", RUNNER_TEMP="runner", RELEASE_STATE="missing")
        self.script("sdk/build-tools/36.0.0/apksigner", 'test -s "${@: -1}"')
        self.script("bin/python3", '''
            test "$1" = scripts/package-release-source.py
            printf 'source archive fixture' > "android-art-optimizer-$2-source.zip"
        ''')
        self.script("bin/gh", '''
            printf '%s\n' "$*" >> gh.log
            case "$1 $2" in
              'release view')
                case "$RELEASE_STATE" in
                  missing) exit 1 ;;
                  draft) echo true ;;
                  published) echo false ;;
                esac ;;
              'release create') ;;
              'release upload')
                shift 3
                for asset in "$@"; do
                  [ "$asset" = --clobber ] || cp "$asset" server/
                done ;;
              'release download') cp server/* "$5/" ;;
              'release edit') touch published ;;
              'api repos/'*) echo "${LATEST_TAG:-$GITHUB_REF_NAME}" ;;
              *) exit 2 ;;
            esac
        ''')
        self.script("bin/curl", '''
            printf '%s\n' "$*" > curl.log
            url_seen=false
            while [ "$#" -gt 0 ]; do
              case "$1" in
                --fail|--location|--retry-all-errors) shift ;;
                --retry|--retry-delay) shift 2 ;;
                "https://github.com/$GITHUB_REPOSITORY/releases/latest/download/android-art-optimizer.apk")
                  url_seen=true; shift ;;
                --output)
                  test "$url_seen" = true
                  cp server/android-art-optimizer.apk "$2"
                  shift 2 ;;
                *) exit 2 ;;
              esac
            done
        ''')

    def script(self, name, body):
        path = self.root / name
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + textwrap.dedent(body), encoding="utf-8")
        path.chmod(0o755)

    def run_step(self, name, success=True):
        result = subprocess.run(
            [self.bash, "-e", "-o", "pipefail", "-c", 'export PATH="$PWD/bin:$PATH"\n' + workflow_step(name)],
            cwd=self.root, env=self.env, capture_output=True, text=True,
        )
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def package_and_upload(self):
        self.run_step("Verify signature and package source")
        self.run_step("Create draft release")

    def test_releases_upload_identical_stable_and_versioned_apks_and_verify_latest(self):
        for tag in ["v0.3.0", "v0.3.1"]:
            with self.subTest(tag=tag):
                self.env["GITHUB_REF_NAME"] = tag
                self.apk.write_bytes(b"signed APK " + tag.encode())
                self.package_and_upload()
                expected = {f"android-art-optimizer-{tag}.apk", "android-art-optimizer.apk",
                            f"android-art-optimizer-{tag}-source.zip"}
                manifest = (self.root / "SHA256SUMS.txt").read_text().splitlines()
                # Windows coreutils marks binary inputs with '*'; Linux defaults to text.
                self.assertEqual({line.split()[1].lstrip("*") for line in manifest}, expected)
                for line in manifest:
                    digest, name = line.split()
                    name = name.lstrip("*")
                    self.assertEqual(hashlib.sha256((self.root / "server" / name).read_bytes()).hexdigest(), digest)
                for name in [f"android-art-optimizer-{tag}.apk", "android-art-optimizer.apk"]:
                    self.assertEqual((self.root / "server" / name).read_bytes(), self.apk.read_bytes())
                self.run_step("Verify uploaded assets and publish")
                self.assertTrue((self.root / "published").exists())
                self.run_step("Verify permanent latest APK URL")
                self.assertEqual((self.root / "runner/latest.apk").read_bytes(), self.apk.read_bytes())
                self.assertNotIn("token", (self.root / "curl.log").read_text().lower())
                shutil.rmtree(self.root / "runner/release-download")
                for asset in (self.root / "server").iterdir():
                    asset.unlink()

    def test_draft_rerun_replaces_both_apks(self):
        self.env["RELEASE_STATE"] = "draft"
        (self.root / "server/android-art-optimizer.apk").write_bytes(b"stale draft APK")
        self.package_and_upload()
        self.assertEqual((self.root / "server/android-art-optimizer.apk").read_bytes(), self.apk.read_bytes())
        self.assertNotIn("release create", (self.root / "gh.log").read_text())

    def test_published_release_is_not_modified(self):
        self.env["RELEASE_STATE"] = "published"
        self.run_step("Verify signature and package source")
        self.run_step("Create draft release", success=False)
        self.assertNotIn("release upload", (self.root / "gh.log").read_text())

    def test_missing_built_apk_fails_packaging(self):
        self.apk.unlink()
        self.run_step("Verify signature and package source", success=False)
        self.assertFalse((self.root / "android-art-optimizer.apk").exists())

    def test_missing_or_corrupted_stable_download_prevents_publication(self):
        for missing in [False, True]:
            with self.subTest(missing=missing):
                self.package_and_upload()
                stable = self.root / "server/android-art-optimizer.apk"
                if missing:
                    stable.unlink()
                else:
                    stable.write_bytes(b"corrupted download")
                self.run_step("Verify uploaded assets and publish", success=False)
                self.assertFalse((self.root / "published").exists())
                shutil.rmtree(self.root / "runner/release-download")

    def test_wrong_latest_apk_is_detected_after_publication(self):
        self.package_and_upload()
        self.run_step("Verify uploaded assets and publish")
        (self.root / "server/android-art-optimizer.apk").write_bytes(b"wrong latest APK")
        self.run_step("Verify permanent latest APK URL", success=False)

    def test_maintenance_release_does_not_override_github_latest_selection(self):
        self.env["LATEST_TAG"] = "v0.4.0"
        self.package_and_upload()
        self.run_step("Verify uploaded assets and publish")
        self.run_step("Verify permanent latest APK URL")
        self.assertFalse((self.root / "curl.log").exists())
        self.assertNotIn("--latest", (self.root / "gh.log").read_text())


if __name__ == "__main__":
    unittest.main()
