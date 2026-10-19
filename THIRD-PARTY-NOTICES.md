# Third-Party Notices

LLMage is a fork of XMage. This file records the third-party code, assets and
data distributed with the project, the terms each is included under, and where
the full licence text ships. It complements [LICENSE.txt](LICENSE.txt) (the
upstream MIT licence) and [NOTICE](NOTICE). Verbatim licence texts live in
[`LICENSES/`](LICENSES/).

## How this file is maintained

- Inventory date: **2026-10-18**.
- Basis: the release tree of the initial public commit. All 43 module POMs were
  read (64 external dependency declarations, 54 distinct `group:artifact` pairs),
  together with the three assembly descriptors, the WebProxy package and
  `git ls-files` for every asset path named here.
- Every repository path named in this file exists in that tree. Counts were
  produced with `git ls-files` and by parsing the POMs, not copied from prose.
- Rule: a change that adds, removes or upgrades a third-party component updates
  this file in the same commit. `LICENSES/` holds only verbatim licence texts;
  this file holds attribution and the component-to-licence mapping.
- This is a provenance record, not legal advice.

## Licence summary

| Scope | Terms |
|---|---|
| The repository as a whole, including engine, server, console and tools | MIT — [LICENSE.txt](LICENSE.txt) |
| `Mage.Client/src/main/java/mage/client/dialog/CheckBoxList.java` | GPL-3.0-or-later |
| `mage-client.jar` and the client distribution archive | GPL-3.0-or-later (combined work, see below) |
| Bundled third-party jars, assets and data | As listed in the sections below |

## The GPL-3.0 component and `mage-client.jar`

One source file in the repository is GPL-3.0-or-later, inherited unmodified from
upstream XMage:

- `Mage.Client/src/main/java/mage/client/dialog/CheckBoxList.java`
  Copyright (C) 2006-2012 University of Waikato, Hamilton, New Zealand
  (from the Weka project, `@author fracpete`).

The file header carries the standard GPL notice; the **full licence text is not
in that header** — it ships as
[`LICENSES/GPL-3.0-or-later.txt`](LICENSES/GPL-3.0-or-later.txt), and a copy is
placed in every client distribution archive.

`CheckBoxList` is instantiated by `mage.client.deckeditor.CardSelector` and
`mage.client.dialog.PickCheckBoxDialog`, so it is compiled into
`mage-client.jar`. That jar therefore contains both GPL-3.0-or-later and
MIT-licensed code and is a combined work. Consequently:

- **`mage-client.jar`, and the client distribution archive that contains it, are
  conveyed under GPL-3.0-or-later**, not MIT alone.
- The complete corresponding source for that jar is the public source tree at
  the released version; every release is built from it.
- The GPL-3.0 text ships in `LICENSES/` and in the archive root.
- The MIT terms in [LICENSE.txt](LICENSE.txt) continue to apply to the
  MIT-covered parts, and the upstream MIT notice is retained.

This statement covers the client binary only. The engine, server, console and
WebProxy artifacts do not contain `CheckBoxList.java` and remain MIT.

## Upstream project

- **XMage** — https://github.com/magefree/mage — MIT License,
  © 2010 BetaSteward and contributors. The bulk of this repository (engine,
  server, client, card implementations, sample data) is derived from XMage. The
  MIT notice in [LICENSE.txt](LICENSE.txt) is the upstream one and must be
  preserved in all copies. LLMage modifications are © 2026 jtb (see [NOTICE](NOTICE)).

## Java dependency inventory

Versions are those declared in the module POMs. All jars are unmodified
artifacts resolved from Maven Central at build time, except JSPF (vendored, see
below); none is patched or repackaged by this project.

### LGPL-2.1

Text: [`LICENSES/LGPL-2.1.txt`](LICENSES/LGPL-2.1.txt).

| Artifact | Version | Used by |
|---|---|---|
| `org.swinglabs:swingx` | 1.6.1 | client |
| `org.swinglabs:swing-layout` | 1.0.3 | client |
| `javazoom:jlayer` | 1.0.1 | client |
| `com.googlecode.soundlibs:mp3spi` | 1.9.5.4 | client |
| `com.googlecode.soundlibs:tritonus-share` | 0.3.7.4 | client |
| `net.sf.trove4j:trove4j` | 3.0.3 | client/server |
| `trove:trove` | 1.0.2 | client/server |
| `org.jboss.remoting:jboss-remoting` | 2.5.4.SP5 | server |
| `jboss:jboss-serialization` | 4.2.2.GA | server |
| `org.jdesktop:beansbinding` | 1.2.1 | client |

