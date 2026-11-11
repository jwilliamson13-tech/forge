package forge.ai.simulation;

import com.google.common.collect.*;
import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardType;
import forge.card.CardStateName;
import forge.game.*;
import forge.game.ability.effects.DetachedCardEffect;
import forge.game.card.*;
import forge.game.card.token.TokenInfo;
import forge.game.combat.Combat;
import forge.game.mana.Mana;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.PlayerZoneBattlefield;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import forge.ai.HonestPlay;

public class GameCopier {
    private static final ZoneType[] ZONES = new ZoneType[] {
        ZoneType.Battlefield,
        ZoneType.Hand,
        ZoneType.Graveyard,
        ZoneType.Library,
        ZoneType.Exile,
        ZoneType.Stack,
        ZoneType.Command,
    };

    private Game origGame;
    private BiMap<Player, Player> playerMap = HashBiMap.create();
    private BiMap<Card, Card> cardMap = HashBiMap.create();
    private CopiedGameObjectMap gameObjectMap;
    private GameSnapshot snapshot = null;
    /** Copy the spells on the stack too (see {@link GameSimulator#COPY_STACK}); decided per copier. */
    private boolean copyStack = GameSimulator.COPY_STACK;
    private final Map<SpellAbility, SpellAbility> stackMap = new HashMap<>();
    /**
     * The player the copies are for. With {@link Game#AI_PRUNE_HIDDEN_INFO} on, what it can't know of the other players'
     * hidden cards stays out of them. A copy made without naming one is for the player named last.
     */
    private Player viewer;
    /**
     * With the prune on, what each hidden card of the real game is copied as: the card the viewer believes is in its
     * place, or null for the placeholder. A card that isn't a key is copied as it is. Made again for each copy.
     */
    private Map<Card, IPaperCard> pruned;
    /** The real cards in hidden places that the viewer may see right now, so they are copied as they are. */
    private final List<Card> seenHidden = new ArrayList<>();
    /** The copies that weren't built from the real card they map to, so nothing else of that card goes into them. */
    private final Set<Card> notFromTheirCard = Collections.newSetFromMap(new IdentityHashMap<>());

    public GameCopier(Game origGame) {
        this.origGame = origGame;
        // a snapshot copies every card as it is, so it has no place in a pruned copy
        if (origGame.EXPERIMENTAL_RESTORE_SNAPSHOT && !origGame.AI_PRUNE_HIDDEN_INFO) {
            this.snapshot = new GameSnapshot(origGame);
        }
    }

    /** Makes {@link #makeCopy} copy the spells on the stack, so that a decision with a spell on the stack can be simulated. */
    public GameCopier withStack(final boolean copy) {
        this.copyStack = copy;
        return this;
    }

    public Game getOriginalGame() {
        return origGame;
    }

    /**
     * Cuts every link to the game this copy was made from (and the maps between the two). Only the copied game stays
     * alive. For search nodes that are kept for a long time; after this {@link #find} and {@link #reverseFind} find
     * nothing, and {@link #makeCopy} must not be called again.
     */
    public void release() {
        if (gameObjectMap != null) {
            releasedGame = gameObjectMap.getGame();
        }
        origGame = null;
        playerMap = HashBiMap.create();
        cardMap = HashBiMap.create();
        stackMap.clear();
        gameObjectMap = null;
        snapshot = null;
    }

    private Game releasedGame;

    public Game getCopiedGame() {
        return gameObjectMap != null ? gameObjectMap.getGame() : releasedGame;
    }

