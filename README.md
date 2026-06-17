> [!IMPORTANT]
> Vibecode-maintained Android manga reader with built-in online sources. No guarantee for full stability.

---
<div align ="center">

![Android 6.0](https://img.shields.io/badge/android-6.0+-brightgreen) [![Sources count](https://img.shields.io/badge/dynamic/yaml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fglitch-228%2Fkaisoku-parsers%2Frefs%2Fheads%2Fmaster%2F.github%2Fsummary.yaml&query=total&label=manga%20sources&color=%23E9321C)](https://github.com/glitch-228/kaisoku-parsers) [![License](https://img.shields.io/github/license/glitch-228/Kaisoku)](https://github.com/glitch-228/Kaisoku/blob/devel/LICENSE)

</div>

### 🌳 Project Lineage

**Kotatsu → Kaisoku → Dorado**

Dorado is a fork of Kaisoku, which itself originated as a fork of Kotatsu

This project builds upon the work of both upstream projects while maintaining compatibility with the existing ecosystem while introducing additional privacy, security, and transparency improvements

**Dorado adds:**

- 🔒 Privacy Hardening
- 🛡️ Security Improvements
- 📖 Transparent Defaults


<div align="center">

### Main Features

</div>
    
**🔒 Security & Privacy Enhancements**

<div align=left>

---

>This fork includes several security, privacy, and transparency improvements compared to the original repository

---

</div>

<div align=left>
    
### Security & Privacy Modifications

---

After reviewing the source code, several privacy and security concerns were identified and addressed

</div>
</div>

<div align="left">
    
### 1. Removed Default Telegram Backup Bot

> **Original Behavior**

The Telegram backup feature contained a hardcoded default bot token. Users enabling Telegram backups without configuring their own bot credentials could inadvertently upload backup archives containing application data to a third-party Telegram bot

✅ **Changes Made**

* Removed the hardcoded Telegram bot token
* Telegram backups now require explicit user configuration
* No backup data is transmitted unless the user provides their own credentials

---

### 2. Improved Network Security

> **Original Behavior**

The application allowed cleartext (HTTP) network traffic through the Android network security configuration

**Potential Risk**

Traffic sent over HTTP can be observed or modified by network operators, public Wi-Fi providers, or other intermediaries

✅ **Changes Made**

* Disabled unnecessary cleartext traffic where possible
* Encouraged HTTPS-only communication
* Added warnings for sources that do not support encrypted connections

---

### 3. Disabled Discord Rich Presence Integration

> **Original Behavior**

Discord Rich Presence functionality relied on user-provided Discord account tokens through KizzyRPC integration

**Potential Risk**

Using personal Discord tokens may violate Discord's Terms of Service and exposes reading activity through Discord presence features

✅ **Changes Made**

* Removed/disabled Discord Rich Presence integration
* Eliminated the need for users to provide Discord account tokens

---

### 4. Reduced Third-Party Data Sharing

> **Original Behavior**

The application supported optional integration with third-party tracking platforms such as MyAnimeList, AniList, Shikimori, and Kitsu

✅ **Changes Made**

* Tracking services remain strictly optional.
* Documentation has been updated to clearly explain what information may be shared when users connect external tracking accounts

---

### 5. Removed Crash Reporting Infrastructure

> **Original Behavior**

The project contained ACRA crash reporting components. Although crash submission was disabled by default, the infrastructure remained present

✅ **Changes Made**

* Removed crash reporting dependencies and related code
* No crash reports are transmitted to external servers

---

## Transparency Statement

This fork prioritizes:

* User privacy
* No data collection
* Explicit user consent
* Secure network communication
* Removal of unnecessary third-party integrations

Users are encouraged to review the source code and build the application themselves if additional verification is desired

---
<div>

## Disclaimer

This project is an independent fork and is not affiliated with or endorsed by the original Kaisoku project or its contributors


<div align="left">

* Online [manga catalogues](https://github.com/glitch-228/kaisoku-parsers) (with 1200+ manga sources)
* Regular source fixes and additions taken from other active forks
* Mihon/Tachiyomi-compatible extensions, including extension repositories, direct repo-link import from Explore, in-app extension management, and app-private loading without system-wide APK installs
* Usagi plugins
* Search manga by name, genres and more filters
* Favorites organized by user-defined categories
* Reading history, bookmarks and incognito mode support
* Download manga and read it offline. Third-party CBZ archives are also supported
* Clean and convenient Material You UI, optimized for phones, tablets and desktop
* Tappable manga details backdrops for quick browsing and full-image viewing
* Standard and Webtoon-optimized customizable reader, gesture support on reading interface
* Notifications about new chapters with updates feed, manga recommendations (with filters)
* Integration with manga tracking services: Shikimori, AniList, MyAnimeList, Kitsu
* Password / fingerprint-protected access to the app
* Automatically sync app data with other devices on the same account
* Periodic Telegram backups, including support for using your own bot token
* Support for older devices running Android 6.0+

</div>

### In-App Screenshots

<div align="center">
    <img src="./metadata/en-US/images/phoneScreenshots/1.png" alt="Mobile view" width="250"/>
    <img src="./metadata/en-US/images/phoneScreenshots/2.png" alt="Mobile view" width="250"/>
    <img src="./metadata/en-US/images/phoneScreenshots/3.png" alt="Mobile view" width="250"/>
    <img src="./metadata/en-US/images/phoneScreenshots/4.png" alt="Mobile view" width="250"/>
    <img src="./metadata/en-US/images/phoneScreenshots/5.png" alt="Mobile view" width="250"/>
    <img src="./metadata/en-US/images/phoneScreenshots/6.png" alt="Mobile view" width="250"/>
</div>

<br>

<div align="center">
    <img src="./metadata/en-US/images/tenInchScreenshots/1.png" alt="Tablet view" width="400"/>
    <img src="./metadata/en-US/images/tenInchScreenshots/2.png" alt="Tablet view" width="400"/>
</div>


### Certificate fingerprints

```plaintext
E8:40:7F:62:D5:64:F5:91:12:D0:E9:B1:B4:15:E9:A3:80:D9:67:3A
```

```plaintext
37:BA:22:C3:90:AD:44:4A:88:9C:75:31:AF:E5:69:AB:56:E7:36:AE:6C:82:7A:ED:36:B7:E9:2B:DB:8A:A1:D7
```

### License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

<div align="left">

You may copy, distribute and modify the software as long as you track changes/dates in source files. Any modifications
to or software including (via compiler) GPL-licensed code must also be made available under the GPL along with build &
install instructions.

</div>

### DMCA disclaimer

<div align="left">

The developers of this application do not have any affiliation with the content available in the app and does not store
or distribute any content. This application should be considered a web browser, all content that can be found using this
application is freely available on the Internet. All DMCA takedown requests should be sent to the owners of the website
where the content is hosted.

</div>
