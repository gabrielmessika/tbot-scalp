# Changelog — T-Bot Scalp

## 2026-03-31

### Fix getEquity() race condition au startup — peakEquity gonflé (faux drawdown)

**Bug** — `getEquity()` faisait **2 appels API séparés** à `spotClearinghouseState` : un pour `getSpotUsdcBalance()` (total - hold) et un pour `getSpotUsdcTotal()`. Au startup, si l'API spot retourne temporairement `hold=0` (race condition HL), `spotBalance = spotTotal` (~$128) au lieu de `total - hold` (~$95). Comme `accountValue` (~$95) inclut le hold, equity = $128 + $95 = $223 au lieu de $194 → `initEquity` verrouille ce pic → faux drawdown 13%.

**Fix** :
- Nouveau `getSpotUsdcInfo()` : **un seul appel API** retourne `[total, hold]` — plus de race entre 2 appels
- Cross-validation : si `spotHold=0` mais `totalMarginUsed > 0` (positions ouvertes), détection de la race → fallback conservateur `spotTotal` seul (ignore unrealized PnL plutôt que double-compter)
- `getAvailableBalance()` aussi migré vers `getSpotUsdcInfo()`
- Ajout logging dans `initEquity()` pour tracer le peakEquity initial au démarrage

### Fix equity/balance double-counting sur comptes unifiés (Portfolio Margin)

**Bug critique** — Sur les comptes Hyperliquid en Portfolio Margin (USDC en spot utilisé comme collatéral perps), l'equity et la balance étaient surestimées par double-comptage.

**`getEquity()` — double-comptage du hold spot**
- Avant : `spotTotal + (accountValue - marginUsed)` → le `spotTotal` ($192) inclut déjà le `hold` ($66) qui EST le collatéral perps (`accountValue` ≈ $66). Résultat : equity gonflée de ~$29
- Après : `spotBalance (total - hold) + accountValue` → pas de double-comptage
- **Impact** : `peakEquity` gonflé à $308 au lieu de ~$192 → faux drawdown de 28% → circuit breaker bloquait tout le trading

**`getAvailableBalance()` — mauvaise source en cours de trade**
- Avant : retournait le free margin perps ($29) quand des positions étaient ouvertes, ignorant le spot available ($126)
- Après : utilise toujours `spotBalance` (total - hold) sur comptes unifiés — c'est ce que HL utilise pour la marge des nouvelles positions

### Fix syncWithExchange — safety guard trop aggressif

Le safety guard `exchangePositions.isEmpty() && tracking > 0` bloquait la sync quand une position était **réellement** fermée sur HL (pas une erreur API).

- Avant : return immédiat si exchange retourne 0 positions → position STX restait "stuck" indéfiniment côté bot
- Après : query `getRecentCloseFillPrices()` d'abord — si des fills de fermeture existent pour les coins trackés, la position est réellement fermée → sync procède normalement. Seule l'absence de fills déclenche le guard

### Fix recoverPositions — enrichissement depuis le journal

Au redémarrage, les positions récupérées depuis HL perdaient toutes les métadonnées bot (score, candles, timestamps, trigger OIDs).

**Avant** (valeurs remises à 0) :
- `openTimestamp` = now, `candlesElapsed` = 0
- `score` = 0, `clientOrderId` / `exchangeOrderId` = null
- `tpTriggerId` / `slTriggerId` = null (syncWithExchange ne pouvait pas déterminer TP_HIT vs SL_HIT)
- `timeframe` = premier TF configuré (ignorait le TF réel du trade)
- SL/TP = fallback conservateur uniquement

