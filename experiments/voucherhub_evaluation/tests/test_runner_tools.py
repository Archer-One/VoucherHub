import importlib.util
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


RUNNER = load("run_n_to_1", ROOT / "runner/run_n_to_1.py")


class RunnerToolsTest(unittest.TestCase):
    def test_five_to_one_distributes_100_as_twenty_each(self):
        self.assertEqual(RUNNER.distribute(100, 5), [20, 20, 20, 20, 20])

    def test_distribution_preserves_total(self):
        for total in [1, 10, 20, 40, 60, 80, 100]:
            for workers in [1, 3, 5]:
                parts = RUNNER.distribute(total, workers)
                self.assertEqual(sum(parts), total)
                self.assertLessEqual(max(parts) - min(parts), 1)

    def test_current_payees_have_v3_onion_addresses(self):
        payload = json.loads((ROOT / "config/servers_new.json").read_text(encoding="utf-8"))
        for node in payload["nodes"]:
            onion = node.get("onion", "")
            self.assertTrue(onion.endswith(".onion"), node["name"])
            self.assertEqual(len(onion), 62, node["name"])


if __name__ == "__main__":
    unittest.main()
