# Konwerter – zmiana formatów wideo i audio (Android, bez internetu)

- MOV, MKV, AVI, WebM, 3GP, TS → MP4 (H.264 + AAC) albo szybkie MP4 bez kodowania
- zmniejszanie: 1080p / 720p / 480p, jakość wysoka / średnia / mały plik, usuwanie dźwięku
- dźwięk z filmu i audio (MP3, FLAC, OGG, OPUS, WAV, AMR) → M4A albo WAV
- wiele plików naraz, w tle z powiadomieniem; wyniki w Filmy/Konwerter i Muzyka/Konwerter
- Udostępnij → Konwerter z Galerii i Moich plików; motywy kolorów i e-ink; aktualizacje w aplikacji

## Pierwsze budowanie
1. github.com/new → nazwa `konwerter`, Private, bez README.
2. Termux:
```
cd ~ && unzip -o "$(ls -t ~/storage/downloads/Konwerter*.zip | head -1)" && cd Konwerter && git init && git add . && git -c user.name=ja -c user.email=ja@x.pl commit -m start && git branch -M main && git remote add origin "$(git -C ~/Audioteka remote get-url origin | sed 's/audioteka/konwerter/')" && git push -u origin main
```
3. github.com/Bartoszelek/konwerter/releases → konwerter.apk → zainstaluj, w Ustawieniach wklej token.

## Kolejne wersje
```
cd ~ && unzip -o "$(ls -t ~/storage/downloads/Konwerter*.zip | head -1)" && cd Konwerter && git add -A && git -c user.name=ja -c user.email=ja@x.pl commit -m aktualizacja && git push
```
