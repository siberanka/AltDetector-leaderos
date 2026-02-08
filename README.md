# AltDetector (LeaderOS-Auth Uyumlu)

Bu proje, orijinal AltDetector eklentisinin LeaderOS Auth altyapısına uygun hale getirilmis surumudur.

## Ozet

AltDetector, oyuncularin IP gecmisine gore olasi alt hesaplarini tespit eder ve yetkili kullanicilara bildirir.  
Bu surum, `LeaderOS-Auth` ile birlikte calisacak sekilde guncellenmistir.

- LeaderOS-Auth baglantisi olan sunucularda, alt kontrolu oyuncu **basariyla authenticate olduktan sonra** yapilir.
- Boylece sadece lobiye giren ama henuz giris yapmamis denemeler alt eslesmelerini kirletmez.

LeaderOS-Auth projesi:
- https://github.com/leaderos-net/minecraft-leaderos-auth

## Ozellikler

- Oyuncu girisinde IP tabanli olasi alt hesap tespiti
- `/alt` komutu ile manuel oyuncu sorgulama
- SQLite ve MySQL destegi
- PlaceholderAPI destegi
- PremiumVanish / SuperVanish uyumu
- Discord webhook entegrasyonu
- Discord embed baslik/aciklama icin placeholder destegi
- Discord embed thumbnail icin oyuncu kafa URL template destegi

## LeaderOS-Auth Uyumlulugu

Bu surumde `LeaderOS-Auth` tespit edilirse:

1. Oyuncu sunucuya baglandiginda alt taramasi hemen calismaz.
2. Eklenti, oyuncunun LeaderOS tarafinda authenticated olmasini bekler.
3. Auth basarili oldugunda alt kontrolu calisir.

`LeaderOS-Auth` kurulu degilse eklenti normal join davranisina geri doner.

## Discord Webhook Placeholderlari

`discord.embed-title`, `discord.embed-description`, `discord.embed-thumbnail-url` alanlarinda:

- `{creator}` / `{player}`: tetikleyen oyuncu adi
- `{content}`: AltDetector tarafindan olusturulan temiz mesaj
- `{server}`: `discord.mc-server-name` degeri

Ornek thumbnail:

`https://minotar.net/helm/{creator}/100.png`

Ek olarak PlaceholderAPI aktifse `%placeholder%` formatindaki degerler de cozulur.

## Komutlar

- `/alt [oyuncu]`
- `/alt delete <oyuncu>`

## Izinler

- `altdetector.alt`
- `altdetector.alt.delete`
- `altdetector.alt.seevanished`
- `altdetector.exempt`
- `altdetector.notify`
- `altdetector.notify.seevanished`

## Yapilandirma

Tum ayarlar `src/main/resources/config.yml` dosyasinda iki dilli (TR/EN) yorumlarla aciklanmistir.

## Derleme

```bash
mvn clean package
```

Derlenen jar dosyasi `target/` klasorune olusur.
