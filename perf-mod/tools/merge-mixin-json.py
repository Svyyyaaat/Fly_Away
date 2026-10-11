# Resolve merge conflicts in flyaway_perf.*.mixins.json by taking the union of ours/theirs lists.
import json, subprocess, sys
out = subprocess.run(['git', 'diff', '--name-only', '--diff-filter=U'], capture_output=True, text=True).stdout.split()
for f in out:
    if not f.endswith('.mixins.json'): continue
    a = json.loads(subprocess.run(['git', 'show', f':2:{f}'], capture_output=True, text=True).stdout)
    b = json.loads(subprocess.run(['git', 'show', f':3:{f}'], capture_output=True, text=True).stdout)
    for k in ('mixins', 'client', 'server'):
        if k in a or k in b:
            a[k] = sorted(set(a.get(k, [])) | set(b.get(k, [])))
    open(f, 'w').write(json.dumps(a, indent=2) + '\n')
    subprocess.run(['git', 'add', f]); print('merged', f)
