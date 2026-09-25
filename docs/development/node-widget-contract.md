# Node widget contract (v2)

How third-party mods and resource packs restyle the skill tree's **node frames**
— the border images drawn behind every skill icon, one set per skill category.

Built-in epicskills art is *not* overridable by file placement. Art at the old
shadowing-prone paths (`textures/gui/widget/node/…`) is ignored entirely; the
internal built-ins live under `epicskills:textures/gui/skill_tree/widget/` and
only this declaration format can replace what a player sees. All art shown for
a category is chosen by epicskills code from explicit declarations — never by
resource-pack load order.

## Declaration file

Place a file at:

```
assets/<your-namespace>/skilltree/node_widgets.json
```

The namespace the file lives in identifies the provider. Every namespace on
the client resource stack is scanned — mods **and** user resource packs
(resource packs sit above mod packs, so a player-side pack can restyle art
without any mod). If two packs ship a declaration at the *same* location, the
pack stack's usual topmost-pack rule decides which file is read; that is the
only place pack order plays a role.

```json
{
  "priority": 100,
  "categories": {
    "efn_arts":   { "images": "efn:textures/gui/skilltree/efn_arts/" },
    "efn_sekiro": { "builtin": "identity" },
    "guard":      { "images": "mymod:textures/gui/skilltree/guard_restyle/" }
  }
}
```

- **`priority`** (optional, int, default `100`): file-level. Built-in art is
  the fallback used when no valid declaration covers a category — **any valid
  declaration wins regardless of its priority value**. Priority arbitrates
  only *between* declarations: the highest value wins; ties break
  alphabetically by provider namespace (`Locale.ROOT`). There are no
  per-entry priorities. A non-integer value is malformed: the file is kept
  and its priority defaults to `100`.
- **Category keys** match the Epic Fight `SkillCategory` name,
  case-insensitively (e.g. `guard`, `dodge`, `mover`, or an addon category).
- Each category entry declares **exactly one** of:
  - **`images`** — a namespaced **folder** that must contain **all four**
    state images: `locked.png`, `unlockable.png`, `acquired.png`,
    `equipped.png`. Missing any one rejects the whole entry (logged once).
  - **`builtin`** — reuse one of the five shipped styles by name:
    `dodge`, `guard`, `identity`, `passive`, `mover`.

A category with no valid declaration falls back to its built-in set — or to
the `dodge` set for categories that have no built-in art (addon categories).

## Geometry is derived, never declared

Providers ship images only. epicskills reads each image's dimensions once and
draws it at natural size, centered on the 32×32 skill icon:

```
offsetX = (imageWidth  − 32) / 2
offsetY = (imageHeight − 32) / 2
```

An unreadable image falls back to 38×38 geometry (offset 3,3). There is no
way to declare offsets, sizes, or nine-slice behavior — size your PNGs.

## Validation and logging

Provider data can crash nothing. Malformed JSON ignores that file; an invalid
entry (both or neither of `images`/`builtin`, unknown `builtin` name,
unparsable `images` location, missing state image) rejects that entry. Every
rejection is logged once at INFO — this system never logs at WARN or above,
because broken third-party art is not the player's problem. Each category's
final resolution (winner and ignored losers) is also logged once per session
at INFO.

## The kill switch

Players can disable all overrides via the client config
(`config/epicskills-client.toml`):

```toml
ignoreModOverrides = false
```

When set to `true`, epicskills always uses its built-in sets, regardless of
declarations (the resolution log says so). Default is `false`; the file/key
does not ship pre-set.

## Tree chrome (backgrounds and tab icons) — separate and simpler

Tree pages and tabs look for chrome in the **tree owner's** namespace at
`textures/gui/skill_tree/{background,icon}/<tree-name>.png`. There is no
declaration file for chrome. If a tree ships no chrome, epicskills falls back
to the built-in `battleborn` background/icon and logs it once per tree per
session at INFO; trees that do ship art are untouched. This fallback, like
everything here, is safe to hot-reload (F3+T re-scans declarations and chrome
existence).
