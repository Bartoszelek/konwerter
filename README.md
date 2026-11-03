# Player – odtwarzacz wideo i audio (silnik VLC) + Konwerter

- odtwarza praktycznie wszystko (MKV, AVI, MOV, WMV, FLV, TS, WebM, dźwięk AC3/DTS/TrueHD, FLAC, APE…)
- biblioteka: Wideo (foldery, „Kontynuuj oglądanie”), Muzyka (utwory, wykonawcy, albumy, foldery), Playlisty, Historia
- gesty: jasność / głośność / przewijanie, podwójne stuknięcie ±10 s, szczypanie = wypełnij
- napisy (z pliku, z filmu, pobieranie z OpenSubtitles), ścieżki dźwięku, przesunięcia, korektor
- prędkość 0,25–4×, A-B, wyłącznik czasowy, rozdziały, blokada ekranu, obrót, zrzut klatki
- odtwarzanie w tle, pływające okienko (PiP), ekran blokady, słuchawki, Android Auto
- sieć: adres URL, IPTV (M3U), udziały SMB i serwery DLNA, wysyłanie na Chromecast / Google TV
- Konwerter: MP4, MP4 HEVC, MP4 · dźwięk AAC (VLC), MOV, 3GP, GIF, M4A, WAV; wycinanie fragmentu z odtwarzacza

Aktualizacja:
```
cd ~ && unzip -o "$(ls -t ~/storage/downloads/Konwerter*.zip | head -1)" && cd Konwerter && git add -A && git -c user.name=ja -c user.email=ja@x.pl commit -m player && git push
```
