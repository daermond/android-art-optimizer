#!/usr/bin/env python3
"""Map a stable release tag to an Android version with predictable update ordering."""
import re
import sys


def release_version(tag):
    match = re.fullmatch(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", tag)
    if not match:
        raise ValueError("Use a vmajor.minor.patch tag, without leading zeroes")
    major, minor, patch = map(int, match.groups())
    code = major * 1_000_000 + minor * 1_000 + patch
    if minor > 999 or patch > 999 or not 0 < code <= 2_100_000_000:
        raise ValueError("Version exceeds the supported Android version-code range")
    return tag[1:], code


if __name__ == "__main__":
    name, code = release_version(sys.argv[1])
    print(f"VERSION_NAME={name}\nVERSION_CODE={code}")
