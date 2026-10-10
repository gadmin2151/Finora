#!/usr/bin/env python3
"""Offline Kotlin/JUnit verification without Gradle daemons or shared build outputs."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[2]
cache = root / '.local/nightly-20261009/gradle-home'
lib = next(cache.glob('wrapper/dists/gradle-9.7.1-bin/*/gradle-9.7.1/lib'))
java = root / '.local/jdk21/jdk-21.0.12.1+1/Contents/Home/bin/java'
classes = root / '.local/native-20261010/push-isolated-classes'
classes.mkdir(parents=True, exist_ok=True)
modules = cache / 'caches/modules-2/files-2.1'

def jar(module, version):
    return next((modules / module / version).glob('*/*.jar'))

cp = os.pathsep.join(map(str, [lib / 'kotlin-stdlib-2.4.0.jar', lib / 'annotations-24.0.1.jar', lib / 'kotlinx-coroutines-core-jvm-1.10.2.jar', lib / 'kotlinx-serialization-core-jvm-1.11.0.jar', lib / 'kotlinx-serialization-json-jvm-1.11.0.jar', jar('junit/junit', '4.13.2'), jar('org.hamcrest/hamcrest-core', '1.3')]))
sources = [root / 'android/app/src/main/java/work/gadmin/finora/notifications' / name for name in ('PushModels.kt', 'PushTapPolicy.kt', 'PushUpdatePolicy.kt', 'PushReceiptRouting.kt', 'PushController.kt', 'PushApiContract.kt')]
sources += [root / 'android/app/src/main/java/work/gadmin/finora/data/NativeNavigation.kt']
sources += [root / 'android/app/src/test/java/work/gadmin/finora/notifications' / name for name in ('PushControllerTest.kt', 'PushApiContractTest.kt', 'PushUpdatePolicyTest.kt', 'PushReceiptRoutingTest.kt', 'PushTapPolicyTest.kt')]
subprocess.run([str(java), '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(classes), *map(str, sources)], check=True)
subprocess.run([str(java), '-cp', str(classes) + os.pathsep + cp, 'org.junit.runner.JUnitCore', 'work.gadmin.finora.notifications.PushControllerTest', 'work.gadmin.finora.notifications.PushApiContractTest', 'work.gadmin.finora.notifications.PushUpdatePolicyTest', 'work.gadmin.finora.notifications.PushReceiptRoutingTest', 'work.gadmin.finora.notifications.PushTapPolicyTest'], check=True)
