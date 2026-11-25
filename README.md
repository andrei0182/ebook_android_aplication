# Cititor EPUB

Aplicație Android pentru **ascultat** și **citit** cărți, documente și articole, cu voce naturală și text sincronizat cuvânt cu cuvânt.

## Funcții

**Ascultare**
- Trei tipuri de voce:
  - **Naturală** (Piper): voci neurale **gratuite**, care rulează **pe telefon, fără internet**. Există în română (Mihai) și engleză (Lessac, Amy, Ryan, Alba). Se descarcă o singură dată (~65 MB per voce), iar cărțile în română/engleză primesc automat vocea potrivită.
  - **Telefon**: motorul Text-to-Speech instalat (gratuit).
  - **AI ElevenLabs**: cele mai expresive, dar necesită cheia ta API (cu plată).
- **Evidențiere cuvânt cu cuvânt** în text, cu derulare automată, pentru ambele tipuri de voce.
- **Ecran „Se redă”**: copertă mare, fundal colorat după copertă, textul curent cu cuvântul rostit evidențiat, bară de poziție în capitol, viteză (0,5x–3x), voce, sleep timer (inclusiv „la sfârșitul capitolului”).
- **Mini-player** în bibliotecă; controale în notificare, pe ecranul de blocare și din căști; merge cu ecranul stins.
- Pauză automată la deconectarea căștilor sau la apeluri.

**Import**
- **EPUB** (2 și 3), **PDF** (cu text; coperta = prima pagină), **TXT** (UTF-8 sau diacritice vechi Windows-1250).
- **Articole web**: lipești un link, aplicația extrage textul articolului (fără meniuri și reclame).
- **Text lipit** sau **partajat** din orice aplicație (butonul „Distribuie” → Cititor EPUB); se pot partaja și linkuri și fișiere.

**Citire**
- Bibliotecă tip grilă cu coperți și progres; buton 🎧 pe fiecare carte pentru ascultare directă.
- Cititor cu cuprins, mărime text, temă Luminos / Sepia / Întunecat, font cu sau fără serife.
- Dublu-tap pe un paragraf pornește ascultarea de acolo.

## Voci naturale gratuite (Piper)

Voce → **Naturală** → atinge o voce pentru a o descărca, apoi atinge-o din nou pentru a o alege și a auzi o mostră. Ține apăsat pe o voce descărcată pentru a o șterge. Vocile rulează local cu [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (fixat la v1.13.8). Modelele Piper sunt descărcate din release-ul `tts-models` al sherpa-onnx.

## Voci AI ElevenLabs (opțional)

1. Creează un cont pe elevenlabs.io și o cheie API (Profil → API Keys).
2. În aplicație: player → butonul vocii (sau meniu → Voce) → **Voce AI** → introdu cheia → **Încarcă**.
3. Alege o voce (atingerea redă o mostră) și modelul: *Calitate maximă (Multilingual v2)* sau *Rapid, cost redus (Flash v2.5)*.

Fiecare caracter citit consumă credite din contul tău ElevenLabs. Audio-ul generat se salvează pe telefon (până la ~300 MB), deci reascultarea aceluiași fragment nu costă din nou. Cheia se păstrează doar pe telefon și se trimite doar către api.elevenlabs.io.

## Voce de telefon în limba română

Instalează/actualizează **Speech Services by Google** din Magazin Play, apoi Setări → Ieșire text-în-vorbire → Instalează date vocale → Română. Din aplicație: Voce → Voce telefon → „Voce și limbă (setări sistem)”.

## Compilare

Necesită JDK 17+ și Android SDK (platforma 35).

```bash
./gradlew assembleDebug
```

APK-ul: `app/build/outputs/apk/debug/app-debug.apk`. Teste unitare:

```bash
./gradlew testDebugUnitTest
```

La fiecare push, GitHub Actions construiește APK-ul și îl publică ca artefact („cititor-epub-debug”) în tab-ul **Actions**.

## Structură

| Fișier | Rol |
|---|---|
| `epub/EpubBook.kt` | Parser EPUB; împarte capitolele în paragrafe și marchează fiecare cuvânt pentru sincronizare |
| `epub/EpubWriter.kt` | Generează EPUB din text (pentru PDF, TXT, articole, text lipit) |
| `data/Importer.kt` | Import PDF / TXT / link web / text |
| `data/Library.kt` | Biblioteca și progresul |
| `tts/TtsService.kt` | Serviciul de redare în fundal (notificare, MediaSession, sleep timer) |
| `tts/SystemSpeaker.kt` | Vocea telefonului, cu poziție pe cuvânt |
| `tts/PiperVoices.kt`, `tts/PiperEngine.kt`, `tts/PiperSpeaker.kt` | Voci naturale gratuite offline: descărcare, model, redare fără pauze |
| `tts/ElevenLabsSpeaker.kt`, `tts/ElevenLabsApi.kt` | Voci AI ElevenLabs cu marcaje de timp pe caracter, cache local |
| `ui/MainActivity.kt` | Biblioteca, importul, mini-player |
| `ui/PlayerActivity.kt` | Ecranul „Se redă” |
| `ui/ReaderActivity.kt` | Cititorul cu evidențiere sincronizată |
| `ui/VoicesActivity.kt` | Alegerea vocii |

Cerințe: Android 8.0 (API 26) sau mai nou.
