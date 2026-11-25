# Cititor EPUB

Aplicație Android pentru **citit** și **ascultat** cărți `.epub`.

## Funcții

**Bibliotecă**
- Adaugi cărți `.epub` din telefon (butonul „Adaugă carte”) sau le deschizi direct din managerul de fișiere („Deschide cu…”).
- Copertă, titlu, autor și progres pentru fiecare carte; poziția de citire se salvează automat.
- Apasă lung pe o carte pentru a o șterge.

**Citire**
- Suport EPUB 2 și EPUB 3 (cuprins NCX sau nav), imagini, linkuri interne.
- Cuprins, mărime text, temă Luminos / Sepia / Întunecat, font cu sau fără serife.
- Atinge textul pentru a ascunde/arăta meniurile; butoanele ⏪ ⏩ dau pagina, ⏮ ⏭ schimbă capitolul.

**Ascultare (Text-to-Speech)**
- Butonul ▶ citește cu voce tare de la paragraful vizibil; **dublu-tap** pe un paragraf pornește citirea de acolo.
- Paragraful citit este evidențiat și pagina îl urmărește; trece automat la capitolul următor.
- Merge cu ecranul stins / în fundal, cu controale în notificare, pe ecranul de blocare și din căști.
- Viteză și ton reglabile, oprire automată (sleep timer), pauză automată la deconectarea căștilor.
- Limba vocii se alege după limba cărții (ex. română), dacă vocea este instalată.

## Voce în limba română

Aplicația folosește motorul Text-to-Speech al telefonului. Pentru o voce bună în română:
1. Instalează/actualizează **Speech Services by Google** (Google Text-to-Speech) din Magazin Play.
2. Setări → Sistem → Limbă și introducere text → Ieșire text-în-vorbire → ⚙ → Instalează date vocale → Română.

Din aplicație: Setări (iconița „T”) → „Voce și limbă (setări sistem)”.

## Compilare

Necesită JDK 17+ și Android SDK (platforma 35).

```bash
./gradlew assembleDebug
```

APK-ul rezultă în `app/build/outputs/apk/debug/app-debug.apk`. Teste unitare (parserul EPUB):

```bash
./gradlew testDebugUnitTest
```

La fiecare push, GitHub Actions construiește APK-ul și îl publică ca artefact („cititor-epub-debug”) în pagina rulării din tab-ul **Actions**.

## Structură

| Fișier | Rol |
|---|---|
| `epub/EpubBook.kt` | Parser EPUB (container, OPF, spine, cuprins, copertă) și pregătirea capitolelor pe paragrafe |
| `data/Library.kt` | Biblioteca de cărți și progresul de citire |
| `ui/MainActivity.kt` | Ecranul bibliotecii |
| `ui/ReaderActivity.kt` | Cititorul (WebView), setări, cuprins, controale audio |
| `tts/TtsService.kt` | Serviciul de redare vocală în fundal (notificare, MediaSession, audio focus) |

Cerințe: Android 8.0 (API 26) sau mai nou.
