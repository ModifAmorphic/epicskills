# Epic Skills data model

This document is for datapack and addon developers who want to add or modify
skill trees. It describes how Epic Fight concepts, skill trees, and tree
entries fit together, how the data is addressed on disk, and what happens when
pieces are missing.

## The three layers

1. **Skill categories (owned by Epic Fight).** Every Epic Fight skill belongs
   to exactly one category (`SkillCategory`, e.g. `dodge`, `guard`,
   `identity`, `passive`, `mover`). Categories are an Epic Fight registry —
   epicskills never defines them, it only reacts to them. A category decides
   which node-frame art a skill's tree node uses (see
   [node-widget-contract.md](node-widget-contract.md)) and whether a skill may
   appear in a tree at all (only *learnable* categories may).
2. **Skill trees (tabs).** A tree is **metadata only**: menu bar color,
   lock/unlock state, visibility, and display priority. It contains no skills.
   Trees become the tabs on the left edge of the skill tree screen, ordered by
   `priority` (ascending), then registry name.
3. **Skill tree entries (nodes).** An entry carries the actual node list for
   one tree: which skills, their parents, screen positions, ability-point
   costs, per-node unlock conditions, and optional cross-tree imports.

The tree and its entry are **paired by registry ID**: the entry
`mymod:fire_tree` is merged into the tree `mymod:fire_tree`. This pairing is
what lets a datapack add nodes to another pack's tree — or split a tree's
definition across packs — while keeping a single tab.

## File locations: why "epicskills" appears twice

Trees and entries live in NeoForge **datapack registries** (`epicskills:tree`
and `epicskills:entry`). Datapack registry files follow vanilla's layout:

```
data/<pack-namespace>/epicskills/tree/<name>.json
data/<pack-namespace>/epicskills/entry/<name>.json
```

- The first path segment (`data/<pack-namespace>/`) says **which pack** ships
  the file.
- The `epicskills/` segment is the **registry's namespace** — it says which mod
  owns the registry the file loads into. Vanilla uses the same convention
  (`data/<pack>/minecraft/...` for vanilla registries).
- `<name>` is the entry ID, namespaced by the pack: `mymod:fire_tree`.

So the built-in trees live at `data/epicskills/epicskills/tree/battleborn.json`
(the epicskills pack, writing into the epicskills-owned registry), while your
addon's tree would be `data/mymod/epicskills/tree/fire_tree.json` → registered
as `mymod:fire_tree`.

### Tree JSON (`epicskills:tree`)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `menu_color` | `[r, g, b]` | `[255, 255, 255]` | UI accent color |
| `locked` | bool | `false` | Tree starts locked |
| `conditions` | EntityPredicate | *(none)* | What unlocks a locked tree |
| `hidden` | bool | `false` | Hide the tab while locked |
| `disabled` | bool | `false` | Skip the tree entirely |
| `unlock_tip` | translation key | *(none)* | Tooltip shown on a locked tab |
| `priority` | int | `100` | Tab ordering |

### Entry JSON (`epicskills:entry`)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `priority` | int | `0` | Entries merge in descending order (later entries can't overwrite an already-placed skill) |
| `nodes` | list | `[]` | The node list, see below |

Each node:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `skill` | skill ID | *required* | Namespaced Epic Fight skill ID |
| `parents` | list of `{ "skill": …, "control_points": [[x,y], …] }` | *(none)* | Prerequisites; control points shape the connector line |
| `conditions` | EntityPredicate | *(none)* | Extra unlock condition for this node |
| `custom_condition` | bool | `false` | Mark a condition the mod can't auto-track |
| `ability_points` | int | `0` | Cost in ability points |
| `position_in_screen` | `[x, y]` | *required* | Node position on the page |
| `hidden` | bool | `false` | Reserve layout space but don't render |
| `import` | tree ID | *(none)* | Mirror this skill's node from another tree |
| `unlock_tip` | translation key | *(none)* | Tooltip while condition-blocked |

## Missing-piece behavior

| Situation | Result |
|---|---|
| No trees at all | Chat message *"No skill tree page"*; the screen refuses to open |
| Tree without a matching entry | Blank tab — the page exists but has no nodes |
| Entry without a matching tree | Logged as dead data (`Unknown skill tree id`) and ignored |
| Node whose skill's category isn't learnable | Node dropped with a log warning |
| Skill not referenced by any entry | Simply absent from epicskills — it remains a normal Epic Fight skill |

## Tree lifecycle and synchronization

`locked` + `conditions` control progression. Conditions are **any vanilla
`EntityPredicate`** matched against the player — for example the built-in
`infernal_might` tree unlocks when the player *is located in* `minecraft:the_nether`.
While a tree is locked, its nodes can't be spent on; `hidden` additionally
removes the tab from the screen until unlocked. `disabled` removes the tree
server-side entirely (no tab, no progression, its entries skipped).

The check runs on the **server** every tick for every locked tree with
conditions; on success the server flips the tree to unlocked and syncs the
state (and any condition-gated nodes it unlocked) to the client, which shows a
toast. Node unlocks and ability-point spends go through the server too — the
client tree screen is a view, not a source of truth.

## Attribution: skills, namespaces, and shared categories

Skills are Epic Fight registry entries with namespaced IDs
(`epicfight:roll`, `mymod:flame_slash`). Attribution for content belongs at
the *skill* level; epicskills adds attribution at the *tree* level (the tree
ID's namespace) and the *node art* level (the declaration file's namespace —
see the widget contract). Categories cut across trees: two trees can (and do)
contain skills of the same category, and one skill can appear in several
trees.

**Worked example — `endurance` in two trees.** The skill `epicfight:endurance`
is a real node in `battleborn` (between Guard and Berserker).
`infernal_might` wants it too, but a copy would desync: unlocking it in one
tree should unlock it in both. So `infernal_might` declares a node with
`"skill": "epicfight:endurance"` and `"import": "epicskills:battleborn"`.
Imported nodes render translucent, aren't clickable for unlocking, and simply
mirror the state of the original node in the owning tree.

## Built-in trees

- **`epicskills:battleborn`** — the general-purpose tree: mobility (Roll →
  Phantom Ascent, Step → Technician, Emergency Escape), swordsmanship (Sword
  Master + Guard → Parrying → Revelation), and berserker branch
  (Berserker/Adrenaline Fiend → Endurance → Impact Guard; Hypervitality →
  Demolition Leap / Bonebreaker). Its capstone, Meteor Slam, is gated behind
  killing the Ender Dragon (a stat-based `PlayerPredicate` condition).
- **`epicskills:infernal_might`** — a small offspec tree (Stamina Pilager,
  Death Harvest, Forbidden Strength, the endurance import, Adaptive Skin). It
  is locked and `conditions`-gated to the Nether, both as a progression gate
  and to demonstrate the condition system: the tab shows an unlock tip
  ("Find the unholy power in a deeper world") until the player enters
  `minecraft:the_nether`.

## What this system replaced

Before epicskills, Epic Fight taught skills through per-mob skill-book drop
tables: every skill had to be attached to specific mobs' loot, players
grounded for the right book, and there was no notion of progression order or
cost. The tree system replaces that grind with a spendable resource (ability
points, earned by converting experience) and an explicit, datapack-editable
progression graph, while Epic Fight's own skill learning/equipping machinery
stays intact underneath.