These libraries are dynamically linked, unmodified, and replaceable: the
corresponding jar in the application's lib directory can be swapped for a
modified build of the same library, which is how the relinking right of LGPL-2.1
section 6 is exercised. Source for each is available from Maven Central under
the same coordinates.

### CDDL-1.1 or GPL-2.0 with Classpath Exception

Text: [`LICENSES/CDDL-1.1.txt`](LICENSES/CDDL-1.1.txt) (the CDDL option is the
one relied on here).

| Artifact | Version | Used by |
|---|---|---|
| `javax.xml.bind:jaxb-api` | 2.3.1 | server, tests |
| `com.sun.xml.bind:jaxb-impl` | 2.3.1 | server, tests |
| `javax.mail:mail` | 1.5.0-b01 | server |
| `com.sun.jersey:jersey-core` | 1.19.4 | server |
| `com.sun.jersey:jersey-client` | 1.19.4 | server |
| `com.sun.jersey.contribs:jersey-multipart` | 1.19.4 | server |

### GPL-2.0 with Classpath Exception

Text: [`LICENSES/GPL-2.0.txt`](LICENSES/GPL-2.0.txt). The Classpath Exception
allows these modules to be linked into the application.

| Artifact | Version | Used by |
|---|---|---|
| `org.openjfx:javafx-controls` | 17.0.20 | client |
| `org.openjfx:javafx-swing` | 17.0.20 | client |
| `org.openjfx:javafx-web` | 17.0.20 | client |

### MPL-2.0 or EPL-1.0

Text: [`LICENSES/MPL-2.0.txt`](LICENSES/MPL-2.0.txt) (the MPL-2.0 option is the
one relied on here).

| Artifact | Version | Used by |
|---|---|---|
| `com.h2database:h2` | 1.4.197 | server (runtime) |

### EPL-2.0 or Apache-2.0

