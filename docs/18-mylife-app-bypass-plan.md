# 18 — mylife App: Bypass-Plan für gerootetes Gerät

## Ausgangslage

- **Gerootetes Gerät**: Samsung Galaxy A22 5G (SM-A226B), Android 13, Magisk 30.7
- **STRONG_INTEGRITY**: Bereits erreicht über TrickyStore v1.4.1 + Integrity Box V31 + OEM Keybox (key.sh)
- **CamAPS FX**: Hat sich nach initialem Erfolg selbst zerstört (DexGuard Anti-Tamper)
- **mylife App**: Keine Root-Detection, kein Anti-Tamper → deutlich bessere Aussichten

## Warum mylife App statt CamAPS FX?

| Eigenschaft | CamAPS FX | mylife App |
|---|---|---|
| Root Detection | DexGuard → App löscht sich | **KEINE** |
| Anti-Tamper | PairIP → Delayed Kill | **KEINE** |
| Code Obfuscation | DexGuard (undurchsichtig) | **KEINE** (Klartext) |
| Bolus-Steuerung | Ja (Closed Loop Micro-Bolus + manuelle Bolus-Berechnung) | Ja (mylife Dose UI) |
| Closed Loop | Ja (Cambridge Algorithm) | Nein |

## Bypass-Strategie

### Phase 1: Einfacher Test (kein Bypass nötig)

Da die mylife App **keine Root-Detection** hat, sollte sie einfach starten:

1. App vom Play Store installieren (bereits geschehen, v2.4.3_001)
2. App normal starten
3. Einrichtung durchführen (Login, Pump-Pairing)
4. Bei Pump-Verbindung → Key Exchange wird ausgelöst

### Phase 2: Key Exchange (Play Integrity + Key Attestation)

Der Key Exchange ist die einzige Hürde. Er benötigt:

1. **Play Integrity Token** → STRONG_INTEGRITY bereits erreicht
2. **Key Attestation** → TrickyStore fälscht TEE-Attestation
3. **gRPC zu `connect.ml.pr.sec01.proregia.io:8090`**

**Potentielle Probleme**:
- Key Attestation ist strenger als Play Integrity allein
- ProRegia könnte die Attestation Chain gegen eine CRL (Certificate Revocation List) prüfen
- Wenn der OEM-Keybox in Googles Revocation-Liste steht, schlägt Key Attestation fehl

### Phase 3: Falls Key Attestation fehlschlägt

**Option A**: Neuen Keybox generieren
- `key.sh` aktualisieren oder anderen OEM-Key verwenden
- Google revoziert bekannte Keyboxes periodisch

**Option B**: Key Attestation per Frida bypassen
- Da die App **kein Anti-Tamper** hat, kann Frida sicher verwendet werden
- Hook: `IDeviceAttestation.AttestKey()` → gefälschtes Attestation Result
- Hook: `ValidateKeyAttestationAsync()` → Success Response simulieren

**Option C**: Traffic-Interception
- Kein Certificate Pinning → MITM über Root-CA
- gRPC Responses manipulieren (Key Attestation Response fälschen)
- Tool: mitmproxy mit gRPC-Support

**Option D**: vicktor/ypsomed-pump Relay
- Proxy-Approach des GitHub-Repos als Fallback
- Erfordert separaten Server, der den Key Exchange durchführt
- Komplexer aber bewährt

## Schritt-für-Schritt Testplan

### Vorbereitung
```bash
# 1. Sicherstellen dass STRONG_INTEGRITY noch besteht
# Play Integrity Checker App testen

# 2. Logcat vorbereiten (auf PC)
adb logcat -c && adb logcat | tee /tmp/mylife-test.log

# 3. Anderes Telefon ausschalten (BLE-Konflikt vermeiden)
```

### Test
```bash
# 4. mylife App starten
adb shell am start -n net.sinovo.mylife.app/.SplashScreen

# 5. Einrichtung durchführen
# → Login mit Ypsomed-Konto
# → Pump-Pairing starten
# → YpsoPump in Pairing-Modus versetzen

# 6. Key Exchange beobachten
adb logcat | grep -iE "proregia|integrity|attest|key.exchange|grpc|8090"
```

### Diagnose bei Fehler
```bash
# Key Exchange Fehler analysieren
adb logcat | grep -iE "error|exception|fail|denied|reject"

# gRPC Traffic prüfen (wenn Pinning nicht aktiv)
# → mitmproxy oder Frida-basierter Interceptor

# Key Attestation separat testen
# → Android Key Attestation Test App
```

## Erwartete Ergebnisse

| Szenario | Wahrscheinlichkeit | Aufwand |
|---|---|---|
| App startet und Pump verbindet direkt | 60% | Minimal |
| App startet, Key Exchange schlägt fehl (Attestation) | 25% | Frida Hook |
| App startet, Key Exchange schlägt fehl (Integrity) | 10% | Keybox erneuern |
| App startet nicht (unbekannter Check) | 5% | Analyse nötig |

## Key Lifecycle nach erfolgreichem Key Exchange

Siehe ausführliche Analyse in [19-key-lifecycle-pump-rotation.md](19-key-lifecycle-pump-rotation.md).

**Kernaussagen**:

1. **Einmaliger Key Exchange reicht**: Der Shared Key funktioniert unbegrenzt, solange die App ihn nicht verwirft
2. **Pumpe rotiert NICHTS**: Der Pump Public Key ist factory-burned und ändert sich nie
3. **28-Tage-Expiry nur app-seitig**: Kann durch Manipulation von `sharedKeyDate` oder Frida-Hook umgangen werden
4. **Nach Pumpen-Neustart**: Shared Key bleibt gültig, nur Counter müssen neu synchronisiert werden (erst lesen, dann schreiben)
5. **Commander Key = gleicher Lifecycle**: Kein separater Schlüssel mit eigener Laufzeit

**Praktisch**: Wenn der Key Exchange einmal klappt, haben wir einen stabilen verschlüsselten Kanal, ohne dass die Pumpe uns je rauswerfen kann.

## Risiken

1. **Pump-Insulin-Sicherheit**: mylife Dose ermöglicht Bolus-Abgabe. Ein Fehler im Bypass könnte theoretisch zu falscher Insulinabgabe führen. → **Immer manuell an der Pumpe verifizieren**
2. **BLE-Timing**: Nur ein Gerät kann gleichzeitig mit der Pumpe verbunden sein. → Zweites Telefon ausschalten
3. **Keybox-Revocation**: Google kann den verwendeten OEM-Keybox jederzeit revozieren. → key.sh regelmäßig aktualisieren
4. **Counter-Sync nach Reboot**: Nach Pumpen-Neustart MUSS erst eine Pump-Nachricht gelesen werden, bevor Befehle gesendet werden können — sonst Fehler 138/139
