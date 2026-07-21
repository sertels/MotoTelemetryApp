# Moto Telemetry App - Voge 900DSX & BMW F900 Architecture

Bu proje, **Voge 900DSX** (ve BMW F900 mimarisi tabanlı diğer motosikletler) için geliştirilmiş, sürüş verilerini canlı olarak takip eden, analiz eden ve kaydeden bir Android telemetri uygulamasıdır.

## 🚀 Temel Özellikler

- **Canlı Motor Verileri (OBD2):** Bluetooth ELM327 adaptörü üzerinden Hız, Devir, Gaz Kolu Yüzdesi ve Ön/Arka Fren Basıncı (UDS/Enhanced PID) takibi.
- **Fiziksel Analiz (IMU):** Telefonun dahili sensörlerini kullanarak gerçek zamanlı **Yatış Açısı (Lean Angle)** ve **G-Kuvveti** ölçümü.
- **Rota Takibi (GPS):** Google Maps entegrasyonu ile sürüş rotasının harita üzerinde çizilmesi.
- **Veri Kaydı (Room DB):** Tüm telemetri verilerinin (Hız, Devir, Yatış, GPS vb.) 5Hz (saniyede 5 kez) frekansla yerel veri tabanına kaydedilmesi.
- **Modern Dashboard:** Jetpack Compose ile tasarlanmış, anlık verileri görselleştiren sportif gösterge paneli.

## 🛠 Kullanılan Teknolojiler

- **Dil:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Mimari:** MVVM & Clean Architecture
- **Veri Tabanı:** Room Database
- **Bağlantı:** Bluetooth Classic (RFCOMM) & UDS Protocol
- **Konum:** Google Play Services Fused Location
- **Navigasyon:** Jetpack Navigation

## 📋 Kurulum ve Kullanım

1.  **Google Maps API:** `app/src/main/AndroidManifest.xml` dosyasındaki `com.google.android.geo.API_KEY` alanına kendi API anahtarınızı ekleyin.
2.  **OBD2 Adaptörü:** Bluetooth ELM327 adaptörünüzü motosikletinize takın ve telefonunuzla eşleştirin (Eşleşme isminin "OBDII" içerdiğinden emin olun).
3.  **İzinler:** Uygulama ilk açılışta Bluetooth, Konum ve Bildirim izinlerini isteyecektir.
4.  **Takip:** Ana ekrandaki "Takibi Başlat" butonu ile veri toplama döngüsünü ve arka plan servisini başlatabilirsiniz.

## 📸 Dashboard Arayüzü

- **Panel:** Hız, Devir, Gaz ve Fren barlarını gösterir. Motosiklet silüeti telefonun eğimine göre gerçek zamanlı olarak yatar.
- **Geçmiş:** Kaydedilen rotayı harita üzerinde Polyline olarak görüntüler.

## ⚠️ Önemli Notlar

- **Yatış Açısı:** En doğru ölçüm için telefonun motosiklet üzerinde dikey ve sağlam bir şekilde monte edilmesi önerilir.
- **UDS Desteği:** Fren basıncı gibi veriler BMW/Voge özel PID'leridir. ELM327 adaptörünüzün kalitesine göre bu verilerin okunabilirliği değişebilir.

---
*Geliştiren: Sertel Şekerci*