Texts: [`LICENSES/EPL-2.0.txt`](LICENSES/EPL-2.0.txt) and
[`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt).

| Artifact | Version | Used by |
|---|---|---|
| `org.eclipse.jetty:jetty-server` | 12.0.22 | WebProxy |
| `org.eclipse.jetty:jetty-http` | 12.0.22 | WebProxy |
| `org.eclipse.jetty.ee10:jetty-ee10-servlet` | 12.0.22 | WebProxy |
| `org.eclipse.jetty.websocket:jetty-websocket-jetty-server` | 12.0.22 | WebProxy |

### BSD-3-Clause

| Artifact | Version | Used by |
|---|---|---|
| `org.glassfish.jaxb:jaxb-core` | 3.0.2 | server, tests |

`jaxb-core` 3.0.2 ships its own BSD 3-Clause licence file inside the jar
(© 2018 Oracle and/or its affiliates); it is listed here separately because the
older JAXB artifacts above are CDDL/GPL dual-licensed.

### BSD-2-Clause

- `com.github.luben:zstd-jni` (1.5.7-4, Maven dependency of `Mage.Server`) —
  **zstd-jni** by Luben Karavelov (https://github.com/luben/zstd-jni), BSD
  2-Clause License. Used by `mage.server.game.ReplayCodec` for the replay
  `.game` container format (zstd, level 9). The jar bundles the native zstd
  library for all supported platforms (linux/amd64, aarch64, arm, i386, …),
  downloaded from Maven Central at build time — not vendored in this repo.

### Remaining runtime dependencies

Every other external artifact is Apache-2.0, MIT, BSD, ISC or public domain.
That set includes `gson`, `guava`, `protobuf-java`, `ormlite-jdbc`, `forms_rt`,
`forms`, `concurrent` (public domain), `log4j`, `truevfs-profile-base`,
`commons-compress`, `commons-lang3`, `shiro-core`, `batik-transcoder`, `jetlang`,
`jsoup`, `prettytime`, `reflections`, `slf4j-reload4j`, `unbescape` and
`sqlite-jdbc`. No AGPL-licensed and no proprietary artifact is bundled.

Test-scope only, not shipped in any runtime archive: `junit:junit` 4.13.2
(EPL-1.0), `org.junit.jupiter:junit-jupiter` 5.8.1 and
`org.junit.vintage:junit-vintage-engine` 5.8.1 (EPL-2.0),
`org.assertj:assertj-core` 3.21.0 (Apache-2.0) and
`me.fabriciorby:maven-surefire-junit5-tree-reporter` 1.2.1 (Apache-2.0).

## Vendored Maven artifacts

- `repository/com/googlecode/jspf/jspf-core/0.9.1/` — **JSPF (Java Simple
  Plugin Framework)**, `net.xeoh` / Ralph Biedert, vendored because the
  artifact is no longer on Maven Central (originally
  https://code.google.com/p/jspf, mirrored at
  https://github.com/gearlles/jspf). The project describes itself as "Free
  (beer and speech)" but ships no licence text, and none is embedded in the jar
  or its POM. No licence grant is recorded: this dependency is unresolved and is
  redistributed only because the build requires it (compile scope in
  `Mage.Common` and `Mage.Client`, so it appears in every built lib directory).
- `Mage.Client/plugins/mage-counter-plugin.jar` — first-party sample plugin
  (built from `Mage.Plugins/Mage.Counter.Plugin`, org.mage); included as a
  prebuilt artifact.

## Card icons (`Mage.Client/src/main/resources/card/icons/`)

Per the upstream `readme-icons.txt` in that directory, the card icons come from
three sources; the original/ subfolders keep unmodified source files and
prepared/ contains edited derivatives. 24 of the 25 files in prepared/ carry an
attribution header naming the source (16 Font Awesome, 6 Bootstrap, 2 Pixabay);
the 25th, `Mage.Client/src/main/resources/card/icons/prepared/child-reaching.svg`,
retains only the embedded Font Awesome notice.

- **Font Awesome Free** (5.15.2 / 6.4.0) — https://fontawesome.com —
  Icons licensed CC BY 4.0 (see https://fontawesome.com/license/free).
  Includes 32 files in
  `Mage.Client/src/main/resources/card/icons/original/fontawesome/` with
  embedded licence comments, plus edited derivatives in the prepared folder
  (e.g. `ring.svg`, `child-reaching.svg`, `ankh.svg`, `khanda.svg`, `mars.svg`,
  `truck-monster.svg`, `skull-crossbones.svg` and others with Font Awesome
  icon names). Copyright © Fonticons, Inc.
- **Bootstrap Icons** — https://github.com/twbs/icons — MIT License.
  Includes 24 files in
  `Mage.Client/src/main/resources/card/icons/original/bootstrap/` plus edited
  derivatives in the prepared folder (e.g. `binoculars-fill.svg`,
  `hexagon-fill.svg`, `reply-fill.svg`, `square-fill.svg`). Copyright © Bootstrap.
- **Pixabay** — https://pixabay.com — Pixabay Content License. Two edited
  derivatives remain in the prepared folder (the two `swords-310788`
  derivatives), each with a source header. The unmodified Pixabay source SVG
  that upstream kept alongside them is **not distributed** in this repository
  or in any release archive, because the Pixabay Content License does not permit
  standalone redistribution of unaltered content.

## Card data (Scryfall)

- `Utils/mtg-cards-data.txt` and `Utils/mtg-sets-data.txt` are generated from
  the **Scryfall** API (https://scryfall.com) by
  `Utils/mtg-cards-data-scryfall.py`. Card data provided by Scryfall; used
  under their API terms (https://scryfall.com/docs/api), which permit free use
  in Magic software and prohibit paywalling access to the data.
- `Mage/src/main/resources/scryfall-name-map.json` and
  `Mage/src/main/resources/scryfall-token-map.json` are fork-generated lookup
  maps derived from Scryfall bulk card data: card name to Scryfall card UUID,
  and token name to printing. They are covered by the same Scryfall API terms.
  Scryfall does not endorse this project.
- The client's image downloaders (Gatherer, Scryfall, magiccards.info and
  other sources) fetch card images at runtime from those public services;
  no card images are stored in this repository.

## Card back and card imagery

The official Magic: The Gathering card back is a Wizards of the Coast asset. It
is **not distributed** with this repository or with any release archive: the web
client draws a neutral card back in CSS with no bundled artwork, and the desktop
client's image downloader can fetch the official image from Wikipedia at runtime
in the same way upstream XMage does. No permission from Wizards of the Coast is
recorded and none is claimed.

Card frame and mana-symbol PNGs inherited from upstream are listed under
inherited assets below; they are WotC-derived imagery whose rights are not
cleared, and they carry the same inherited-risk status as the rest of upstream's
artwork.

## Deck data

- `Mage.WebProxy/web/frontend/decks/builtin.json` (5.99 MB, generated
  2026-09-28) is a single bundle of 5,013 decklists:
  - 3,841 lists from **mtgtop8** (`"majorsCorpus": "mtgtop8-majors"`), with
    2,962 distinct author names and 469 distinct event labels;
  - 1,172 lists copied from the upstream XMage sample decks below.

  Decklists are factual records of tournament results; the selection,
  normalisation and JSON compilation are LLMage's. mtgtop8 publishes tournament
  results and a Wizards of the Coast copyright disclaimer, but as of 2026-10-18
  no terms of use or redistribution grant could be located. The `author` and
  event (`subcategory`) fields are **retained as published**, because the deck
  picker shows the author and event to identify the list. Personal names are
  therefore present; they are public tournament credits, not account data.
- `Mage.Client/release/sample-decks/` — 1,173 tracked files (1,172 `.dck`
  decks plus `jumpstart_custom.txt`), carried unchanged from upstream XMage with
  no recorded licence or provenance. Player names and event names appear in
  filenames and `NAME:` lines, with the same factual-record caveat as above.
- Fork-curated P1 deck pools: `Mage/src/main/resources/p1/standard-train-decks.json`,
  `Mage.Server.Plugins/Mage.Player.P1/src/main/resources/p1/standard-train-decks.json`
  (a byte-identical copy) and `Mage.Tests/src/test/resources/p1/standard-heldout.json`.
  These are first-party card-name/count maps curated for the P1 seat from the
  same public tournament lists; they contain no third-party code.

## Web client

- `Mage.WebProxy/web/frontend/base.css` — design tokens and reset adapted and
  trimmed from the **mage-bench** project's Astro site
  (https://github.com/GregorStocks/mage-bench), from its website stylesheet
  `base.css`. **MIT License, Copyright (c) 2026 Gregor Stocks.** Modified by
  this project (trimmed to tokens, reset and body baseline). The MIT text is the
  one in [LICENSE.txt](LICENSE.txt); mage-bench's own `LICENSE.txt` carries the
  same grant.

## Assets inherited from upstream without recorded provenance

The following asset sets are carried over unchanged from upstream XMage,
which ships them without licence or provenance documentation. They are
reproduced here in the same spirit as every other XMage fork; if an author
comes forward, this file will be updated:

- `Mage.Client/sounds/` — 32 UI sound files (.wav)
- `Mage.WebProxy/web/frontend/sounds/` — a curated subset (10 files) copied
  verbatim from `Mage.Client/sounds/` so the web client serves the desktop's
  own event sounds; same upstream provenance as above.
- `Mage.Client/src/main/resources/` — UI images, theme graphics, card frames and
  country-flag PNGs (flags are public domain). The subdirectories involved
  include avatars, buttons, phases, info, cardrender, flags, background, card,
  dlg, draft, game, hint, menu, tables and winloss.
  - `Mage.WebProxy/web/frontend/avatars/` — a curated subset (the 23 selectable
    player avatars 10–32.jpg, plus i64.jpg for computer seats) copied verbatim
    from `Mage.Client/src/main/resources/avatars/` so the web client renders the
    desktop's own player avatars; same upstream provenance as above. The
    desktop's special-avatar set at
    `Mage.Client/src/main/resources/avatars/special/*.gif` (server-side special
    avatars, id ≥ 1000) and
    `Mage.Client/src/main/resources/avatars/unknown.jpg` are **not copied into
    the web subset**; they are still present in the desktop module.
- `Mage.Client/release/backgrounds/` — 3 desktop backgrounds
- Card frame / mana symbol PNGs under
  `Mage.Client/src/main/java/mage/client/util/resources/images/`
- **Catch-all:** every other file under `Mage.Client/src/main/resources/` that
  is not described elsewhere in this file is likewise inherited from upstream
  XMage with no recorded provenance or licence, and is redistributed on the same
  basis. This sentence is the authoritative statement for those files; the
  directory list above is illustrative, not exhaustive.

## Release archives

`LICENSE.txt`, `NOTICE`, this file and the `LICENSES/` directory are placed at
the root of every distribution archive — the client, server and console zips
built from each module's assembly descriptor, and the WebProxy package
(the jar's target directory, next to the copied dependency lib directory). The
WebProxy jar is also shipped inside the server's plugins directory by the
deploy script, with the same notices alongside the server archive.
