# Mobium

Mobium is a client-side performance mod for Minecraft (Fabric, Quilt and NeoForge). It makes entities and block
entities cheaper to draw by keeping their geometry on the GPU and sending only small per-frame updates. Vanilla,
Entity Model Features and Iris still decide how everything looks: Mobium changes how geometry reaches the GPU, never
what ends up on screen.

> Mobium is in early development. Nothing is accelerated yet; this page will describe the features once the first
> beta is released.

## Planned features

| Module | What it does |
| --- | --- |
| Static cache | Chests, signs, beds, shelves, item frames, armor stands and display entities are captured once and redrawn from GPU buffers until they change |
| GPU part transform | Animated mobs keep their normal animations, but the GPU builds their vertices instead of the CPU |
| Animation LOD (optional) | Far-away mobs reuse last frame's pose; off by default |

Every feature can be switched off, and Mobium falls back to the vanilla renderer whenever it meets something it does
not understand.

## Compatibility

Mobium is built to work with Sodium, Iris shader packs, Entity Model Features / Entity Texture Features (including
the Fresh Animations resource pack), EntityCulling and ModMenu.

## Requirements

- Minecraft 26.3
- Fabric: [Fabric API](https://modrinth.com/mod/fabric-api) and
  [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- NeoForge: [KotlinLangForge](https://modrinth.com/mod/kotlin-lang-forge)

## License

Mobium is licensed under the [GNU Lesser General Public License v3.0](LICENSE).
