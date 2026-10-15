#!/usr/bin/env python3
"""Create the public release update manifest from the APK's actual package metadata."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--aapt2', type=Path, required=True)
args = parser.parse_args()
if not args.apk.is_file() or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,119}\.apk', args.apk.name):
    parser.error('Expected a local APK with a safe release filename')
badging = subprocess.check_output([str(args.aapt2), 'dump', 'badging', str(args.apk)], text=True)
package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
minimum = re.search(r"(?:minSdkVersion|sdkVersion):'([0-9]+)'", badging)
if package is None or minimum is None or package[1] != 'com.qingji.app':
    raise SystemExit('Cannot read the intended application metadata')
if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', package[3]):
    raise SystemExit('Unsupported version name')
size = args.apk.stat().st_size
if not 0 < size <= 128 * 1024 * 1024:
    raise SystemExit('APK exceeds updater download limit')
metadata = dict(schemaVersion=1, applicationId=package[1], versionCode=int(package[2]),
                versionName=package[3], minSdk=int(minimum[1]), apkName=args.apk.name,
                apkSize=size, sha256=hashlib.file_digest(args.apk.open('rb'), 'sha256').hexdigest())
args.output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
print('Update manifest generated for ' + package[3])
