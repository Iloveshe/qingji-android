#!/usr/bin/env python3
"""Install verified official build tools inside /workspace, then build QingJi.

Uses the inherited proxy and TLS verification. Never prints credentials.
This script accepts the Android SDK licenses as part of the requested install.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
TOOLS = Path('/workspace/toolchains')
DOWNLOADS = TOOLS / 'downloads'
SDK = TOOLS / 'android-sdk'
JDK = TOOLS / 'jdk-17'
GRADLE_CACHE = TOOLS / 'gradle-cache'
for folder in (DOWNLOADS, SDK, GRADLE_CACHE, TOOLS / 'android-user'):
    folder.mkdir(parents=True, exist_ok=True)


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def download(url, target, checksum=None, algorithm='sha256'):
    def valid():
        if not target.exists():
            return False
        if checksum is None:
            return False
        h = hashlib.new(algorithm)
        with target.open('rb') as f:
            for block in iter(lambda: f.read(1024 * 1024), b''):
                h.update(block)
        return h.hexdigest().lower() == checksum.lower()
    if valid():
        return
    temporary = target.with_suffix(target.suffix + '.part')
    run(['curl', '--fail', '--location', '--show-error', '--connect-timeout', '10',
         '--max-time', '300', '--retry', '2', '--retry-delay', '1', url, '-o', temporary])
    temporary.replace(target)
    if checksum and not valid():
        raise RuntimeError(f'Checksum mismatch: {target.name}')


def tag(node):
    return node.tag.rsplit('}', 1)[-1]


def child(node, name):
    return next((n for n in node if tag(n) == name), None)


def value(node, name, fallback=''):
    n = child(node, name)
    return n.text.strip() if n is not None and n.text else fallback


print('Installing full JDK 17 from Eclipse Adoptium...', flush=True)
if not (JDK / 'bin/javac').exists():
    metadata = DOWNLOADS / 'jdk-metadata.json'
    download('https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse', metadata)
    assets = json.loads(metadata.read_text())
    package = assets[0]['binary']['package']
    archive = DOWNLOADS / 'jdk17.tar.gz'
    download(package['link'], archive, package['checksum'])
    staging = TOOLS / 'jdk-staging'
    staging.mkdir(exist_ok=True)
    with tarfile.open(archive, 'r:gz') as t:
        t.extractall(staging, filter='data')
    extracted = next(p for p in staging.iterdir() if p.is_dir() and (p / 'bin/javac').exists())
    if JDK.exists():
        shutil.rmtree(JDK)
    extracted.rename(JDK)

env = dict(os.environ)
env['JAVA_HOME'] = str(JDK)
env['PATH'] = str(JDK / 'bin') + os.pathsep + env.get('PATH', '')
env['ANDROID_HOME'] = str(SDK)
env['ANDROID_SDK_ROOT'] = str(SDK)
env['ANDROID_USER_HOME'] = str(TOOLS / 'android-user')
env['GRADLE_USER_HOME'] = str(GRADLE_CACHE)

print('Installing Android command-line tools from Google...', flush=True)
sdkmanager = SDK / 'cmdline-tools/latest/bin/sdkmanager'
if not sdkmanager.exists():
    repository = DOWNLOADS / 'sdk-repository.xml'
    download('https://dl.google.com/android/repository/repository2-1.xml', repository)
    root = ET.parse(repository).getroot()
    candidates = []
    for remote in root:
        package_path = remote.attrib.get('path', '')
        if not package_path.startswith('cmdline-tools;'):
            continue
        channel = child(remote, 'channelRef')
        if channel is not None and channel.attrib.get('ref', 'channel-0') != 'channel-0':
            continue
        archives = child(remote, 'archives')
        if archives is None:
            continue
        revision = child(remote, 'revision')
        rev = tuple(int(value(revision, k, '0')) for k in ('major', 'minor', 'micro')) if revision is not None else (0, 0, 0)
        for archive in archives:
            if value(archive, 'host-os') != 'linux':
                continue
            complete = child(archive, 'complete')
            if complete is None:
                continue
            checksum = child(complete, 'checksum')
            if checksum is None or not checksum.text:
                continue
            candidates.append((package_path.endswith(';latest'), rev, value(complete, 'url'),
                               checksum.text.strip(), checksum.attrib.get('type', 'sha1')))
    if not candidates:
        raise RuntimeError('Google repository did not list Linux command-line tools')
    _, revision, archive_url, checksum, algorithm = max(candidates)
    archive = DOWNLOADS / 'android-commandline-tools.zip'
    download('https://dl.google.com/android/repository/' + archive_url, archive, checksum, algorithm)
    staging = TOOLS / 'android-cli-staging'
    staging.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            target = (staging / entry.filename).resolve()
            if not target.is_relative_to(staging.resolve()):
                raise RuntimeError('Unsafe SDK archive path')
        z.extractall(staging)
    destination = SDK / 'cmdline-tools/latest'
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        shutil.rmtree(destination)
    (staging / 'cmdline-tools').rename(destination)
    for executable in (destination / 'bin').iterdir():
        if executable.is_file():
            executable.chmod(executable.stat().st_mode | 0o111)

proxy_url = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy') or os.environ.get('HTTP_PROXY') or os.environ.get('http_proxy')
sdk_proxy = []
gradle_proxy = []
if proxy_url:
    proxy = urlsplit(proxy_url)
    if not proxy.hostname:
        raise RuntimeError('Configured proxy has no hostname')
    port = proxy.port or (443 if proxy.scheme == 'https' else 80)
    sdk_proxy = ['--proxy=http', '--proxy_host=' + proxy.hostname, '--proxy_port=' + str(port)]
    for scheme in ('http', 'https'):
        gradle_proxy += [f'-D{scheme}.proxyHost={proxy.hostname}', f'-D{scheme}.proxyPort={port}']

# Java needs the managed environment CA too; preserve inherited runtime variables.
truststore = Path('/etc/ssl/certs/java/cacerts')
java_opts = []
if truststore.exists():
    java_opts = ['-Djavax.net.ssl.trustStore=' + str(truststore)]
env['JAVA_OPTS'] = env.get('JAVA_OPTS', '') + ' ' + ' '.join(java_opts)

base = [sdkmanager, '--sdk_root=' + str(SDK), *sdk_proxy]
print('Accepting required SDK licenses and installing API 36 / Build Tools 35...', flush=True)
run([*base, '--licenses'], input=('y\n' * 100).encode(), env=env)
run([*base, 'platforms;android-36', 'build-tools;35.0.0', 'platform-tools'], input=('y\n' * 100).encode(), env=env)
(PROJECT / 'local.properties').write_text('sdk.dir=' + str(SDK) + '\n')
(TOOLS / 'qingji-build-paths.json').write_text(json.dumps({'sdk': str(SDK), 'jdk': str(JDK), 'gradle_cache': str(GRADLE_CACHE)}, indent=2))
print('Android SDK installed. Building and linting the app...', flush=True)
run([PROJECT / 'gradlew', '--no-daemon', *gradle_proxy, *java_opts, ':app:assembleDebug', ':app:lintDebug'], env=env)
apk = PROJECT / 'app/build/outputs/apk/debug/app-debug.apk'
if not apk.exists():
    raise RuntimeError('Build returned without an APK')
metadata = json.loads((apk.parent / 'output-metadata.json').read_text())
version = metadata['elements'][0]['versionName']
delivery = Path('/workspace/library-files') / ('qingji-' + version + '-debug.apk')
delivery.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(apk, delivery)
print('APK generated: ' + str(delivery), flush=True)
