# launch.py <gamedir> [extra jvm args...] -- [extra game args...]
import json, os, sys, subprocess
MC = os.path.dirname(os.path.abspath(__file__)) + "/../mc"
MC = os.path.abspath(MC)
gamedir = os.path.abspath(sys.argv[1])
rest = sys.argv[2:]
if '--' in rest:
    i = rest.index('--'); xjvm, xgame = rest[:i], rest[i+1:]
else:
    xjvm, xgame = rest, []
v = json.load(open(f"{MC}/versions/1.21.1/1.21.1.json"))
nf = json.load(open(f"{MC}/versions/neoforge-21.1.252/neoforge-21.1.252.json"))
def allowed(lib):
    for r in lib.get('rules', []):
        osn = r.get('os', {}).get('name')
        ok = osn is None or osn == 'linux'
        if r['action'] == 'allow' and not ok: return False
        if r['action'] == 'disallow' and ok: return False
    return True
cp, seen = [], set()
for lib in nf['libraries'] + v['libraries']:
    if not allowed(lib): continue
    a = lib.get('downloads', {}).get('artifact')
    if not a: continue
    p = f"{MC}/libraries/{a['path']}"
    key = lib['name'].rsplit(':', 1)[0] if lib['name'].count(':') == 2 else lib['name']
    if key in seen: continue
    seen.add(key); cp.append(p)
natives = f"{gamedir}/natives"; os.makedirs(natives, exist_ok=True)
sub = {'${library_directory}': f"{MC}/libraries", '${classpath_separator}': ':', '${version_name}': 'neoforge-21.1.252',
       '${natives_directory}': natives, '${launcher_name}': 'flyaway-test', '${launcher_version}': '1', '${classpath}': ':'.join(cp)}
def s(x):
    for k, val in sub.items(): x = x.replace(k, val)
    return x
jvm = ['java', '-Xmx10G', '-Xms2G', '-XX:+UseG1GC'] + xjvm
for a in v['arguments']['jvm']:
    if isinstance(a, str): jvm.append(s(a))
for a in nf['arguments']['jvm']: jvm.append(s(a))
game = ['--username', 'Tester', '--version', 'neoforge-21.1.252', '--gameDir', gamedir, '--assetsDir', f"{MC}/assets",
        '--assetIndex', v['assetIndex']['id'], '--uuid', '00000000000000000000000000000001', '--accessToken', '0',
        '--userType', 'msa', '--versionType', 'release', '--width', os.environ.get('MCW','1280'), '--height', os.environ.get('MCH','720')] + nf['arguments']['game'] + xgame
cmd = jvm + [nf['mainClass']] + game
os.chdir(gamedir)
os.execvp(cmd[0], cmd)
