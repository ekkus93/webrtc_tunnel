import json
import tempfile
import unittest
from pathlib import Path

from exact_sha_closure import (
    BASELINE_REQUIRED_STATUSES,
    ClosureError,
    evaluate_statuses,
    load_manifest,
)


class ExactShaClosureTest(unittest.TestCase):
    def make_repo(self, todo_text: str = "# TODO\n\n- [x] complete\n"):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        manifest_dir = root / ".github" / "closure-manifests"
        manifest_dir.mkdir(parents=True)
        docs = root / "docs"
        docs.mkdir()
        (docs / "TODO.md").write_text(todo_text, encoding="utf-8")
        (docs / "REPORT.md").write_text("# Report\n", encoding="utf-8")
        manifest = {
            "schema_version": 1,
            "closure_id": "fixture",
            "todo_path": "docs/TODO.md",
            "required_documents": ["docs/TODO.md", "docs/REPORT.md"],
            "required_statuses": list(BASELINE_REQUIRED_STATUSES),
        }
        manifest_path = manifest_dir / "fixture.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        return temporary, root, manifest_path

    def test_valid_manifest(self):
        temporary, root, manifest_path = self.make_repo()
        self.addCleanup(temporary.cleanup)
        manifest = load_manifest(manifest_path, root)
        self.assertEqual("fixture", manifest.closure_id)

    def test_unchecked_task_is_rejected(self):
        temporary, root, manifest_path = self.make_repo("# TODO\n\n- [ ] not done\n")
        self.addCleanup(temporary.cleanup)
        with self.assertRaisesRegex(ClosureError, "unchecked tasks"):
            load_manifest(manifest_path, root)

    def test_manifest_cannot_remove_required_status(self):
        temporary, root, manifest_path = self.make_repo()
        self.addCleanup(temporary.cleanup)
        raw = json.loads(manifest_path.read_text(encoding="utf-8"))
        raw["required_statuses"] = raw["required_statuses"][:-1]
        manifest_path.write_text(json.dumps(raw), encoding="utf-8")
        with self.assertRaisesRegex(ClosureError, "cannot weaken"):
            load_manifest(manifest_path, root)

    def test_all_success(self):
        payload = {
            "statuses": [
                {"context": context, "state": "success"}
                for context in BASELINE_REQUIRED_STATUSES
            ]
        }
        self.assertEqual("success", evaluate_statuses(payload).state)

    def test_missing_is_pending(self):
        payload = {
            "statuses": [
                {"context": BASELINE_REQUIRED_STATUSES[0], "state": "success"},
            ]
        }
        evaluation = evaluate_statuses(payload)
        self.assertEqual("pending", evaluation.state)
        self.assertIn(BASELINE_REQUIRED_STATUSES[-1], evaluation.missing)

    def test_failure_fails_closed(self):
        payload = {
            "statuses": [
                {"context": context, "state": "failure" if index == 2 else "success"}
                for index, context in enumerate(BASELINE_REQUIRED_STATUSES)
            ]
        }
        evaluation = evaluate_statuses(payload)
        self.assertEqual("failure", evaluation.state)
        self.assertEqual((BASELINE_REQUIRED_STATUSES[2],), evaluation.failing)

    def test_newest_status_wins(self):
        context = BASELINE_REQUIRED_STATUSES[0]
        payload = {
            "statuses": [
                {"context": context, "state": "success"},
                {"context": context, "state": "failure"},
                *[
                    {"context": item, "state": "success"}
                    for item in BASELINE_REQUIRED_STATUSES[1:]
                ],
            ]
        }
        self.assertEqual("success", evaluate_statuses(payload).state)


if __name__ == "__main__":
    unittest.main()
