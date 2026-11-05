# FarView (Fabric 26.3)

FarView, normal Minecraft çizim mesafesini yükseltmeden istemcinin aldığı chunk'lardan 3B, bloklu uzak arazi üreten bir istemci modu prototipidir. En yüksek LOD'da her blok örneklenir; uzakta örnekler birleştirilir. Hücreler yükseklik farklarında üst ve yan yüzler çizer.

## İlk sürümdeki davranış

- Yakın çevrede vanilla chunk çizimi kullanılır.
- İstemcinin aldığı chunk'lardan yüzey yüksekliği ve yaklaşık harita rengi çıkarılır.
- Chunk başına 16×16 yüzey örneği saklanır. Yakın LOD hücreleri üst yüz ve koyulaştırılmış yan yüzlerle basamaklı arazi oluşturur.
- Normal görüşte chunk'lar birleştirilir. Kamera FOV'u zoom nedeniyle daralınca yakında 16×16, sonraki halkada 8×8 ve daha uzakta 4×4 geometri kullanılır.
- Çizim ve mesh hazırlama görüş yönüne göre geniş bir koniyle sınırlandırılır; görünmeyen hücreler çizim kuyruğuna gönderilmez.
- Arazi özeti diske yazılır; sonraki oturumda yakın çevredeki kayıtlar arka planda yüklenir.
- Uzak mesafede chunk'lar gruplandırılarak daha az geometri çizilir.
- F8 ile uzaklık 128, 256, 512 ve 1024 chunk seçenekleri arasında değişir (ilk açılışta 1024 seçilidir).
- F9 uzak arazi çizimini açıp kapatır.
- Bellek önbelleği sınırlıdır. Disk ve iş kuyruğu oyun ana iş parçacığını bekletmez.

Bu ilk prototipte bitki, yapı, mağara, blok dokusu, gölge ve ışık haritası LOD'a dahil değildir. Yüzeyler renkli arazi ağları olarak gösterilir. Voxy/Distant Horizons kalitesine yaklaşmak için sonraki adımda texture atlası, biome tint'i, kesişim/kaynak işleme, mesh batching ve gerçek oyun içi görsel test gerekir.

Uzak chunk görünümü için chunk verisinin daha önce keşfedilmiş veya sunucudan alınmış olması gerekir. Vanilla çok oyunculu sunucudan hiç gönderilmemiş chunk verisi istemci tarafından alınamaz.

## Derleme

Gereksinimler: Java 25, Fabric Loader 0.20.1 ve Fabric API 0.165.0+26.3.

```powershell
.\gradlew.bat build
```

Oluşan mod dosyası `build/libs/farview-0.1.0.jar` olur. Fabric Loader ve Fabric API 26.3 kurulumunda bu JAR'ı `mods` klasörüne koy.

## Ayarlar

`config/farview.properties` dosyası oyun ilk açıldığında oluşturulur. F8 mesafeyi, F9 uzak arazi çizimini değiştirir.

- `maxDistanceChunks`: 128, 256, 512 veya 1024
- `memoryBudgetMb`: bellekte tutulacak yüzey önbelleği üst sınırı (varsayılan 512 MB, 128 MB-16 GB arası)
- `enabled`: uzak arazi çizimini aç/kapat

Önbellek `config/farview/cache/` altına tek oyunculu dünyaların kayıt klasörü ve boyuta göre ayrı bölge dosyaları olarak yazılır. Dünya değişince RAM'deki mesh ve chunk listesi temizlenir; her kayıt kendi disk önbelleğini kullanır.

Zoom algılama kamera FOV'unun normal değere göre daralmasına dayanır; zoom eklentisi FOV'u değiştirmiyorsa yüksek çözünürlük tetiklenmeyebilir. Bu hâlâ basitleştirilmiş yükseklik alanıdır; yapı, bitki, mağara, biyom renk tonu ve gerçek blok dokuları dahil değildir. Oyun içi görsel/performance denemesi kullanıcı testinde yapılacaktır.

## Çökme düzeltmesi

Minecraft 26.3'te uzak arazi çizimi artık açık render geçişinin içine yeni GPU geçişi açmıyor; geometri oyunun çizim kuyruğuna ekleniyor. Yüzey örnekleme 64 örnek/chunk ile önceki 256 örnekli yakalamadan daha hafiftir. Oyun içi performans ve görsel testi kullanıcı yapacaktır.
