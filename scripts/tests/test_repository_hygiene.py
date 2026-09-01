import json
import re
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
LEGACY_PLANS = (
    "EPIC_05_PLAN.md",
    "EPIC_06_PLAN.md",
    "EPIC_07_PLAN.md",
    "IMPLEMENTATION.md",
    "android/ANDROID_IMPLEMENTATION_PLAN.md",
)


class RepositoryHygieneTest(unittest.TestCase):
    def test_readme_documents_each_component_and_verification(self):
        readme = (REPOSITORY_ROOT / "README.md").read_text()

        for expected in (
            "## Backend",
            "## Frontend",
            "## Android",
            "## Verification",
            "python -m unittest discover -s scripts/tests",
        ):
            with self.subTest(expected=expected):
                self.assertIn(expected, readme)

    def test_ci_checks_every_component(self):
        workflow = (REPOSITORY_ROOT / ".github/workflows/ci.yml").read_text()

        for expected in (
            "npm run lint",
            "npm run type-check",
            "npm run test:run",
            "cargo fmt --check",
            "cargo clippy --all-targets --all-features",
            "cargo test --all-features",
            "./gradlew lint test",
            "python -m unittest discover -s scripts/tests",
        ):
            with self.subTest(expected=expected):
                self.assertIn(expected, workflow)

    def test_generated_editor_state_and_legacy_plans_are_not_present(self):
        idea_dir = REPOSITORY_ROOT / "android/.idea"
        self.assertFalse(any(path.is_file() for path in idea_dir.rglob("*")))
        for relative_path in LEGACY_PLANS:
            with self.subTest(relative_path=relative_path):
                self.assertFalse((REPOSITORY_ROOT / relative_path).exists())

    def test_api_spec_uses_wire_format_names(self):
        spec = (REPOSITORY_ROOT / "SPEC.md").read_text()
        self.assertNotRegex(spec, r"\{(?:packageName|versionCode)\}")

        json_blocks = re.findall(r"```json\n(.*?)\n```", spec, flags=re.DOTALL)
        self.assertGreater(len(json_blocks), 0)
        for block in json_blocks:
            payload = json.loads(block)
            for key in self._keys(payload):
                with self.subTest(key=key):
                    self.assertIsNone(
                        re.search(r"[A-Z]", key),
                        f"API field {key!r} is not snake_case",
                    )

    @classmethod
    def _keys(cls, value):
        if isinstance(value, dict):
            for key, child in value.items():
                yield key
                yield from cls._keys(child)
        elif isinstance(value, list):
            for child in value:
                yield from cls._keys(child)


if __name__ == "__main__":
    unittest.main()
