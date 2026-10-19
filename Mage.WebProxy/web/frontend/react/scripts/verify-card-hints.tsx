/**
 * Card hints helper — standalone behavioural checks.
 *
 * Run with:  bash scripts/verify-card-hints.sh
 *
 * Drives the REAL modules: the parser/collector/groupers in `utils/cardHints.ts`
 * against synthetic game states whose rules strings mirror what the ENGINE puts on
 * the wire (`<br/><hintstart/>` as its own rules entry, hint lines after it), and
 * the REAL modal component (`components/CardHintsModal.tsx`) plus the REAL header
 * (GameHeader) through react-dom/server with a real store-shaped state.
 *
 * The wiring layer (the header button's onClick, the App mount, the CSS import,
 * the launch path) cannot be asserted from SSR markup — it is grep-checked in the
 * .sh wrapper, the same split verify-emblem-badge-clickable.sh uses.
 *
 * What is pinned here:
 *  (a) MARKER PARSING edge cases:
 *      - a card with no marker is NOT a hint (never collected, never rendered),
 *      - a marker in the MIDDLE of the rules list: only the entries after it are
 *        hints (entries before it are rules text, never hints),
 *      - MULTIPLE hint lines after one marker all become hints, in order,
 *      - a marker as the LAST entry yields an empty hint list (still a "hint
 *        card" for the desktop's removeIf — asserted so the semantics stay the
 *        desktop's, not an accident of this port),
 *      - a marker STRING inside a larger rule line does NOT start hints (the
 *        engine pushes the marker as its own entry; the desktop compares whole
 *        entries — `rule.equals(HINT_START_MARK)`).
 *  (b) COLLECTION scope: hand (own seat only), stack, battlefield/command/
 *      graveyard/exile per player; attribution (player + zone) exact; the
 *      helper-emblem "xmage" zone is absent from the wire and absent here.
 *  (c) FILTER: the player dropdown offers ALL + seats that own hint cards; the
 *      player filter narrows to one seat.
 *  (d) SEARCH: matching on player name / card name / card id / zone / hint text,
 *      multi-word OR semantics (the desktop splits the query on spaces and
 *      matches ANY word), no-match handling.
 *  (e) GROUPING: group-by-hints unique+sorted with "and N other" at
 *      MAX_CARD_IDS_PER_HINT=3; group-by-cards player+zone groups with
 *      name-sorted cards.
 *  (f) MODAL rendering (SSR): the empty state (the desktop's own text), the
 *      populated group-by view, the controls and Close; the header's Hints button
 *      present for a player and absent for a spectator (desktop `isMe` rule).
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement } from 'react'
import {
  HINT_START_MARK,
  MAX_CARD_IDS_PER_HINT,
  EMPTY_HINTS_WARNING,
  FILTER_ALL,
  parseCardHints,
  cardHasHints,
  collectCardHints,
  filterCardHints,
  playerFilterChoices,
  groupByHints,
  groupByCards,
  hintCardListLabel,
} from '../src/utils/cardHints'
import {
  openCardHints,
  closeCardHints,
  getCardHintsOpen,
  subscribeCardHints,
} from '../src/store/slices/cardHints'
import { useGameStore } from '../src/store/gameStore'
import CardHintsModal from '../src/components/CardHintsModal'
import { GameHeader } from '../src/components/GameHeader'
import { EXPLAIN } from '../src/hints/explain'
import type { GameState, Player } from '../src/types'

// react-dom/server reads zustand's getServerSnapshot (the INITIAL state). Mirror
// every setState into it so server renders see the scenario (same lever as
// verify-seat-names.tsx).
const ssrSnapshot = (useGameStore as unknown as { getInitialState: () => Record<string, unknown> }).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ── fixtures ─────────────────────────────────────────────────────────────────

/** A card shaped like the wire (`Permanent` fields included so it fits boards). */
function card(name: string, id: string, rules: string[] = []) {
  return { id, name, imageUrl: '', typeLine: 'Creature', manaCost: '', rules, tapped: false, damage: 0 }
}
function plainCard(name: string, id: string, rules: string[] = ['Does a thing.']) {
  return { id, name, imageUrl: '', typeLine: 'Land', manaCost: '', rules, tapped: false, damage: 0 }
}
function player(name: string, isMe: boolean, over: Partial<Player> = {}): Player {
  return {
    name, playerId: `pid-${name}`, life: 20, isMe, isActive: isMe, hasPriority: isMe,
    hasLeft: false, timerActive: false, priorityTimeLeftSecs: 0, isMonarch: false,
    hasInitiative: false, passedTurn: false, passedUntilEndOfTurn: false,
    passedUntilNextMain: false, passedAllTurns: false, passedUntilStackResolved: false,
    passedUntilEndStepBeforeMyTurn: false, manaPool: null, counters: [], commanders: [],
    libraryCount: 40, handCount: 0, battlefield: [], hand: [], graveyard: [], exile: [],
    ...over,
  } as Player
}

