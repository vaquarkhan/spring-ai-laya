import json, time, urllib.request, glob
from concurrent.futures import ThreadPoolExecutor

exec(open(r"C:\tools\laya\probe2.py", encoding="utf8").read().split("out = {}")[0])

def timed(fn):
    t = time.perf_counter(); fn(); return round(time.perf_counter() - t, 1)

out = {}
with ThreadPoolExecutor(4) as pool:
    out["chunks4x4_parallel4_s"] = timed(lambda: list(pool.map(lambda i: post("/v1/systemone/batch", {"states": states[i:i+4], "questions": Q}), range(0, 16, 4))))
    out["singles16_parallel4_s"] = timed(lambda: list(pool.map(lambda s: post("/v1/systemone", {"state": s, "questions": Q}), states)))
    out["chunks8x2_parallel2_s"] = timed(lambda: list(pool.map(lambda i: post("/v1/systemone/batch", {"states": states[i:i+8], "questions": Q}), range(0, 16, 8))))
json.dump(out, open(r"C:\tools\laya\probe3.json", "w"), indent=1)
