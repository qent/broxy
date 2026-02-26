# liquid glass migration plan

## ui inventory

| screen | navigation/control zones (glass on macOS) | content zones (prefer solid/readable) | notes |
|---|---|---|---|
| main window scaffold | global top bar, left navigation rail, floating action button | screen body area | top bar and rail use clear/regular glass variants |
| servers | server cards, search field, delete dialog | long capability/status text lines | dense lists keep strong contrast via card alpha and dimming policy |
| presets | preset cards, search field, delete dialog | list text content | active preset border remains explicit |
| clients | client cards, connection tabs, action buttons | json snippets and long descriptions | monospace config snippets stay on regular glass cards |
| server editor | header actions, transport selector cards, input containers | multiline env/headers text | text fields use higher opacity when reduce transparency is enabled |
| preset editor | header actions, selector sections, capability cards | long capability descriptions | capability rows keep low-noise visuals |
| settings | all setting rows, toggles, dropdowns, showcase block | numeric/text input values | settings is primary control surface for glass tuning |
| dialogs/callouts | dialog container, action buttons | confirmation text | dialog body remains high-contrast |

## usage rules

- apply glass primarily to navigation and controls: top bar, rail, cards, dialogs, floating controls.
- keep high-information text areas readable: avoid decorative blur overlays over dense text blocks.
- use `DimmingPolicy.Auto` for bright/noisy backgrounds and `Always` for forced readability checks.
- avoid glass for very dense editors/tables unless `reduceTransparency=false` and contrast is acceptable.

## exclusions

- backend/ui-adapter logic is unchanged.
- windows/linux do not use vibrancy; they default to solid rendering through shared glass primitives with `glassEnabled=false`.
