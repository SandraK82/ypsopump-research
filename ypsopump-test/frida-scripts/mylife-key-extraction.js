/**
 * mylife-key-extraction.js
 *
 * Extrahiert den Shared Key und die 116-Byte-Payload während des
 * Pairing-Prozesses zwischen mylife App und der YpsoPump.
 *
 * Unterschiede zu CamAPS (04-key-extraction.js):
 *   - Target: net.sinovo.mylife.app (statt com.camdiab.fx_alert)
 *   - KEIN Anti-Tamper-Bypass nötig (mylife hat keinen)
 *   - mylife bündelt eigenes libsodium.so -> gleiche Hooks
 *
 * Key Exchange Ablauf:
 *   1. App generiert ECDH Keypair (X25519)
 *   2. App sendet Public Key an Pumpe (via Proregia-Backend)
 *   3. Pumpe antwortet mit ihrem Public Key
 *   4. SharedSecret = X25519(myPriv, pumpPub)
 *   5. SharedKey = HChaCha20(SharedSecret, zeros_16B)
 *   6. SharedKey für XChaCha20-Poly1305 Verschlüsselung
 *
 * Verwendung:
 *   # Kein Security-Bypass nötig! Direkt starten:
 *   frida -U -f net.sinovo.mylife.app \
 *     -l mylife-key-extraction.js \
 *     --no-pause
 *
 *   # Dann in der mylife App: Pumpe verbinden / pairen
 *   # Frida fängt den Shared Key ab
 *
 *   adb pull /data/local/tmp/mylife_keys/shared_key.hex
 *   # -> Hex-Key in YpsoPump Test App importieren
 */

"use strict";

const KEY_DIR = "/data/local/tmp/mylife_keys";

function hexdump_compact(ptr, len) {
    const bytes = ptr.readByteArray(len);
    const arr = new Uint8Array(bytes);
    return Array.from(arr).map(b => ("0" + b.toString(16)).slice(-2)).join("");
}

function saveFile(name, data) {
    const path = KEY_DIR + "/" + name;
    const f = new File(path, "wb");
    f.write(data);
    f.close();
    console.log("[+] Gespeichert: " + path);
}

function saveText(name, text) {
    const path = KEY_DIR + "/" + name;
    const f = new File(path, "w");
    f.write(text);
    f.close();
    console.log("[+] Gespeichert: " + path);
}

// Key-Verzeichnis erstellen
Java.perform(function() {
    try {
        Java.use("java.io.File").$new(KEY_DIR).mkdirs();
    } catch(e) {}
});

console.log("[*] ====================================");
console.log("[*] mylife Key Extraction Script");
console.log("[*] Target: net.sinovo.mylife.app");
console.log("[*] Keys werden nach " + KEY_DIR + " gespeichert");
console.log("[*] KEIN Anti-Tamper-Bypass nötig (mylife hat keinen)");
console.log("[*] ====================================");

// ================================================================
// 1. libsodium Hooks — Crypto-Primitives
// ================================================================

