package forge.ai;

import com.google.common.collect.*;
import forge.LobbyPlayer;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.card.sticker.Sticker;
import forge.game.combat.Combat;
import forge.game.cost.*;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.*;
import forge.util.collect.FCollectionView;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

/**
 * A {@link PlayerControllerAi} that keeps the game's {@link DecisionStack}, which says which player is deciding.
 * <p>
 * forge-game can't see forge-ai, so every AI decision, including the ones made while a spell resolves, arrives through a
 * controller call. Bracketing each call here is what gives the tripwire and {@link HonestPlay} a decider. The stack
 * frames can't: both seats run the same classes. Forge sim asks for this class through
 * {@link LobbyPlayerAi#setScopedDecisions}, so default play never makes one.
 * <p>
 * Keep one override per override in {@link PlayerControllerAi}. {@code HiddenReadWatchTest} fails if one is missing,
 * which is how a new upstream decision gets caught.
 */
public final class ScopedPlayerControllerAi extends PlayerControllerAi {
    public ScopedPlayerControllerAi(Game game, Player p, LobbyPlayer lp) {
        super(game, p, lp);
    }

    private <T> T choose(final Supplier<T> decision) {
        final DecisionStack deciders = getPlayer().getGame().AI_DECIDERS;
        deciders.enter(getPlayer());
        try {
            return decision.get();
        } finally {
            deciders.exit(getPlayer());
        }
    }

