import json, time
from concurrent.futures import ThreadPoolExecutor

exec(open(r"C:\tools\laya\probe2.py", encoding="utf8").read().split("out = {}")[0])
S = states[:8]
pool = ThreadPoolExecutor(4)

def timed(fn):
    t = time.perf_counter(); fn(); return round(time.perf_counter() - t, 1)

configs = {
    "A_singles_sequential": lambda: [post("/v1/systemone", {"state": s, "questions": Q}) for s in S],
    "B_singles_parallel4": lambda: list(pool.map(lambda s: post("/v1/systemone", {"state": s, "questions": Q}), S)),
    "C_one_batch_of_8": lambda: post("/v1/systemone/batch", {"states": S, "questions": Q}),
    "D_two_batches_of_4_sequential": lambda: [post("/v1/systemone/batch", {"states": S[i:i+4], "questions": Q}) for i in (0, 4)],
    "E_two_batches_of_4_parallel": lambda: list(pool.map(lambda i: post("/v1/systemone/batch", {"states": S[i:i+4], "questions": Q}), (0, 4))),
}
out = {k: [] for k in configs}
post("/v1/systemone/batch", {"states": S[:2], "questions": Q})  # warm-up
for rnd in range(2):
    for name, fn in configs.items():
        out[name].append(timed(fn))
        json.dump(out, open(r"C:\tools\laya\probe4.json", "w"), indent=1)
out["done"] = True
json.dump(out, open(r"C:\tools\laya\probe4.json", "w"), indent=1)
