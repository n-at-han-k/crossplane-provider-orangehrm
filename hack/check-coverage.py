#!/usr/bin/env python3
"""Every operation in the document is accounted for, and every one that a
managed resource should call is actually called.

The generator drops an operation on purpose in three cases, and this spells
the same three rules a second time so that the two have to agree:

  list / search   a GET on a collection, or a POST that answers 200. A
                  Crossplane resource reads one thing by its external name
                  and never lists.
  action          a POST that answers 200 on a path of its own --
                  /lifecycle/{name}/validate. A verb, not a resource.
  verb path       a path whose collection has neither a create nor a read
                  anywhere in the document -- the rights revokes and the
                  single-member removals. Nothing there can be owned.

Anything else must appear in a controller. An operation that should be wired
and is not is a resource the provider silently does not cover.

    python3 hack/check-coverage.py
"""

import re
import sys
from collections import Counter
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / "reference/openapi-schema-rt/request_tracker_rest2.yaml"
VERBS = ("get", "put", "post", "delete", "patch")


def member(path):
    return path.rstrip("/").split("/")[-1].startswith("{")


def collection(path):
    path = path.rstrip("/")
    return path.rsplit("/", 1)[0] if member(path) else path


def describes_a_resource(paths, wanted):
    """A collection someone can create in or read from -- not a bare verb."""
    for path, item in paths.items():
        if collection(path) != wanted:
            continue
        for verb in VERBS:
            if verb not in item:
                continue
            if member(path) and verb == "get":
                return True
            if not member(path) and (verb in ("put", "patch")
                                     or (verb == "post" and "201" in item[verb].get("responses", {}))):
                return True
    return False


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
        sys.exit("missing {} -- run: git submodule update --init".format(SPEC))

    doc = yaml.safe_load(SPEC.open())
    paths = doc["paths"]
    calls = wired()
    resourceful = {c: describes_a_resource(paths, c)
                   for c in {collection(p) for p in paths}}

    counts, missing, skipped = Counter(), [], []

    for path, item in paths.items():
        for verb in VERBS:
            if verb not in item:
                continue
            method, op = verb.upper(), item[verb]
            where = "{:6} {:52} ({})".format(method, path, op.get("operationId"))

            # Ordered so that each operation gets the name of what it
            # actually is: a search on a collection that describes no
            # resource is still a search, not a verb path.
            if not member(path) and method == "GET":
                counts["list"] += 1
                skipped.append("list             " + where)
            elif not member(path) and method == "POST" \
                    and "201" not in op.get("responses", {}):
                counts["search or action"] += 1
                skipped.append("search or action " + where)
            elif not resourceful[collection(path)]:
                counts["verb path"] += 1
                skipped.append("verb path        " + where)
            else:
                counts["wired"] += 1
                if (method, re.sub(r"\{[^}]*\}", "%v", path)) not in calls:
                    missing.append(where)

    for line in sorted(skipped):
        print(line)

    if missing:
        print("\nNOT COVERED -- should be wired into a controller and is not:")
        for line in missing:
            print("  " + line)
        sys.exit(1)

    total = sum(counts.values())
    print("\n{} operations: {}".format(
        total, ", ".join("{} {}".format(n, name) for name, n in sorted(counts.items()))))


if __name__ == "__main__":
    main()
