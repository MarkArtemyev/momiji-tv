# Controls and customization

## Home

Use D-pad arrows and OK to navigate. Long-press a card for its actions. The header provides Home, Apps, Games, profile selection and Settings. Search can use the remote's search/menu key, Ctrl+F on a keyboard, or the installed system voice recognizer.

In appearance settings, choose card size, columns, font, accent, corners and wallpaper. App labels are hidden by default; **Подписи приложений** restores them. Accessibility names remain available even when the visual title is hidden. Cards without artwork keep a readable fallback.

**Атмосферные эффекты** offers maple leaves, fireflies and stars. Color, density and speed are profile-specific. The effects are off by default and stop when the screen is hidden, when power-saving/reduced-motion rules apply, or when animations are disabled. Use economy mode to reduce focus animation on slower devices.

Layouts export through Android's file picker as JSON. Import creates a profile rather than overwriting an existing one. History, website sessions, wallpapers and private artwork are not included in the layout export.

## Browser

- **Menu** or holding OK opens browser controls.
- **Element mode** moves focus among page elements.
- **Cursor mode** uses the D-pad as a pointer for mouse-oriented sites.
- Standard keyboard Tab, arrows and Enter remain available for forms and navigation.
- Play/pause and seeking keys control supported web video or the native player.
- When a direct supported stream is discovered, **Видеоплеер** opens the Media3 player.
- In the native player, Menu opens available quality options, restart and statistics; Info opens statistics.

Native playback history belongs to the profile captured when playback starts. Switching Home profiles does not transfer an active playback checkpoint to the new profile. Resuming from Home opens the source page; start the native player again when it becomes available.

## Computer controller

Open pairing from Browser and visit the local address shown there from a computer on the same trusted network. Enter the pairing code. The controller supports pointer movement, click/drag, scrolling, keyboard/text input and media controls in Momiji Browser. Disconnecting the paired controller revokes its token; reopening pairing issues a new code.

The controller uses local HTTP on port 8791. It is not intended for exposure to the public internet and is not a system-wide keyboard driver for third-party TV apps.

## Network & protection

Open **Сеть и защита Momiji** from Home settings or the browser menu. Routing defaults to off; filtering defaults to on. Start with defaults, then select TCP splitting or your own SOCKS5 server only when needed. DoH is a separate optional choice.

Site exceptions restore normal filtering behavior for a site. A custom rule exception can permit one resource while other rules stay active. If login or playback fails, try a site exception and check third-party cookie/popup settings. Reopen the player or reload the page after changing routing.
