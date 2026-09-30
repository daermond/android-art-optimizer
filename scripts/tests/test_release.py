import importlib.util
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


version = load("release_version", "release_version.py")
source = load("package_source", "package-release-source.py")


class VersionTests(unittest.TestCase):
    def test_update_order_and_rerun_stability(self):
        tags = ["v0.1.0", "v0.1.1", "v0.2.0", "v1.0.0"]
        codes = [version.release_version(tag)[1] for tag in tags]
        self.assertEqual(codes, [1000, 1001, 2000, 1000000])
        self.assertEqual(version.release_version("v0.1.0"), ("0.1.0", 1000))

    def test_invalid_or_ambiguous_versions_fail(self):
        for tag in ["0.1.0", "v01.0.0", "v1.2.3-rc1", "v0.0.0", "v1.1000.0", "v1.0.1000", "v2101.0.0", "v1.2.3\n"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version.release_version(tag)


class SourceTests(unittest.TestCase):
    @staticmethod
    def app_archive(name):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr(name, "example")
        return stream.getvalue()

    def test_tracked_credentials_are_rejected(self):
        for name in ["secrets/key.txt", "release.p12", "release.JKS", "local.properties", "password.dpapi"]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                with patch.object(source.subprocess, "check_output", side_effect=["a" * 40, self.app_archive(name)]):
                    with self.assertRaisesRegex(ValueError, "sensitive tracked file"):
                        source.package("HEAD", "v0.1.0", Path(directory) / "source.zip", Path(directory) / "cache")

    def test_tampered_dependency_cache_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / "cache"
            cache.mkdir()
            (cache / source.DEPENDENCIES[0][0]).write_bytes(b"tampered")
            with patch.object(source.subprocess, "check_output", side_effect=["a" * 40, self.app_archive("README.md")]):
                with self.assertRaisesRegex(ValueError, "Cached source checksum mismatch"):
                    source.package("HEAD", "v0.1.0", root / "source.zip", cache)
            self.assertFalse((root / "source.zip").exists())

    def test_bundle_identifies_commit_and_preserves_build_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / "cache"
            cache.mkdir()
            data = b"dependency source"
            (cache / "dependency.zip").write_bytes(data)
            app = io.BytesIO()
            with zipfile.ZipFile(app, "w") as archive:
                entry = zipfile.ZipInfo("gradlew")
                entry.external_attr = 0o100755 << 16
                archive.writestr(entry, "#!/bin/sh")
            dependency = ("dependency.zip", "https://example.invalid/source", hashlib.sha256(data).hexdigest())
            with patch.object(source, "DEPENDENCIES", [dependency]), patch.object(source.subprocess, "check_output", side_effect=["a" * 40, app.getvalue()]):
                source.package("HEAD", "v0.1.0", root / "source.zip", cache)
            with zipfile.ZipFile(root / "source.zip") as archive:
                manifest = json.loads(archive.read("SOURCE_MANIFEST.json"))
                self.assertEqual(manifest["commit"], "a" * 40)
                self.assertEqual(manifest["tag"], "v0.1.0")
                self.assertEqual(archive.read("dependency-sources/dependency.zip"), data)
                self.assertEqual(archive.getinfo("android-art-optimizer/gradlew").external_attr >> 16, 0o100755)


if __name__ == "__main__":
    unittest.main()
