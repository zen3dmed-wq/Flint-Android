"""Private CI-only VLESS -> HTTP fixture for Android emulator TUN tests.

Both listeners bind to 127.0.0.1. Android emulator reaches the VLESS listener
through its standard host alias 10.0.2.2. No production account/server is used.
"""
import argparse
import http.server
import json
import pathlib
import signal
import socket
import subprocess
import threading
import time

FIXTURE_ID = '11111111-1111-4111-8111-111111111111'
MARKER = b'FLINT_VPN_TUNNEL_OK'


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(MARKER)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(MARKER)

    def log_message(self, fmt, *args):
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', required=True, type=pathlib.Path)
    parser.add_argument('--directory', required=True, type=pathlib.Path)
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    config = args.directory / 'fixture-xray.json'
    config.write_text(json.dumps({
        'log': {'loglevel': 'warning'},
        'inbounds': [{'listen': '127.0.0.1', 'port': 18443, 'protocol': 'vless',
                      'settings': {'decryption': 'none', 'clients': [{'id': FIXTURE_ID}]},
                      'streamSettings': {'network': 'tcp', 'security': 'none'}}],
        'outbounds': [{'protocol': 'freedom', 'settings': {'redirect': '127.0.0.1:18080'}}]
    }), encoding='utf-8')
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 18080), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    stopped = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    with (args.directory / 'fixture-xray.log').open('wb') as log:
        child = subprocess.Popen([str(args.binary.resolve()), 'run', '-config', str(config.resolve())], stdout=log, stderr=log)
        try:
            for _ in range(100):
                if child.poll() is not None:
                    raise RuntimeError('Fixture core failed; inspect fixture-xray.log')
                try:
                    with socket.create_connection(('127.0.0.1', 18443), timeout=0.2):
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise RuntimeError('Fixture core readiness timed out')
            (args.directory / 'ready').write_text('loopback-only\n', encoding='ascii')
            print('Flint local VPN fixture ready on loopback ports 18443 and 18080', flush=True)
            while not stopped.wait(0.5):
                if child.poll() is not None:
                    raise RuntimeError('Fixture core exited unexpectedly')
        finally:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    main()
