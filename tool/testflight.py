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

def dis_grup():
    """Apple hesabı App Store Connect kullanıcısı olmayan test kullanıcıları için dış test grubu."""
    gs = call("GET", f"/apps/{APP_ID}/betaGroups?limit=50")["data"]
    g = next((x for x in gs if not x["attributes"].get("isInternalGroup")), None)
    if not g:
        g = call("POST", "/betaGroups", {"data": {"type": "betaGroups",
            "attributes": {"name": "Aile ve arkadaşlar"},
            "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}}}}})["data"]
        print("Dış test grubu açıldı: Aile ve arkadaşlar")
    # bilgi ve inceleme bilgisi (dış test Apple'ın kısa beta incelemesinden geçer)
    try:
        bi = call("GET", f"/apps/{APP_ID}/betaAppLocalizations")["data"]
        if not bi:
            call("POST", "/betaAppLocalizations", {"data": {"type": "betaAppLocalizations",
                 "attributes": {"locale": "tr", "feedbackEmail": "sviapp.developer@gmail.com",
                                "description": "PDF'ini yükle; özetini oku, testlerle çalış."},
                 "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}}}}}, quiet=True)
    except urllib.error.HTTPError:
        pass
    try:
        rd = call("GET", f"/apps/{APP_ID}/betaAppReviewDetail")["data"]
        r = ns["META"]["review"]
        call("PATCH", f"/betaAppReviewDetails/{rd['id']}", {"data": {"type": "betaAppReviewDetails", "id": rd["id"],
             "attributes": {"contactFirstName": r["contactFirstName"], "contactLastName": r["contactLastName"],
                            "contactEmail": r["contactEmail"], "contactPhone": r["contactPhone"],
                            "demoAccountRequired": True, "demoAccountName": r["demoAccountName"],
                            "demoAccountPassword": r["demoAccountPassword"], "notes": r["notes"]}}}, quiet=True)
    except urllib.error.HTTPError as e:
        print("   beta inceleme bilgisi yazılamadı:", getattr(e, "body", "")[:200])
    if builds:
        bid = builds[0]["id"]
        try:
            call("POST", f"/betaGroups/{g['id']}/relationships/builds", {"data": [{"type": "builds", "id": bid}]}, quiet=True)
        except urllib.error.HTTPError:
            pass
        try:
            call("POST", "/betaAppReviewSubmissions", {"data": {"type": "betaAppReviewSubmissions",
                 "relationships": {"build": {"data": {"type": "builds", "id": bid}}}}}, quiet=True)
            print("Son derleme dış test için Apple beta incelemesine gönderildi.")
        except urllib.error.HTTPError as e:
            print("   beta incelemesi:", getattr(e, "body", "")[:300])
    return g


for EMAIL in [e.strip() for e in EMAIL.split(",") if e.strip()]:
    testers = call("GET", f"/betaTesters?filter[email]={EMAIL}&filter[apps]={APP_ID}")["data"]
    if testers:
        print("Test kullanıcısı zaten var:", EMAIL)
        continue
    try:
        call("POST", "/betaTesters", {"data": {"type": "betaTesters",
             "attributes": {"email": EMAIL},
             "relationships": {"betaGroups": {"data": [{"type": "betaGroups", "id": grp["id"]}]}}}}, quiet=True)
        print("İç teste davet gönderildi:", EMAIL)
    except urllib.error.HTTPError as e:
        print("   iç teste eklenemedi (App Store Connect kullanıcısı değil):", getattr(e, "body", "")[:200])
        g = dis_grup()
        call("POST", "/betaTesters", {"data": {"type": "betaTesters",
             "attributes": {"email": EMAIL},
             "relationships": {"betaGroups": {"data": [{"type": "betaGroups", "id": g["id"]}]}}}})
        print("Dış teste davet gönderildi:", EMAIL)
print("Tamam: iPhone'da TestFlight uygulamasını aç, Yükle Detayla'yı kur.")
