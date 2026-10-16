# SMS Gateway (Android / Kotlin) — SMS depuis un PC Windows via USB, avec la SIM du téléphone

## 1. Architecture

```
Excel / programme PC
        │  HTTP (JSON + jeton)  →  http://127.0.0.1:8765   (sur le PC)
        ▼
   adb forward tcp:8765 tcp:8765      ← tunnel à travers le câble USB (ADB)
        ▼
 ┌─────────────────────────── Téléphone Android ───────────────────────────┐
 │ GatewayService (service de premier plan)                                │
 │   ├─ LocalHttpServer : écoute UNIQUEMENT 127.0.0.1:8765                 │
 │   ├─ ApiRouter : Host/Origin → jeton → validation                       │
 │   └─ JobManager : quota + file Room (1 SMS à la fois) ───┐              │
 │                                                          ▼              │
 │  SmsSender → SmsManager.createForSubscriptionId(subId) → SIM choisie    │
 │  SmsResultReceiver ← callbacks SENT / DELIVERED (par segment) → Room    │
 └─────────────────────────────────────────────────────────────────────────┘
```

**Pourquoi HTTP local + ADB forward ?** Le câble USB d'un téléphone Android est, côté PC, un périphérique
MTP/ADB. Sans driver ni code côté PC, `adb forward` est le seul canal USB bidirectionnel disponible sur un
Windows « classique » ; l'application n'a besoin que d'un serveur sur `127.0.0.1`. Alternatives écartées :
*Android Open Accessory* (driver libusb/WinUSB à installer sur le PC, protocole propriétaire),
*USB tethering* (le serveur écouterait sur une interface réseau, donc plus exposé).
Prérequis : **Options développeur + Débogage USB** activés sur le téléphone.

Le serveur et la file d'envoi vivent dans le téléphone : **le PC n'est pas nécessaire pendant l'envoi**.
Si le câble est débranché, les SMS déjà en file continuent à partir ; le PC reprend le suivi en rebranchant
(`adb forward` + `GET /api/jobs/{id}`).

### Arborescence

```
SmsGateway/
├── settings.gradle.kts · build.gradle.kts · gradle.properties · gradle/wrapper/gradle-wrapper.properties
├── pc-client/send_from_excel.py            exemple Windows (Excel → API)
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml · res/values/{strings,themes}.xml
        └── java/mg/smsgateway/
            ├── App.kt                      conteneur de dépendances
            ├── Config.kt                   port, limites, délais
            ├── data/   Entities · Daos · AppDatabase · SettingsRepository   (Room)
            ├── domain/ SpreadsheetImporter · PhoneNumberValidator · SmsValidator · JobManager
            ├── sim/    SimManager                                           (SubscriptionManager)
            ├── sms/    SmsSender · SmsResultReceiver · ScheduledCampaignScheduler
            ├── server/ LocalHttpServer · ApiRouter                          (HTTP local + sécurité)
            ├── service/ GatewayService                                      (service de premier plan)
            ├── ui/     MainActivity · MainViewModel · Screens · CampaignScreen
            └── util/   AppLogger · Util
```

## 2. Campagnes depuis le téléphone

L'écran **Créer une campagne SMS** permet de sélectionner un classeur `.xlsx` ou un fichier `.csv`
avec le sélecteur Android, d'examiner les colonnes candidates, de confirmer une colonne et sa ligne d'en-tête,
et de corriger/sélectionner les numéros. Les numéros peuvent aussi être saisis ou collés manuellement.
L'ancien format Excel `.xls` n'est pas pris en charge; convertissez-le en `.xlsx` ou `.csv`.
L'import est local et n'envoie aucun SMS. Une valeur Excel stockée en nombre sans format de zéros ne permet pas
de retrouver avec certitude un zéro initial perdu; ces valeurs sont signalées avant envoi.

