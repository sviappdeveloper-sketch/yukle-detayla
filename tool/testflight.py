#!/usr/bin/env python3
"""Yükle Detayla: TestFlight iç testini hazırlar (tarayıcı gerektirmez).

 - "Ekip" adlı iç test grubunu açar (tüm derlemelere erişim, yeni derlemeler otomatik gelir).
 - TESTER_EMAIL adresini (App Store Connect kullanıcısı) gruba ekler; Apple ona davet e-postası yollar.
 - En son derlemenin dışa aktarım (şifreleme) bilgisini doldurur.
Ortam: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_P8, TESTER_EMAIL
"""
import os
import sys
import urllib.error

sys.argv = sys.argv[:1]
HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, "asc_islem.py"), encoding="utf-8").read()
# yalnızca yardımcı fonksiyonları al (token, call)
ns = {"__file__": os.path.join(HERE, "asc_islem.py"), "__name__": "asc"}
exec(src.split("# ---------------------------------------------------------------- 1) uygulama")[0], ns)
call = ns["call"]
BUNDLE = ns["BUNDLE"]

EMAIL = os.environ.get("TESTER_EMAIL", "").strip()
app = call("GET", f"/apps?filter[bundleId]={BUNDLE}")["data"][0]
APP_ID = app["id"]
print("Uygulama:", app["attributes"]["name"], APP_ID)

groups = call("GET", f"/apps/{APP_ID}/betaGroups?limit=50")["data"]
grp = next((g for g in groups if g["attributes"].get("isInternalGroup")), None)
if grp:
    print("İç test grubu var:", grp["attributes"]["name"])
else:
    grp = call("POST", "/betaGroups", {"data": {"type": "betaGroups",
        "attributes": {"name": "Ekip", "isInternalGroup": True, "hasAccessToAllBuilds": True},
        "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}}}}})["data"]
    print("İç test grubu açıldı: Ekip")

builds = call("GET", f"/builds?filter[app]={APP_ID}&sort=-uploadedDate&limit=3")["data"]
for b in builds:
    a = b["attributes"]
    print("Derleme:", a["version"], a.get("processingState"), "şifreleme:", a.get("usesNonExemptEncryption"))
    if a.get("usesNonExemptEncryption") is None and a.get("processingState") == "VALID":
        try:
            call("PATCH", f"/builds/{b['id']}", {"data": {"type": "builds", "id": b["id"],
                 "attributes": {"usesNonExemptEncryption": False}}})
        except urllib.error.HTTPError:
            pass
if builds and not grp["attributes"].get("hasAccessToAllBuilds", True):
    try:
        call("POST", f"/betaGroups/{grp['id']}/relationships/builds",
             {"data": [{"type": "builds", "id": builds[0]["id"]}]})
    except urllib.error.HTTPError:
        pass

if EMAIL:
    testers = call("GET", f"/betaTesters?filter[email]={EMAIL}&filter[apps]={APP_ID}")["data"]
    if testers:
        try:
            call("POST", f"/betaGroups/{grp['id']}/relationships/betaTesters",
                 {"data": [{"type": "betaTesters", "id": testers[0]["id"]}]}, quiet=True)
        except urllib.error.HTTPError:
            pass
        print("Test kullanıcısı zaten var:", EMAIL)
    else:
        call("POST", "/betaTesters", {"data": {"type": "betaTesters",
             "attributes": {"email": EMAIL, "firstName": "Seyfullah", "lastName": "Sivi"},
             "relationships": {"betaGroups": {"data": [{"type": "betaGroups", "id": grp["id"]}]}}}})
        print("Davet gönderildi:", EMAIL)
print("Tamam: iPhone'da TestFlight uygulamasını aç, Yükle Detayla'yı kur.")
