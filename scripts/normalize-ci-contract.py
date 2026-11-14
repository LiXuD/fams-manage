#!/usr/bin/env python3
"""Put legacy FAMS path keys in the same namespace for OpenAPI comparison."""

import argparse
from pathlib import Path

import yaml


API_PREFIX = "/api/v1"


def normalize(document: dict) -> dict:
    """Keep the contract intact apart from legacy root-level path spellings."""
    if [server.get("url") for server in document.get("servers", [])] != [API_PREFIX]:
        raise ValueError("Expected the FAMS v1 server before normalizing paths")
    paths = {}
    for path, operation in document["paths"].items():
        normalized = path.removeprefix(API_PREFIX) if path.startswith(API_PREFIX + "/") else path
        if normalized in paths:
            raise ValueError(f"Duplicate route after normalizing {path}: {normalized}")
        paths[normalized] = operation
    return {**document, "paths": paths}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    document = normalize(yaml.safe_load(args.source.read_text(encoding="utf-8")))
    args.destination.write_text(yaml.safe_dump(document, allow_unicode=True, sort_keys=False), encoding="utf-8")


if __name__ == "__main__":
    main()
