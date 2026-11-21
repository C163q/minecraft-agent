"""Build the client mod against a locally installed 26.3 Fabric launcher profile."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def game_classpath(profile):
    profile = profile.resolve()
    libraries = profile.parents[2] / "libraries"
    jars = [profile.with_suffix(".jar")]
    for lib in json.loads(profile.read_text())["libraries"]:
        parts = lib["name"].split(":")
        group, name, version = parts[:3]
        default = Path(*group.split("."), name, version, "-".join(parts[1:]) + ".jar")
        artifact = lib.get("downloads", {}).get("artifact", {}).get("path", default)
        path = libraries / artifact
        if path.is_file():
            jars.append(path)
    return os.pathsep.join(map(str, jars))


def build_jar(source, destination, classpath):
    destination.parent.mkdir(exist_ok=True, parents=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as work:
        classes = Path(work) / "classes"
        classes.mkdir()
        subprocess.run(["javac", "--release", "25", "-proc:none", "-cp", classpath, "-d", classes,
                        *sorted((source / "java").rglob("*.java"))], check=True)
        jar = Path(work) / destination.name
        project_root = Path(__file__).resolve().parents[1]
        subprocess.run(["jar", "cf", jar, "-C", classes, ".", "-C", source / "resources", ".",
                        "-C", project_root, "LICENSE"], check=True)
        jar.replace(destination)


if __name__ == "__main__":
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path, help="Resolved Fabric version JSON in versions/<instance>/")
    args = parser.parse_args()
    jar = root / "build/minecraft-agent.jar"
    build_jar(root / "src/main", jar, game_classpath(args.profile))
    print(jar)