Dans le message, tapez `#` pour choisir une variable disponible, par exemple `#NOM`, `#PRENOM` ou `#CLASSE`.
Les en-têtes sont normalisés en majuscules (les accents et espaces sont simplifiés) et chaque colonne est aussi
accessible par sa lettre Excel (`#A`, `#B`, etc.). Les variables sont mises en évidence, et l'envoi est bloqué si
un nom de colonne est inconnu ou si un destinataire n'a pas cette colonne. Chaque SMS reçoit son propre message
personnalisé; les segments et le quota sont calculés selon le contenu final de chaque destinataire.

Le brouillon (fichier source, message, destinataires et valeurs de colonnes importées) est stocké dans Room.
L'envoi immédiat passe par le même
`SmsManager`, la même SIM, la même file séquentielle, le même quota et les mêmes résultats par destinataire que
l'API PC. La confirmation dans l'application exige aussi de confirmer l'autorisation de contacter la liste.
L'interrupteur « Envoi autorisé » reste propre à l'API USB; l'envoi mobile confirmé n'en dépend pas.

Une campagne différée est **unique**. Sa transition persistante `SCHEDULED → QUEUED` est conditionnelle en base;
une alarme répétée ou un rappel ne peut donc pas créer un second démarrage. L'utilisateur peut modifier le
message, les numéros ou l'heure, ou annuler jusqu'au déclenchement. L'annulation après le début arrête les SMS
restants, mais ne rappelle pas ceux déjà remis au système téléphonique. Les campagnes et destinataires sont
conservés dans Room; le receiver de démarrage restaure les alarmes après redémarrage.

La planification utilise `AlarmManager.setAndAllowWhileIdle` sans permission spéciale d'alarme exacte. Android
ou le constructeur peut donc retarder le déclenchement, notamment en mode Doze/économie d'énergie; l'heure n'est
pas garantie à la seconde. Les rappels 5 min et 1 min ne sont créés que s'ils sont encore dans le futur. Si
`POST_NOTIFICATIONS` n'est pas autorisée, l'application l'indique et n'affiche pas de rappel, mais l'alarme
d'envoi reste enregistrée. L'envoi après redémarrage nécessite que le système ait terminé son démarrage et que
l'application puisse lancer son service de premier plan.

Cette version envoie via la SIM locale uniquement; aucun fournisseur SMS externe n'est intégré. Elle ne contourne
ni les quotas Android/opérateur ni les permissions SMS.

## 3. Compiler

Prérequis : Android Studio (JDK 17 embarqué), SDK Android 35 (Android Studio le propose).

1. *File → Open* → dossier `SmsGateway`. Android Studio télécharge Gradle 8.9 et synchronise.
   (`gradle-wrapper.jar` n'est pas fourni ; Android Studio s'en passe. En ligne de commande :
   `gradle wrapper --gradle-version 8.9`, puis `gradlew.bat assembleDebug`.)
2. *Build → Build Bundle(s) / APK(s) → Build APK(s)*.
   Résultat : `app\build\outputs\apk\debug\app-debug.apk`.

Versions utilisées (cohérentes entre elles, mettez-les à jour si Android Studio le propose) :
AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.12.01, Room 2.6.1, minSdk 26, target/compileSdk 35.

## 4. Installer sur le téléphone et connecter à Windows

**Téléphone** : Paramètres → À propos → toucher 7 fois « Numéro de build » → Options pour les développeurs →
activer **Débogage USB**.

**PC Windows** : installer les [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)
(dézipper, par ex. `C:\platform-tools`, l'ajouter au PATH). Si `adb devices` ne liste rien, installer le pilote USB
du fabricant (ou « Google USB Driver »). Brancher en USB, accepter la fenêtre « Autoriser le débogage USB ? »
(cocher « Toujours autoriser »).

```bat
adb devices                                   :: doit afficher "device" (pas "unauthorized")
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n mg.smsgateway/.ui.MainActivity     :: ouvre l'app et démarre la passerelle
adb forward tcp:8765 tcp:8765                 :: tunnel USB PC:8765 -> téléphone:8765
adb forward --list
adb shell dumpsys deviceidle whitelist +mg.smsgateway    :: (recommandé) hors économie de batterie
adb forward --remove tcp:8765                 :: fermer le tunnel
```

**Première configuration sur le téléphone** : 1) *Autoriser* les permissions ; 2) choisir la **SIM** ;
3) *Paramètres* → régler **SMS disponibles** (plafond) ; 4) *Paramètres* → *Afficher/Copier* le **jeton** ;
5) activer **« Envoi autorisé »**. Tant que le plafond vaut 0, aucune SIM n'est choisie ou l'interrupteur est coupé,
l'API refuse tout envoi.

