#!/usr/bin/env python3
"""Generate or verify reviewed offline OSS assets from resolved Gradle metadata.

Run `./gradlew :app:updateReleaseOssInventory` first when dependencies change.
Normal builds never overwrite committed assets. This script uses only the local
Gradle cache populated by the releaseRuntimeClasspath resolution.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ElementTree
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "app/src/main/assets/oss"
INVENTORY_FILE = ASSET_ROOT / "release-runtime-components.txt"
CATALOG_FILE = ASSET_ROOT / "oss_licenses.json"
LICENSE_ROOT = ASSET_ROOT / "licenses"
GRADLE_MODULE_CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"


@dataclass(frozen=True, order=True)
class Component:
    coordinate: str
    dependency_kind: str
    group: str
    artifact: str
    version: str


@dataclass(frozen=True)
class LicenseMetadata:
    identifier: str
    name: str
    url: str
    source: str


def parse_inventory() -> list[Component]:
    components: list[Component] = []
    for line in INVENTORY_FILE.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        dependency_kind, coordinate = line.split("|", maxsplit=1)
        group, artifact, version = coordinate.split(":", maxsplit=2)
        components.append(
            Component(coordinate, dependency_kind, group, artifact, version),
        )
    if components != sorted(components, key=lambda value: value.coordinate):
        raise RuntimeError("Release runtime inventory is not sorted by coordinate")
    if len({value.coordinate for value in components}) != len(components):
        raise RuntimeError("Release runtime inventory contains duplicate coordinates")
    return components


def module_root(group: str, artifact: str, version: str) -> Path:
    return GRADLE_MODULE_CACHE / group / artifact / version


def one_cached_file(group: str, artifact: str, version: str, pattern: str) -> Path:
    matches = sorted(module_root(group, artifact, version).glob(f"*/{pattern}"))
    if not matches:
        raise RuntimeError(f"Missing cached artifact: {group}:{artifact}:{version} {pattern}")
    return matches[0]


def pom_license(
    group: str,
    artifact: str,
    version: str,
    visited: frozenset[str] = frozenset(),
) -> LicenseMetadata:
    coordinate = f"{group}:{artifact}:{version}"
    if coordinate in visited:
        raise RuntimeError(f"POM parent cycle: {coordinate}")
    pom = one_cached_file(group, artifact, version, "*.pom")
    root = ElementTree.parse(pom).getroot()
    namespace = root.tag.partition("}")[0] + "}" if root.tag.startswith("{") else ""
    licenses = root.findall(f"{namespace}licenses/{namespace}license")
    if licenses:
        if len(licenses) != 1:
            raise RuntimeError(f"Expected one POM license for {coordinate}")
        name = (licenses[0].findtext(f"{namespace}name") or "").strip()
        url = (licenses[0].findtext(f"{namespace}url") or "").strip()
        identifier = normalize_license(name, url)
        return LicenseMetadata(identifier, name, url, f"POM {coordinate}")
    parent = root.find(f"{namespace}parent")
    if parent is None:
        raise RuntimeError(f"No license or parent POM metadata for {coordinate}")
    parent_group = (parent.findtext(f"{namespace}groupId") or "").strip()
    parent_artifact = (parent.findtext(f"{namespace}artifactId") or "").strip()
    parent_version = (parent.findtext(f"{namespace}version") or "").strip()
    inherited = pom_license(
        parent_group,
        parent_artifact,
        parent_version,
        visited | {coordinate},
    )
    return LicenseMetadata(
        inherited.identifier,
        inherited.name,
        inherited.url,
        f"parent {inherited.source}",
    )


def normalize_license(name: str, url: str) -> str:
    lowered = f"{name} {url}".lower()
    if "android software development kit license" in lowered:
        return "ANDROID-SDK-LICENSE"
    if "bsd-3-clause" in lowered:
        return "BSD-3-Clause"
    if "apache" in lowered and "2.0" in lowered:
        return "Apache-2.0"
    if "mit license" in lowered or "opensource.org/licenses/mit" in lowered:
        return "MIT"
    raise RuntimeError(f"Unreviewed license metadata: {name!r} {url!r}")


def binary_artifact(component: Component) -> Path | None:
    root = module_root(component.group, component.artifact, component.version)
    candidates = sorted(root.glob("*/*.aar")) + sorted(root.glob("*/*.jar"))
    candidates = [
        value for value in candidates
        if not value.name.endswith("-sources.jar") and not value.name.endswith("-javadoc.jar")
    ]
    return candidates[0] if candidates else None


def zip_entry(component: Component, entry_name: str) -> bytes:
    artifact = binary_artifact(component)
    if artifact is None:
        raise RuntimeError(f"No binary artifact for {component.coordinate}")
    with zipfile.ZipFile(artifact) as archive:
        try:
            return archive.read(entry_name)
        except KeyError as error:
            raise RuntimeError(f"Missing {entry_name} in {component.coordinate}") from error


def normalized_license_text(value: bytes | str) -> str:
    text = value.decode("utf-8") if isinstance(value, bytes) else value
    lines: list[str] = []
    for source_line in text.splitlines():
        line = source_line.rstrip(" \t")
        if re.fullmatch(r"[<=>]{7,}", line):
            line = "-" * len(line)
        lines.append(line)
    return "\n".join(lines).strip()


def display_entry_id(component: Component, license_identifier: str) -> str | None:
    if license_identifier == "ANDROID-SDK-LICENSE":
        return None
    if component.coordinate.startswith(
        "androidx.datastore:datastore-preferences-external-protobuf:",
    ):
        return "datastore-external-protobuf"
    if component.group.startswith("androidx.") or component.group == "androidx.compose":
        return "androidx"
    if component.group == "com.google.android.material":
        return "material-components"
    if component.group == "org.jetbrains.kotlin":
        return "kotlin"
    if component.group == "org.jetbrains.kotlinx":
        if component.artifact.startswith("kotlinx-coroutines"):
            return "kotlin-coroutines"
        if component.artifact.startswith("kotlinx-serialization"):
            return "kotlin-serialization"
    if component.group == "com.squareup.okio":
        return "okio"
    if component.group == "com.google.errorprone":
        return "error-prone-annotations"
    if component.group == "com.google.guava":
        return "guava-listenablefuture"
    if component.group == "org.jspecify":
        return "jspecify"
    if component.group == "org.jetbrains" and component.artifact == "annotations":
        return "jetbrains-annotations"
    if component.group == "com.materialkolor":
        return "materialkolor"
    if component.group == "com.github.ajalt.colormath":
        return "colormath"
    if component.group == "dev.drewhamilton.poko":
        return "poko-annotations"
    if component.group.startswith("org.jetbrains.compose"):
        return "compose-multiplatform"
    if component.group.startswith("org.jetbrains.androidx."):
        return "compose-multiplatform"
    raise RuntimeError(f"No reviewed display mapping for {component.coordinate}")


LIBRARY_METADATA = {
    "androidx": (
        "AndroidX / Jetpack Compose",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "POM metadata and bundled META-INF license files were reviewed. "
        "No standalone NOTICE file was present in the resolved AndroidX artifacts.",
    ),
    "datastore-external-protobuf": (
        "DataStore external Protocol Buffers",
        "BSD 3-Clause License",
        "BSD-3-Clause",
        "licenses/bsd-3-clause.txt",
        "The DataStore artifact POM and its bundled LICENSE identify BSD-3-Clause.",
    ),
    "error-prone-annotations": (
        "Error Prone Annotations",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "License source: resolved artifact POM metadata.",
    ),
    "guava-listenablefuture": (
        "Guava ListenableFuture",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "License source: inherited Guava parent POM metadata and the bundled source header.",
    ),
    "jetbrains-annotations": (
        "JetBrains Annotations",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "License source: resolved artifact POM metadata.",
    ),
    "jspecify": (
        "JSpecify",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "License source: resolved artifact POM metadata.",
    ),
    "kotlin": (
        "Kotlin",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "License source: resolved Kotlin POM metadata.",
    ),
    "kotlin-coroutines": (
        "Kotlin Coroutines",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "License source: resolved Kotlin Coroutines POM metadata.",
    ),
    "kotlin-serialization": (
        "Kotlin Serialization",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "License source: resolved Kotlin Serialization POM metadata.",
    ),
    "material-components": (
        "Material Components for Android",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "License source: resolved artifact POM metadata.",
    ),
    "okio": (
        "Okio",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "License source: resolved Okio POM metadata.",
    ),
    "materialkolor": (
        "MaterialKolor",
        "The MIT License",
        "MIT",
        "licenses/mit-materialkolor.txt",
        "License source: resolved MaterialKolor POM metadata (MIT); licence text from the "
        "project's LICENSE at tag 4.0.5. Ports Material Color Utilities for seed-colour "
        "scheme generation (テーマカラー/パレットスタイル); the ported files carry Google's "
        "Apache License 2.0 headers, whose text is listed under Apache License 2.0.",
    ),
    "compose-multiplatform": (
        "Compose Multiplatform (JetBrains)",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "License source: resolved JetBrains Compose/androidx-port POM metadata. Pulled in "
        "as MaterialKolor's multiplatform runtime; on Android these delegate to AndroidX.",
    ),
    "colormath": (
        "Colormath",
        "The MIT License",
        "MIT",
        "licenses/mit-colormath.txt",
        "License source: resolved Colormath POM metadata; licence text from the project's "
        "LICENSE.txt.",
    ),
    "poko-annotations": (
        "Poko Annotations",
        "Apache License 2.0",
        "Apache-2.0",
        "licenses/apache-2.0.txt",
        "License source: resolved Poko POM metadata.",
    ),
}

# Hand-reviewed entries with no Maven runtime component: bundled font files. Their licence
# texts live as committed assets and pass through generation unchanged.
STATIC_LIBRARIES: list[dict[str, object]] = [
    {
        "id": "kosugi-maru-font",
        "name": "Kosugi Maru",
        "licenseName": "Apache License 2.0",
        "licenseIdentifier": "Apache-2.0",
        "notice": "Kosugi Maru is a rounded Japanese typeface by MOTOYA, distributed through "
        "Google Fonts under the Apache License 2.0. The font file is bundled as an app "
        "resource (res/font/kosugi_maru.ttf) for the 丸文字体 comment-font setting; it is "
        "not a Maven runtime component.",
        "licenseTextAsset": "licenses/apache-2.0.txt",
        "components": ["font:kosugi-maru:regular"],
    },
    {
        "id": "noto-sans-jp-font",
        "name": "Noto Sans JP",
        "licenseName": "SIL Open Font License 1.1",
        "licenseIdentifier": "OFL-1.1",
        "notice": "Noto Sans JP is a Japanese gothic (sans) typeface from the Noto project, "
        "distributed through Google Fonts under the SIL Open Font License 1.1. The variable "
        "font file is bundled as an app resource (res/font/noto_sans_jp.ttf) for the "
        "ゴシック体 comment-font setting; it is not a Maven runtime component.",
        "licenseTextAsset": "licenses/ofl-1.1-noto-sans-jp.txt",
        "components": ["font:noto-sans-jp:variable"],
    },
    {
        "id": "noto-serif-jp-font",
        "name": "Noto Serif JP",
        "licenseName": "SIL Open Font License 1.1",
        "licenseIdentifier": "OFL-1.1",
        "notice": "Noto Serif JP is a Japanese serif (mincho) typeface from the Noto project, "
        "distributed through Google Fonts under the SIL Open Font License 1.1. The variable "
        "font file is bundled as an app resource (res/font/noto_serif_jp.ttf) for the "
        "明朝体 comment-font setting; it is not a Maven runtime component.",
        "licenseTextAsset": "licenses/ofl-1.1-noto-serif-jp.txt",
        "components": ["font:noto-serif-jp:variable"],
    },
    # Hand-reviewed native code (docs/OSS_LICENSES.md, native runtime): the Local AI runtime built from the
    # pinned llama.cpp submodule, and the NDK's shared C++ runtime it needs. Not in the Gradle graph.
    {
        "id": "llama-cpp",
        "name": "llama.cpp",
        "licenseName": "The MIT License",
        "licenseIdentifier": "MIT",
        "notice": "llama.cpp and ggml (github.com/ggml-org/llama.cpp, revision "
        "b11039-2-g4fea119de), compiled into the native library libmemoripple_llm.so "
        "for the on-device Local AI. The text carries llama.cpp's LICENSE, the licence "
        "of the nlohmann/json header it bundles and links, and the MIT attributions its "
        "sources state for adapted code. Reviewed from the pinned submodule and the "
        "linked symbols; cpp-httplib, subprocess.h, llguidance, llamafile and KleidiAI "
        "are not linked. It is not a Maven runtime component.",
        "licenseTextAsset": "licenses/mit-llama-cpp.txt",
        "components": ["native:llama.cpp:b11039-2-g4fea119de", "native:nlohmann-json:3.12.0"],
    },
    {
        "id": "llvm-libcxx",
        "name": "LLVM libc++",
        "licenseName": "Apache License 2.0 with LLVM Exceptions",
        "licenseIdentifier": "Apache-2.0 WITH LLVM-exception",
        "notice": "The shared C++ runtime libc++_shared.so from Android NDK 29.0.14206865, "
        "packaged because the native Local AI runtime is built with it. Licence text "
        "from the NDK's LLVM toolchain NOTICE. It is not a Maven runtime component.",
        "licenseTextAsset": "licenses/apache-2.0-with-llvm-exception.txt",
        "components": ["native:llvm-libc++:ndk-29.0.14206865"],
    },
    {
        "id": "unicode-data",
        "name": "Unicode Character Database",
        "licenseName": "Unicode License v3",
        "licenseIdentifier": "Unicode-3.0",
        "notice": "Tables in llama.cpp's src/unicode-data.cpp, generated by "
        "scripts/gen-unicode-data.py from the Unicode Character Database "
        "(UnicodeData.txt; whitespace per PropList.txt; NFD through Python's "
        "unicodedata), linked into libmemoripple_llm.so. The UCD version used is not "
        "recorded in the source. Licence text: the Unicode License V3 copy in Android "
        "NDK 29.0.14206865's NOTICE.toolchain, verbatim. It is not a Maven runtime "
        "component.",
        "licenseTextAsset": "licenses/unicode-3.0.txt",
        "components": ["native:unicode-character-database:llama.cpp-b11039"],
    },
]

# Committed licence texts the generator cannot derive from Maven artifacts.
PASSTHROUGH_LICENSE_ASSETS = [
    "licenses/mit-colormath.txt",
    "licenses/mit-materialkolor.txt",
    "licenses/ofl-1.1-noto-sans-jp.txt",
    "licenses/ofl-1.1-noto-serif-jp.txt",
    "licenses/mit-llama-cpp.txt",
    "licenses/apache-2.0-with-llvm-exception.txt",
    "licenses/unicode-3.0.txt",
]


def google_third_party_notices(components: list[Component]) -> tuple[bytes, list[str]]:
    records: dict[tuple[str, str], set[str]] = {}
    source_coordinates: set[str] = set()
    for component in components:
        if component.group != "com.google.android.gms":
            continue
        artifact = binary_artifact(component)
        if artifact is None or artifact.suffix != ".aar":
            continue
        with zipfile.ZipFile(artifact) as archive:
            names = set(archive.namelist())
            if not {"third_party_licenses.json", "third_party_licenses.txt"} <= names:
                continue
            index = json.loads(archive.read("third_party_licenses.json"))
            text = archive.read("third_party_licenses.txt")
            source_coordinates.add(component.coordinate)
            for name, location in index.items():
                start = int(location["start"])
                end = start + int(location["length"])
                license_text = normalized_license_text(text[start:end])
                records.setdefault((name, license_text), set()).add(component.coordinate)
    if not records:
        raise RuntimeError("No Google Play services third-party notice bundle found")
    name_counts: dict[str, int] = {}
    for name, _ in records:
        name_counts[name] = name_counts.get(name, 0) + 1
    sections: list[str] = []
    for (name, license_text), sources in sorted(
        records.items(),
        key=lambda value: (value[0][0].casefold(), hashlib.sha256(value[0][1].encode()).hexdigest()),
    ):
        title = name
        if name_counts[name] > 1:
            artifacts = ", ".join(sorted(value.split(":")[1] for value in sources))
            title = f"{name} ({artifacts})"
        sections.append(f"{title}\n{'-' * len(title)}\n\n{license_text}\n")
    output = normalized_license_text("\n".join(sections)).encode("utf-8") + b"\n"
    return output, sorted(source_coordinates)


def generated_outputs() -> dict[Path, bytes]:
    components = parse_inventory()
    reviewed: list[dict[str, object]] = []
    library_components: dict[str, list[str]] = {key: [] for key in LIBRARY_METADATA}
    non_open_source: list[dict[str, object]] = []
    for component in components:
        license_metadata = pom_license(component.group, component.artifact, component.version)
        entry_id = display_entry_id(component, license_metadata.identifier)
        reviewed.append(
            {
                "coordinate": component.coordinate,
                "dependencyKind": component.dependency_kind.lower(),
                "licenseIdentifier": license_metadata.identifier,
                "licenseName": license_metadata.name,
                "licenseUrl": license_metadata.url,
                "licenseSource": license_metadata.source,
                "displayEntryId": entry_id,
            },
        )
        if entry_id is None:
            non_open_source.append(
                {
                    "coordinate": component.coordinate,
                    "termsName": license_metadata.name,
                    "termsUrl": license_metadata.url,
                    "metadataSource": license_metadata.source,
                },
            )
        else:
            library_components[entry_id].append(component.coordinate)

    libraries: list[dict[str, object]] = []
    for entry_id, metadata in LIBRARY_METADATA.items():
        name, license_name, license_identifier, asset, notice = metadata
        coordinates = sorted(library_components[entry_id])
        if not coordinates:
            raise RuntimeError(f"Reviewed library has no runtime components: {entry_id}")
        libraries.append(
            {
                "id": entry_id,
                "name": name,
                "licenseName": license_name,
                "licenseIdentifier": license_identifier,
                "notice": notice,
                "licenseTextAsset": asset,
                "components": coordinates,
            },
        )

    google_notices, google_notice_sources = google_third_party_notices(components)
    libraries.append(
        {
            "id": "google-play-services-third-party",
            "name": "Google Play services – third-party notices",
            "licenseName": "Multiple open-source licenses",
            "licenseIdentifier": None,
            "notice": "License text content comes from third_party_licenses.json/text bundled "
            "in the resolved Google Play services AARs. Whitespace and separator lines are "
            "normalized before deterministic grouping.",
            "licenseTextAsset": "licenses/google-play-services-third-party.txt",
            "components": google_notice_sources,
        },
    )
    libraries.extend(STATIC_LIBRARIES)
    libraries.sort(key=lambda value: str(value["name"]).casefold())

    apache_component = next(
        value for value in components
        if value.coordinate.startswith("androidx.activity:activity-compose:")
    )
    bsd_component = next(
        value for value in components
        if value.coordinate.startswith(
            "androidx.datastore:datastore-preferences-external-protobuf:",
        )
    )
    apache_text = normalized_license_text(
        zip_entry(
            apache_component,
            "META-INF/androidx/activity/activity-compose/LICENSE.txt",
        ),
    ).encode("utf-8") + b"\n"
    bsd_text = normalized_license_text(
        zip_entry(
            bsd_component,
            "META-INF/androidx/datastore/datastore-preferences-external-protobuf/LICENSE.txt",
        ),
    ).encode("utf-8") + b"\n"

    catalog = {
        "schemaVersion": 1,
        "generatedFrom": ":app:releaseRuntimeClasspath",
        "componentCount": len(components),
        "directComponentCount": sum(value.dependency_kind == "DIRECT" for value in components),
        "transitiveComponentCount": sum(
            value.dependency_kind == "TRANSITIVE" for value in components
        ),
        "libraries": libraries,
        "nonOpenSourceTerms": sorted(
            non_open_source,
            key=lambda value: str(value["coordinate"]),
        ),
        "runtimeComponents": reviewed,
    }
    catalog_bytes = (
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")
    outputs = {
        CATALOG_FILE: catalog_bytes,
        LICENSE_ROOT / "apache-2.0.txt": apache_text,
        LICENSE_ROOT / "bsd-3-clause.txt": bsd_text,
        LICENSE_ROOT / "google-play-services-third-party.txt": google_notices,
    }
    for relative in PASSTHROUGH_LICENSE_ASSETS:
        path = ASSET_ROOT / relative
        if not path.is_file():
            raise RuntimeError(f"Missing committed licence asset: {relative}")
        outputs[path] = path.read_bytes()
    return outputs


def write_or_verify(write: bool) -> None:
    outputs = generated_outputs()
    mismatches: list[str] = []
    for path, content in outputs.items():
        if write:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        elif not path.exists() or path.read_bytes() != content:
            mismatches.append(str(path.relative_to(ROOT)))
    expected = set(outputs)
    if LICENSE_ROOT.exists():
        unexpected = sorted(
            value for value in LICENSE_ROOT.iterdir()
            if value.is_file() and value not in expected
        )
        mismatches.extend(str(value.relative_to(ROOT)) for value in unexpected)
    if mismatches:
        raise RuntimeError(
            "OSS assets are missing or stale:\n" + "\n".join(sorted(mismatches)),
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--write",
        action="store_true",
        help="Explicitly replace committed OSS assets after dependency/license review.",
    )
    args = parser.parse_args()
    try:
        write_or_verify(args.write)
    except Exception as error:
        print(error, file=sys.stderr)
        return 1
    print("OSS assets updated." if args.write else "OSS assets verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
