import argparse
import importlib
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
initialize = importlib.import_module("init")
deployment = importlib.import_module("deploy")


class ConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="finora-config-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def test_private_secrets_and_https_configuration(self):
        initialize.initialize(
            self.root,
            domain="finance.example.com",
            image="ghcr.io/example/finora:latest",
            local_ai=True,
        )
        env = (self.root / ".env").read_text()
        self.assertIn("COMPOSE_FILE=compose.yaml:compose.tls.yaml\n", env)
        self.assertIn("APP_URL=https://finance.example.com\n", env)
        self.assertIn("COOKIE_SECURE=true\n", env)
        self.assertIn("BIND_ADDRESS=127.0.0.1\n", env)
        self.assertIn("COMPOSE_PROFILES=local-ai\n", env)
        for path in [self.root / ".env", self.root / ".secrets/admin_password"]:
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertGreaterEqual(
            len((self.root / ".secrets/admin_password").read_text().strip()), 24
        )

    def test_rerun_preserves_every_byte(self):
        initialize.initialize(self.root)
        paths = [self.root / ".env", self.root / ".secrets/admin_password"]
        before = [path.read_bytes() for path in paths]
        self.assertFalse(
            initialize.initialize(self.root, domain="another.example.com", port=9090)
        )
        self.assertEqual(before, [path.read_bytes() for path in paths])

    def test_missing_env_does_not_rotate_an_existing_secret(self):
        initialize.initialize(self.root)
        (self.root / ".env").unlink()
        password = (self.root / ".secrets/admin_password").read_bytes()
        with self.assertRaises(SystemExit):
            initialize.initialize(self.root)
        self.assertEqual(password, (self.root / ".secrets/admin_password").read_bytes())

    def test_missing_secret_does_not_invent_a_new_password(self):
        initialize.initialize(self.root)
        (self.root / ".secrets/admin_password").unlink()
        with self.assertRaises(SystemExit):
            initialize.initialize(self.root)
        self.assertFalse((self.root / ".secrets/admin_password").exists())

    def test_invalid_values_do_not_create_files(self):
        for value in [
            "https://example.com",
            "example.com/path",
            "foo;evil.com",
            "x\nSECRET_KEY=bad",
            "-bad.example.com",
        ]:
            with (
                self.subTest(value=value),
                self.assertRaises(argparse.ArgumentTypeError),
            ):
                initialize.initialize(self.root, domain=value)
        for value in ["--privileged", "repo:tag\nBIND_ADDRESS=0.0.0.0", "$(id)"]:
            with (
                self.subTest(value=value),
                self.assertRaises(argparse.ArgumentTypeError),
            ):
                initialize.initialize(self.root, image=value)
        for value in ["0", "65536", "not-a-port"]:
            with (
                self.subTest(value=value),
                self.assertRaises(argparse.ArgumentTypeError),
            ):
                initialize.app_port(value)
        self.assertEqual(list(self.root.iterdir()), [])

    def test_existing_private_file_is_never_overwritten(self):
        target = self.root / "secret"
        initialize.private_file(target, "original")
        with self.assertRaises(FileExistsError):
            initialize.private_file(target, "replacement")
        self.assertEqual(target.read_text(), "original")


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="finora-deploy-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        initialize.initialize(self.root)
        self.addCleanup(patch.stopall)
        patch.object(deployment, "ROOT", self.root).start()
        self.events = []
        self.installed = True
        self.existing_db = True
        self.failure = None
        self.project = {
            "services": {
                "api": {
                    "image": "ghcr.io/example/finora:latest",
                    "environment": {"APP_URL": "http://localhost:8088"},
                }
            }
        }
        patch.object(deployment, "config", lambda: self.project).start()
        patch.object(deployment, "compose", self.compose).start()
        patch.object(
            deployment, "backup", lambda path: self.events.append(("backup", str(path)))
        ).start()

    def compose(self, *args, capture=False):
        self.events.append(args)
        if args[0] == self.failure:
            raise subprocess.CalledProcessError(1, ["docker", "compose", *args])
        output = ""
        if args[0] == "ps":
            output = "test-container\n" if self.existing_db else ""
        elif args[0] == "exec" and args[3] == "psql":
            output = "t\n" if self.installed else "f\n"
        elif args == ("config", "--services"):
            output = "db\ninit\napi\nworker\n"
        return subprocess.CompletedProcess(args, 0, stdout=output)

    def test_existing_installation_backed_up_before_migration(self):
        deployment.deploy()
        events = [event[0] for event in self.events]
        self.assertLess(events.index("pull"), events.index("backup"))
        self.assertLess(events.index("backup"), events.index("stop"))
        self.assertLess(events.index("stop"), events.index("run"))
        self.assertLess(events.index("run"), events.index("up"))
        self.assertIn(("run", "--rm", "--no-deps", "init"), self.events)
        start = next(event for event in self.events if event[0] == "up")
        self.assertIn("--no-build", start)
        self.assertIn("--wait", start)
        self.assertNotIn("ollama", start)

    def test_registry_failure_does_not_stop_running_app(self):
        self.failure = "pull"
        with self.assertRaises(subprocess.CalledProcessError):
            deployment.deploy()
        self.assertEqual([event[0] for event in self.events], ["pull"])

    def test_backup_failure_prevents_migration(self):
        with (
            patch.object(
                deployment, "backup", side_effect=OSError("test backup failure")
            ),
            self.assertRaises(OSError),
        ):
            deployment.deploy()
        self.assertNotIn("stop", [event[0] for event in self.events])
        self.assertNotIn("run", [event[0] for event in self.events])

    def test_migration_failure_never_starts_new_application(self):
        self.failure = "run"
        with self.assertRaises(subprocess.CalledProcessError):
            deployment.deploy()
        self.assertNotIn("up", [event[0] for event in self.events])

    def test_first_install_has_no_backup_and_runs_migration(self):
        self.existing_db = False
        self.installed = False
        deployment.deploy()
        self.assertNotIn("backup", [event[0] for event in self.events])
        self.assertIn(("run", "--rm", "--no-deps", "init"), self.events)

    def test_local_image_is_built_without_registry_pull(self):
        self.project["services"]["api"]["image"] = "finora:local"
        deployment.deploy()
        self.assertEqual(self.events[0], ("build", "api"))
        self.assertNotIn("pull", [event[0] for event in self.events])

    def test_missing_configuration_refuses_to_deploy(self):
        (self.root / ".env").unlink()
        with self.assertRaises(SystemExit):
            deployment.deploy()
        self.assertEqual(self.events, [])

    @unittest.skipUnless(os.name == "posix", "POSIX deployment lock")
    def test_concurrent_deployment_is_rejected(self):
        import fcntl

        with (self.root / ".deploy.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(SystemExit):
                deployment.main()
        self.assertEqual(self.events, [])


class BackupRecoveryTests(unittest.TestCase):
    def test_failed_backup_resumes_exact_containers_without_init_dependency(self):
        module = importlib.import_module("backup")
        events = []

        def compose(*args, **kwargs):
            if args == ("ps", "--status", "running", "--services"):
                return subprocess.CompletedProcess(args, 0, stdout="api\nworker\ndb\n")
            if args[:2] == ("ps", "-q"):
                return subprocess.CompletedProcess(
                    args, 0, stdout="original-" + args[2]
                )
            if args[0] == "exec":
                raise subprocess.CalledProcessError(1, ["pg_dump"])
            return subprocess.CompletedProcess(args, 0)

        with (
            tempfile.TemporaryDirectory(prefix="finora-backup-test-") as directory,
            patch.object(module, "ensure_identity", return_value="test-recipient"),
            patch.object(module, "compose", compose),
            patch.object(module, "command", lambda args, **kwargs: events.append(args)),
            self.assertRaises(subprocess.CalledProcessError),
        ):
            module.backup(Path(directory) / "backup.age")
        self.assertEqual(
            events, [["docker", "start", "original-api", "original-worker"]]
        )


if __name__ == "__main__":
    unittest.main()
