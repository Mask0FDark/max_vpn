from bridge.framing import encode_frames
from bridge.max_rpc import make_envelope
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import hashlib
import json

token = b"t"*64
secret = hashlib.sha256(b"MAXVPN-MAX-TRANSPORT-V1:"+token).digest()
key = hashlib.sha256(secret).digest()
rid = "a"*32
nonce = b"1"*12
payload = json.dumps({"v":1,"status":"ok","data":"SEVMTE8="},separators=(",",":")).encode("ascii")
ciphertext = b"MX2"+nonce+AESGCM(key).encrypt(nonce,payload,rid.encode("ascii"))
envelope = make_envelope("response",ciphertext,rid,sender="host",recipient="mobile")
frames = encode_frames(envelope,packet_id="b"*32)
print("PLAINTEXT=",payload.decode("ascii"))
print("FRAME=",frames[0])
