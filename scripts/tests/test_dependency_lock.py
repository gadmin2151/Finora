import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location(
    "dependency_lock", Path(__file__).resolve().parents[1] / "check-dependency-lock.py"
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def test_current_lock_matches_declarations():
    assert (
        checker.validate(
            checker.ROOT / "backend/requirements.in",
            checker.ROOT / "backend/requirements.txt",
        )
        == []
    )


@pytest.mark.parametrize(
    ("source", "locked", "expected"),
    [
        ("cryptography>=44,<49", "cryptography==50.0.1", "outside"),
        ("pillow-heif>=1,<2", "Pillow_Heif==1.8.0", None),
        ("uvicorn[standard]>=0.34,<1", "uvicorn==0.54.0", None),
        ("pillow>=11", "fastapi==0.141.1", "missing"),
        ("pillow>=11", "pillow>=12", "exact version"),
        ("pillow>=11", "pillow==12.*", "exact version"),
        ("pillow>=11", "pillow==12.3.0\npillow==12.2.0", "duplicate"),
    ],
)
def test_version_conflicts_and_normalized_package_names(
    tmp_path, source, locked, expected
):
    source_file, lock_file = tmp_path / "requirements.in", tmp_path / "requirements.txt"
    source_file.write_text(source + "\n")
    lock_file.write_text(locked + "\n")
    errors = checker.validate(source_file, lock_file)
    if expected:
        assert any(expected in error for error in errors)
    else:
        assert errors == []
