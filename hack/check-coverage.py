#!/usr/bin/env python3
"""Every operation in the document is either wired into a controller or a search.

The generator drops an operation on purpose in exactly one case: a list or a
search, which a Crossplane managed resource never calls -- it reads one
resource by its external name and nothing else. That is a rule, not a list of
exceptions, and this proves it stayed one: an operation that is neither wired
nor a search is a resource the provider silently does not cover.

    python3 hack/check-coverage.py
"""

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / "reference/request-tracker-openapi/request_tracker_rest2.yaml"
VERBS = ("get", "put", "post", "delete", "patch")

# The same rule the generator applies, spelled once more here so that the two
# have to agree. A trailing "s" is the whole pluralisation rule -- see the
# generator's own note about it.
def plural(segment):
    low = segment.lower()
    return low.endswith("s") and not low.endswith(("ss", "us", "is"))


def member(path):
    return path.rstrip("/").split("/")[-1].startswith("{")


def wired():
    """(METHOD, path) of every call the generated controllers actually make."""
    calls = set()
    pattern = re.compile(r'Do(?:Create)?Request\(ctx, "([A-Z]+)", (?:fmt\.Sprintf\()?"([^"]+)"')
    for go in (ROOT / "internal/controller").rglob("*.go"):
        for method, path in pattern.findall(go.read_text()):
            calls.add((method, path))
    return calls


def main():
    if not SPEC.exists():
        sys.exit("missing {} -- see reference/ in the README".format(SPEC))

    doc = yaml.safe_load(SPEC.open())
    calls = wired()

    missing, searches = [], []

    for path, item in doc["paths"].items():
        for verb in VERBS:
            if verb not in item:
                continue
            method = verb.upper()
            # A controller spells its path with %v where the document spells
            # a parameter, and appends the external name to the member path.
            interpolated = re.sub(r"\{[^}]*\}", "%v", path)
            hit = (method, interpolated) in calls or \
                  (method, re.sub(r"/%v$", "", interpolated)) in calls

            if hit:
                continue

            last = path.rstrip("/").split("/")[-1]
            is_search = not member(path) and (
                method in ("GET", "DELETE") or (method == "POST" and plural(last)))

            (searches if is_search else missing).append(
                "{:6} {}  ({})".format(method, path, item[verb].get("operationId")))

    for line in searches:
        print("search/list, not a managed operation:  " + line)

    if missing:
        print("\nNOT COVERED -- neither wired into a controller nor a search:")
        for line in missing:
            print("  " + line)
        sys.exit(1)

    total = sum(1 for item in doc["paths"].values() for v in VERBS if v in item)
    print("\n{} operations: {} wired into controllers, {} searches."
          .format(total, total - len(searches), len(searches)))


if __name__ == "__main__":
    main()
