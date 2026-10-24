#!/usr/bin/env python3
"""Live loopback for Trail Link 1.1 internet mode, independent re-implementation of the wire format.
1) decrypts a message sealed by the app's Kotlin code (vector file), 2) two clients A/B through the broker:
A publishes a retained sealed position, B (fresh connection) gets it, decrypts, rejects a replay, 3) clears retained."""
import hashlib, json, os, ssl, sys, time, uuid, unicodedata
import paho.mqtt.client as mqtt
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PASS = "loopback-test-passcode"
def chan(p): return "TL" + hashlib.sha256(("TrailLink/name/v1/" + p).encode()).hexdigest()[:6]
def netkey(p):
    p = unicodedata.normalize("NFC", p.strip())
    return hashlib.pbkdf2_hmac("sha256", p.encode(), ("com.victor.traillink/net/v1/" + chan(p)).encode(), 100000, 32)
KEY = netkey(PASS)
TOPIC = "traillink/v1/" + hashlib.sha256(KEY + b"topic").hexdigest()[:16]
def seal(obj):
    n = os.urandom(12); return b"TL1" + n + AESGCM(KEY).encrypt(n, json.dumps(obj).encode(), TOPIC.encode())
def open_(b):
    if b[:3] != b"TL1": return None
    try: return json.loads(AESGCM(KEY).decrypt(b[3:15], b[15:], TOPIC.encode()))
    except Exception: return None

vec = sys.argv[2] if len(sys.argv) > 2 else None
if vec:
    t, h = open(vec).read().split()
    assert t == TOPIC, (t, TOPIC)
    m = open_(bytes.fromhex(h)); assert m and m["name"] == "Kotlin", m
    print("1) Kotlin-sealed message decrypted OK:", m["name"], m["lat"], m["lon"], "topic", TOPIC)

host, port = (sys.argv[1] if len(sys.argv) > 1 else "broker.hivemq.com:8883").split(":")
def client(tag):
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"tl-loop-{tag}-{uuid.uuid4().hex[:6]}")
    c.tls_set(cert_reqs=ssl.CERT_REQUIRED); return c
last = {}
def guard(m):
    k = m["id"]; prev = last.get(k)
    if prev and (m["ts"], m["seq"]) <= prev: return "REPLAY"
    last[k] = (m["ts"], m["seq"]); return "OK"

t0 = time.time()
a = client("A"); a.connect(host, int(port), 30); a.loop_start()
while not a.is_connected() and time.time() - t0 < 15: time.sleep(0.1)
assert a.is_connected(), "A could not connect"
print(f"2) A connected to {host}:{port} over TLS in {time.time()-t0:.1f}s")
msg = {"v": 1, "id": "loopA001", "name": "LoopA", "short": "LA", "lat": 61.2, "lon": -149.9, "ts": int(time.time()*1000), "seq": 1, "src": "phone"}
wire = seal(msg)
a.publish(f"{TOPIC}/loopA001", wire, qos=1, retain=True).wait_for_publish(10)
print("   A published retained sealed position", len(wire), "bytes")

got = []
b = client("B")
b.on_message = lambda c, u, m: got.append((m.topic, m.payload, m.retain))
b.on_connect = lambda c, u, f, rc, p=None: c.subscribe(TOPIC + "/+", 1)
t1 = time.time(); b.connect(host, int(port), 30); b.loop_start()
while not got and time.time() - t1 < 15: time.sleep(0.1)
assert got, "B got nothing"
topic, payload, retained = got[0]
m = open_(payload); assert m == msg, m
print(f"3) B (new connection) received retained={retained} on {topic} after {time.time()-t1:.1f}s, decrypted: {m['name']} {m['lat']},{m['lon']}  guard={guard(m)}")
print("   replay of same payload -> guard:", guard(open_(payload)))
wrong = AESGCM(netkey("some-other-passcode"))
try: wrong.decrypt(payload[3:15], payload[15:], TOPIC.encode()); print("   WRONG KEY DECRYPTED?!"); sys.exit(1)
except Exception: print("   wrong passcode cannot decrypt: OK")
# live update
got.clear(); msg2 = dict(msg, ts=int(time.time()*1000), seq=2, lat=61.21)
t2 = time.time(); a.publish(f"{TOPIC}/loopA001", seal(msg2), qos=1, retain=True).wait_for_publish(10)
while not got and time.time() - t2 < 10: time.sleep(0.05)
m2 = open_(got[0][1]); print(f"4) live update delivered in {(time.time()-t2)*1000:.0f} ms, guard={guard(m2)}")
a.publish(f"{TOPIC}/loopA001", b"", qos=1, retain=True).wait_for_publish(10)
time.sleep(1); got.clear()
c = client("C"); c.on_message = lambda cl, u, m: got.append(m); c.on_connect = lambda cl, u, f, rc, p=None: cl.subscribe(TOPIC + "/+", 1)
c.connect(host, int(port), 30); c.loop_start(); time.sleep(4)
print("5) retained cleared; fresh subscriber sees", len([g for g in got if g.payload]), "messages")
for x in (a, b, c): x.loop_stop(); x.disconnect()
print("LOOPBACK PASS")
