# AOSP Android 15 Reference Sources

Cloned from `android15-release` branch of AOSP.

## Sources

- `NfcNci/` — `https://android.googlesource.com/platform/packages/apps/Nfc` branch `android15-release`
  - commit: `8d8c121` (HEAD of android15-release as of 2026-03-09)
- `framework/` — `https://android.googlesource.com/platform/frameworks/base` branch `android15-release`

## Clone commands

```sh
# NFC app
git clone --filter=blob:none --no-checkout --depth=1 --branch android15-release \
  https://android.googlesource.com/platform/packages/apps/Nfc /tmp/aosp_nfc15
cd /tmp/aosp_nfc15
git sparse-checkout init --cone
git sparse-checkout set src/com/android/nfc/cardemulation src/com/android/nfc
git checkout android15-release

# Framework (for ApduServiceInfo)
git clone --filter=blob:none --no-checkout --depth=1 --branch android15-release \
  https://android.googlesource.com/platform/frameworks/base /tmp/aosp_base15
cd /tmp/aosp_base15
git sparse-checkout init --cone
git sparse-checkout set nfc/java/android/nfc
git checkout android15-release
```

## Key findings for LmrtHijackModule implementation

### AidRoutingManager.commit() — ONE parameter in Android 15
```java
// Android 15 (android15-release)
private void commit(HashMap<String, AidEntry> routeCache)
// NOT two parameters like in the aosp-main/Android 16 version
```

### RegisteredServicesCache.getInstalledServices() — EXISTS in Android 15
```java
// Line 407 of RegisteredServicesCache.java
ArrayList<ApduServiceInfo> getInstalledServices(int userId)
```

### Empty AID catch-all in Android 15 (NCI 2.0)
In `AidRoutingManager.configureRouting()` lines 407-436:
- Condition: `(mDefaultRoute != mDefaultIsoDepRoute || mDefaultIsoDepRoute == ROUTE_HOST)`
- AND `NfcService.getInstance().getNciVersion() >= NCI_VERSION_2_0`
- Sets `entry.aidInfo = RegisteredAidCache.AID_ROUTE_QUAL_PREFIX`
- Puts `"" → entry` into routeCache

### NfcService.commitRouting() — NO parameters in Android 15
```java
// Android 15
public void commitRouting()   // no boolean arg
```
