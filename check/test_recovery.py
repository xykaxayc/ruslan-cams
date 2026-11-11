"""Regression checks; no connections to real recorders or keychain."""
import importlib.util
import json
import logging
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('cams_start', ROOT / 'start.py')
app = importlib.util.module_from_spec(spec)
with patch("logging.handlers.RotatingFileHandler", return_value=logging.NullHandler()):
    spec.loader.exec_module(app)


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        cfg = patch.object(app, 'CFG', {'grid': []})
        cfg.start()
        self.addCleanup(cfg.stop)

    def test_http_success_does_not_hide_stalled_video_of_second_camera(self):
        state = {
            'shop_1_src': {'producers': [{'id': 1, 'bytes_recv': 20000}], 'consumers': [{}]},
            'shop_2_src': {'producers': [{'id': 2, 'bytes_recv': 1000}], 'consumers': [{}]},
        }
        prev = {}
        app.video_health(state, {'shop_1': 'shop', 'shop_2': 'shop'}, prev, 10)
        state['shop_1_src']['producers'][0]['bytes_recv'] += 20000
        active, stalled, _ = app.video_health(state, {'shop_1': 'shop', 'shop_2': 'shop'}, prev, 40)
        self.assertIn('shop', active)
        self.assertIn('shop', stalled)

    def test_no_viewers_does_not_require_video_traffic(self):
        state = {'shop_1_src': {'producers': [{}], 'consumers': []}}
        prev = {}
        app.video_health(state, {'shop_1': 'shop'}, prev, 10)
        _, stalled, _ = app.video_health(state, {'shop_1': 'shop'}, prev, 40)
        self.assertEqual(stalled, set())

    def test_reconnected_source_gets_a_new_counter_baseline(self):
        prev = {}
        app.video_health({'a_src': {'producers': [{'id': 1, 'bytes_recv': 50000}], 'consumers': [{}]}}, {'a': 'shop'}, prev, 10)
        _, stalled, _ = app.video_health({'a_src': {'producers': [{'id': 2, 'bytes_recv': 0}], 'consumers': [{}]}}, {'a': 'shop'}, prev, 40)
        self.assertEqual(stalled, set())

    def test_output_demand_counts_when_source_has_not_connected(self):
        state = {'a_src': {'producers': [{}]}, 'a': {'consumers': [{}]}}
        prev = {}
        app.video_health(state, {'a': 'shop'}, prev, 10)
        _, stalled, _ = app.video_health(state, {'a': 'shop'}, prev, 40)
        self.assertEqual(stalled, {'shop'})

    def test_missing_camera_is_published_after_device_recovers(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, 'www').mkdir()
            cfg = {'grid': [{'id': 'shop_1', 'device': 'shop', 'channel': 1, 'title': 'Shop'}]}
            with patch.object(app, 'HERE', folder), patch.object(app, 'CFG', cfg), \
                 patch.object(app, 'configure_device', return_value={1: {'sub': (41000, '/ch01/1')}}):
                devices, mapping = {}, {}
                changed = app.recover_missing_devices(devices, {'shop': 'test'}, mapping)
                self.assertTrue(changed)
                self.assertEqual(mapping['shop_1'], 'shop')
                grid = json.loads(Path(folder, 'www/grid.json').read_text())
                self.assertEqual(grid['cams'][0]['streams']['sub'], 'shop_1')
                self.assertIn('shop_1_src:', Path(folder, 'go2rtc.yaml').read_text())
                self.assertNotIn('test', Path(folder, 'go2rtc.yaml').read_text())

    def test_failed_recovery_does_not_remove_working_camera(self):
        cfg = {'grid': [{'id': 'a', 'device': 'one', 'channel': 1}, {'id': 'b', 'device': 'two', 'channel': 2}]}
        devices = {'one': {1: {'sub': (41000, '/x')}}}
        with patch.object(app, 'CFG', cfg), patch.object(app, 'configure_device', return_value={}), \
             patch.object(app, 'publish_streams'):
            self.assertFalse(app.recover_missing_devices(devices, {'two': 'test'}, {}))
        self.assertEqual(devices['one'][1]['sub'], (41000, '/x'))

    def test_dead_child_is_replaced(self):
        dead = subprocess.Popen([sys.executable, '-c', 'pass'])
        dead.wait()
        replacement = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(10)'])
        try:
            with patch.object(app, 'services', {'go2rtc': dead, 'web': replacement}), \
                 patch.object(app, 'start_go2rtc', side_effect=lambda: app.services.update(go2rtc=replacement)):
                app.supervise_services()
                self.assertIsNone(app.services['go2rtc'].poll())
        finally:
            replacement.terminate(); replacement.wait()

    def test_live_http_does_not_reset_stall_counter_in_monitor(self):
        class EndLoop(Exception):
            pass
        response = type('Response', (), {
            'raise_for_status': lambda self: None,
            'json': lambda self: {
                'a_src': {'producers': [{'id': 1, 'bytes_recv': 1000}], 'consumers': [{}]},
            },
        })()
        with tempfile.TemporaryDirectory() as folder, \
             patch.object(app, 'LOGS', folder), patch.object(app, 'TUN', {('shop', 80): {'port': 1, 'dev_port': 80}}), \
             patch.object(app.time, 'sleep', side_effect=[None, None, None, EndLoop()]), \
             patch.object(app, 'supervise_services', return_value=False), \
             patch.object(app, 'p2p_alive', return_value=True), \
             patch.object(app, 'net_iface', return_value='test'), \
             patch.object(app.requests, 'get', return_value=response), \
             patch.object(app, 'probe_tunnel', return_value=(True, '')), \
             patch.object(app, 'reconnect_device', return_value=True) as reconnect:
            with self.assertRaises(EndLoop):
                app.health_loop({'a': 'shop'}, {}, {})
            self.assertEqual(reconnect.call_args.args, ('shop',))
            text = Path(folder, 'health.csv').read_text()
            self.assertIn('video stalled', text)

    def test_empty_grid_keeps_valid_config_for_later_recovery(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(app, 'HERE', folder), patch.object(app, 'CFG', {'grid': []}):
            mapping = app.publish_streams({})
            self.assertEqual(mapping, {})
            self.assertEqual(json.loads(Path(folder, 'www/grid.json').read_text())['cams'], [])
            self.assertIn('streams: {}', Path(folder, 'go2rtc.yaml').read_text())

    def test_p2p_restart_does_not_close_new_tunnel_with_reused_sequence(self):
        class Forward:
            listen_port = 41000
            target = 40000
            def retarget(self, port): self.target = port
        fwd = Forward()
        tunnels = {('shop', 80): {'seq': 7, 'port': 40000, 'mode': 'p2p', 'fwd': fwd}}
        self.assertTrue(hasattr(app, 'restart_p2p_server'), 'P2P restart must discard stale sequence IDs')
        with patch.object(app, 'TUN', tunnels), patch.object(app, 'DEV_ID', {'shop': 'fake'}), \
             patch.object(app, 'p2p_proc', None), patch.object(app, 'start_p2p_server', return_value=True), \
             patch.object(app, 'tunnel', return_value={'p2p_seq': 7, 'port': 50000, 'p2p_mode': 'p2p'}), \
             patch.object(app.requests, 'post') as post:
            app.restart_p2p_server()
            self.assertEqual(fwd.target, 50000)
            self.assertEqual(tunnels[('shop', 80)]['seq'], 7)
            self.assertFalse(post.called, 'old sequence must not close a tunnel in the new daemon')

    def test_selected_ezviz_cameras_are_published_without_credentials(self):
        cfg = {'grid': [], 'ezviz': [
            {'id': 'ezviz_fridge_entry', 'serial': 'TEST1', 'title': 'Хол-ник вход'},
            {'id': 'ezviz_general', 'serial': 'TEST2', 'title': 'Общий вид'},
        ]}
        with tempfile.TemporaryDirectory() as folder, patch.object(app, 'HERE', folder), patch.object(app, 'CFG', cfg):
            mapping = app.publish_streams({})
            grid = json.loads(Path(folder, 'www/grid.json').read_text())
            self.assertEqual([c['id'] for c in grid['cams']], ['ezviz_fridge_entry', 'ezviz_general'])
            self.assertEqual(mapping, {'ezviz_fridge_entry': 'ezviz', 'ezviz_general': 'ezviz'})
            yaml = Path(folder, 'go2rtc.yaml').read_text()
            self.assertIn('http://127.0.0.1:8560/ezviz_general.ts', yaml)
            self.assertIn('rtsp://127.0.0.1:8554/ezviz_general_src', yaml)
            self.assertNotIn('TEST1', yaml)
            self.assertNotIn('media-key', yaml)

    def test_ezviz_bridge_is_supervised_when_configured(self):
        live = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(10)'])
        try:
            with patch.object(app, 'CFG', {'ezviz': [{'id': 'camera'}]}), \
                 patch.object(app, 'services', {'go2rtc': live, 'web': live}), \
                 patch.object(app, 'start_ezviz', create=True, side_effect=lambda: app.services.update(ezviz=live)):
                app.supervise_services()
                self.assertIn('ezviz', app.services)
        finally:
            live.terminate(); live.wait()

    def test_stalled_ezviz_stream_restarts_bridge(self):
        class EndLoop(Exception): pass
        response = type('Response', (), {
            'raise_for_status': lambda self: None,
            'json': lambda self: {'camera_src': {'producers': [{'id': 1, 'bytes_recv': 1000}], 'consumers': [{}]}},
        })()
        with tempfile.TemporaryDirectory() as folder, \
             patch.object(app, 'CFG', {'grid': [], 'ezviz': [{'id': 'camera'}]}), \
             patch.object(app, 'LOGS', folder), patch.object(app, 'TUN', {}), \
             patch.object(app.time, 'sleep', side_effect=[None,None,None,EndLoop()]), \
             patch.object(app, 'supervise_services', return_value=False), \
             patch.object(app, 'p2p_alive', return_value=True), \
             patch.object(app, 'net_iface', return_value='test'), \
             patch.object(app.requests, 'get', return_value=response), \
             patch.object(app, 'start_ezviz') as restart:
            with self.assertRaises(EndLoop): app.health_loop({'camera': 'ezviz'}, {}, {})
            self.assertEqual(restart.call_count,1)

    def test_shutdown_kills_child_which_ignores_sigterm(self):
        child = subprocess.Popen([sys.executable, '-c', 'import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); print("ready",flush=True); time.sleep(60)'], stdout=subprocess.PIPE, text=True)
        child.stdout.readline()
        try:
            t0 = time.monotonic()
            app.terminate_process(child, timeout=0.2)
            self.assertIsNotNone(child.poll())
            self.assertLess(time.monotonic() - t0, 2)
        finally:
            if child.poll() is None: child.kill(); child.wait()
            child.stdout.close()


if __name__ == '__main__':
    unittest.main()
