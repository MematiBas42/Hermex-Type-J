#!/usr/bin/env python3
import os
import json
from collections import defaultdict

ROOT = '/home/ubuntu/projects/Hermex-Type-J'
ELEMENTS_JSON = os.path.join(ROOT, 'scripts/ios_elements.json')
PLAN_PATH = os.path.join(ROOT, 'plan.md')

with open(ELEMENTS_JSON, 'r') as f:
    elements = json.load(f)

# Group elements by category
by_cat = defaultdict(list)
for e in elements:
    by_cat[e['category']].append(e)

# Categorization and human-friendly headers
CATEGORY_METADATA = {
    'AppRoot': ('Uygulama Kökü ve Giriş Katmanı (AppRoot)', 'Kök uygulama pencereleri, sunucu karşılama ekranları, oturum açma ve avatar butonları.'),
    'Auth': ('Kimlik Doğrulama ve Güvenlik (Auth)', 'Biometrik kilit (FaceID/Fingerprint), PIN ekranı, AppLockWindow ve arka planda güvenli pencere kalkanı.'),
    'Config': ('Yapılandırma ve Performans (Config)', 'Performans telemetrisi, HitchMeter ölçümü ve bildirim izin bildirimleri.'),
    'Chat': ('Sohbet Çekirdeği, Transkript ve Kompozer (Chat)', 'ZStack tabanlı katmanlı sohbet, LazyColumn içerik boşlukları, BottomComposerMaterialFade gradyanı, Context Window rozeti, tekil Send/Stop butonu, mesaj balonları, araç kartları ve düşünme akordeonları.'),
    'Bots': ('Botlar, Odalar ve Çoklu Ajanlar (Bots)', 'Bot gelen kutusu, oda yönetimi, avatar/profil düzenleyici, bot bağlantı durumu ve @bot etiketleme.'),
    'Kanban': ('Kanban Panosu ve Görev Akışı (Kanban)', 'Sütunlar arası yatay kaydırma, kart sürükle-bırak, kart detay modalı ve durum odak görünümleri.'),
    'Tasks': ('Görevler ve Zamanlayıcı (Tasks)', 'CronJob zamanlayıcı, periyodik görev düzenleyici sheet, çalışma geçmişi ve ajanda görünümü.'),
    'Workspace': ('Çalışma Alanı ve Dosya Gezgini (Workspace)', 'Çalışma alanı yöneticisi, dosya ağacı, dosya önizleme ve dizin taşıma.'),
    'Workspace/SourceViewer': ('Kaynak Kod Görüntüleyici (SourceViewer)', 'Sözdizimi vurgulamalı kaynak kod inceleme, satır numaralandırma ve tam ekran görünüm.'),
    'Onboarding': ('İlk Kurulum ve Karşılama Akışı (Onboarding)', 'Karşılama sayfası, bağlantı modu seçimi, Tailscale entegrasyonu, ajan istem sayfası ve durum rozetleri.'),
    'Memory': ('Bellek ve Proje Bağlamı (Memory)', 'Kalıcı ajan hafızası, proje bağlamı, bellek düzenleme ve anahtar-değer yönetimi.'),
    'Insights': ('Analiz ve Harcama İstatistikleri (Insights)', 'Token kullanım grafikleri, model bazında maliyet dağılımı ve en aktif oturum listeleri.'),
    'SessionList': ('Oturum Listesi ve Yönetimi (SessionList)', 'Filtreleme, çoklu oturum seçimi, arşivleme, silme, logo başlığı ve oturum kartları.'),
    'Settings': ('Ayarlar ve Kişiselleştirme (Settings)', 'Model seçici, sunucu uç noktaları, özel HTTP başlıkları, uygulama ikonu seçimi, tema ve haptik ayarları.'),
    'Skills': ('Yetenekler ve Eklentiler (Skills)', 'Kategori bazlı yetenek tarayıcısı, yetenek detayları ve parametre inceleme.'),
    'MoveToHermes': ('Dışa/İçe Aktarım ve Göç (MoveToHermes)', 'WebUI ve harici sunuculardan Hermes sistemine veri göçü, profil eşleştirme ve raporlama.'),
    'Shared': ('Paylaşılan UI Bileşenleri (Shared)', 'Eylem bildirimleri (ActionToast), adaptif cam efektleri (AdaptiveGlass) ve özel başlık editörleri.'),
    'LiveActivities': ('Canlı Aktiviteler ve Ada (LiveActivities)', 'Kilit ekranı paneli, Dynamic Island kompakt/genişletilmiş görünümleri ve Android Ongoing Bildirim / Glance widget karşılıkları.'),
    'AppIntents': ('Siri / Sistem Kısayolları (AppIntents)', 'Yeni sohbet başlatma, sesli dikte ve Android Shortcuts.xml karşılıkları.'),
    'ShareExtension': ('Sistem Paylaşım Menüsü (ShareExtension)', 'Metin ve dosya paylaşımında doğrudan Hermes oturumu taslağına aktarım (ACTION_SEND).'),
    'Push': ('Zengin Anlık Bildirimler (Push)', 'APNs / FCM zengin bildirim ayrıştırma, ek indirme ve arka plan senkronizasyonu.'),
    'Hermes': ('Hermes Protokol ve Sunucu Bağlantısı (Hermes)', 'Çekirdek Hermes WebSocket/REST köprüsü, seans eşitleme ve sunucu bildirimleri.')
}