    private void decide(final Runnable decision) {
        choose(() -> {
            decision.run();
            return null;
        });
    }

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        return choose(() -> super.getAbilityToPlay(hostCard, abilities, triggerEvent));
    }

    @Override
    public boolean sideboardsByItself() {
        return choose(() -> super.sideboardsByItself());
    }

    @Override
    public List<String> getSideboardingReport() {
        return choose(() -> super.getSideboardingReport());
    }

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        return choose(() -> super.sideboard(deck, gameType, message));
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        return choose(() -> super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder));
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        return choose(() -> super.divideShield(effectSource, affected, shieldAmount));
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        return choose(() -> super.specifyManaCombo(sa, colorSet, manaAmount, different));
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        return choose(() -> super.announceRequirements(ability, min, max, announce));
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return choose(() -> super.choosePermanentsToSacrifice(sa, min, max, validTargets, message));
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return choose(() -> super.choosePermanentsToDestroy(sa, min, max, validTargets, message));
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        return choose(() -> super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params));
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        return choose(() -> super.chooseContraptionsToCrank(contraptions));
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        return choose(() -> super.helpPayForAssistSpell(cost, sa, max, requested));
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        return choose(() -> super.choosePlayerToAssistPayment(optionList, sa, title, max));
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        return choose(() -> super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params));
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player targetedPlayer, Map<String, Object> params) {
        return choose(() -> super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params));
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params) {
        return choose(() -> super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params));
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        return choose(() -> super.chooseSingleSpellForEffect(spells, sa, title, params));
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        return choose(() -> super.confirmAction(sa, mode, message, options, cardToShow, params));
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode, String string, int bid, Player winner) {
        return choose(() -> super.confirmBidAction(sa, mode, string, bid, winner));
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        return choose(() -> super.confirmStaticApplication(hostCard, mode, message, logic));
    }

    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        return choose(() -> super.confirmTrigger(wrapper));
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String prompt, SpellAbility sa) {
        return choose(() -> super.confirmPayment(costPart, prompt, sa));
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        return choose(() -> super.confirmReplacementEffect(replacementEffect, effectSA, affected, question));
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return choose(() -> super.exertAttackers(attackers));
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return choose(() -> super.enlistAttackers(attackers));
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return choose(() -> super.orderBlockers(attacker, blockers));
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        return choose(() -> super.orderBlocker(attacker, blocker, oldBlockers));
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return choose(() -> super.orderAttackers(blocker, attackers));
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addSuffix) {
        decide(() -> super.reveal(cards, zone, owner, messagePrefix, addSuffix));
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addSuffix) {
        decide(() -> super.reveal(cards, zone, owner, messagePrefix, addSuffix));
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        return choose(() -> super.arrangeForScry(topN));
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return choose(() -> super.arrangeForSurveil(topN));
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        return choose(() -> super.willPutCardOnTop(c));
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        return choose(() -> super.orderMoveToZoneList(cards, destinationZone, source));
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        return choose(() -> super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser));
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean canSetupTargets) {
        decide(() -> super.playSpellAbilityNoStack(effectSA, canSetupTargets));
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        return choose(() -> super.chooseCardsToDelve(genericAmount, grave));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int num, CardCollectionView hand, String[] uTypes, SpellAbility sa) {
        return choose(() -> super.chooseCardsToDiscardUnlessType(num, hand, uTypes, sa));
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        return choose(() -> super.chooseManaFromPool(manaChoices));
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        return choose(() -> super.chooseSomeType(kindOfType, sa, validTypes, isOptional));
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        return choose(() -> super.vote(sa, prompt, options, votes, forPlayer, optional));
    }

    @Override
    public Sticker chooseSticker(List<Sticker> options, Card target, SpellAbility sa, boolean isOptional) {
        return choose(() -> super.chooseSticker(options, target, sa, isOptional));
    }

    @Override
    public int chooseStickerNamePosition(Sticker sticker, Card target) {
        return choose(() -> super.chooseStickerNamePosition(sticker, target));
    }

    @Override
    public Card chooseCardToKeepStickers(CardCollectionView options) {
        return choose(() -> super.chooseCardToKeepStickers(options));
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        return choose(() -> super.chooseSector(assignee, ai, sectors));
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        return choose(() -> super.chooseSprocket(assignee, sprockets));
    }

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        return choose(() -> super.choosePDRollToIgnore(rolls));
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return choose(() -> super.chooseRollToIgnore(rolls));
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        return choose(() -> super.chooseDiceToReroll(rolls));
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return choose(() -> super.chooseRollToModify(rolls));
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return choose(() -> super.chooseRollToSwap(rolls));
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        return choose(() -> super.chooseRollSwapValue(swapChoices, currentResult, power, toughness));
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        return choose(() -> super.mulliganKeepHand(firstPlayer, cardsToReturn));
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        return choose(() -> super.tuckCardsViaMulligan(hand, cardsToReturn));
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        decide(() -> super.declareAttackers(attacker, combat));
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        decide(() -> super.declareBlockers(defender, combat));
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        return choose(() -> super.chooseSpellAbilityToPlay());
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        return choose(() -> super.playChosenSpellAbility(sa));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return choose(() -> super.chooseCardsToDiscardToMaximumHandSize(numDiscard));
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return choose(() -> super.chooseCardsToRevealFromHand(min, max, valid));
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return choose(() -> super.chooseStartingPlayer(isFirstGame));
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return choose(() -> super.chooseStartingHand(zones));
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return choose(() -> super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return choose(() -> super.chooseNumber(sa, title, min, max));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String string, int min, int max, Map<String, Object> params) {
        return choose(() -> super.chooseNumber(sa, string, min, max, params));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> options, Player relatedPlayer) {
        return choose(() -> super.chooseNumber(sa, title, options, relatedPlayer));
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        return choose(() -> super.chooseFlipResult(sa, flipper, call));
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility saSrc, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return choose(() -> super.chooseTarget(saSrc, allTargets));
    }

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) {
        decide(() -> super.notifyOfValue(saSource, realtedTarget, value));
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultVal) {
        return choose(() -> super.chooseBinary(sa, question, kindOfChoice, defaultVal));
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Map<String, Object> params) {
        return choose(() -> super.chooseBinary(sa, question, kindOfChoice, params));
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        return choose(() -> super.chooseModeForAbility(sa, possible, min, num, allowRepeat));
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card card, ColorSet colors) {
        return choose(() -> super.chooseColorAllowColorless(message, card, colors));
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        return choose(() -> super.chooseColor(message, sa, colors));
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        return choose(() -> super.chooseColors(message, sa, min, max, options));
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        return choose(() -> super.chooseCounterType(options, sa, prompt, params));
    }

    @Override
    public String chooseKeywordForPump(final List<String> options, final SpellAbility sa, final String prompt, final Card tgtCard) {
        return choose(() -> super.chooseKeywordForPump(options, sa, prompt, tgtCard));
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        return choose(() -> super.chooseSingleReplacementEffect(possibleReplacers));
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleStatics) {
        return choose(() -> super.chooseSingleStaticAbility(possibleStatics));
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        return choose(() -> super.chooseProtectionType(sa, choices));
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        return choose(() -> super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect));
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        return choose(() -> super.payCostDuringRoll(cost, sa));
    }

    @Override
    public boolean payCombatCost(Card c, Cost cost, SpellAbility sa, String prompt) {
        return choose(() -> super.payCombatCost(c, cost, sa, prompt));
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        return choose(() -> super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt));
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) {
        return choose(() -> super.applyManaToCost(toPay, ability, prompt, matrix, effect));
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt) {
        return choose(() -> super.getCostDecisionMaker(player, ability, effect, prompt));
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        return choose(() -> super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers));
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        return choose(() -> super.orderSimultaneousSa(activePlayerSAs));
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        decide(() -> super.orderAndPlaySimultaneousSa(activePlayerSAs));
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        return choose(() -> super.playTrigger(host, wrapperAbility, isMandatory));
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        return choose(() -> super.playSaFromPlayEffect(tgtSA));
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        return choose(() -> super.chooseTargetsFor(currentAbility));
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        return choose(() -> super.chooseNewTargetsFor(ability, filter, optional));
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        return choose(() -> super.chooseCardsPile(sa, pile1, pile2, faceUp));
    }

    @Override
    public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) {
        decide(() -> super.revealAnte(message, removedAnteCards));
    }

    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) {
        decide(() -> super.revealAISkipCards(message, deckCards));
    }

    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {
        decide(() -> super.revealUnsupported(unsupported));
    }

    @Override
    public Map<DeckSection, List<? extends PaperCard>> complainCardsCantPlayWell(Deck myDeck) {
        return choose(() -> super.complainCardsCantPlayWell(myDeck));
    }

    @Override
    public CardCollectionView cheatShuffle(CardCollectionView list) {
        return choose(() -> super.cheatShuffle(list));
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        return choose(() -> super.chooseCardsYouWonToAddToDeck(losses));
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        return choose(() -> super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction));
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        return choose(() -> super.chooseCardName(sa, faces, message));
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        return choose(() -> super.chooseCardName(sa, cpp, valid, message));
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        return choose(() -> super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider));
    }

    @Override
    public List<Card> chooseCardsForZoneChange( ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        return choose(() -> super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider));
    }

    @Override
    public void resetAtEndOfTurn() {
        decide(() -> super.resetAtEndOfTurn());
    }

    @Override
    public void autoPassCancel() {
        decide(() -> super.autoPassCancel());
    }

    @Override
    public void awaitNextInput() {
        decide(() -> super.awaitNextInput());
    }

    @Override
    public void cancelAwaitNextInput() {
        decide(() -> super.cancelAwaitNextInput());
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        return choose(() -> super.chooseSingleCardFace(sa, faces, message));
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        return choose(() -> super.chooseSingleCardFace(sa, message, cpp, name));
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        return choose(() -> super.chooseSingleCardState(sa, states, message, params));
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return choose(() -> super.chooseCardsForSplice(sa, cards));
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen, List<OptionalCostValue> optionalCostValues) {
        return choose(() -> super.chooseOptionalCosts(chosen, optionalCostValues));
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        return choose(() -> super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max));
    }

    @Override
    public int chooseNumberForCostReduction(final SpellAbility sa, final int min, final int max) {
        return choose(() -> super.chooseNumberForCostReduction(sa, min, max));
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return choose(() -> super.orderCosts(costs));
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        return choose(() -> super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional));
    }
}