const ME = 'HintsQA'
const OPP = 'HintsOpp'

function state(over: Partial<GameState>): GameState {
  return {
    status: 'playing', turn: 3, phase: null, step: null,
    activePlayer: ME, priorityPlayer: ME,
    stack: [], combat: [], players: [], llmDebug: [], revealed: [], lookedAt: [], stateSeq: 1,
    ...over,
  } as GameState
}

/**
 * The scenario board — hint cards in every scanned zone, one hint-free card, and
 * one hint shared by two cards.
 *
 * Expected collection (7 entries, in scan order):
 *   HintsQA|hand|Gaea's Cradle                    1 hint
 *   HintsQA|stack|Shock                           1 hint
 *   HintsQA|battlefield|Krenko, Mob Boss          1 hint
 *   HintsQA|battlefield|Boseiju, Who Endures      2 hints (incl. the shared one)
 *   HintsQA|graveyard|Fatal Push                  1 hint
 *   HintsQA|exile|Otawara, Soaring City           1 hint
 *   HintsOpp|battlefield|Creatures you control    1 hint
 */
function board(): GameState {
  const me = player(ME, true, {
    hand: [
      card("Gaea's Cradle", 'aaaaaaaa-1111', ['{T}: Add {G} for each creature you control.', HINT_START_MARK, 'Creatures you control: 4']),
      plainCard('Plains', 'bbbbbbbb-2222'),
    ],
    battlefield: [
      card('Krenko, Mob Boss', 'cccccccc-3333', ['{T}: Create X 1/1 red Goblin tokens.', HINT_START_MARK, 'Goblins you control: 7']),
      card('Boseiju, Who Endures', 'dddddddd-4444', ['Channel - {1}{G}: destroy.', HINT_START_MARK, 'Creatures you control: 4', "Can't be countered"]),
    ],
    graveyard: [card('Fatal Push', 'eeeeeeee-5555', ['Destroy target creature.', HINT_START_MARK, 'Revolt is on'])],
    exile: [card('Otawara, Soaring City', 'ffffffff-6666', ['Channel.', HINT_START_MARK, 'Legendary creatures you control: 2'])],
  })
  const opp = player(OPP, false, {
    battlefield: [card('Creatures you control', 'abcdabcd-7777', ['Static text.', HINT_START_MARK, 'Creatures you control: 3'])],
  })
  return state({
    players: [me, opp],
    stack: [card('Shock', '99999999-8888', ['Deal 2 damage.', HINT_START_MARK, "Target's toughness: 3"])],
  })
}

// ─────────────────────────────────────────────────────────────────────────────
section('parseCardHints - marker semantics (desktop-exact)')

check('no rules at all -> not a hint card', parseCardHints([]) === null && parseCardHints(undefined) === null)
check('rules with no marker -> not a hint card', parseCardHints(['Deal 2 damage.', 'Draw a card.']) === null)
check('marker as its own entry -> the lines after it are hints',
  JSON.stringify(parseCardHints(['Rules text.', HINT_START_MARK, 'Hint one', 'Hint two'])) === JSON.stringify(['Hint one', 'Hint two']),
  JSON.stringify(parseCardHints(['Rules text.', HINT_START_MARK, 'Hint one', 'Hint two'])))