content = []
content.append("""# Hermex-Type-J: Kapsamlı Native iOS Eşitliği ve UI/UX Mimari Planı

Bu belge, `jcartu/Hermex-Type-J` Android (Jetpack Compose) istemcisinin, orijinal iOS (`HermesMobile` SwiftUI) kod tabanındaki **tüm 404 öğe, 21 modül ve 12 temel mimari desen** ile %100 deterministik eşitliğe kavuşturulması için hazırlanan eksiksiz mühendislik şartnamesidir.

---

## 1. Tespit Edilen Temel Kusurlar ve Kök Neden Analizi (Canlı Cihaz Doğrulaması)

Furkan'ın telefonunda (`io.github.jcartu.hermex`) tespit edilen arayüz bozulmalarının kök nedenleri ve çözüm hedefleri:

1. **Üst Bar ve Kaydırma Kırpılması (`2:26 AM` ve Kopyalama Butonu):**
   * *Kök Neden:* `HermesChatPane.kt` içinde üst barın saydam olması ve `LazyColumn`'un `Modifier.weight(1f)` ile sert donanımsal kesme sınırına (`clipToBounds`) tabi tutulması.
   * *Çözüm:* `ZStack` (Box) modeline geçiş, `LazyColumn`'un tüm ekranı kaplaması ve dinamik `contentPadding(top = statusBar + 56.dp, bottom = composerHeight)` verilmesi.

2. **Dikey Boşluk ve Tek Başına Duran `(72)` Context Window Rozeti:**
   * *Kök Neden:* `ContextWindowIndicatorView.kt` bileşeninin dikey `Column` içinde `Modifier.align(Alignment.End)` ile tek başına tam satırlık bir blok olarak yerleştirilmesi.
   * *Çözüm:* Rozetin kompozer üstündeki araç satırına veya çip dizisine kompakt bir çip/rozet olarak entegre edilmesi.

3. **Çift "Durdur" (Stop) Butonu:**
   * *Kök Neden:* `ChatActiveRunStatusRow` (kırmızı kare buton) ile `ComposerSendButton` (mavi yuvarlak durdur butonu) bileşenlerinin her ikisinin de bağımsız `isStreaming` kontrolü yapıp iki ayrı stop butonu çizmesi.
   * *Çözüm:* Durum satırındaki stop butonunun kaldırılması; tekil `ComposerSendButton`'un `AnimatedContent` ile Send -> Stop morflanması.

4. **Sağ Kenarda Kesilen Workspace Çipi:**
   * *Kök Neden:* `ChatComposerSelectorChips.kt` içindeki `horizontalScroll` bileşeninin kenar gradyanı (`fadingEdge`) olmadan dar ekranda sağ sınırda doğrudan kesilmesi.
   * *Çözüm:* `Brush.horizontalGradient` alfa maskesi veya dinamik ölçeklenen çip etiketlemesi.

5. **Alt Gezinti Çubuğu ve Android Hareket Çizgisi Çakışması:**
   * *Kök Neden:* `HermexApp.kt` içindeki global `Scaffold`'un sohbete girildiğinde `NavigationBar`'ı (80dp) açık bırakması ve `navigationBarsPadding` eksikliği nedeniyle sistem hareket çizgisinin etiketlerin üstüne binmesi.
   * *Çözüm:* Sohbette `NavigationBar`'ın gizlenmesi ve `Modifier.navigationBarsPadding()` eklenmesi.

6. **Yan Yana İki Klasör Görünümü (Git İkonu Hatası):**
   * *Kök Neden:* iOS'taki `point.curvedto` (Git dalı) ikonu yerine Material `Icons.Outlined.Source` kullanılması ve bunun `Icons.Outlined.Folder` ile neredeyse aynı görünmesi.
   * *Çözüm:* `Icons.AutoMirrored.Outlined.ForkRight` veya özelleştirilmiş Git branch vektör ikonu kullanılması.

---

## 2. Zorunlu 12 Temel Mimari Desen ve Yetenek (Architectural Capabilities)

Aşağıdaki mimari direktifler kod tabanında deterministik olarak doğrulanmak zorundadır:

- [ ] `ROOT_SCAFFOLD_TABBAR_AUTOHIDE`: `HermexApp.kt` içinde `ChatRoute` rota hedefine girildiğinde alttaki 80dp'lik `NavigationBar` otomatik olarak gizlenecek, sohbet tam ekran viewport kazanacaktır.
- [ ] `ZSTACK_BOX_OVERLAY_ARCHITECTURE`: `HermesChatPane.kt` ve `ChatPane.kt` içindeki kısıtlayıcı dikey `Column` yapısı terk edilip tam ekran `Box(Modifier.fillMaxSize())` katmanlama mimarisine geçilecektir.
- [ ] `EDGE_TO_EDGE_WINDOW_INSETS`: `WindowInsets.statusBars`, `WindowInsets.navigationBarsPadding()` ve yumuşak klavye yükselmesi için `Modifier.imePadding()` eksiksiz uygulanacaktır.
- [ ] `TRANSLUCENT_TOPBAR_BLUR`: Orijinal `ChatNavigationBackground.swift` ile tam parite sağlanarak üst barda yarı saydam buzlu cam (`alpha = 0.85f` + RenderEffect/Blur) zemin kullanılacaktır. Mesajlar barın arkasından akacaktır.
- [ ] `BOTTOM_COMPOSER_MATERIAL_FADE`: Orijinal `BottomComposerMaterialFade.swift` bileşeni Compose'a uyarlanacak; kompozerin arkasında 96dp'lik dikey gradyan alfa maskesi (`0.0 -> 0.18 -> 0.72 -> 1.0`) oluşturulacaktır.
- [ ] `CONTEXT_WINDOW_INDICATOR_INLINE`: Bağımsız bir satır kaplayan `(72)` rozeti dikey yığından kaldırılıp kompozer araç çubuğuna kompakt inline çip olarak entegre edilecektir.
- [ ] `SINGLE_MORPHING_STOP_BUTTON`: `ChatActiveRunStatusRow` ve `ComposerSendButton` içindeki çift durdurma butonu tekilleştirilecek; tek bir animasyonlu (`AnimatedContent`) Send/Stop butonu kullanılacaktır.
- [ ] `GIT_BRANCH_ICON_PARITY`: `Icons.Outlined.Source` klasör benzeri simgesi yerine Git dalını temsil eden ayrık `Icons.AutoMirrored.Outlined.ForkRight` simgesi atanacaktır.
- [ ] `HORIZONTAL_CHIP_SCROLL_FADING`: `ChatComposerSelectorChips` satırının sağ ve sol kenarlarına yumuşak alfa geçişi (`fadingEdge`) verilerek çip kırpılmaları önlenecektir.
- [ ] `SPRING_PHYSICS_AND_HAPTICS`: Otomatik kaydırma ve panel açılışlarında iOS yay fiziği (`spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)`) ve dokunsal geri bildirim (`LocalHapticFeedback`) kullanılacaktır.
- [ ] `RICH_ONGOING_NOTIFICATION_GLANCE`: iOS Live Activities & Dynamic Island yetenekleri Android tarafında `AgentRunService` ön plan bildirimi (Media/CallStyle zengin progress) ve Glance tabanlı ana ekran widget'ı ile tam pariteye getirilecektir.
- [ ] `OBTAINIUM_ACTIONS_RELEASE_PIPELINE`: Repoya eklenen `.github/workflows/android-release.yml` ile upstream değişikliklerinde otomatik `assembleRelease`, debug/release anahtar imzalaması (`apksigner`) ve GitHub Release (`v3.0.x`) üretilerek Obtainium üzerinden tek tıkla güncelleme sağlanacaktır.

---

## 3. Modül Bazında Detaylı Öğe Envanteri ve Parite Görev Listesi

""")

