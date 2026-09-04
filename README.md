# ore-lizards
Source code for the minecraft mod: Ore Lizards

## Supported Minecraft versions

One git branch per Minecraft version, named after the version. Every branch is the same mod
(version `1.2.1+mc<version>`) ported to that game version's Fabric, Mojang mappings and GeckoLib
release; `main` (1.20.1) is the behavioural source of truth and each branch's `CHANGELOG.md` top
section lists only what had to differ there.

| Branch | Minecraft | GeckoLib | Java | Notes |
|---|---|---|---|---|
| `main` / `1.20.1` | 1.20.1 | 4.8.4 | 17 | **Source of truth.** All behaviour changes land here first. |
| `1.16.5` | 1.16.5 | 3.0.107 | 8 | No deepslate or copper in 1.16; iron/gold drop ingots. Java 8 syntax. |
| `1.17.1` | 1.17.1 | 3.0.32 | 16 | Deepslate attribution also samples the block underfoot (worlds end at Y=0). |
| `1.18.2` | 1.18.2 | 3.0.80 | 17 | GeckoLib 3: hold-last-frame and per-bone glow re-implemented. |
| `1.19.2` | 1.19.2 | 3.1.40 | 17 | GeckoLib 3.1. |
| `1.19.4` | 1.19.4 | 4.2 | 17 | |
| `1.20.2` | 1.20.2 | 4.3.1 | 17 | |
| `1.20.4` | 1.20.4 | 4.4.4 | 17 | |
| `1.20.6` | 1.20.6 | 4.5.4 | 21 | |
| `1.21.1` | 1.21.1 | 4.9.2 | 21 | |
| `1.21.3` | 1.21.3 | 4.7.1 | 21 | |
| `1.21.4` | 1.21.4 | 4.8.5 | 21 | Pickaxe rule becomes "can mine diamond ore"; item model definitions. |
| `1.21.5` | 1.21.5 | 5.1.0 | 21 | GeckoLib 5 render-state rewrite of the tint/glow layer; baked spawn-egg texture. |
| `1.21.6` | 1.21.6 | 5.2.0 | 21 | |
| `1.21.7` | 1.21.7 - 1.21.8 | 5.2.1 | 21 | |
| `1.21.8` | 1.21.8 | 5.2.2 | 21 | |
| `1.21.10` | 1.21.10 | 5.3-alpha-3 | 21 | **Experimental** - the only GeckoLib build for 1.21.10 is an alpha. |
| `1.21.11` | 1.21.11 | 5.4.5 | 21 | Submit/collector renderer; `ResourceLocation` is `Identifier` here. |
| `26.1.2` | >= 26.1.2 | 5.5.2 | 25 | Unobfuscated Minecraft: non-remapping Loom plugin. |
| `26.2` | 26.2 | 5.5.4 | 25 | Unobfuscated Minecraft: non-remapping Loom plugin. |

Build any of them with `./gradlew build` from a checkout of that branch; the jar lands in
`build/libs/orelizards-1.2.1+mc<version>.jar`.