function hookSodium() {
    const sodium = Process.findModuleByName("libsodium.so");
    if (!sodium) {
        console.log("[!] libsodium.so noch nicht geladen, warte...");
        setTimeout(hookSodium, 500);
        return;
    }

    console.log("[+] libsodium.so gefunden: " + sodium.base);

    // --- crypto_scalarmult (X25519 ECDH) ---
    const scalarmult = Module.findExportByName("libsodium.so", "crypto_scalarmult");
    if (scalarmult) {
        Interceptor.attach(scalarmult, {
            onEnter: function(args) {
                this.q = args[0]; // output: shared secret (32B)
                this.n = args[1]; // our private key (32B)
                this.p = args[2]; // their public key (32B)

                console.log("\n[ECDH] crypto_scalarmult() aufgerufen!");
                console.log("  Private Key (n): " + hexdump_compact(this.n, 32));
                console.log("  Pump PubKey (p): " + hexdump_compact(this.p, 32));
            },
            onLeave: function(retval) {
                console.log("  Shared Secret (q): " + hexdump_compact(this.q, 32));
                console.log("  Return: " + retval);

                saveText("ecdh_private_key.hex", hexdump_compact(this.n, 32));
                saveText("ecdh_pump_pubkey.hex", hexdump_compact(this.p, 32));
                saveText("ecdh_shared_secret.hex", hexdump_compact(this.q, 32));
                saveFile("ecdh_shared_secret.bin", this.q.readByteArray(32));
            }
        });
        console.log("[+] crypto_scalarmult gehooked");
    }

    // --- crypto_scalarmult_base (Public Key Generation) ---
    const scalarmult_base = Module.findExportByName("libsodium.so", "crypto_scalarmult_base");
    if (scalarmult_base) {
        Interceptor.attach(scalarmult_base, {
            onEnter: function(args) {
                this.q = args[0]; // output: public key (32B)
                this.n = args[1]; // private key (32B)
                console.log("\n[ECDH] crypto_scalarmult_base() — Public Key Generation");
                console.log("  Private Key: " + hexdump_compact(this.n, 32));
            },
            onLeave: function(retval) {
                console.log("  Our Public Key: " + hexdump_compact(this.q, 32));
                saveText("our_public_key.hex", hexdump_compact(this.q, 32));
                saveText("our_private_key.hex", hexdump_compact(this.n, 32));
            }
        });
        console.log("[+] crypto_scalarmult_base gehooked");
    }

    // --- crypto_core_hchacha20 (KDF: SharedSecret -> SharedKey) ---
    // >>> DAS IST DAS HAUPTZIEL <<<
    const hchacha20 = Module.findExportByName("libsodium.so", "crypto_core_hchacha20");
    if (hchacha20) {
        Interceptor.attach(hchacha20, {
            onEnter: function(args) {
                // hchacha20(out, in, k, c)
                // out = 32B derived key
                // in = 16B nonce (zeros during key derivation)
                // k = 32B key (= shared secret from ECDH)
                // c = 16B constant (or NULL = "expand 32-byte k")
                this.out = args[0];
                this.input = args[1];
                this.key = args[2];
                this.constant = args[3];

                console.log("\n[KDF] crypto_core_hchacha20()");
                console.log("  Input Key (k):  " + hexdump_compact(this.key, 32));
                console.log("  Nonce (in):     " + hexdump_compact(this.input, 16));
                if (this.constant && !this.constant.isNull()) {
                    console.log("  Constant (c):   " + hexdump_compact(this.constant, 16));
                }
            },
            onLeave: function(retval) {
                const sharedKeyHex = hexdump_compact(this.out, 32);
                console.log("  === SHARED KEY === " + sharedKeyHex);

                saveText("shared_key.hex", sharedKeyHex);
                saveFile("shared_key.bin", this.out.readByteArray(32));
                saveText("kdf_input_key.hex", hexdump_compact(this.key, 32));
                saveText("kdf_nonce.hex", hexdump_compact(this.input, 16));

                console.log("\n  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                console.log("  !!! SHARED KEY ERFOLGREICH EXTRAHIERT !!!");
                console.log("  !!! Datei: " + KEY_DIR + "/shared_key.hex");
                console.log("  !!! Key:   " + sharedKeyHex);
                console.log("  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                console.log("  !!! Jetzt: adb pull " + KEY_DIR + "/shared_key.hex");
                console.log("  !!! Dann in YpsoPump Test App importieren !!!");
            }
        });
        console.log("[+] crypto_core_hchacha20 gehooked (HAUPTZIEL)");
    }

    // --- crypto_aead_xchacha20poly1305_ietf_encrypt (Bestätigung) ---
    const encrypt = Module.findExportByName("libsodium.so",
        "crypto_aead_xchacha20poly1305_ietf_encrypt");
    if (encrypt) {
        Interceptor.attach(encrypt, {
            onEnter: function(args) {
                this.ciphertext = args[0];
                this.plaintext = args[2];
                this.plaintext_len = args[3].toInt32();
                this.nonce = args[7];
                this.key = args[8];

                console.log("\n[ENCRYPT] xchacha20poly1305");
                console.log("  Plaintext (" + this.plaintext_len + "B): " +
                           hexdump_compact(this.plaintext, Math.min(this.plaintext_len, 64)));
                console.log("  Nonce: " + hexdump_compact(this.nonce, 24));
                // Key nicht loggen (schon extrahiert)
            }
        });
        console.log("[+] xchacha20poly1305_encrypt gehooked");
    }

    // --- crypto_aead_xchacha20poly1305_ietf_decrypt (Bestätigung) ---
    const decrypt = Module.findExportByName("libsodium.so",
        "crypto_aead_xchacha20poly1305_ietf_decrypt");
    if (decrypt) {
        Interceptor.attach(decrypt, {
            onEnter: function(args) {
                this.plaintext = args[0];
                this.ciphertext = args[3];
                this.ciphertext_len = args[4].toInt32();
                this.nonce = args[7];
                this.key = args[8];

                console.log("\n[DECRYPT] xchacha20poly1305");
                console.log("  Ciphertext (" + this.ciphertext_len + "B)");
                console.log("  Nonce: " + hexdump_compact(this.nonce, 24));
            },
            onLeave: function(retval) {
                if (retval.toInt32() === 0) {
                    const ptLen = this.ciphertext_len - 16;
                    console.log("  Decrypted (" + ptLen + "B): " +
                               hexdump_compact(this.plaintext, Math.min(ptLen, 128)));
                } else {
                    console.log("  DECRYPT FAILED (auth error)");
                }
            }
        });
        console.log("[+] xchacha20poly1305_decrypt gehooked");
    }
}

// ================================================================
// 2. BLE Write Hook — 116-Byte-Payload beim Pairing
// ================================================================

Java.perform(function() {
    // BluetoothGattCharacteristic.setValue()
    try {
        const BleChar = Java.use("android.bluetooth.BluetoothGattCharacteristic");
        BleChar.setValue.overload("[B").implementation = function(value) {
            if (value && value.length >= 100) {
                const bytes = Java.array("byte", value);
                const hex = Array.from(bytes).map(b => ("0" + ((b + 256) % 256).toString(16)).slice(-2)).join("");

                console.log("\n[BLE-WRITE] " + value.length + " bytes an Characteristic");
                console.log("  Hex: " + hex.substring(0, 200));

                if (value.length === 116) {
                    console.log("\n  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                    console.log("  !!! 116-BYTE PAIRING PAYLOAD GEFUNDEN !!!");
                    console.log("  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                    console.log("  AppPubKey (32B): " + hex.substring(0, 64));
                    console.log("  Challenge (32B): " + hex.substring(64, 128));
                    console.log("  Nonce (24B):     " + hex.substring(128, 176));
                    console.log("  AuthTag (16B):   " + hex.substring(176, 208));
                    console.log("  Meta (12B):      " + hex.substring(208, 232));

                    saveText("pairing_payload_116.hex", hex);
                    saveText("pairing_app_pubkey.hex", hex.substring(0, 64));
                    saveText("pairing_challenge.hex", hex.substring(64, 128));
                    saveText("pairing_nonce.hex", hex.substring(128, 176));
                    saveText("pairing_authtag.hex", hex.substring(176, 208));
                    saveText("pairing_meta.hex", hex.substring(208, 232));
                }
            }
            return this.setValue(value);
        };
        console.log("[+] BluetoothGattCharacteristic.setValue gehooked");
    } catch(e) {
        console.log("[!] BLE Hook fehlgeschlagen: " + e);
    }

    // SharedPreferences — Key Speicherung überwachen
    try {
        const SharedPrefsEditor = Java.use("android.app.SharedPreferencesImpl$EditorImpl");
        SharedPrefsEditor.putString.implementation = function(key, value) {
            if (key && (key.toLowerCase().indexOf("key") !== -1 ||
                        key.toLowerCase().indexOf("secret") !== -1 ||
                        key.toLowerCase().indexOf("token") !== -1 ||
                        key.toLowerCase().indexOf("shared") !== -1 ||
                        key.toLowerCase().indexOf("crypto") !== -1)) {
                console.log("\n[PREFS] putString('" + key + "', '" +
                           (value ? value.substring(0, 200) : "null") + "')");
                if (value && value.length === 64) {
                    console.log("  [!!!] Möglicher 32-Byte Hex Key!");
                    saveText("prefs_" + key.replace(/[^a-zA-Z0-9_]/g, "_") + ".txt", value);
                }
            }
            return this.putString(key, value);
        };
        console.log("[+] SharedPreferences.putString gehooked");
    } catch(e) {
        console.log("[!] SharedPrefs Hook fehlgeschlagen: " + e);
    }

    // Monitor JCA KeyAgreement (falls mylife JCA statt libsodium nutzt)
    try {
        const KeyAgreement = Java.use("javax.crypto.KeyAgreement");
        KeyAgreement.generateSecret.overload().implementation = function() {
            const secret = this.generateSecret();
            if (secret && secret.length === 32) {
                const hex = Array.from(secret).map(b => ("0" + ((b + 256) % 256).toString(16)).slice(-2)).join("");
                console.log("\n[JCA] KeyAgreement.generateSecret(): " + hex);
                saveText("jca_shared_secret.hex", hex);
            }
            return secret;
        };
        console.log("[+] JCA KeyAgreement.generateSecret gehooked");
    } catch(e) {
        console.log("[!] JCA Hook fehlgeschlagen: " + e);
    }
});

// Start
hookSodium();

console.log("\n[*] Key Extraction bereit!");
console.log("[*] Jetzt mylife App starten und mit der Pumpe verbinden/pairen.");
console.log("[*] Alle Keys werden nach " + KEY_DIR + " gespeichert.");
console.log("[*] Hauptziel: " + KEY_DIR + "/shared_key.hex");