check('multiple hint lines keep their order',
  JSON.stringify(parseCardHints([HINT_START_MARK, 'b', 'a', 'c'])) === JSON.stringify(['b', 'a', 'c']))
check('mid-rules marker: entries BEFORE it are never hints',
  JSON.stringify(parseCardHints(['A rules line', 'Another rules line', HINT_START_MARK, 'Only hint'])) === JSON.stringify(['Only hint']))
check('marker as the LAST entry -> empty hint list (still a hint card, desktop removeIf semantics)',
  JSON.stringify(parseCardHints(['Rules.', HINT_START_MARK])) === JSON.stringify([]))
check('the marker STRING inside a larger line does NOT start hints (desktop compares whole entries)',
  parseCardHints([`Rules text with ${HINT_START_MARK} embedded`, 'Looks like a hint']) === null)
check('two markers -> every marker entry is skipped, all other lines after the first are hints (desktop `continue`s on each marker)',
  JSON.stringify(parseCardHints([HINT_START_MARK, 'h1', HINT_START_MARK, 'h2'])) === JSON.stringify(['h1', 'h2']))
check('cardHasHints mirrors parseCardHints',
  cardHasHints({ rules: [HINT_START_MARK, 'h'] }) && !cardHasHints({ rules: ['no marker'] }))
check('an empty hint line survives (the engine can emit one)',
  JSON.stringify(parseCardHints([HINT_START_MARK, ''])) === JSON.stringify(['']))

// ─────────────────────────────────────────────────────────────────────────────
section('collectCardHints - zones + attribution')

const collected = collectCardHints(board())
const names = collected.map((h) => `${h.player}|${h.zone}|${h.card.name}`)
check('exactly the 7 hint cards collected, scan order preserved',
  collected.length === 7
  && names[0] === `${ME}|hand|Gaea's Cradle`
  && names[1] === `${ME}|stack|Shock`
  && names[6] === `${OPP}|battlefield|Creatures you control`,
  String(collected.length) + ' :: ' + names.join(' ; '))
check('own hand scanned', names.includes(`${ME}|hand|Gaea's Cradle`), names.join(' ; '))
check('battlefield scanned for both seats',
  names.includes(`${ME}|battlefield|Krenko, Mob Boss`) && names.includes(`${OPP}|battlefield|Creatures you control`))
check('graveyard scanned', names.includes(`${ME}|graveyard|Fatal Push`))
check('exile scanned', names.includes(`${ME}|exile|Otawara, Soaring City`))
check('stack scanned', collected.some((h) => h.zone === 'stack' && h.card.name === 'Shock'))
check('a hint-free card is never collected', !collected.some((h) => h.card.name === 'Plains'))
check('no "xmage" zone (helper emblems are not on the web wire)', !collected.some((h) => h.zone === 'xmage'))
check('hint lines ride the entry', collected.find((h) => h.card.name === 'Boseiju, Who Endures')?.hints.length === 2)
check('null state -> empty list (not a crash)', collectCardHints(null).length === 0)
check('command zone scanned (CardView commanders)', (() => {
  const s = board()
  s.players[0]!.commanders = [card('Commander Scanned', 'cdcdcdcd-0000', ['Rules.', HINT_START_MARK, 'Commander hint'])]
  const out = collectCardHints(s)
  return out.some((h) => h.zone === 'command' && h.card.name === 'Commander Scanned')
})())
check('a watcher board (no isMe seat) still scans the shared + per-player zones', (() => {
  const s = board()
  for (const p of s.players) { (p as unknown as { isMe: boolean }).isMe = false; p.hand = [] }
  const out = collectCardHints(s)
  const stack = out.find((h) => h.zone === 'stack')
  return out.length === 6 && stack?.player === '' && out.some((h) => h.player === OPP)
})())

// ─────────────────────────────────────────────────────────────────────────────
section('filter - player dropdown + player filter')

