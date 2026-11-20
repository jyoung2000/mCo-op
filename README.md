# MCo-op

Turns a singleplayer world into a drop-in co-op world shared between friends, for Minecraft 26.3 on Fabric and NeoForge (one jar for both).

- Every friend keeps a copy of the world. Whoever is playing hosts; friends drop in and out.
- When the host quits, another player takes over without losing the world. If the host crashes, a friend takes over.
- Play offline on your own copy and your changes are merged back the next time you meet.
- Save points, admins and a shared mod list are built in.

## Install

1. Install Fabric Loader or NeoForge for Minecraft 26.3.
2. Put `MCo-op-<version>.jar` in your `mods` folder. The same jar works on both loaders.
3. Open the **Co-op Worlds** screen from the title menu.

Don't install e4mc next to this mod: it already contains e4mc's tunnel.

## Privacy

World data and player names travel between friends over the e4mc relay when a direct connection isn't possible.

## Building

Needs JDK 25.

```
./gradlew build      # jar in build/libs
./gradlew :core:test # unit tests
```

## Credits

Made by yessirski. Built on [e4mc](https://github.com/vgskye/e4mc-minecraft-architectury) by Skye (MIT); this project isn't affiliated with or endorsed by e4mc.

## License

[MIT](LICENSE)