    public Game makeCopy() {
        return makeCopy(null, null);
    }
    public Game makeCopy(PhaseType advanceToPhase, Player aiPlayer) {
        if (snapshot != null) {
            // How do we advance to phase when using restores?
            return snapshot.makeCopy();
        }
        if (aiPlayer != null) {
            viewer = aiPlayer;
        }
        seenHidden.clear();
        notFromTheirCard.clear();
        pruned = origGame.AI_PRUNE_HIDDEN_INFO ? planPrune() : null;

        List<RegisteredPlayer> origPlayers = origGame.getMatch().getPlayers();
        List<RegisteredPlayer> newPlayers = new ArrayList<>();
        for (RegisteredPlayer p : origPlayers) {
            newPlayers.add(clonePlayer(p));
        }

        GameRules currentRules = origGame.getRules();
        Match newMatch = new Match(currentRules, newPlayers, origGame.getView().getTitle());
        Game newGame = new Game(newPlayers, currentRules, newMatch);
        newGame.markAsSimulationCopy();
        newGame.setNoGUIUser();
        newGame.dangerouslySetTimestamp(origGame.getTimestamp());

        for (int i = 0; i < origGame.getPlayers().size(); i++) {
            Player origPlayer = origGame.getPlayers().get(i);
            Player newPlayer = newGame.getPlayer(origPlayer.getId());
            newPlayer.setTeam(origPlayer.getTeam());
            newPlayer.setLife(origPlayer.getLife(), null);
            newPlayer.setLifeLostLastTurn(origPlayer.getLifeLostLastTurn());
            newPlayer.setLifeLostThisTurn(origPlayer.getLifeLostThisTurn());
            newPlayer.setLifeGainedThisTurn(origPlayer.getLifeGainedThisTurn());
            newPlayer.setCommitedCrimeThisTurn(origPlayer.getCommittedCrimeThisTurn());
            newPlayer.setDilemmasFacedThisTurn(origPlayer.getDilemmasFacedThisTurn());
            newPlayer.setLifeStartedThisTurnWith(origPlayer.getLifeStartedThisTurnWith());
            newPlayer.setDamageReceivedThisTurn(origPlayer.getDamageReceivedThisTurn());
            newPlayer.setLandsPlayedThisTurn(origPlayer.getLandsPlayedThisTurn());
            newPlayer.setCounters(HashMultiset.create(origPlayer.getCounters()));
            newPlayer.setSpeed(origPlayer.getSpeed());
            // Blessing state travels with the copied command-zone effect card
            // (wired via copyEffectCardsToSnapshot below); calling setBlessing
            // here would create a second effect card the zone copy duplicates.
            newPlayer.setDescended(origPlayer.getDescended());
            newPlayer.setLibrarySearched(origPlayer.getLibrarySearched());
            newPlayer.setSpellsCastLastTurn(origPlayer.getSpellsCastLastTurn());
            for (int j = 0; j < origPlayer.getSpellsCastThisTurn(); j++) {
                newPlayer.addSpellCastThisTurn();
            }
            newPlayer.setMaxHandSize(origPlayer.getMaxHandSize());
            newPlayer.setUnlimitedHandSize(origPlayer.isUnlimitedHandSize());
            newPlayer.setCrankCounter(origPlayer.getCrankCounter());
            // TODO creatureAttackedThisTurn
            for (Mana m : origPlayer.getManaPool()) {
                newPlayer.getManaPool().addManaNoEvent(m);
            }
            playerMap.put(origPlayer, newPlayer);
        }

        PhaseHandler origPhaseHandler = origGame.getPhaseHandler();
        Player newPlayerTurn = playerMap.get(origPhaseHandler.getPlayerTurn());
        newGame.getPhaseHandler().devModeSet(origPhaseHandler.getPhase(), newPlayerTurn, origPhaseHandler.getTurn());
        newGame.getTriggerHandler().suppressMode(TriggerType.ChangesZone);
        for (Player p : newGame.getPlayers()) {
            ((PlayerZoneBattlefield) p.getZone(ZoneType.Battlefield)).setTriggers(false);
        }

        copyGameState(newGame, aiPlayer);

        for (Player origPlayer : playerMap.keySet()) {
            Player newPlayer = playerMap.get(origPlayer);
            origPlayer.copyCommandersToSnapshot(newPlayer, gameObjectMap::map);
            origPlayer.copyEffectCardsToSnapshot(newPlayer, gameObjectMap::map);
            ((PlayerZoneBattlefield) newPlayer.getZone(ZoneType.Battlefield)).setTriggers(true);
        }
        newGame.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);

        for (Card c : newGame.getCardsInGame()) {
            Card origCard = (Card) reverseFind(c);
            if (origCard.hasRemembered() && !notFromTheirCard.contains(c)) {
                for (Object o : origCard.getRemembered()) {
                    if (o instanceof GameObject) {
                        // Sometimes, a spell can "remember" a token card that's not in any zone
                        // (and thus wouldn't have been copied) - for example Swords to Plowshares
                        // remembering its target for LKI. Skip these to not crash in find().
                        if (o instanceof Card && ((Card)o).getZone() == null) {
                           continue;
                        }
                        // The same for a card that still names a zone it is no longer in, so the copy holds no card
                        // with its id: a prepared spell's effect remembering the spell copy (Carnivorous Cultivator's
                        // Enroot) once that copy has left exile. There is nothing in the copy to remember.
                        if (o instanceof Card && !cardMap.containsKey(o)) {
                            continue;
                        }
                        // The same for an ability (a spell remembering itself or the ability that created it): the copy of
                        // the stack is only made below, so there is nothing to map it to yet.
                        if (o instanceof SpellAbility) {
                            continue;
                        }
                        c.addRemembered(find((GameObject) o));
                    } else {
                        System.err.println(c + " Remembered: " + o + "/" + o.getClass());
                        c.addRemembered(o);
                    }
                }
            }
            for (SpellAbility sa : c.getSpellAbilities()) {
                Player activatingPlayer = sa.getActivatingPlayer();
                if (activatingPlayer != null && activatingPlayer.getGame() != newGame) {
                    sa.setActivatingPlayer(gameObjectMap.map(activatingPlayer));
                }
            }
        }