const choices = playerFilterChoices(board())
check('ALL is first', choices[0] === FILTER_ALL)
check('own seat offered', choices.includes(ME))
check('opponent seat offered', choices.includes(OPP))
check('all seats listed unconditionally (desktop setGameData), no duplicates',
  choices.length === 3, choices.join(','))
const onlyMe = filterCardHints(collected, ME, '')
check('player filter narrows to that seat', onlyMe.length === 6 && onlyMe.every((h) => h.player === ME),
  String(onlyMe.length))
const onlyOpp = filterCardHints(collected, OPP, '')
check('the opponent filter shows only their card',
  onlyOpp.length === 1 && onlyOpp[0]!.player === OPP, JSON.stringify(onlyOpp.map((h) => h.card.name)))
check('ALL keeps everything', filterCardHints(collected, FILTER_ALL, '').length === collected.length)

// ─────────────────────────────────────────────────────────────────────────────
section('search - desktop semantics (any word, player/card/id/zone/hint)')

const byPlayer = filterCardHints(collected, FILTER_ALL, ME)
check('search matches the PLAYER name', byPlayer.length === 6 && byPlayer.every((h) => h.player === ME),
  String(byPlayer.length))
const byCard = filterCardHints(collected, FILTER_ALL, 'krenko')
check('search matches the CARD name, case-insensitively',
  byCard.length === 1 && byCard[0]!.card.name === 'Krenko, Mob Boss')
const byId = filterCardHints(collected, FILTER_ALL, 'cccccccc')
check('search matches the card ID', byId.length === 1 && byId[0]!.card.name === 'Krenko, Mob Boss')
const byHint = filterCardHints(collected, FILTER_ALL, 'goblins')
check('search matches the HINT text',
  byHint.length === 1 && byHint[0]!.card.name === 'Krenko, Mob Boss', JSON.stringify(byHint.map((h) => h.card.name)))
const byWord = filterCardHints(collected, FILTER_ALL, 'soaring city')
check('multi-word query is ANY-word (the desktop splits on spaces)',
  byWord.some((h) => h.card.name === 'Otawara, Soaring City'), JSON.stringify(byWord.map((h) => h.card.name)))
const byZone = filterCardHints(collected, FILTER_ALL, 'graveyard')
check('search matches the ZONE label (base = player@zone@name)',
  byZone.length === 1 && byZone[0]!.zone === 'graveyard', JSON.stringify(byZone.map((h) => h.zone)))
check('no match -> empty list (the modal then shows the desktop empty text)',
  filterCardHints(collected, FILTER_ALL, 'zzzznothing').length === 0)
check('search combines with the player filter',
  filterCardHints(collected, OPP, 'creatures').length === 1            // card name match
  && filterCardHints(collected, ME, 'creatures').length === 3,         // Gaea's Cradle + Boseiju + Otawara hints
  String(filterCardHints(collected, ME, 'creatures').length))

// ─────────────────────────────────────────────────────────────────────────────
section('grouping - hints vs cards')

const hintGroups = groupByHints(filterCardHints(collected, FILTER_ALL, ''))
check('group-by-hints is unique + sorted',
  hintGroups.length === new Set(hintGroups.map((g) => g.hint)).size
  && JSON.stringify(hintGroups.map((g) => g.hint)) === JSON.stringify(hintGroups.map((g) => g.hint).slice().sort()),
  JSON.stringify(hintGroups.map((g) => g.hint)))
check('7 unique hint lines across the board', hintGroups.length === 7, String(hintGroups.length))
const cyc = hintGroups.find((g) => g.hint === 'Creatures you control: 4')
check('a shared hint groups its cards', cyc != null && cyc.cards.length === 2,
  JSON.stringify(cyc?.cards.map((c) => c.card.name)))
check('cards inside a group are id-sorted (the desktop sorts by id)',
  (() => { const ids = cyc?.cards.map((c) => c.card.id ?? '') ?? []; return JSON.stringify(ids) === JSON.stringify(ids.slice().sort()) })())
