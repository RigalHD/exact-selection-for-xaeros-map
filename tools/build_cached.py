#!/usr/bin/env python3
"""Build with Java 21 and an existing Loom cache, without running Gradle or using the network.

python3 tools/build_cached.py --cache /path/to/gradle-home/caches --deps /path/to/xaero-jars
Xaero JARs are compile dependencies only; they are never packaged in the result.
"""
import argparse
import json
from pathlib import Path
import os
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
# build.gradle is the single source of the release version.
VERSION = re.search(r"^version\s*=\s*'([^']+)'", (ROOT / 'build.gradle').read_text(), re.M).group(1)


def run(args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT)


def one(paths, what):
    paths = list(paths)
    if len(paths) != 1:
        raise SystemExit(f'Expected one {what}, found {len(paths)}: {paths}')
    return paths[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', required=True, type=Path)
    parser.add_argument('--deps', required=True, type=Path)
    args = parser.parse_args()
    cache, deps = args.cache.resolve(), args.deps.resolve()
    mc = one((cache / 'fabric-loom/minecraftMaven/net/minecraft/minecraft-merged').glob('1.21.11-*/*.jar'), 'named Minecraft 1.21.11 JAR')
    intermediary = one((cache / 'fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-intermediary').glob('1.21.11-*/*.jar'), 'intermediary Minecraft 1.21.11 JAR')
    mapping_jar = one((cache / 'fabric-loom/1.21.11').glob('*/mappings.jar'), 'Loom mappings JAR')
    libraries = [p for p in (cache / 'modules-2/files-2.1').rglob('*.jar') if not p.name.endswith('-sources.jar')]
    remapper_libs = [p for p in libraries if any(group in str(p) for group in
                     ('/net.fabricmc/tiny-remapper/', '/net.fabricmc/mapping-io/', '/org.ow2.asm/'))]
    raw_deps = list(deps.glob('*.jar'))
    ids = {}
    for jar in raw_deps:
        with zipfile.ZipFile(jar) as z:
            mod = json.loads(z.read('fabric.mod.json'))
            ids[mod['id']] = mod['version']
    for mod, expected in {'xaeroworldmap': '1.41.0', 'xaerominimap': '26.1.0', 'xaerolib': '1.1.15'}.items():
        if ids.get(mod) != expected:
            raise SystemExit(f'{mod}: expected {expected}, got {ids.get(mod)}')
    build = ROOT / 'build/cached'
    if build.exists(): shutil.rmtree(build)
    named, classes, tests = build / 'named', build / 'classes', build / 'test-classes'
    for path in (named, classes, tests): path.mkdir(parents=True, exist_ok=True)
    mapping = build / 'mappings.tiny'
    with zipfile.ZipFile(mapping_jar) as z: mapping.write_bytes(z.read('mappings/mappings.tiny'))
    remapper = ['java', '-cp', os.pathsep.join(map(str, remapper_libs)), 'net.fabricmc.tinyremapper.Main']
    for jar in raw_deps:
        run([*remapper, jar, named / jar.name, mapping, 'intermediary', 'named', intermediary, *raw_deps])
    cp = os.pathsep.join(map(str, [mc, *named.glob('*.jar'), *libraries]))
    run(['javac', '-encoding', 'UTF-8', '--release', '21', '-proc:none', '-cp', cp,
         '-d', classes, *sorted((ROOT / 'src/main/java').rglob('*.java'))])
    run(['javac', '-encoding', 'UTF-8', '--release', '21', '-proc:none', '-cp', str(classes) + os.pathsep + cp,
         '-d', tests, *sorted((ROOT / 'src/test/java').rglob('*.java'))])
    test_cp = os.pathsep.join([str(tests), str(classes), cp])
    for test in sorted((ROOT / 'src/test/java/dev/exactselection').glob('*Check.java')):
        run(['java', '-ea', '-cp', test_cp, 'dev.exactselection.' + test.stem])
    resources = ROOT / 'src/main/resources'
    for source in resources.rglob('*'):
        if source.is_file():
            dest = classes / source.relative_to(resources)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if source.name == 'fabric.mod.json':
                dest.write_text(source.read_text().replace('${version}', VERSION))
            else: shutil.copy2(source, dest)
    libs = ROOT / 'build/libs'
    libs.mkdir(parents=True, exist_ok=True)
    dev_jar = build / 'named.jar'
    run(['jar', '--create', '--file', dev_jar, '-C', classes, '.'])
    artifact = libs / f'exact-selection-for-xaeros-map-{VERSION}.jar'
    artifact.unlink(missing_ok=True)
    run([*remapper, '--mixin', dev_jar, artifact, mapping, 'named', 'intermediary', mc, *named.glob('*.jar'), *libraries])
    with zipfile.ZipFile(artifact) as z:
        assert not any(n.startswith(('xaero/', 'net/minecraft/', 'net/fabricmc/')) for n in z.namelist())
        assert not any('Check.class' in n for n in z.namelist())
        assert b'net/minecraft/class_310' in z.read('dev/exactselection/ExactSelectionController.class')
        assert json.loads(z.read('fabric.mod.json'))['version'] == VERSION
    print(f'Built and checked: {artifact}')


if __name__ == '__main__': main()