        // Undo effects first before calculating them below, to avoid them applying twice.
        // The effects hold the card they came from as it was when the state was last checked, and GameAction checks
        // with the copies a zone change made ("TODO filter out old copies from zone change"): such a source is a
        // different object than the card in the zone, with the same id. Find it by id too, and skip an effect whose
        // card is no longer in the game at all, there is nothing to undo for it in the copy.
        final IEntityMap lenient = lenientMap(newGame);
        for (StaticEffect effect : origGame.getStaticEffects().getEffects()) {
            if (lenient.map(effect.getSource()) != null) {
                effect.removeMapped(lenient);
            }
        }

        if (origPhaseHandler.getCombat() != null) {
            newGame.getPhaseHandler().setCombat(new Combat(origPhaseHandler.getCombat(), gameObjectMap));
        }

        newGame.getAction().checkStateEffects(true); //ensure state based effects and triggers are updated
        newGame.getTriggerHandler().resetActiveTriggers();

        if (copyStack)
            copyStack(origGame, newGame, gameObjectMap);

        // TODO update thisTurnCast

        if (pruned != null) {
            newGame.REVEAL_LOG = notepadFor(newGame);
        }
        if (origGame.HIDDEN_READ_WATCH != null) {
            origGame.HIDDEN_READ_WATCH.copyChecked(leaks(newGame));
        }

        if (advanceToPhase != null) {
            newGame.getPhaseHandler().devAdvanceToPhase(advanceToPhase, () -> GameSimulator.resolveStack(newGame, aiPlayer.getWeakestOpponent()));
        }

