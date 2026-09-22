import urllib.request, json
try:
    resp = urllib.request.urlopen('http://localhost:8080/api/packages', timeout=5)
    print(resp.read().decode('utf-8'))
except Exception as e:
    print('Error:', e)
