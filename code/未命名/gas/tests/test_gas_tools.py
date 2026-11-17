import importlib.util
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "measure_gas.py"
SPEC = importlib.util.spec_from_file_location("measure_gas", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class GasToolsTest(unittest.TestCase):
    def test_summary_drops_exactly_one_minimum_and_maximum(self):
        result = MODULE.summarize([100, 200, 300, 400, 500])
        self.assertEqual(result["median"], 300)
        self.assertEqual(result["trimmed_mean"], 300)
        self.assertEqual(result["min"], 100)
        self.assertEqual(result["max"], 500)

    def test_contract_contains_all_benchmarks(self):
        source = (MODULE_PATH.parent / "contracts/VoucherHubGasBenchmarks.sol").read_text()
        for name in ["VoucherHubLifecycleGas", "VoucherHubBatchRedeemGas",
                     "AccioNormalChannelGas", "AccioHiddenLifecycleGas"]:
            self.assertIn("contract " + name, source)


if __name__ == "__main__":
    unittest.main()
