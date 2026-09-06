import sys
import urllib.request
import urllib.parse

if len(sys.argv) < 2:
    print("Usage: eval_tv.py <js_code>")
    sys.exit(1)

code = sys.argv[1]
url = "http://192.168.0.178:8888/api/eval?js=" + urllib.parse.quote(code)
try:
    resp = urllib.request.urlopen(url, timeout=5)
    print("Response:", resp.read().decode('utf-8'))
except Exception as e:
    print("Error:", e)
