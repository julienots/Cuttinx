"""Small adb helper: find a UI element by its text / description and tap it (Compose exposes both)."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

def sh(*a): return subprocess.run(["adb", *a], capture_output=True, text=True).stdout

def nodes():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = sh("shell", "cat", "/sdcard/ui.xml")
    try: root = ET.fromstring(xml[xml.find("<"):])
    except Exception: return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            out.append(((n.get("text") or "") + " | " + (n.get("content-desc") or ""), (x1 + x2) // 2, (y1 + y2) // 2))
    return out

def tap(label, tries=6, exact=False):
    for _ in range(tries):
        for txt, x, y in nodes():
            parts = [p.strip() for p in txt.split("|")]
            if any((p == label) if exact else (label.lower() in p.lower() and p) for p in parts):
                sh("shell", "input", "tap", str(x), str(y)); print("tap", label, x, y); return True
        time.sleep(1.5)
    print("not found:", label); return False

if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "tap": sys.exit(0 if tap(sys.argv[2], exact=len(sys.argv) > 3) else 1)
    if cmd == "list": [print(t) for t, _, _ in nodes() if t.strip(" |")]