const listLabel = hintCardListLabel([...Array(5).keys()].map((i) => ({
  player: 'p', zone: 'battlefield', hints: ['x'], card: card(`C${i}`, `0000000${i}-abcd`),
})))
check('MAX_CARD_IDS_PER_HINT = 3 card codes then "and N other"',
  MAX_CARD_IDS_PER_HINT === 3 && listLabel === '[000], [000], [000] and 2 other', listLabel)
check('fewer than the cap -> no "and N other"',
  hintCardListLabel([{ player: 'p', zone: 'battlefield', hints: ['x'], card: card('Only', '11112222') }]) === '[111]')

const cardGroups = groupByCards(filterCardHints(collected, FILTER_ALL, ''))
check('group-by-cards: player+zone groups in scan order',
  cardGroups.map((g) => `${g.player}-${g.zone}`).join(' ; ')
  === `${ME}-hand ; ${ME}-stack ; ${ME}-battlefield ; ${ME}-graveyard ; ${ME}-exile ; ${OPP}-battlefield`,
  cardGroups.map((g) => `${g.player}-${g.zone}`).join(' ; '))
check('group-by-cards: my battlefield group holds both cards, name-sorted',
  cardGroups.find((g) => g.player === ME && g.zone === 'battlefield')?.cards.map((c) => c.card.name).join('|')
  === 'Boseiju, Who Endures|Krenko, Mob Boss',
  cardGroups.find((g) => g.player === ME && g.zone === 'battlefield')?.cards.map((c) => c.card.name).join('|'))

// ─────────────────────────────────────────────────────────────────────────────
section('modal store - open/close')

check('modal store starts closed', getCardHintsOpen() === false)
let storeCalls = 0
const unsub = subscribeCardHints(() => { storeCalls++ })
openCardHints()
check('openCardHints flips the store + notifies', getCardHintsOpen() === true && storeCalls === 1)
openCardHints()
check('opening twice is a no-op (no repeat notify)', storeCalls === 1)
closeCardHints()
check('closeCardHints flips back + notifies', getCardHintsOpen() === false && storeCalls === 2)
closeCardHints()
check('closing twice is a no-op', storeCalls === 2)
unsub()

// ─────────────────────────────────────────────────────────────────────────────
section('modal SSR - empty state, populated views, controls')

const render = (el: unknown) => renderToStaticMarkup(el as never)

setLive({ gameState: board(), inReplay: false, replayState: null })
check('closed modal renders nothing', render(createElement(CardHintsModal)) === '')

// empty state: a board whose cards carry no hints anywhere
setLive({ gameState: state({ players: [player(ME, true, { battlefield: [plainCard('Bear', 'x1', ['Just a bear.'])] }), player(OPP, false)] }) })
openCardHints()
const emptyHtml = render(createElement(CardHintsModal))
check('empty board -> the desktop\u2019s explicit warning, not a blank panel',
  emptyHtml.includes(EMPTY_HINTS_WARNING), emptyHtml.slice(0, 400))
check('empty board -> body reports 0 cards', emptyHtml.includes('data-hint-count="0"'))
check('empty board -> controls still rendered (filter/group/search)',
  emptyHtml.includes('card-hints-filter') && emptyHtml.includes('card-hints-group') && emptyHtml.includes('card-hints-search'))
check('empty board -> title shows the default group', emptyHtml.includes('Card hints helper [Hints]'))

// populated board: default view = group by hints, ALL players
setLive({ gameState: board(), replayState: null })
const fullHtml = render(createElement(CardHintsModal))
check('populated board -> 7 cards reported',
  fullHtml.includes('data-hint-count="7"'), /data-hint-count="\d+"/.exec(fullHtml)?.[0] ?? '')
check('default group = Hints, in the dynamic title (desktop title format)',
  fullHtml.includes('Card hints helper [Hints]'), /Card hints helper \[[^\]]*\]/.exec(fullHtml)?.[0] ?? '')
check('seat headers rendered',
  fullHtml.includes(`data-hints-seat="${ME}"`) && fullHtml.includes(`data-hints-seat="${OPP}"`))
check('watcher label never appears for a real seat', !fullHtml.includes('>watcher:'))
check('a hint line renders with its text', fullHtml.includes('Creatures you control: 4'))
check('hint rows carry the hint text for harness assertions',
  fullHtml.includes('data-hint-text="Goblins you control: 7"'), fullHtml.slice(0, 300))
