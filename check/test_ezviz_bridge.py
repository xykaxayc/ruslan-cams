import importlib.util,json,os,tempfile,threading,unittest,urllib.request,urllib.error
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]

class BridgeTests(unittest.TestCase):
    def setUp(self):
        path=ROOT/'ezviz_bridge.py'
        self.assertTrue(path.exists(),'EZVIZ bridge is not implemented')
        spec=importlib.util.spec_from_file_location('ezviz_bridge',path)
        self.bridge=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.bridge)

    def test_russian_null_auth_address_is_normalized_without_changing_other_regions(self):
        for host,want in [('apiirus.ezvizru.com','https://auth.ezvizru.com'),('apiieu.ezvizlife.com','https://null')]:
            token={'api_url':host,'service_urls':{'authAddr':'https://null'}}
            result=self.bridge.normalize_token(token)
            self.assertEqual(result['service_urls']['authAddr'],want)
            self.assertEqual(token['service_urls']['authAddr'],'https://null')

    def test_persisted_credentials_have_private_permissions(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'token.json'
            self.bridge.save_private_json(path,{'session_id':'secret'})
            self.assertEqual(path.stat().st_mode & 0o777,0o600)
            self.assertEqual(json.loads(path.read_text())['session_id'],'secret')

    def test_http_routes_select_only_configured_camera(self):
        cameras=[{'id':'entry','serial':'ABC'},{'id':'general','serial':'DEF'}]
        server=self.bridge.create_server(object(),cameras,{'ABC':'key1','DEF':'key2'},port=0)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        def upstream(handler,config,client):
            handler.send_response(200);handler.end_headers();handler.wfile.write(config.serial.encode())
        try:
            url=f'http://127.0.0.1:{server.server_port}'
            with patch.object(self.bridge.sdk,'_handle_stream_proxy_get',side_effect=upstream):
                self.assertEqual(urllib.request.urlopen(url+'/entry.ts').read(),b'ABC')
                self.assertEqual(urllib.request.urlopen(url+'/general.ts').read(),b'DEF')
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    urllib.request.urlopen(url+'/other.ts')
                self.assertEqual(caught.exception.code,404)
                caught.exception.close()
        finally:
            server.shutdown();server.server_close();thread.join()

    def test_parallel_cloud_bootstrap_does_not_invert_sdk_token_lock(self):
        client=self.bridge.CloudClient()
        token_held=threading.Event();request_entered=threading.Event()
        def sdk_request(client,*args,**kwargs):
            request_entered.set()
            with client._token_lock: return 'ok'
        def metadata_request():
            with client._token_lock:
                token_held.set()
                request_entered.wait(1)
                client._http_request('GET','https://example.invalid')
        def stream_request():
            token_held.wait(1)
            client._http_request('GET','https://example.invalid')
        with patch.object(self.bridge.EzvizClient,'_http_request',sdk_request):
            a=threading.Thread(target=metadata_request,daemon=True)
            b=threading.Thread(target=stream_request,daemon=True)
            a.start();b.start();a.join(1);b.join(1)
            self.assertFalse(a.is_alive() or b.is_alive(),'parallel bootstrap deadlocked on inverted HTTP/token locks')

if __name__=='__main__':unittest.main()
