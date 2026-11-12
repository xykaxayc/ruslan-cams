#!/usr/bin/env python3
"""Selected EZVIZ cloud cameras → loopback MPEG-TS, using pyezvizapi==2.0.0."""
import copy
import json
import logging
import os
import shutil
import tempfile
from http.server import BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse

from pyezvizapi.client import EzvizClient
from pyezvizapi.cli import main as sdk

HERE = Path(__file__).resolve().parent
PRIVATE = HERE / 'ezviz' / 'private'


def normalize_token(token):
    token = copy.deepcopy(token)
    urls = token.get('service_urls')
    if token.get('api_url') in ('api.ezvizru.com', 'apiirus.ezvizru.com') and isinstance(urls, dict):
        if str(urls.get('authAddr') or '').lower() in ('', 'null', 'none', 'https://null', 'http://null'):
            urls['authAddr'] = 'https://auth.ezvizru.com'
    return token


def save_private_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=path.name + '.')
    try:
        with os.fdopen(fd, 'w') as out:
            json.dump(data, out)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


class CloudClient(EzvizClient):
    """Keep the SDK's token/request lock; normalize Russian discovery metadata."""
    def get_service_urls(self):
        urls = super().get_service_urls()
        return normalize_token({'api_url': self._token['api_url'], 'service_urls': urls})['service_urls']


def create_server(client, cameras, keys, port=8560):
    configs = {}
    for cam in cameras:
        path = f"/{cam['id']}.ts"
        channel = int(cam.get('channel', 0))
        configs[path] = sdk.StreamProxyConfig(
            serial=cam['serial'], channel=channel, client_type=9, token_index=0,
            refresh_vtm=True, timeout=15, path=path,
            ffmpeg_path=shutil.which('ffmpeg') or '/opt/homebrew/bin/ffmpeg',
            allow_encrypted=False, decrypt_video=True, decrypt_codec='auto',
            max_packets=None, media_key=keys[cam['serial']])

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            config = configs.get(urlparse(self.path).path)
            if config is None:
                self.send_error(404, 'Stream not found')
                return
            try:
                sdk._handle_stream_proxy_get(self, config, client)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as error:
                # Print exception type only: transport errors can contain signed URLs.
                print('EZVIZ stream failed:', config.serial, type(error).__name__, flush=True)
                self.close_connection = True

        def log_message(self, *_):
            pass

    return sdk.StreamProxyHTTPServer(('127.0.0.1', port), Handler)


def main():
    os.umask(0o077)
    # The SDK CLI can include transport context in errors; keep it out of logs.
    logging.disable(logging.CRITICAL)
    token_path = PRIVATE / 'token.json'
    token = normalize_token(json.loads(token_path.read_text()))
    keys = json.loads((PRIVATE / 'keys.json').read_text())
    cameras = json.loads((HERE / 'cams.json').read_text()).get('ezviz', [])
    client = CloudClient(token=token, timeout=15,
                         on_token_updated=lambda value: save_private_json(token_path, normalize_token(value)))
    client.login()
    server = create_server(client, cameras, keys)
    print(f'EZVIZ bridge: {len(cameras)} cameras on 127.0.0.1:8560', flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
