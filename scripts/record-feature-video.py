"""Run one existing native fixture in a fresh isolated profile with cinematic capture.
Paths and optional saved artifacts are explicit local JSON inputs, never source defaults.
This launcher never compiles Java, overwrites production worlds or uploads videos.
"""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, sqlite3, subprocess, sys, time, uuid


def run(config_file, scene_name):
    config = json.loads(Path(config_file).read_text(encoding='utf-8-sig'))
    scene = config['scenes'][scene_name]
    version = Path(config['version_directory']).resolve()
    mcroot = version.parent.parent
    artifact = Path(config['artifact']).resolve()
    source = Path(config['source_world']).resolve()
    assert artifact.is_file() and (source / 'level.dat').is_file()
    output = Path(config['output']).resolve()
    profile = output / (scene_name + '-' + str(uuid.uuid4()))
    game = profile / 'game'
    mods = game / 'mods'
    mods.mkdir(parents=True)
    for file in [artifact] + [Path(p) for p in config['base_mods'] + scene.get('extra_mods', [])]:
        assert file.is_file(), str(file)
        shutil.copy2(file, mods / file.name)
    shutil.copytree(source, game / 'saves' / 'SmokeWorld',
                    ignore=shutil.ignore_patterns('session.lock', 'mineagent-save-identity.json'))
    for destination, filename in scene.get('inputs', {}).items():
        target = (game / destination).resolve()
        assert target.is_relative_to(game.resolve()) and target != game.resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(filename, target)
    if scene.get('provider', False) or scene.get('provider_config', False):
        oldpath = Path(config['provider_db']).resolve()
        with sqlite3.connect(oldpath.as_uri() + '?mode=ro', uri=True) as old:
            rows = old.execute("select key,value,secret from mineagent_config where key in ('provider.openai.apiKey','provider.openai.baseUrl')").fetchall()
        assert len(rows) == 2 and any(secret and value for key, value, secret in rows), 'PROVIDER_NOT_CONFIGURED'
        destination = game / 'mineagent-runtime-data/runtime.db'
        destination.parent.mkdir()
        with sqlite3.connect(destination) as db:
            db.execute('CREATE TABLE mineagent_config(key TEXT PRIMARY KEY,value TEXT NOT NULL,secret INTEGER NOT NULL CHECK(secret IN (0,1)))')
            db.executemany('insert into mineagent_config values(?,?,?)', rows + [
                ('provider.openai.model', 'deepseek-flash', 0), ('provider.priority', 'openai-compatible,ollama', 0),
                ('voice.output.enabled', 'false', 0)])
    (game / 'options.txt').write_text('lang:zh_cn\npauseOnLostFocus:false\nmaxFps:60\nrenderDistance:8\nsimulationDistance:6\nfullscreen:false\nautoJump:false\nfov:0.0\nguiScale:3\ntutorialStep:none\n', encoding='utf-8')
    (game / 'cinematic.json').write_text(json.dumps(scene['camera'], ensure_ascii=False), encoding='utf-8')
    manifest = json.loads((version / (version.name + '.json')).read_text(encoding='utf-8'))

    def allowed(rules):
        if not rules:
            return True
        result = False
        for rule in rules:
            if 'features' in rule:
                continue
            platform = rule.get('os', {})
            if platform.get('name', 'windows') != 'windows':
                continue
            if 'arch' in platform and not re.search(platform['arch'], 'amd64'):
                continue
            if 'version' in platform and not re.search(platform['version'], '10.0'):
                continue
            result = rule.get('action') == 'allow'
        return result

    libraries = []
    for library in manifest['libraries']:
        if not allowed(library.get('rules')):
            continue
        relative = library.get('downloads', {}).get('artifact', {}).get('path')
        if relative:
            file = mcroot / 'libraries' / relative
            assert file.is_file(), relative
            libraries.append(str(file))
    libraries.append(str(version / (version.name + '.jar')))
    natives = version / (version.name + '-natives')
    command = [config['java'], '--sun-misc-unsafe-memory-access=allow', '--enable-native-access=ALL-UNNAMED',
               '-Djava.library.path=' + str(natives), '-Djna.tmpdir=' + str(natives),
               '-Dorg.lwjgl.system.SharedLibraryExtractPath=' + str(natives), '-Dio.netty.native.workdir=' + str(natives),
               '-Dminecraft.launcher.brand=divzero-cinematic', '-Dminecraft.launcher.version=1.0',
               '-DlibraryDirectory=' + str(mcroot / 'libraries'), '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED',
               '--add-exports=jdk.naming.dns/com.sun.jndi.dns=java.naming', '-Dmineagent.cinematic=true',
               '-Dmineagent.cinematic.ffmpeg=' + config['ffmpeg'], '-Dfile.encoding=UTF-8',
               '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Xmx6G']
    for key, value in scene['properties'].items():
        assert re.fullmatch(r'mineagent\.[A-Za-z0-9.]+', key)
        command.append('-D' + key + '=' + str(value).lower() if isinstance(value, bool) else '-D' + key + '=' + str(value))
    command += ['-cp', ';'.join(libraries), 'net.neoforged.fml.startup.Client', '--username', 'DivZero',
                '--version', version.name, '--gameDir', str(game), '--assetsDir', str(mcroot / 'assets'),
                '--assetIndex', manifest.get('assetIndex', {}).get('id', '30'), '--uuid', '00000000000000000000000000000001',
                '--accessToken', '0', '--clientId', '0', '--xuid', '0', '--versionType', 'release',
                '--quickPlaySingleplayer=SmokeWorld', '--quickPlayPath=' + str(game / 'quickplay.log'),
                '--width', '1920', '--height', '1080', '--fml.neoForgeVersion', '26.1.2.106',
                '--fml.mcVersion', '26.1.2', '--fml.neoFormVersion', '1']
    env = os.environ.copy()
    (profile / 'profile').mkdir()
    env['LOCALAPPDATA'] = str(profile / 'profile')
    audit = game / 'real-provider-audit'
    audit.mkdir()
    env['MINEAGENT_REAL_PROVIDER_AUDIT_DIR'] = str(audit)
    env['MINEAGENT_REAL_PROVIDER_MAX_CALLS'] = 'unlimited' if scene.get('provider', False) else '0'
    env['MINEAGENT_REAL_PROVIDER_PARALLEL'] = 'true'
    (profile / 'scene.json').write_text(json.dumps(scene, ensure_ascii=False, indent=2), encoding='utf-8')
    with (profile / 'console.log').open('wb') as log:
        process = subprocess.Popen(command, cwd=game, env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        (profile / 'process.json').write_text(json.dumps({'pid': process.pid, 'artifactSha256': hashlib.sha256(artifact.read_bytes()).hexdigest()}), encoding='utf-8')
        print(json.dumps({'status': 'FILMING_STARTED', 'scene': scene_name, 'pid': process.pid, 'profile': str(profile)}), flush=True)
        try:
            deadline = time.monotonic() + scene.get('timeout', 2400)
            approved = False
            reviewed_host = set()
            if scene.get('reviewed_host_sha256'):
                raw = game / 'runtime-item-source.json'
                assert hashlib.sha256(raw.read_bytes()).hexdigest() == scene['reviewed_host_sha256'], 'HOST_SOURCE_CHANGED'
                for row in json.loads(raw.read_text(encoding='utf-8'))['receipts']:
                    args = row['arguments']
                    if row['tool'] == 'python_execute':
                        reviewed_host.add(('PYTHON', args['script']))
                    elif row['tool'] == 'python_install_packages':
                        reviewed_host.add(('INSTALL_PACKAGES', '专用 Python 环境安装（PyPI）：\n' + '\n'.join(args['packages'])))
            while process.poll() is None:
                if time.monotonic() >= deadline:
                    raise subprocess.TimeoutExpired(command, scene.get('timeout', 2400))
                pending = game / 'runtime-item-smoke/pending-approval.json'
                if scene.get('reviewed_saved_sha256') and not approved and pending.exists():
                    raw = game / 'runtime-item-source.json'
                    assert hashlib.sha256(raw.read_bytes()).hexdigest() == scene['reviewed_saved_sha256'], 'SAVED_SOURCE_CHANGED'
                    pack = json.loads(pending.read_text(encoding='utf-8'))['package']
                    digest = pack['canonicalSha256']
                    assert re.fullmatch('[a-f0-9]{64}', digest), 'CURRENT_PACKAGE_HASH_REQUIRED'
                    (pending.parent / 'approve.txt').write_text(digest, encoding='utf-8')
                    (profile / 'saved-approval.json').write_text(json.dumps({'sourceSha256': scene['reviewed_saved_sha256'], 'packageSha256': digest, 'reviewedSavedArtifact': True}), encoding='utf-8')
                    approved = True
                if reviewed_host:
                    for request in (game / 'python-host-smoke').glob('command-*.json'):
                        pending_host = json.loads(request.read_text(encoding='utf-8'))
                        if (pending_host['kind'], pending_host['script']) not in reviewed_host:
                            continue  # The fixture's deliberate negative request is never approved.
                        digest = pending_host['hash']
                        assert re.fullmatch('[a-f0-9]{64}', digest)
                        review = request.parent / ('approve-' + pending_host['operation'] + '.txt')
                        if not review.exists():
                            review.write_text(digest, encoding='utf-8')
                time.sleep(.25)
            code = process.returncode
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=30)
            code = -1
    receipt = game / 'cinematic/recording.json'
    fixture_failures = []
    for directory in game.iterdir():
        if directory.is_dir() and (directory.name.endswith('-smoke') or directory.name == 'native-acceptance'):
            for item in directory.glob('*.json'):
                if 'failure' in item.name:
                    fixture_failures.append(str(item.relative_to(game)))
                elif item.name in ('server-final.json', 'server.json', 'result.json', 'client.json'):
                    state = json.loads(item.read_text(encoding='utf-8-sig'))
                    if state.get('failures') or 'FAIL' in str(state.get('status', '')):
                        fixture_failures.append(str(item.relative_to(game)))
    result = {'scene': scene_name, 'exitCode': code, 'profile': str(profile), 'recording': json.loads(receipt.read_text()) if receipt.exists() else None,
              'fixtureFailures': fixture_failures,
              'providerStarted': len(list(audit.glob('*-started.json'))), 'providerCompleted': len(list(audit.glob('*-completed.json')))}
    (profile / 'launcher-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False), flush=True)
    return 0 if code == 0 and not fixture_failures and result['recording'] and not result['recording']['error'] else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--scene', required=True)
    args = parser.parse_args()
    sys.exit(run(args.config, args.scene))