> **Android 13+ et APK « sideloadée »** : si l'autorisation SMS est grisée ou n'apparaît pas, ouvrir
> *Paramètres → Applications → SMS Gateway → ⋮ → « Autoriser les paramètres restreints »*, puis revenir aux
> permissions. L'installation via `adb install` évite parfois cette étape, pas toujours selon la version/le fabricant.

## 4. Permissions

| Permission | Pourquoi | Si refusée |
|---|---|---|
| `SEND_SMS` | envoyer par `SmsManager` | `PERMISSION_DENIED` : aucun job accepté |
| `READ_PHONE_STATE` | lister les SIM (`SubscriptionManager`), état SIM/réseau | aucune SIM détectée → envoi refusé |
| `READ_PHONE_NUMBERS` | numéro de la SIM (informatif, souvent vide) | « numéro non disponible » |
| `POST_NOTIFICATIONS` (13+) | afficher la notification du service | le service tourne, notification masquée |
| `FOREGROUND_SERVICE(_SPECIAL_USE)`, `WAKE_LOCK` | service qui survit en arrière-plan / écran éteint | — |
| `INTERNET` | **uniquement** pour ouvrir un socket serveur sur 127.0.0.1 ; l'app n'ouvre aucune connexion sortante | — |

Aucune permission n'est contournée. L'app n'est **pas** l'application SMS par défaut : ce n'est pas nécessaire pour
envoyer. Conséquence : les SMS envoyés n'apparaissent normalement pas dans l'application « Messages » du téléphone.
(Google Play restreint fortement `SEND_SMS` ; l'APK est prévue pour une installation directe.)

## 5. API locale (JSON, `Authorization: Bearer <jeton>`)

`GET /api/ping` (sans jeton) · `GET /api/device` · `GET /api/sims` · `GET /api/status` ·
`POST /api/sms/validate` · `POST /api/sms/send` · `POST /api/sms/cancel` `{"jobId":"…"}` ·
`POST /api/jobs/{id}/cancel` · `GET /api/jobs` · `GET /api/jobs/{id}` ·
`GET /api/jobs/{id}/recipients?status=FAILED&offset=0&limit=200` · `GET /api/logs?limit=100`

Corps de `validate` / `send` :

```json
{ "subscriptionId": 3, "message": "Bonjour", "recipients": ["+261341234567", "034 12 345 67"],
  "skipInvalid": false, "requestId": "uuid-optionnel-idempotence" }
```

`validate` répond (extrait) : `valid`, `recipientCount`, `rows`, `duplicates`, `invalidRecipients[]`,
`segmentsPerSms`, `encoding`, `smsSegmentsRequired`, `smsLimit`, `smsAlreadyUsed`, `smsReserved`, `smsRemaining`,
`smsMissing`, `errors[{code,message}]`. `send` répond `202 {"jobId","status":"QUEUED"}` ou
`422` avec le même rapport si le job est refusé. Statuts de job : `QUEUED, RUNNING, COMPLETED,
COMPLETED_WITH_ERRORS, CANCELLED`. Statuts de SMS : `PENDING, SENDING, SENT, FAILED, CANCELLED`.

Exemples PowerShell (Windows) :

```powershell
$h = @{ Authorization = "Bearer COLLER_LE_JETON" }
$u = "http://127.0.0.1:8765"
Invoke-RestMethod "$u/api/sims" -Headers $h
$body = @{ subscriptionId = 3; message = "Bonjour, votre commande est disponible."
           recipients = @("+261341234567","+261321234567","+261331234567","+261341234567","+261340000000")
           requestId = [guid]::NewGuid().ToString() } | ConvertTo-Json
$bytes = [Text.Encoding]::UTF8.GetBytes($body)       # accents corrects
Invoke-RestMethod -Method Post "$u/api/sms/validate" -Headers $h -ContentType "application/json" -Body $bytes
$job = Invoke-RestMethod -Method Post "$u/api/sms/send" -Headers $h -ContentType "application/json" -Body $bytes
Invoke-RestMethod "$u/api/jobs/$($job.jobId)" -Headers $h
Invoke-RestMethod -Method Post "$u/api/jobs/$($job.jobId)/cancel" -Headers $h
```

curl.exe : `curl.exe -H "Authorization: Bearer JETON" http://127.0.0.1:8765/api/status`
(utiliser `127.0.0.1`, pas `localhost`, pour éviter la résolution IPv6 que le tunnel ADB ne transporte pas).

### Exemple complet : 5 SMS

Plafond 50, utilisés 12. Message « Bonjour, votre commande est disponible. » (GSM-7, 1 segment), 5 lignes dont 1 doublon :

1. `adb forward tcp:8765 tcp:8765`
2. `GET /api/sims` → `subscriptionId: 3` marqué `"selected": true`.
3. `POST /api/sms/validate` → `rows 5, recipientCount 4, duplicates 1, smsSegmentsRequired 4, smsRemaining 38, valid true`.
4. `POST /api/sms/send` → `202 {"jobId":"…","status":"QUEUED"}`.
5. Sur le téléphone, la file traite un SMS à la fois : `PENDING → SENDING` (demande acceptée par Android) →
   callback `RESULT_OK` → `SENT` ; compteur « utilisés » : 13, 14, 15, 16.
6. `GET /api/jobs/{id}` → `{"status":"COMPLETED","sent":4,"failed":0,"percent":100}` ; utilisés = 16, restants = 34.

À noter : `ê` (et d'autres caractères hors alphabet GSM) fait passer le message en Unicode : 70 caractères/segment
(67 si multi-segments) au lieu de 160 (153). « …est prête » peut donc coûter plus de segments ; `validate` indique
`encoding` et `segmentsPerSms`, et le quota est toujours calculé en segments × destinataires uniques.

## 6. Comment le SMS passe réellement par la SIM

`SmsSender` appelle `SmsManager.createForSubscriptionId(subId)` (API 31+, `getSmsManagerForSubscriptionId` avant)
avec le `subscriptionId` choisi, puis `sendMultipartTextMessage(...)`. Le framework transmet la demande au service
de téléphonie, qui l'envoie au modem du **slot rattaché à cet abonnement** ; le SMS part par la signalisation de
l'opérateur (SMSC) de cette SIM, **sans Internet**, et est décompté du forfait SMS de cette SIM.
L'identité d'une SIM est son `subscriptionId` ; « SIM 1 / SIM 2 » n'est que le numéro de slot affiché.
La SIM choisie est celle de l'écran du téléphone : une requête PC avec un autre `subscriptionId` est refusée (`SIM_MISMATCH`).

## 7. Suivi d'état (ce que « envoyé » veut dire)

`sendMultipartTextMessage()` qui revient sans exception = *demande acceptée*, rien de plus. Un `PendingIntent` par segment
rapporte le résultat réel au `SmsResultReceiver` : `RESULT_OK` (le segment a quitté le téléphone vers le réseau) ou un code
d'erreur (`NO_SERVICE`, `RADIO_OFF`, `LIMIT_EXCEEDED`, …). Un SMS passe `SENT` quand **tous** ses segments sont OK, `FAILED`
sinon. Un second `PendingIntent` demande l'accusé de **livraison** (`deliveredSegments`) : l'opérateur/le destinataire peuvent ne
jamais le fournir, son absence ne signifie pas un échec.

## 8. Quota (jamais dépassé)

* Limite, compteur et SIM sont dans Room et survivent aux redémarrages ; *Nouvelle période* remet le compteur à 0.
* `disponible = limite − utilisés − réservés`. À la création d'un job, `segments × destinataires uniques` doit tenir dans
  `disponible`, sinon **refus total** (`QUOTA_EXCEEDED`, « Il manque N SMS »). Validation et insertion sont sérialisées
  (mutex) : deux requêtes simultanées ne peuvent pas réserver le même quota.
* « utilisés » augmente de 1 à chaque **segment confirmé** par Android. Avant chaque SMS, la file revérifie
  `utilisés + segments ≤ limite` (couvre une limite baissée en cours de route).
* SMS d'issue **inconnue** (timeout de 120 s, app tuée pendant l'envoi) : marqué `FAILED` (« état incertain »), **jamais renvoyé
  automatiquement** (risque de doublon) et ses segments non résolus sont comptés comme **consommés** (hypothèse prudente).
* Le compteur est local : l'app ne connaît pas le solde réel de l'opérateur.

## 9. Sécurité

Serveur lié à `127.0.0.1` uniquement · jeton aléatoire 192 bits (comparaison à temps constant, régénérable) ·
verrouillage 1 min après 10 jetons faux · `Host` limité à `127.0.0.1`/`localhost` et requêtes de navigateur
(`Origin`) refusées (anti-DNS-rebinding) · corps limité à 2 Mo, ≤ 2000 lignes/job, ≤ 10 segments/message ·
interrupteur « Envoi autorisé » (coupé par défaut) · SIM imposée par le téléphone · `requestId` idempotent ·
numéros masqués dans les logs · `allowBackup=false`.
Limite honnête : tant que le tunnel `adb forward` est ouvert, tout programme du PC qui **connaît le jeton** peut utiliser l'API.
Les autres applications du téléphone ne peuvent pas non plus l'utiliser sans le jeton. Fermez le tunnel ou coupez
l'interrupteur quand vous n'envoyez pas.

## 10. Limites Android à connaître

* **Anti-spam du système** : par défaut AOSP affiche une confirmation quand une app envoie ≥ 30 SMS en 1 minute (selon fabricant).
  L'app se limite à 20 segments/minute (réglable dans *Paramètres*). Au-delà, la boîte de dialogue demande une action
  manuelle ; certains guides proposent `adb shell settings put global sms_outgoing_check_max_count <n>` (efficacité variable
  selon le fabricant ; à tester).
* Un envoi de 1000 SMS à 20/min dure ~50 min ; c'est voulu.
* Dual-SIM : si le téléphone demande de choisir la SIM par popup, c'est que le `subscriptionId` n'est pas passé : ne devrait pas
  arriver, la SIM est toujours explicite ici.
* Écran verrouillé : OK (service de premier plan + WakeLock partiel). Désactivez l'optimisation batterie (commande ci-dessus).
* Après redémarrage du téléphone, il faut rouvrir l'app une fois (l'activité démarre le service) : le service n'est pas lancé au boot.
* CDMA : le multi-segment peut être mal supporté (rare à Madagascar).

## 11. Erreurs gérées

SIM absente/non prête (`SIM_NOT_FOUND`, `SIM_NOT_READY`) · réseau (`NO_NETWORK_SERVICE`, réessayé 3 fois puis `FAILED`) ·
numéro invalide (`INVALID_RECIPIENTS`, ou ignoré avec `skipInvalid`) · permission refusée · quota dépassé · message vide/trop long ·
erreurs `SmsManager` (exception ou code de résultat) · subscription invalide · app redémarrée pendant un envoi (`INTERRUPTED`) ·
connexion PC perdue (la file continue) · port 8765 occupé (affiché sur l'écran principal).

## 12. Étendre à d'autres pays

Ajouter un `object XxxRule : CountryRule` (indicatif, règle mobile, format local) et l'ajouter à la liste dans `App.kt`.
Les numéros d'un pays sans règle sont acceptés s'ils respectent E.164 (8 à 15 chiffres). Pour une validation exhaustive,
`libphonenumber` peut être branché derrière `PhoneNumberValidator`.
