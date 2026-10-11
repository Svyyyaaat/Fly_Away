import json, os, sys, hashlib, urllib.request, concurrent.futures as cf
MC = sys.argv[1]
def get(url, path, sha1=None):
    if os.path.exists(path) and (sha1 is None or hashlib.sha1(open(path,'rb').read()).hexdigest()==sha1): return None
    os.makedirs(os.path.dirname(path), exist_ok=True)
    for i in range(4):
        try:
            data = urllib.request.urlopen(url, timeout=60).read()
            open(path,'wb').write(data); return None
        except Exception as e: err = f"{url}: {e}"
    return err
v = json.load(open(f"{MC}/versions/1.21.1/1.21.1.json")) if os.path.exists(f"{MC}/versions/1.21.1/1.21.1.json") else None
if v is None:
    get('https://piston-meta.mojang.com/v1/packages/cedfc3b6dcbca34e2b478d498bf1d56a8fa2f404/1.21.1.json', f"{MC}/versions/1.21.1/1.21.1.json")
    v = json.load(open(f"{MC}/versions/1.21.1/1.21.1.json"))
nf = json.load(open(f"{MC}/versions/neoforge-21.1.252/neoforge-21.1.252.json"))
jobs = []
jobs.append((v['downloads']['client']['url'], f"{MC}/versions/1.21.1/1.21.1.jar", v['downloads']['client']['sha1']))
for lib in v['libraries'] + nf['libraries']:
    a = lib.get('downloads', {}).get('artifact')
    if a and a.get('url'): jobs.append((a['url'], f"{MC}/libraries/{a['path']}", a.get('sha1')))
ai = v['assetIndex']; idx = f"{MC}/assets/indexes/{ai['id']}.json"
get(ai['url'], idx)
for name, o in json.load(open(idx))['objects'].items():
    h = o['hash']; jobs.append((f"https://resources.download.minecraft.net/{h[:2]}/{h}", f"{MC}/assets/objects/{h[:2]}/{h}", h))
errs = []
with cf.ThreadPoolExecutor(16) as ex:
    for r in ex.map(lambda j: get(*j), jobs):
        if r: errs.append(r)
print(len(jobs), "jobs", len(errs), "errors"); print("\n".join(errs[:10]))