# Render all categories
all_cats = list(CATEGORY_METADATA.keys()) + [k for k in by_cat.keys() if k not in CATEGORY_METADATA]
for cat_key in all_cats:
    if cat_key in CATEGORY_METADATA:
        cat_title, cat_desc = CATEGORY_METADATA[cat_key]
    else:
        cat_title, cat_desc = (f"Modül: {cat_key}", f"{cat_key} bileşenleri ve görünümleri.")
        
    cat_elements = by_cat.get(cat_key, [])
    if not cat_elements:
        continue
        
    content.append(f"### {cat_title}")
    content.append(f"*{cat_desc}* (Toplam: {len(cat_elements)} öğe)\n")
    content.append("| Durum | Native iOS Öğesi | Tür | iOS Dosyası | Android Karşılığı | Parite Stratejisi |")
    content.append("|:---:|:---|:---:|:---|:---|:---|")
    
    for e in cat_elements:
        name = e['name']
        elem_type = e['type']
        swift_f = e['swift_file']
        kt_t = e['kt_target']
        status = e['port_status']
        
        # Determine strategy note
        if status == 'Direct Port':
            strategy = "Mevcut Composable paritesi korunarak Insets & Blur uyumu sağlanacak."
        elif status == 'Port Annotated':
            strategy = "Eşleşen dosya içi Composable incelenip iOS SwiftUI durum mantığıyla hizalanacak."
        elif status == 'Platform Equivalent':
            strategy = "Android platform karşılığı (Service/Glance/Shortcuts) ile %100 parite sağlanacak."
        elif status == 'Missing':
            strategy = "Android tarafına sıfırdan Composable / Modül olarak portlanacak."
        else:
            strategy = "Gereksinimler doğrultusunda Compose katmanına aktarılacak."
            
        content.append(f"| - [ ] `{name}` | `{name}` | {elem_type} | `{swift_f}` | `{kt_t}` | {strategy} |")
        
    content.append("\n")

