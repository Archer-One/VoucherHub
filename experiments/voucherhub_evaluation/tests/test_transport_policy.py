"""Executable transport guard checks; these are not live Tor measurements."""
import pathlib
import socket
import subprocess
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
CMD = ['java', '-cp', str(ROOT / 'code/未命名/common/target/classes'),
       'org.voucherhub.common.EvaluationNetworkBenchmark']

class TransportPolicyTest(unittest.TestCase):
    def test_direct_requires_explicit_opt_in(self):
        p = subprocess.run(CMD + ['--role', 'payer', '--transport', 'direct'],
                           capture_output=True, text=True, timeout=10)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn('direct TCP is disabled', p.stderr)

    def test_non_onion_destination_rejected_before_connect(self):
        for host in ['127.0.0.1', 'example.org', 'short.onion', '']:
            p = subprocess.run(CMD + ['--role', 'payer', '--payee-onions', host],
                               capture_output=True, text=True, timeout=10)
            self.assertNotEqual(p.returncode, 0)
            self.assertIn('v3 .onion hostname', p.stderr)

    def test_payee_binds_only_loopback(self):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        process = subprocess.Popen(CMD + ['--role', 'payee', '--port', str(port)],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            # Binding the same port on the LAN interface must stay possible.
            deadline = time.monotonic() + 5
            while True:
                with socket.socket() as probe:
                    try:
                        probe.bind(('127.0.0.1', port))
                    except OSError:
                        break
                if process.poll() is not None or time.monotonic() > deadline:
                    self.fail('payee did not start')
                time.sleep(.05)
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
                route.connect(('192.0.2.1', 9))  # Select local interface; sends no packet.
                local_ip = route.getsockname()[0]
            with socket.socket() as other:
                other.bind((local_ip, port))
        finally:
            process.terminate()
            process.wait(timeout=5)