**Après** (enrichi depuis journal + exchange) :
- `openTimestamp` = timestamp du journal (heure réelle d'ouverture)
- `candlesElapsed` = calculé depuis le vrai timestamp et la durée du TF
- `score`, `clientOrderId`, `exchangeOrderId` restaurés depuis la dernière entrée journal pour ce coin
- `timeframe` depuis le journal (1m ou 3m — le TF réel du trade)
- SL/TP : priorité journal → trigger prices exchange (autoritatif) → fallback conservateur
- Nouveau `getTriggerOidsByCoin()` : restaure les OIDs TP/SL depuis `frontendOpenOrders` après recovery

## 2026-03-30

### Implémentation live trading Hyperliquid

**Architecture complète du live trading** — intégration de l'exécution réelle sur Hyperliquid avec tous les guards issus des bugs corrigés sur t-bot.

#### Nouveaux fichiers
- **`ScheduledTaskService`** — 3 tâches planifiées indépendantes :
  - Analyse + exécution de signaux (toutes les 60s)
  - Polling des ordres limit pending (toutes les 10s) — détection fills + expiration/cancel automatique
  - Sync exchange + lifecycle positions (toutes les 15s) — détection TP/SL hit, trailing stop, break-even
  - Toutes les tâches wrappées en try-catch global (t-bot bug #14 : thread scheduler mort)

#### Ordres limit GTC — mécanisme de suivi et d'annulation
- **GTC + cancel after 1 candle** si non fillé — garde le rebate maker si fillé
- Les ordres pending sont trackés dans `HyperliquidExecutionService.pendingOrders` (ConcurrentHashMap)
- `checkPendingOrders()` toutes les 10s : détecte fills via `userFillsByTime`, cancel les ordres expirés
- Distinction resting vs immediate fill : si le limit order cross le book, il est traité comme FILLED immédiatement (TP/SL posés tout de suite)
- Match par OID uniquement (pas par coin — évite les faux positifs de fill)

#### OrderManagerService — exécution live intégrée
- Mode live : construit un `TradeOrder`, appelle `placeLimitOrder()` (maker) ou `placeMarketEntry()` (taker/IOC)
- Mode dry-run : inchangé
- Balance/equity depuis l'exchange en live (t-bot bug #12 : equity vs available balance pour drawdown)
- `PENDING_FILL` n'est PAS `isSuccess()` — la position n'est créée qu'au fill réel

#### PositionManagerService — lifecycle live complet
- **`processPendingFills()`** — transforme les fills en positions trackées avec TP/SL triggers
- **`syncWithExchange()`** — détecte les positions fermées par triggers TP/SL sur l'exchange
  - Safety guard : si l'exchange retourne 0 positions mais on en tracke > 0, skip le sync (t-bot bug #13)
  - Détermine TP_HIT vs SL_HIT par comparaison du prix courant
- **`recoverPositions()`** — au startup, reconstruit les positions depuis l'exchange
  - Parse leverage (Number, Map `{type, value}`, ou String)
  - SL/TP conservateurs basés sur `maxSlPercent`
  - Détection auto break-even (SL proche de l'entry = BE appliqué, t-bot bug #9)
- **`closePosition()`** — ferme réellement sur l'exchange en live (t-bot bug #1)
- **Break-even + trailing stop** — mise à jour du SL trigger order sur l'exchange (cancel + replace, t-bot bug #7)
- SL/TP candle check en dry-run uniquement (live s'appuie sur les triggers exchange)

#### StartupRunner — position recovery au startup
- Phase ajoutée : `recoverPositions()` **avant** la première analyse (pattern t-bot)
- Backtest au startup skip en live mode

#### ScalpController — endpoints exchange
- `GET /api/bot/exchange/positions` — positions directement depuis l'exchange
- `GET /api/bot/exchange/balance` — balance + equity (live ou dry-run)
- `GET /api/bot/pending-orders` — nombre d'ordres limit en attente
- `/api/state` et `/api/bot/state` — balance/equity réelles depuis l'exchange en live
- `POST /api/close-position/{pair}` — ferme réellement sur l'exchange en live

#### TradeExecution — nouveau statut et champ
- Status `PENDING_FILL` pour ordres limit non encore fillés
- Champ `score` ajouté (propagé du signal à l'ordre)

#### HyperliquidExecutionService — corrections
- `buildRejected()` helper ajouté
- `parseOrderResult()` distingue resting/immediate fill
- `checkOrderFilled()` : match par OID uniquement (pas par coin)
- Score propagé dans tous les builders TradeExecution

#### Bugs t-bot anticipés
| # | Bug t-bot | Protection dans tbot-scalp |
|---|-----------|---------------------------|
| 1 | checkNaturalClose sans closePosition | `closePosition()` ferme sur l'exchange |
| 2 | Prix TP/SL périmés | TP/SL recalculés proportionnellement au fill |
| 7 | BE SL pas mis à jour sur l'exchange | `updateExchangeStopLoss()` cancel+replace |
| 9 | BE perdu au restart | `recoverPositions()` détecte BE par distance SL/entry |
| 12 | Drawdown calculé sur balance au lieu d'equity | `getEffectiveEquity()` |
| 13 | Faux SL_HIT sur erreur 429 | Safety guard si exchange retourne 0 positions |
| 14 | Thread scheduler mort | try-catch global sur toutes les tâches planifiées |

### Ajout frais de trading + slippage de sortie au backtest
- **`takerFeePercent`** = 0.035% (Hyperliquid taker fee par côté) → déduit round-trip (entry+exit) × leverage du PnL de chaque trade
- **`exitSlippagePercent`** = 0.04% → appliqué adversement sur le prix de sortie (trigger→market slippage)
- Implémenté dans `BacktestService.buildResult()` : le PnL intègre maintenant exit slippage + fees
- Config dans `ScalpConfig` : `exitSlippagePercent`, `takerFeePercent` (overridable via properties)
- **Impact** : les ROI fantaisistes (240%–3176%) tombent à -7% à -20% sur toutes les sessions → l'edge apparent était entièrement dans les frais
- Le backtest précédent (14:46) ne modélisait ni fees ni exit slippage, ce qui surestimait massivement la rentabilité du scalp haute fréquence

### Suppression des paires non-performantes
- **Retiré ETH** de la liste des coins (WR 37.4%, avg PnL -$0.33/trade sur tous les backtests — plus gros drag du portefeuille)
- **Retiré FIL** de la liste des coins (WR 32.2%, PnL négatif)
- Modification dans `application.properties` **et** `tbot-scalp.properties` (le fichier override écrasait application.properties)

### Threshold confident relevé
- `scalp.confident-threshold` : **12.0 → 13.0** pour filtrer les signaux de faible qualité
- Les scores 12.0–12.5 avaient WR ~42-47% et PnL moyen 0.29–0.45% — encore positifs mais dégradaient la moyenne

### Sessions backtest — régimes de marché identifiés
- Remplacement des sessions offset-relatives (`90:7`, `60:7`...) par des **dates absolues avec labels** couvrant des régimes de marché distincts :
  - `2025-04-07:7:Bull Run` — hausse avril 2025
  - `2025-06-01:7:Range` — consolidation juin 2025
  - `2025-08-10:7:Bear` — bear août 2025
  - `2025-11-03:7:Crash` — crash novembre 2025
  - `2026-02-06:7:Recovery` — recovery février 2026
  - `0:3:Current` — 3 derniers jours
- Même approche que t-bot, durées conservées spécifiques au scalp (7j / 3j)
- Bug corrigé dans `sessionName()` : les labels des specs numériques (`"0:3:Current"`) étaient ignorés → retournait `"J-0 (3j)"`

### 3 nouvelles stratégies (Auction Theory / Microstructure)
Issues de la recherche sur repos GitHub publics et littérature order flow (Bookmap, Buildix, LuxAlgo, ATAS).

**`AbsorptionCandleStrategy`** (poids 2.5)
- Signal : `relativeVolume / relativeRange > 3×` la moyenne roulante, à un niveau structurel (swing high/low ou VWAP)
- Mécanisme : volume élevé + petit range = orders institutionnels passifs absorbant le flux agressif
- Distinct du scoring volume relatif existant qui récompense l'inverse (volume élevé + grand mouvement)

**`NakedPocStrategy`** (poids 2.5)
- Signal : POC (highest-volume candle mid-price sur 60 bougies) non-revisité depuis 15 bougies, prix >1.5 ATR du POC, bougie de retournement vers le POC
- Mécanisme : auction theory — le prix cherche la zone de valeur maximale (mode volumétrique)
- Distinct de VWAP Bounce : VWAP = moyenne pondérée ; POC = mode

**`OpeningRangeBreakoutStrategy`** (poids 3.0)
- Signal : premier breakout du range des 15 premières minutes après London open (08:00 UTC) ou NY open (13:00 UTC), avec volume ≥1.3× la moyenne du range
- Mécanisme : les opens de session concentrent les ordres institutionnels — le premier breakout du range définit le biais directionnel
- Seul signal temporel du bot — aucune autre stratégie n'exploite la dimension heure
- Guard : range > 3× ATR ignoré (open chaotique) ; signal uniquement sur la **première** clôture hors du range

### Fixes divers
- `tbot-scalp.properties` manquait les mises à jour faites dans `application.properties` → les deux fichiers sont maintenant synchronisés
- `isStrategyEnabled()` dans `AnalysisService` : ajout des 3 nouvelles stratégies
- `ScalpConfig` : ajout des flags `absorptionCandleEnabled`, `nakedPocEnabled`, `openingRangeBreakoutEnabled`
