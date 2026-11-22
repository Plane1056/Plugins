# ApexGuns — Paper 1.21.11

GitHub-ready Java 21 Maven plugin source, a GitHub Actions build workflow, and editable 3D resource-pack source.

**Status:** generated implementation, not compiled or server-play-tested in the authoring environment. The GitHub workflow will actually compile the source and fail visibly if compilation fails. A successful build is not a gameplay test. Test on a spare server before using it on a real world.

## Upload to GitHub
1. Extract this ZIP.
2. Create a GitHub repository.
3. Put the CONTENTS of the extracted folder at the repository root. `pom.xml`, `src`, `.github`, `resource-pack`, and `tools` must be directly inside the repository, not inside an extra ApexGuns subfolder.
4. Include the hidden `.github` directory. It contains `.github/workflows/build.yml`.
5. Commit and push. The workflow runs on pushes and pull requests. You may also run it from Actions > Build ApexGuns > Run workflow.
6. Open Actions > Build ApexGuns > the successful run. Scroll to Artifacts.
7. Download `ApexGuns-Paper-1.21.11`. Extract the downloaded artifact ZIP; the plugin is `ApexGuns-1.0.0.jar` inside.
8. Download the separate resource-pack artifact. Extract the outer GitHub artifact ZIP ONCE; keep the inner resource-pack ZIP intact.

If Actions asks to enable workflows, enable them for this repository. Workflow permissions are read-only except for GitHub's artifact upload service. No repository token or personal secrets need to be configured. Runs time out after 15 minutes; artifacts are retained for 30 days. The workflow does not publish releases or modify repository contents.

## Install
- Server: Paper 1.21.11, Java 21.
- Copy the compiled plugin JAR to the server's `plugins` directory and fully restart.
- Client: Minecraft Java 1.21.11. Enable the inner resource-pack ZIP in Options > Resource Packs.
- No ProtocolLib or client mod is required. Not intended for Folia, Bedrock, Fabric or Forge.
- For multiplayer server pack delivery, host the pack yourself at a public direct-download HTTPS URL and use server.properties resource-pack settings. Pack hosting is not included.

## Commands
Replace YourName with an online player's exact name. Requires OP or apexguns.admin.

```text
/guns give YourName all
/guns give YourName viper
/guns give YourName phantom
/guns give YourName breach
/guns give YourName spectre
/guns ammo YourName viper 128
/guns ammo YourName phantom 256
/guns ammo YourName breach 64
/guns ammo YourName spectre 64
/guns reload
/guns help
```

Guns arrive fully loaded. Reserve ammo is separate and type-specific. Full inventories cause give commands to refuse rather than discard items. apexguns.use defaults to true.

## Controls
- Right-click: fire from main hand.
- Left-click: toggle aiming (pose, improved accuracy, reduced walking speed).
- Q outside inventory: cancel the drop and reload the selected gun.
- Q / Ctrl+Q over a gun in inventory: block the drop.
- E: pick the gun up on your cursor, then click outside your own inventory window to intentionally drop it.
- Ordinary non-gun drops remain unchanged.

These are default keybindings. Server plugins receive actions, not physical keyboard keys. Rebinding Minecraft controls changes the keys used.

## Arsenal and mechanics
- Viper-9: 15-round magazine, 5 base damage, 5-tick shot cooldown, 28-tick reload, 65-block range.
- Phantom AR: 30-round magazine, 6 damage, 3-tick cooldown, 44-tick reload, 95-block range.
- Breach-12: 6 rounds, 8 pellets x 2.5 damage, 20-tick cooldown, 56-tick reload, 32-block range.
- Spectre .50: 5 rounds, 15 damage, 30-tick cooldown, 64-tick reload, 160-block range.
- Default headshot multiplier: 1.75; approximate eye-height detection.
- Timed reload progress, magazine/reserve HUD, pitch recoil, tracers, muzzle smoke and flashes.
- Solid blocks stop hitscan shots. Shotgun pellets aggregate to one normal damage call per target.
- Ammo and gun serials persist in item data.
- Gun retention on death defaults to on; reserve ammo follows normal death rules.
- Damage is health points (2 points = 1 heart) before armor/modifiers. Timings are ticks; at 20 TPS, 20 ticks is one second.

Edit plugins/ApexGuns/config.yml after first startup and run /guns reload. Vanilla immunity frames still apply; the plugin does not forcibly reset them for rapid fire.

## Resource-pack source
`resource-pack/` contains editable model JSON, item definitions, and small original texture PNGs. Its sound definitions alias built-in Minecraft sound events so no downloaded sound files or external sound tooling are needed. This repository edition does not bundle synthesized OGG files from an earlier delivery.

The workflow packages that directory with `pack.mcmeta` directly at the ZIP root. To build locally:

```sh
python3 tools/build_pack.py
```

Output goes into `dist/`, including the pack SHA-1 for optional server delivery. The pack targets format 75.0. Aiming models are pose changes, not optical zoom; fine-tune first-person model transforms for your preferred visual alignment.

## Local plugin build
Install JDK 21 and Maven 3.9+; dependency resolution needs internet access.

```sh
mvn --batch-mode --no-transfer-progress clean verify
```

Or use build.bat / `sh build.sh`. The JAR is written to `target/ApexGuns-1.0.0.jar`.

## Limitations and safety
- All guns are click/event-fired. No guaranteed custom hold-to-fire automatic mode.
- Manual drop prevention is not ownership protection: storage, trading, commands and administrator changes are not blocked. Creative deletion/cloning requires separate testing; survival/adventure is the intended environment.
- Intentionally dropped gun entities can burn, despawn, or be lost in the void.
- Aim/reload is cancelled by slot changes, container opening, death, logout or world change. Moving a gun during reload is checked using serial and slot. Opening the player's own E inventory alone is not reliably reported as InventoryOpenEvent.
- Gun interaction events are captured even when Bukkit marks an air interaction cancelled. Damage uses normal events, but no explicit region-protection integration is implemented. Verify region DAMAGE protection and anti-cheat behavior yourself.
- Other plugins can modify speed, inventories, death retention and damage. Cosmetic hit feedback does not prove a damage event was accepted.
- Do not use Bukkit /reload or hot-reload managers. Fully restart for plugin updates.

## Before production use
- Confirm Actions succeeds and server enables ApexGuns without errors.
- Verify all models, sounds, aiming and pack format on a 1.21.11 client.
- Test Q/Ctrl+Q drop blocking, E cursor-drop, ordinary item drops and container behavior.
- Test empty/partial reload, wrong ammo, swapping during reload and persistent ammo after restart.
- Test gun retention with keepInventory both true and false; check for duplicate drops.
- Verify speed restoration after aim, death, logout and weapon switching.
- Verify walls, headshots, armor, PvP toggles, team rules and protected regions.
- Test a second player picking up an intentionally dropped gun.
- Back up your world first. No runtime tests are included in the build workflow.
