# 🎙 VoiceChat – Appel vocal sur Wi-Fi local

Application Android permettant à plusieurs personnes connectées sur le **même réseau Wi-Fi** de s'appeler vocalement, sans serveur central, sans Internet.

---

## 📱 Fonctionnalités

- **Découverte automatique** des utilisateurs présents sur le réseau Wi-Fi
- **Liste en temps réel** des pairs disponibles (mise à jour toutes les 3 secondes)
- **Appel vocal bidirectionnel** en peer-to-peer via UDP
- **Gestion des appels** : sonner, accepter, refuser, raccrocher
- **Indicateur de statut** : disponible 🟢 / occupe 🔴 / appel entrant 🟡
- **Micro muet** pendant l'appel
- **Minuterie** d'appel en cours
- Fonctionne **sans Internet**, uniquement Wi-Fi local

---

## 🏗 Architecture technique

```
VoiceChat/
├── app/src/main/
│   ├── java/com/voicechat/
│   │   ├── Peer.java               ← Modèle d'un utilisateur réseau
│   │   ├── DiscoveryService.java   ← Découverte UDP multicast (239.255.42.99:45678)
│   │   ├── AudioStreamService.java ← Streaming audio PCM 16-bit via UDP
│   │   ├── PeerAdapter.java        ← RecyclerView adapter
│   │   ├── MainActivity.java       ← Écran liste des pairs
│   │   └── CallActivity.java       ← Écran d'appel en cours
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml   ← UI liste des pairs
│   │   │   ├── activity_call.xml   ← UI écran d'appel
│   │   │   └── item_peer.xml       ← Carte d'un pair
│   │   ├── values/
│   │   │   ├── colors.xml
│   │   │   ├── strings.xml
│   │   │   └── themes.xml
│   │   └── drawable/               ← Icônes et formes SVG
│   └── AndroidManifest.xml
└── build.gradle
```

### Protocole de découverte

| Paramètre     | Valeur              |
|---------------|---------------------|
| Transport     | UDP Multicast       |
| Groupe        | `239.255.42.99`     |
| Port          | `45678`             |
| Format        | JSON UTF-8          |
| Intervalle    | 3 secondes          |
| Timeout peer  | 10 secondes         |

**Types de messages JSON :**
```json
{ "type": "ANNOUNCE|CALL_REQUEST|CALL_ACCEPT|CALL_REJECT|CALL_END|BYE",
  "id": "<uuid>", "name": "<nomAppareil>",
  "audioPort": 49876, "targetId": "<uuid>" }
```

### Streaming audio

| Paramètre        | Valeur                         |
|------------------|--------------------------------|
| Fréquence        | 16 000 Hz                      |
| Canaux           | Mono                           |
| Format           | PCM 16 bits signé              |
| Transport        | UDP point-à-point              |
| Taille paquet    | 320 octets = 10 ms de latence  |
| Source           | `VOICE_COMMUNICATION` (AEC)    |
| Gain appliqué    | ×1.5 (amplification légère)    |

---

## 🚀 Installation & compilation

### Prérequis
- Android Studio **Hedgehog** (2023.1.1) ou plus récent
- JDK 17
- Android SDK API 26+ (Android 8.0 Oreo minimum)

### Étapes

1. **Ouvrir le projet dans Android Studio**
   ```
   File → Open → sélectionner le dossier VoiceChat/
   ```

2. **Synchroniser Gradle**
   ```
   File → Sync Project with Gradle Files
   ```

3. **Compiler et installer**
   ```
   Run → Run 'app'  (ou Shift+F10)
   ```

4. **Installer sur plusieurs appareils** connectés au même Wi-Fi

### Compilation en ligne de commande
```bash
cd VoiceChat
./gradlew assembleDebug
# APK généré dans : app/build/outputs/apk/debug/app-debug.apk
```

---

## 📲 Utilisation

1. **Lancer l'app** sur au moins 2 appareils connectés au **même réseau Wi-Fi**
2. **Attendre quelques secondes** que les appareils se découvrent (annonce toutes les 3s)
3. **Appuyer sur un contact** dans la liste pour initier un appel
4. L'autre appareil reçoit une **notification d'appel entrant** avec les boutons Répondre / Refuser
5. Une fois accepté, la **communication vocale démarre automatiquement**
6. Appuyer sur le bouton rouge **Raccrocher** pour terminer

---

## 🔒 Permissions requises

| Permission                          | Usage                              |
|-------------------------------------|------------------------------------|
| `RECORD_AUDIO`                      | Capture du microphone              |
| `INTERNET`                          | Communication UDP sur le réseau    |
| `ACCESS_WIFI_STATE`                 | Lecture de l'adresse IP Wi-Fi      |
| `ACCESS_NETWORK_STATE`              | Vérification connexion réseau      |
| `CHANGE_WIFI_MULTICAST_STATE`       | Activer la réception multicast UDP |
| `MODIFY_AUDIO_SETTINGS`             | Routage audio vers l'oreillette    |

---

## ⚙️ Configuration avancée

### Changer le port audio
Dans `MainActivity.java`, modifier la constante :
```java
private static final int MY_AUDIO_PORT = 49876;
```
> ⚠️ Chaque appareil utilise un port fixe pour l'instant. Pour supporter plusieurs appels simultanés, il faut attribuer un port dynamique par session.

### Changer la qualité audio
Dans `AudioStreamService.java` :
```java
public static final int SAMPLE_RATE = 16000; // 8000 = téléphone, 44100 = haute qualité
public static final int FRAME_SIZE  = 320;   // Plus petit = moins de latence, plus de paquets
```

### Changer le nom affiché
Le nom est automatiquement récupéré depuis `Settings.Global.device_name` (nom de l'appareil Android). Il peut être personnalisé dans les paramètres système de l'appareil.

---

## 🛠 Améliorations possibles

- [ ] **Compression Opus** pour réduire la bande passante de 60-70%
- [ ] **Chiffrement DTLS** des flux audio (sécurité)
- [ ] **Attribution dynamique des ports** (éviter les conflits)
- [ ] **Appels de groupe** (diffusion audio vers plusieurs pairs)
- [ ] **Historique des appels**
- [ ] **Sonnerie** à l'appel entrant
- [ ] **Mode haut-parleur** / oreillette Bluetooth
- [ ] **Indicateur de signal** (niveau audio entrant)
- [ ] **Nom d'utilisateur personnalisable** dans l'app

---

## 🐛 Dépannage

| Problème                        | Solution                                                              |
|---------------------------------|-----------------------------------------------------------------------|
| Aucun pair détecté              | Vérifier que les deux appareils sont sur le **même sous-réseau** Wi-Fi |
| Aucun pair sur certains routeurs | Certains routeurs bloquent le multicast → activer "Multicast" dans les paramètres du routeur |
| Voix hachée / coupée           | Augmenter `FRAME_SIZE` dans `AudioStreamService.java`                 |
| L'appel ne démarre pas          | Vérifier que `RECORD_AUDIO` est accordé dans Paramètres → Apps       |
| Écho dans la communication      | Utiliser un casque, ou activer l'AEC (déjà configuré via `VOICE_COMMUNICATION`) |

---

## 📄 Licence

Libre d'utilisation pour usage personnel et éducatif.
