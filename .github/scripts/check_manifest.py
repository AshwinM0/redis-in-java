#!/usr/bin/env python3
"""Assert that a built jar declares the expected Main-Class.

Without it `java -jar <jar>` fails with "no main manifest attribute", which is
what the README's quickstart runs.

Usage:
    python .github/scripts/check_manifest.py <jar> <expected-main-class>
"""

import sys
import zipfile


def main():
    if len(sys.argv) != 3:
        return "usage: check_manifest.py <jar> <expected-main-class>"

    jar_path, expected = sys.argv[1], sys.argv[2]

    try:
        with zipfile.ZipFile(jar_path) as jar:
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
    except FileNotFoundError:
        return "jar not found: {}".format(jar_path)
    except KeyError:
        return "{} has no META-INF/MANIFEST.MF".format(jar_path)

    print(manifest.strip())

    if "Main-Class: {}".format(expected) not in manifest:
        return "manifest does not declare Main-Class: {}".format(expected)

    print("\nMain-Class is present: {}".format(expected))
    return 0


if __name__ == "__main__":
    sys.exit(main())
