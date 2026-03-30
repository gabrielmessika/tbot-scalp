# Changelog — T-Bot Scalp

## 2026-03-30

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