# Footer
content.append("""---

## 4. Uygulama ve Doğrulama Yol Haritası

1. **Statik Denetim:** `python3 scripts/verify_plan.py` çalıştırılarak tüm 404 öğenin ve 12 temel mimari desenin planda yer aldığı deterministik olarak teyit edilir.
2. **Faz 1 (Navigasyon & Viewport Düzenlemesi):** `HermexApp.kt` ve `HermesChatPane.kt` üzerinde `Scaffold` ayrıştırması ve `Box` yerleşimi uygulanır.
3. **Faz 2 (Material Fade ve Buzlu Cam):** `BottomComposerMaterialFade` ve `ChatNavigationBackground` Compose bileşenleri geliştirilip eklenir.
4. **Faz 3 (Bileşen Sadeleştirmesi):** Çift stop butonu tekilleştirilir, Context Window rozeti kompozere taşınır, Git ikonu düzeltilir.
5. **Faz 4 (CI/CD Otomasyonu):** `.github/workflows/android-release.yml` forka eklenir, APK imzalanıp Release üretilir ve Obtainium entegrasyonu tamamlanır.
""")

with open(PLAN_PATH, 'w') as out:
    out.write("\n".join(content))

print(f"plan.md generated successfully at {PLAN_PATH} ({len(content)} lines)")
