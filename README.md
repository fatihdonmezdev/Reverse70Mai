# Reverse70Mai 🚗📹

SAIC MG4 Android multimedya ekranı için 70mai araç kamerasının arka kamera görüntüsünü otomatik olarak geri viteste tam/overlay formatta ekrana yansıtan bağımsız Android uygulaması.

---

## 🌟 Özellikler

1. **Geri Viteste Otomatik Tetiklenme:**
   - Araç geri vitese takıldığında SAIC OEM radar/kamera intent'i (`com.saicmotor.360camera.show`) algılanır.
   - 70mai kamerasına arka kamera sensör geçiş komutu (`sensor=1`) HTTP socket üzerinden gönderilir.
   - Ekranda sağ tarafta (1250x720 çözünürlükte) 70mai canlı RTSP yayını gösterilir; sol tarafta orijinal park sensörü / radar paneli açık kalır.
   - Vites geri vitesten çıkarıldığında (`com.saicmotor.360camera.close`) pencere otomatik kapanır ve kamera sensörü normale (`sensor=0`) döner.

2. **TBOX / Hücresel Önceliğini Aşma (Network Routing):**
   - Android multimedya sistemi internet bağlantısı olmayan 70mai Wi-Fi ağı yerine varsayılan olarak dahili SIM / `tbox0` ağını kullanır.
   - Bu uygulama `ConnectivityManager.requestNetwork` ve `bindProcessToNetwork` kullanarak RTSP ve HTTP trafiğini doğrudan 70mai Wi-Fi arayüzüne kilitler. **Hiçbir ADB veya root komutuna gerek kalmadan çalışır!**

3. **Cihaz Açılışında Otomatik Başlama (Boot Autostart):**
   - Araç veya multimedya sistemi açıldığında arka plandaki servis otomatik olarak ayağa kalkar.

4. **Sadece MG4 Donanımına Optimize:**
   - MG4 multimedya işlemcisine özel olarak yalnızca `arm64-v8a` mimarisi dahil edilmiştir (APK boyutu ~47 MB).

---

## 📲 Kurulum

1. [Releases](https://github.com/fatihdonmezdev/Reverse70Mai/releases) sayfasından en son `Reverse70Mai.apk` dosyasını indirin.
2. USB bellek veya dosya yöneticisi ile MG4 multimedya ünitesine yükleyin.
3. Uygulamayı ilk kez açarak **"Diğer uygulamaların üzerinde göster" (Overlay Permission)** iznini verin ve **"Servisi Başlat"** butonuna basın.
4. Multimedya ünitenizin 70mai Wi-Fi ağına bağlı olduğundan emin olun.
5. Vitesi R'ye takın, arka kameranın keyfini çıkarın!
