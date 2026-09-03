"""Risk P-04: a dependency absent from LICENSES.md fails the build.

Costs nothing now; it is an emergency in week 8. Run by CI on every push.
"""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent
catalog = (root / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
licences = (root / "LICENSES.md").read_text(encoding="utf-8").lower()

# module = "group:artifact" -> the artifact name is what LICENSES.md should mention
artifacts = set(re.findall(r'module\s*=\s*"[^:]+:([^"]+)"', catalog))

# Test and build plumbing is not a shipped dependency.
IGNORE = {"junit", "kotest-property", "kotest-assertions-core", "espresso-core",
          "junit-ktx", "compose-bom", "ui-tooling"}

missing = sorted(a for a in artifacts - IGNORE
                 if a.lower().split("-")[0] not in licences and a.lower() not in licences)

if missing:
    print("::error::These dependencies are not declared in LICENSES.md:")
    for m in missing:
        print("   -", m)
    print("\nAdd each with its licence and role. See LICENSES.md section 9.")
    sys.exit(1)

print(f"licence audit clean: {len(artifacts)} catalog entries, all accounted for")