        return newGame;
    }

    /** True if {@code viewer} may know what {@code c} is, where it is now: its own hand and library, or what the rules show it. */
    private static boolean knows(final Player viewer, final Card c) {
        if (viewer == null) {
            return !isHiddenPlace(c);
        }
        if (c.getOwner().equals(viewer) && (c.isInZone(ZoneType.Hand) || c.isInZone(ZoneType.Library))) {
            // story 10 lets the AI keep its own library order
            return true;
        }
        return c.getView().canBeShownTo(viewer.getView()) && c.getView().canFaceDownBeShownTo(viewer.getView());
    }

    /** A hand, a library, or a face-down card: where a card's identity can be hidden from a player. */
    private static boolean isHiddenPlace(final Card c) {
        return c.isFaceDown() || c.isInZone(ZoneType.Hand) || c.isInZone(ZoneType.Library);
    }

    /**
     * What the copy holds in place of each hidden card the viewer can't know. It is built from what the viewer knows and
     * never from the real card, so a copy says nothing about where a card has really gone.
     * <ul>
     * <li>A card the viewer may see right now (its own, or one the rules show it) stays as it is, where it is.</li>
     * <li>Another player's hand and library: the cards on the viewer's notepad last seen in that zone take its unseen
     * slots from the top, in notepad order, and the rest are placeholders. Zone sizes stay the same.</li>
     * <li>A face-down card elsewhere: the object is public, so it is the card the notepad wrote down for it, if the notepad
     * saw it face up in this same zone, and otherwise a placeholder.</li>
     * </ul>
     * A person can't follow a hidden move either: a card seen in hand and then put back on the library is still believed
     * to be in hand. With no viewer every hidden card is a placeholder.
     */
    private Map<Card, IPaperCard> planPrune() {
        final Map<Card, IPaperCard> plan = new IdentityHashMap<>();
        for (final Player owner : origGame.getPlayers()) {
            final Set<Integer> seenNow = new HashSet<>();
            for (final ZoneType zone : List.of(ZoneType.Hand, ZoneType.Library)) {
                for (final Card c : owner.getCardsIn(zone)) {
                    if (knows(viewer, c)) {
                        seenNow.add(c.getId());
                        if (!owner.equals(viewer)) {
                            seenHidden.add(c);
                        }
                    }
                }
            }
            for (final ZoneType zone : List.of(ZoneType.Hand, ZoneType.Library)) {
                final List<IPaperCard> believed = new ArrayList<>();
                if (viewer != null && !owner.equals(viewer)) {
                    for (final Card seen : HonestPlay.rememberedIn(viewer, owner, zone)) {
                        if (!seenNow.contains(seen.getId()) && seen.getPaperCard() != null) {
                            believed.add(seen.getPaperCard());
                        }
                    }
                }
                int next = 0;
                for (final Card c : owner.getCardsIn(zone)) {
                    if (!knows(viewer, c)) {
                        plan.put(c, next < believed.size() ? believed.get(next++) : null);
                    }
                }
            }
        }
        for (final ZoneType zone : ZONES) {
            if (zone == ZoneType.Hand || zone == ZoneType.Library) {
                continue;
            }
            for (final Card c : origGame.getCardsIn(zone)) {
                if (!c.isFaceDown()) {
                    continue;
                }
                if (knows(viewer, c)) {
                    seenHidden.add(c);
                    continue;
                }
                IPaperCard written = null;
                if (viewer != null) {
                    for (final Card seen : HonestPlay.rememberedIn(viewer, c.getOwner(), zone)) {
                        if (seen.getId() == c.getId()) {
                            written = seen.getPaperCard();
                        }
                    }
                }
                plan.put(c, written);
            }
        }
        return plan;
    }

    /**
     * The copy's own notepad: what the viewer knows of the hidden cards the copy holds, so that honest play on the copy
     * goes by the same cards as on the real game. Null when the real game keeps none.
     */
    private RevealLog notepadFor(final Game newGame) {
        if (origGame.REVEAL_LOG == null || viewer == null) {
            return null;
        }
        final RevealLog log = new RevealLog(newGame);
        final Player newViewer = playerMap.get(viewer);
        for (final Map.Entry<Card, IPaperCard> e : pruned.entrySet()) {
            if (e.getValue() != null) {
                log.revealed(newViewer, cardMap.get(e.getKey()));
            }
        }
        for (final Card c : seenHidden) {
            log.revealed(newViewer, cardMap.get(c));
        }
        return log;
    }

    /**
     * The tripwire's check on a copy: each hidden place in it that holds a card the viewer can't know, by the real game,
     * and that isn't on the viewer's notepad by name. A placeholder gives nothing away. This looks only at what the copy
     * ended up holding, so it catches a copy made with the prune off as well as a leak in the prune.
     */
    private Set<String> leaks(final Game newGame) {
        final Set<String> places = new TreeSet<>();
        final Set<String> written = new HashSet<>();
        if (viewer != null && origGame.REVEAL_LOG != null) {
            for (final RevealLog.Entry entry : origGame.REVEAL_LOG.writtenDown(viewer)) {
                written.add(entry.name());
            }
        }
        for (final Map.Entry<Card, Card> e : cardMap.entrySet()) {
            final Card orig = e.getKey();
            final Card copy = e.getValue();
            if (copy.getGame() != newGame || orig.getZone() == null || !isHiddenPlace(orig) || knows(viewer, orig)) {
                continue;
            }
            final String name = copy.getPaperCard() != null ? copy.getPaperCard().getName() : copy.getState(CardStateName.Original).getName();
            if (!HIDDEN_CARD_NAME.equals(name) && !written.contains(name)) {
                places.add((orig.isFaceDown() ? "face-down " : "") + orig.getZone().getZoneType());
            }
        }
        return places;
    }

    private void copyStack(Game origGame, Game newGame, IEntityMap map) {
        // The stack is listed from the top; copy it from the bottom up, because a spell that targets another spell
        // (a counterspell) can only be pointed at the copy of that spell once the copy exists.
        final List<SpellAbilityStackInstance> entries = new ArrayList<>();
        for (SpellAbilityStackInstance origEntry : origGame.getStack()) {
            entries.add(origEntry);
        }
        Collections.reverse(entries);
        for (SpellAbilityStackInstance origEntry : entries) {
            SpellAbility origSa = origEntry.getSpellAbility();
            Card origHostCard = origSa.getHostCard();
            Card newCard = map.map(origHostCard);
            SpellAbility newSa = null;
            if (origSa.isSpell()) {
                newSa = findSAInCard(origSa, newCard);
            }
            if (newSa != null) {
                newSa.setActivatingPlayer(map.map(origSa.getActivatingPlayer()));
                if (origSa.usesTargeting()) {
                    try {
                        for (GameObject o : origSa.getTargets()) {
                            newSa.getTargets().add(map.map(o));
                        }
                    } catch (final RuntimeException e) {
                        // a target that is not in the copy (it left the game meanwhile): leave this entry out rather
                        // than lose the whole copy
                        newSa.resetTargets();
                        continue;
                    }
                }
                newGame.getStack().add(newSa);
                stackMap.put(origSa, newSa);
            }
        } 
    }

    private RegisteredPlayer clonePlayer(RegisteredPlayer p) {
        RegisteredPlayer clone = new RegisteredPlayer(p.getDeck());
        LobbyPlayer lp = p.getPlayer();
        if (!(lp instanceof LobbyPlayerAi)) {
            // TODO should probably also override them if they're normal AI
            lp = new LobbyPlayerAi(p.getPlayer().getName(), Sets.newHashSet(AIOption.USE_FULL_SIMULATION));
        }
        clone.setPlayer(lp);
        return clone;
    }

    private void copyGameState(Game newGame, Player aiPlayer) {
        // Copied cards keep their original ids (id order is AI-visible via
        // Card.compareTo and id-keyed collections; renumbering makes forked
        // games deterministically diverge from the mainline). Sync the fresh-id
        // counters first so ids created during or after the copy cannot collide.
        newGame.dangerouslySyncCardIdCounters(origGame);
        newGame.EXPERIMENTAL_RESTORE_SNAPSHOT = origGame.EXPERIMENTAL_RESTORE_SNAPSHOT;
        newGame.AI_TIMEOUT = origGame.AI_TIMEOUT;
        newGame.AI_SIM_SEARCH = origGame.AI_SIM_SEARCH;
        newGame.AI_HONEST_PLAY = origGame.AI_HONEST_PLAY;
        newGame.AI_PRUNE_HIDDEN_INFO = origGame.AI_PRUNE_HIDDEN_INFO;
        newGame.setAge(origGame.getAge());

        // TODO countersAddedThisTurn

        if (origGame.getStartingPlayer() != null) {
            newGame.setStartingPlayer(playerMap.get(origGame.getStartingPlayer()));
        }
        if (origGame.getMonarch() != null) {
            newGame.setMonarch(playerMap.get(origGame.getMonarch()));
        }
        if (origGame.getMonarchBeginTurn() != null) {
            newGame.setMonarchBeginTurn(playerMap.get(origGame.getMonarchBeginTurn()));
        }
        if (origGame.getHasInitiative() != null) {
            newGame.setHasInitiative(playerMap.get(origGame.getHasInitiative()));
        }
        if (origGame.getDayTime() != null) {
            newGame.setDayTime(origGame.getDayTime());
        }

        for (ZoneType zone : ZONES) {
            for (Card card : origGame.getCardsIn(zone)) {
                addCard(newGame, zone, card, aiPlayer);
            }
            // TODO CardsAddedThisTurn is now messed up
        }
        gameObjectMap = new CopiedGameObjectMap(newGame);

        for (Card card : origGame.getCardsIn(ZoneType.Battlefield)) {
            Card otherCard = cardMap.get(card);
            otherCard.setGameTimestamp(card.getGameTimestamp());
            otherCard.setLayerTimestamp(card.getLayerTimestamp());
            otherCard.setSickness(card.hasSickness());
            otherCard.setState(card.getCurrentStateName(), false);
            if (card.isAttachedToEntity()) {
                GameEntity ge = gameObjectMap.map(card.getEntityAttachedTo());
                otherCard.setEntityAttachedTo(ge);
                ge.addAttachedCard(otherCard);
            }
            if (card.getCrewedByThisTurn() != null) {
                otherCard.setCrewedByThisTurn(card.getCrewedByThisTurn());
            }
            if (card.getCloneOrigin() != null) {
                otherCard.setCloneOrigin(cardMap.get(card.getCloneOrigin()));
            }
            if (card.getHaunting() != null) {
                otherCard.setHaunting(cardMap.get(card.getHaunting()));
            }
            if (card.getSaddledByThisTurn() != null) {
                otherCard.setSaddledByThisTurn(card.getSaddledByThisTurn());
            }
            if (card.isPaired()) {
                otherCard.setPairedWith(cardMap.get(card.getPairedWith()));
            }
            if (card.getCopiedPermanent() != null && !notFromTheirCard.contains(otherCard)) {
                // TODO would it be safe to simply reuse the prototype?
                otherCard.setCopiedPermanent(new CardCopyService(card.getCopiedPermanent()).copyCard(false));
            }
            // TODO: Verify that the above relationships are preserved bi-directionally or not.
        }

        for (Card card : origGame.getCardsIn(ZoneType.Command)) {
            Card otherCard = cardMap.get(card);
            if (card.getEffectSource() != null) {
                // this matters for correct MayPlay matching
                otherCard.setEffectSource(cardMap.get(card.getEffectSource()));
            }
        }

        copyLeftThisTurn(newGame, aiPlayer);
    }

    /**
     * What left the battlefield and the graveyard this turn: revolt (Fatal Push, Narnam Renegade), morbid and the like
     * read it. Without it a play that depends on it is judged in a game where nothing has happened this turn.
     */
    private void copyLeftThisTurn(Game newGame, Player aiPlayer) {
        for (int pass = 0; pass < 2; pass++) {
            final List<Card> origList = pass == 0 ? origGame.getLeftBattlefieldThisTurn() : origGame.getLeftGraveyardThisTurn();
            for (Card lki : new ArrayList<>(origList)) {
                try {
                    final Card now = origGame.findById(lki.getId());
                    Card base = now == null ? null : cardMap.get(now);
                    if (base == null || notFromTheirCard.contains(base)) {
                        // a token (it ceased to exist), or a card that has since gone where the copy hides it: what
                        // left was public, so rebuild it from the last known information
                        final Player owner = playerMap.get(lki.getOwner());
                        if (owner == null) {
                            continue;
                        }
                        base = createCardCopy(newGame, owner, lki, aiPlayer);
                    }
                    final Card copy = CardCopyService.getLKICopy(base);
                    final Player controller = lki.getController() == null ? null : playerMap.get(lki.getController());
                    if (controller != null && !controller.equals(copy.getController())) {
                        copy.setController(controller, copy.getGameTimestamp());
                    }
                    if (pass == 0) {
                        newGame.addLeftBattlefieldThisTurn(copy);
                    } else {
                        newGame.addLeftGraveyardThisTurn(copy);
                    }
                } catch (final RuntimeException e) {
                    // history is a refinement: a card that cannot be rebuilt is left out
                }
            }
        }
    }

    /** The name of the placeholder a pruned copy holds for a card its viewer can't know. */
    public static final String HIDDEN_CARD_NAME = "hidden";
    private static final PaperCard hidden_info_card = new PaperCard(CardRules.fromScript(Lists.newArrayList("Name:" + HIDDEN_CARD_NAME, "Types:Artifact", "Oracle:")), "", CardRarity.Common);
    /** False only in tests, to run the copy path that builds a card from its current state rather than its paper card. */
    static boolean useFromPaperCard = true;
    private Card createCardCopy(Game newGame, Player newOwner, Card c, Player aiPlayer) {
        if (pruned != null && pruned.containsKey(c)) {
            // decided before any copy path, so that every path prunes the same way
            final IPaperCard believed = pruned.get(c);
            final Card newCard = CardFactory.getCard(believed == null ? hidden_info_card : believed, newOwner, c.getId(), newGame);
            notFromTheirCard.add(newCard);
            return newCard;
        }
        if (c.isToken() && !c.isImmutable()) {
            // the SVars go in first: a keyword such as Chapter looks up its abilities on the card as it is added, and
            // a token copy of a Saga (Fable of the Mirror-Breaker) has no script to supply them
            Card result = new TokenInfo(c).makeOneToken(newOwner, c.getId(), c.getSVars());
            new CardCopyService(c).copyCopiableCharacteristics(result, null, null);
            // a token copy of a transforming (or otherwise multi-faced) permanent has its other faces too, and may be
            // showing one of them: without them it cannot be put back in the state it is in
            for (final CardStateName state : c.getStates()) {
                if (!result.hasState(state)) {
                    result.addAlternateState(state, false);
                    result.getState(state).copyFrom(c.getState(state), false);
                }
            }
            result.setBackSide(c.isBackSide());
            return result;
        }
        if (useFromPaperCard && !c.isImmutable() && c.getPaperCard() != null) {
            Card newCard = CardFactory.getCard(c.getPaperCard(), newOwner, c.getId(), newGame);
            newCard.setCommander(c.isCommander());
            return newCard;
        }

        // TODO: The above is very expensive and accounts for the vast majority of GameCopier execution time.
        // The issue is that it requires parsing the original card from scratch from the paper card. We should
        // improve the copier to accurately copy the card from its actual state, so that the paper card shouldn't
        // be needed. Once the below code accurately copies the card, remove the USE_FROM_PAPER_CARD code path.
        Card newCard;
        if (c instanceof DetachedCardEffect)
            newCard = new DetachedCardEffect((DetachedCardEffect) c, newGame, false);
        else
            newCard = new Card(c.getId(), c.getPaperCard(), newGame);
        newCard.setGamePieceType(c.getGamePieceType());
        newCard.setOwner(newOwner);
        newCard.setName(c.getName());
        newCard.setCommander(c.isCommander());
        newCard.setType(new CardType(c.getType()));
        for (StaticAbility stAb : c.getStaticAbilities()) {
            newCard.addStaticAbility(stAb.copy(newCard, true));
        }
        for (SpellAbility sa : c.getSpellAbilities()) {
            SpellAbility saCopy = sa.copy(newCard, true);
            if (saCopy != null) {
                newCard.addSpellAbility(saCopy);
            } else {
                System.err.println(sa.toString());
            }
        }

        return newCard;
    }

    private void addCard(Game newGame, ZoneType zone, Card c, Player aiPlayer) {
        final Player owner = playerMap.get(c.getOwner());
        final Card newCard = createCardCopy(newGame, owner, c, aiPlayer);
        cardMap.put(c, newCard);

        // TODO ExiledWith

        Player zoneOwner = owner;
        // everything the CreatureEvaluator checks must be set here
        if (zone == ZoneType.Battlefield) {
            // TODO: Controllers' list with timestamps should be copied.
            zoneOwner = playerMap.get(c.getController());
            newCard.setController(zoneOwner, 0);

            if (c.isBattle()) {
                newCard.setProtectingPlayer(playerMap.get(c.getProtectingPlayer()));
            }

            newCard.setCameUnderControlSinceLastUpkeep(c.cameUnderControlSinceLastUpkeep());

            newCard.setPTTable(c.getSetPTTable());
            newCard.setPTCharacterDefiningTable(c.getSetPTCharacterDefiningTable());

            newCard.setPTBoost(c.getPTBoostTable());
            // TODO copy by map
            newCard.setDamage(c.getDamage());
            newCard.setDamageReceivedThisTurn(c.getDamageReceivedThisTurn());

            newCard.copyFrom(c);
            newCard.updateKeywordsCache();

            if (c.isTapped()) {
                newCard.setTapped(true);
            }
            if (c.isFaceDown()) {
                newCard.turnFaceDown(true);
                if (c.isManifested()) {
                    newCard.setManifested(c.getManifestedSA());
                }
                if (c.isCloaked()) {
                    newCard.setCloaked(c.getCloakedSA());
                }
            }
            if (c.isMonstrous()) {
                newCard.setMonstrous(true);
            }
            if (c.isRenowned()) {
                newCard.setRenowned(true);
            }
            if (c.isSolved()) {
                newCard.setSolved(true);
            }
            if (c.isSaddled()) {
                newCard.setSaddled(true);
            }
            if (c.isSuspected()) {
                newCard.setSuspected(true);
            }
            if (c.isPlaneswalker()) {
                for (SpellAbility sa : c.getAllSpellAbilities()) {
                    int active = sa.getActivationsThisTurn();
                    if (sa.isPwAbility() && active > 0) {
                        SpellAbility newSa = findSAInCard(sa, newCard);
                        if (newSa != null) {
                            for (int i = 0; i < active; i++) {
                                newCard.addAbilityActivated(newSa);
                            }
                        }
                    }
                }
            }

            newCard.setFlipped(c.isFlipped());
            // a pruned face-down card keeps nothing that belongs to the card it hides: no clone states, no SVars
            final boolean ownCard = !notFromTheirCard.contains(newCard);
            for (Map.Entry<Long, CardCloneStates> e : ownCard ? c.getCloneStates().entrySet() : List.<Map.Entry<Long, CardCloneStates>>of()) {
                newCard.addCloneState(e.getValue().copy(newCard, true), e.getKey());
            }

            Multiset<CounterType> counters = c.getCounters();
            if (!counters.isEmpty()) {
                newCard.setCounters(HashMultiset.create(counters));
            }
            if (c.hasChosenPlayer()) {
                newCard.setChosenPlayer(playerMap.get(c.getChosenPlayer()));
            }
            if (c.hasChosenType()) {
                newCard.setChosenType(c.getChosenType());
            }
            if (c.hasChosenType2()) {
                newCard.setChosenType2(c.getChosenType2());
            }
            if (c.hasChosenColor()) {
                newCard.setChosenColors(Lists.newArrayList(c.getChosenColors()));
            }
            if (c.hasNamedCard()) {
                newCard.setNamedCards(Lists.newArrayList(c.getNamedCards()));
            }

            newCard.setSprocket(c.getSprocket());

            if (ownCard) {
                newCard.setSVars(c.getSVars());
                newCard.copyChangedSVarsFrom(c);
            }
        }

        if (zone == ZoneType.Exile && origGame.AI_SIM_SEARCH && c.hasSuspend()) {
            // Only cards on the battlefield keep their counters otherwise, and GameStateEvaluator scores this card by them.
            newCard.setCounters(HashMultiset.create(c.getCounters()));
        }

        if (zone == ZoneType.Exile && c.isFaceDown()) {
            // Face-down exile state (foretell, "exile face down" effects) must
            // survive the copy: cards rebuilt from their paper card default to
            // face up, leaking hidden information into the copied game.
            newCard.turnFaceDownNoUpdate();
            if (c.isForetold()) {
                newCard.setForetold(true);
                if (c.isForetoldCostByEffect()) {
                    newCard.setForetoldCostByEffect(true);
                }
            }
        }

        if (zone == ZoneType.Stack) {
            newGame.getStackZone().add(newCard);
        } else {
            zoneOwner.getZone(zone).add(newCard);
        }
    }

    private static SpellAbility findSAInCard(SpellAbility sa, Card c) {
        String saDesc = sa.getDescription();
        for (SpellAbility cardSa : c.getAllSpellAbilities()) {
            if (saDesc.equals(cardSa.getDescription())) {
                return cardSa;
            }
        }
        return null;
    }

    private class CopiedGameObjectMap implements IEntityMap {
        private final Game copiedGame;

        public CopiedGameObjectMap(Game copiedGame) {
            this.copiedGame = copiedGame;
        }

        @Override
        public Game getGame() {
            return copiedGame;
        }

        @Override
        public GameObject map(GameObject o) {
            return find(o);
        }
    }

    /**
     * Maps what a static effect refers to, as far as the copy has it: a card is looked up by identity, then by id (a
     * card that changed zones is a new object with the same id), and what is not there is left out instead of throwing.
     * Only for undoing effects; {@link #find} stays strict for everything that must exist in the copy.
     */
    private IEntityMap lenientMap(final Game newGame) {
        final Map<Integer, Card> byId = new HashMap<>();
        for (final Card c : newGame.getCardsInGame()) {
            byId.put(c.getId(), c);
        }
        return new IEntityMap() {
            @Override
            public Game getGame() {
                return newGame;
            }

            @Override
            public GameObject map(final GameObject o) {
                if (o instanceof Card) {
                    final Card found = cardMap.get(o);
                    return found != null ? found : byId.get(((Card) o).getId());
                }
                if (o instanceof Player) {
                    return playerMap.get(o);
                }
                return null;
            }

            @Override
            public CardCollection mapCollection(final CardCollectionView cards) {
                final CardCollection out = new CardCollection();
                for (final Card c : cards) {
                    final Card m = map(c);
                    if (m != null) {
                        out.add(m);
                    }
                }
                return out;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T extends GameObject> List<T> mapList(final List<T> objects) {
                final List<T> out = new ArrayList<>();
                for (final T o : objects) {
                    final GameObject m = map(o);
                    if (m != null) {
                        out.add((T) m);
                    }
                }
                return out;
            }
        };
    }

    public <T extends GameObject> T find(T o) {
        if (snapshot != null) {
            return (T) snapshot.find(o);
        }

        T result = null;
        if (o instanceof Card) {
            result = (T) cardMap.get(o);
            if (result != null) {
                return result;
            } else {
                System.out.println("Couldn't map " + o + "/" + System.identityHashCode(o));
            }
        } else if (o instanceof Player) {
            result = (T) playerMap.get(o);
            if (result != null)
                return result;
        } else if (o instanceof SpellAbility) {
            // a spell on the stack (the target of a counterspell), only known when the stack was copied
            result = (T) stackMap.get(o);
            if (result != null)
                return result;
        }
        if (o != null)
            throw new RuntimeException("Couldn't map " + o + "/" + System.identityHashCode(o));
        return result;
    }
    public GameObject reverseFind(GameObject o) {
        if (snapshot != null) {
            return snapshot.reverseFind(o);
        }

        GameObject result = cardMap.inverse().get(o);
        if (result != null)
            return result;
        // TODO: Have only one GameObject map?
        return playerMap.inverse().get(o);
    }
}