check('card codes are click targets showing the 3-char id prefix',
  fullHtml.includes('card-hints-card-code') && fullHtml.includes('>[ccc]<'),
  fullHtml.slice(fullHtml.indexOf('card-hints-card-code'), fullHtml.indexOf('card-hints-card-code') + 200))
check('no "and N other" when no hint is shared by >3 cards',
  !fullHtml.includes(' and 1 other') && !fullHtml.includes(' and 2 other'))
check('modal has its own CSS class (styles travel with the component)', fullHtml.includes('card-hints-modal'))
check('Close button is present + explained', fullHtml.includes('data-explain="card-hints-close"'))
check('the desktop empty text is NOT shown when hints exist', !fullHtml.includes(EMPTY_HINTS_WARNING))

// icon tokens: the engine prefixes hints with ICON_GOOD/ICON_BAD (HintUtils);
// the desktop maps them to images (ManaSymbols.replaceSymbolsWithHTML). The web
// maps them to glyphs and must NEVER leak the raw token (live run regression:
// "ICON_BADA permanent left the battlefield..." rendered as literal text).
setLive({ gameState: state({ players: [player(ME, true, {
  hand: [card('Fatal Push', 'fafa1111-0001', ['Destroy target creature.', HINT_START_MARK,
    'ICON_BADA permanent left the battlefield under your control this turn']),
    card('Regal Force', 'fafa2222-0002', ['Draw cards.', HINT_START_MARK,
      'ICON_GOODYou have the city\u2019s blessing', 'ICON_REQUIREmust attack', 'ICON_RESTRICTblocks restricted'])],
}), player(OPP, false)] }) })
const iconHtml = render(createElement(CardHintsModal))
// The raw token stays ONLY in the data-hint-text attribute (harness anchor); the
// RENDERED text (the spans ManaText produces) must carry the glyph instead.
const iconRendered = iconHtml.replace(/data-hint-text="[^"]*"/g, '')
check('ICON_BAD maps to a glyph in the rendered text, never leaks as literal',
  !iconRendered.includes('ICON_BAD')
  && iconHtml.includes('data-hint-text="ICON_BADA permanent left')
  && iconRendered.includes('\u2718A permanent left'), iconRendered.slice(0, 300))
check('ICON_GOOD / ICON_REQUIRE / ICON_RESTRICT all map to glyphs',
  !iconRendered.includes('ICON_GOOD') && !iconRendered.includes('ICON_REQUIRE') && !iconRendered.includes('ICON_RESTRICT')
  && iconRendered.includes('\u2714') && iconRendered.includes('\u2757') && iconRendered.includes('\u26D4'))

setLive({ gameState: board(), replayState: null })

console.log('\n## explain ids + header launch point (SSR-visible parts)')

check('header Hints button has an explain id in the registry', 'header-card-hints' in EXPLAIN)
check('filter select explain id registered', 'card-hints-filter' in EXPLAIN)
check('group select explain id registered', 'card-hints-group' in EXPLAIN)
check('search input explain id registered', 'card-hints-search' in EXPLAIN)
check('close button explain id registered', 'card-hints-close' in EXPLAIN)

const liveHeader = render(createElement(GameHeader, { gameState: board(), gameTitle: 'Test Game' } as never))
check('live header renders the 💡 Hints button with its explain id',
  liveHeader.includes('header-card-hints') && liveHeader.includes('💡 Hints'), liveHeader.slice(0, 300))

const spectatorHeader = render(createElement(GameHeader, {
  gameState: state({ isSpectator: true, players: [player(OPP, false, { battlefield: [card('X', 'abababab-0001', [HINT_START_MARK, 'h'])] })] }),
  gameTitle: 'Watched Game',
} as never))
check('watcher header does NOT render the Hints button (desktop: toolHintsHelper visible when isMe)',
  !spectatorHeader.includes('header-card-hints'), spectatorHeader.slice(0, 300))

closeCardHints()

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
