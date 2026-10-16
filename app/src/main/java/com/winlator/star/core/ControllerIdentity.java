package com.winlator.star.core;

import com.winlator.star.container.Container;
import com.winlator.star.container.Shortcut;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Controller identity": what the virtual pads say they are to a Wine game.
 *
 * <p>The fake evdev layer (fakeinput.cpp, PAD_IDENTITIES) answers EVIOCGID / EVIOCGNAME with the
 * row picked by {@link #ENV}; unset or empty is the generic "Generic HID Gamepad N" (1234:5678+N),
 * which is what every Wine session saw before this setting existed. Buttons, axes and force
 * feedback are the same for every identity. {@link #TABLE} below mirrors that native table.
 *
 * <p>Bannerlator's Wine layers are built --with-sdl --without-udev, so winebus reads the pads only
 * through its SDL bus. That is where an identity can make the pad vanish, and every row here was
 * checked against it (proton-wine dlls/winebus.sys):
 * <ul>
 *   <li>bus_sdl.c is_sdl_ignored_device() drops 28DE:11FF ("handled with evdev"), and evdev is
 *       compiled out: the Steam virtual gamepad is never offered for Wine ({@link Identity#wine}).
 *   <li>main.c is_hidraw_enabled() prefers hidraw for 054C:09CC / 054C:0CE6 and the bus drops a
 *       non-hidraw device that conflicts ("ignoring non-hidraw device"). PROTON_DISABLE_HIDRAW lifts
 *       that per VID/PID; see {@link #launchEnv}.
 *   <li>Switch Pro, the Joy-Con pair and the Steam Deck are on neither list.
 * </ul>
 *
 * <p>Host SDL (in winebus) keys its mapping database on bus/vendor/product/version, and every
 * non-generic identity has an entry there describing the REAL device's evdev layout (xpad puts the
 * triggers on ABS_Z/ABS_RZ, ours are ABS_BRAKE/ABS_GAS like Android). Applied to our pad that would
 * put the right stick on a trigger, so {@link #launchEnv} hands SDL a mapping of our own layout for
 * the identity's GUID (SDL_GAMECONTROLLERCONFIG, user priority, replaces the database entry). It is
 * exactly what SDL generates automatically for the generic pad.
 *
 * <p>Per container in extraData under {@link #EXTRA}, with a per-game shortcut override of the same
 * key; an explicit FAKE_EVDEV_IDENTITY in the game's or the container's env vars wins over both.
 */
public final class ControllerIdentity {
    public static final String EXTRA = "controllerIdentity";
    public static final String ENV = "FAKE_EVDEV_IDENTITY";

    public static final String GENERIC = "generic";
    public static final String XBOX_360 = "xbox360";
    public static final String XBOX_ONE = "xboxone";
    public static final String XBOX_SERIES = "xboxseries";
    public static final String STEAM_VIRTUAL = "steamvirtual";
    public static final String DINPUT_PAD = "dinputpad";
    public static final String DS4 = "ds4";
    public static final String DUALSENSE = "dualsense";
    public static final String SWITCH_PRO = "switchpro";
    public static final String JOYCON_PAIR = "joyconpair";
    public static final String STEAM_DECK = "steamdeck";

    private static final String EXPERIMENTAL =
            " (experimental — button icons only; may break input in some games)";

    /** The generic pad's evdev name (fakeinput.cpp GAMEPAD_NAME_TEMPLATE). */
    private static final String GENERIC_NAME_TEMPLATE = "Generic HID Gamepad %d";

    /** One row of the identity table. */
    public static final class Identity {
        public final String value;
        public final String label;
        public final int vendor;
        public final int product;
        /** EVIOCGNAME, %d = the player slot (0..3); null for the generic row. */
        final String nameTemplate;
        /** Level 2: only the button icons change, and some games' own controller code may not cope. */
        public final boolean experimental;
        /** Offered for Wine containers and games (false: winebus would drop the pad). */
        public final boolean wine;
        /** Fixed names Wine may give the pad in DirectInput besides the per-slot one (see dinputNames). */
        final String[] busNames;
        /** SDL hints that keep SDL off the vendor's private HID protocol (it would talk it to our pad). */
        final String[] sdlHints;
        /** winebus prefers hidraw for this VID/PID: needs PROTON_DISABLE_HIDRAW or the pad is dropped. */
        final boolean hidrawPreferred;

        Identity(String value, String label, int vendor, int product, String nameTemplate,
                 boolean experimental, boolean wine, String[] busNames, String[] sdlHints,
                 boolean hidrawPreferred) {
            this.value = value;
            this.label = label;
            this.vendor = vendor;
            this.product = product;
            this.nameTemplate = nameTemplate;
            this.experimental = experimental;
            this.wine = wine;
            this.busNames = busNames;
            this.sdlHints = sdlHints;
            this.hidrawPreferred = hidrawPreferred;
        }

        public String vidPid() {
            return vendor == 0 ? "1234:5678+slot"
                    : String.format(Locale.ROOT, "%04X:%04X", vendor, product);
        }
    }

    private static final String[] NONE = {};
    private static final String HIDAPI_OFF = "SDL_JOYSTICK_HIDAPI=0";

    // Order = the order the pickers show. Names after the template are what SDL calls the pad when
    // it knows the id (SDL_CreateJoystickName's custom name) and what Proton's hidclass reports as
    // the product string for it (dlls/hidclass.sys/device.c device_strings[]).
    private static final Identity[] TABLE = {
            new Identity(GENERIC, "Generic", 0, 0, null, false, true, NONE, NONE, false),
            new Identity(XBOX_360, "Xbox 360", 0x045E, 0x028E, "Xbox 360 Controller (%d)", false, true,
                    new String[]{"Xbox 360 Controller", "Controller (XBOX 360 For Windows)"}, NONE, false),
            new Identity(XBOX_ONE, "Xbox One", 0x045E, 0x02EA, "Xbox One S Controller (%d)", false, true,
                    new String[]{"Xbox One S Controller", "Controller (Xbox One For Windows)"}, NONE, false),
            new Identity(XBOX_SERIES, "Xbox Series X|S", 0x045E, 0x0B12, "Xbox Series X Controller (%d)", false, true,
                    new String[]{"Xbox Series X Controller", "Controller (Xbox One For Windows)"}, NONE, false),
            new Identity(DINPUT_PAD, "Classic DirectInput pad", 0x1209, 0x0001, "USB Gamepad (%d)", false, true,
                    NONE, NONE, false),
            new Identity(DS4, "PlayStation DualShock 4" + EXPERIMENTAL, 0x054C, 0x09CC, "Wireless Controller (%d)", true, true,
                    new String[]{"Wireless Controller"},
                    new String[]{HIDAPI_OFF, "SDL_JOYSTICK_HIDAPI_PS4=0"}, true),
            new Identity(DUALSENSE, "PlayStation DualSense" + EXPERIMENTAL, 0x054C, 0x0CE6, "DualSense Wireless Controller (%d)", true, true,
                    new String[]{"Wireless Controller"},
                    new String[]{HIDAPI_OFF, "SDL_JOYSTICK_HIDAPI_PS5=0"}, true),
            new Identity(SWITCH_PRO, "Nintendo Switch Pro" + EXPERIMENTAL, 0x057E, 0x2009, "Pro Controller (%d)", true, true,
                    NONE, new String[]{HIDAPI_OFF, "SDL_JOYSTICK_HIDAPI_SWITCH=0"}, false),
            new Identity(JOYCON_PAIR, "Joy-Con pair (L+R)" + EXPERIMENTAL, 0x057E, 0x2008, "Nintendo Switch Combined Joy-Cons (%d)", true, true,
                    NONE, new String[]{HIDAPI_OFF, "SDL_JOYSTICK_HIDAPI_JOY_CONS=0", "SDL_JOYSTICK_HIDAPI_SWITCH=0"}, false),
            new Identity(STEAM_DECK, "Steam Deck" + EXPERIMENTAL, 0x28DE, 0x1205, "Steam Deck Controller (%d)", true, true,
                    NONE, new String[]{HIDAPI_OFF, "SDL_JOYSTICK_HIDAPI_STEAMDECK=0"}, false),
            // Linux/Steam runtime only: winebus' SDL bus ignores 28DE:11FF and its evdev bus is not built.
            new Identity(STEAM_VIRTUAL, "Steam Virtual Gamepad (Linux/Steam runtime only)", 0x28DE, 0x11FF, "Microsoft X-Box 360 pad %d", false, false,
                    new String[]{"Controller (XBOX 360 For Windows)"}, NONE, false),
    };

    // Our pad as SDL's Linux joystick driver numbers it: buttons in key-code order BTN_A, BTN_B,
    // BTN_X, BTN_Y, BTN_TL, BTN_TR, BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR (b0..b10);
    // axes in code order ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_GAS (right trigger), ABS_BRAKE (left
    // trigger) (a0..a5); the d-pad is hat 0. The same mapping SDL generates for the generic pad.
    // "*" = keep the joystick's own name. A Nintendo identity keeps it positional: the bottom button
    // is SDL's south button, which SDL labels "B" on Nintendo pads.
    private static final String PAD_MAPPING = "*,a:b0,b:b1,x:b2,y:b3,back:b6,guide:b8,start:b7,"
            + "leftstick:b9,rightstick:b10,leftshoulder:b4,rightshoulder:b5,"
            + "dpup:h0.1,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,"
            + "leftx:a0,lefty:a1,rightx:a2,righty:a3,lefttrigger:a5,righttrigger:a4,";
    /** fakeinput.cpp GAMEPAD_VERSION, part of SDL's GUID. */
    private static final int PAD_VERSION = 0x0110;

    private ControllerIdentity() {}

    public static Identity find(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (Identity id : TABLE) if (id.value.equals(v)) return id;
        return null;
    }

    /** A known value, or null for absent / blank / unknown. */
    public static String normalize(String raw) {
        Identity id = find(raw);
        return id != null ? id.value : null;
    }

    /** The identities a Wine container or game can pick, in picker order. */
    public static List<Identity> wineChoices() {
        List<Identity> out = new ArrayList<>();
        for (Identity id : TABLE) if (id.wine) out.add(id);
        return Collections.unmodifiableList(out);
    }

    /** The picker label for a stored value (unknown = Generic). */
    public static String label(String value) {
        Identity id = find(value);
        return id != null ? id.label : TABLE[0].label;
    }

    /** A Wine-pickable value for anything stored (unknown or Linux-only = Generic). */
    public static String forWine(String value) {
        Identity id = find(value);
        return id != null && id.wine ? id.value : GENERIC;
    }

    /**
     * The identity this Wine launch presents: an explicit FAKE_EVDEV_IDENTITY in the game's, then the
     * container's env vars (unknown or empty = generic, exactly as the native layer reads it), else
     * the game's own choice, else the container's. A Linux-only identity stored on a container or
     * game (e.g. from an imported config) falls back to Generic rather than losing the pad.
     */
    public static String effective(Container container, Shortcut shortcut) {
        String env = null;
        if (shortcut != null) env = envGet(shortcut.getExtra("envVars"), ENV);
        if (env == null && container != null) env = envGet(container.getEnvVars(), ENV);
        if (env != null) {
            Identity id = find(env);
            return id != null ? id.value : GENERIC;
        }
        if (shortcut != null) {
            String own = normalize(shortcut.getExtra(EXTRA));
            if (own != null) return forWine(own);
        }
        return container != null ? forWine(container.getControllerIdentity()) : GENERIC;
    }

    /** Where {@link #effective} took the value from, for the launch log. */
    public static String source(Container container, Shortcut shortcut) {
        if (shortcut != null && envGet(shortcut.getExtra("envVars"), ENV) != null) return "game env var";
        if (container != null && envGet(container.getEnvVars(), ENV) != null) return "container env var";
        if (shortcut != null && normalize(shortcut.getExtra(EXTRA)) != null) return "this game's settings";
        return "container";
    }

    /**
     * The guest env this identity needs, in insertion order. The caller puts each entry before the
     * container's and the game's own env vars are merged, so an explicit value there still wins;
     * PROTON_DISABLE_HIDRAW* and SDL_GAMECONTROLLERCONFIG are merged with an existing value instead
     * (see {@link #mergeEnv}). Generic adds nothing: the variable stays unset, as before.
     *
     * <p>PROTON_DISABLE_HIDRAW: main.c accepts "1" (all devices) or a list containing "0x%04X/0x%04X"
     * (case-insensitive substring match). Some Proton 10 builds look the variable up with a length
     * of 20, i.e. as "PROTON_DISABLE_HIDRA", so both spellings are set.
     */
    public static Map<String, String> launchEnv(String value) {
        Map<String, String> env = new LinkedHashMap<>();
        Identity id = find(value);
        if (id == null || id.vendor == 0) return env;
        env.put(ENV, id.value);
        for (String hint : id.sdlHints) {
            int eq = hint.indexOf('=');
            env.put(hint.substring(0, eq), hint.substring(eq + 1));
        }
        if (id.hidrawPreferred) {
            String vidPid = String.format(Locale.ROOT, "0x%04X/0x%04X", id.vendor, id.product);
            env.put("PROTON_DISABLE_HIDRAW", vidPid);
            env.put("PROTON_DISABLE_HIDRA", vidPid);
        }
        if (id.wine && !DINPUT_PAD.equals(id.value)) {
            env.put("SDL_GAMECONTROLLERCONFIG", sdlGuid(id) + "," + PAD_MAPPING);
        }
        return env;
    }

    /**
     * Combines one of {@link #launchEnv}'s values with a value the user already set. A hidraw list
     * keeps "1" (already everything) or gains our VID/PID; a mapping list keeps the user's lines
     * last, so their mapping for the same GUID wins. Anything else: the user's value wins.
     */
    public static String mergeEnv(String name, String ours, String existing) {
        if (existing == null || existing.isEmpty()) return ours;
        if (name.startsWith("PROTON_DISABLE_HIDRA")) {
            if (existing.trim().equals("1") || existing.toUpperCase(Locale.ROOT).contains(ours.toUpperCase(Locale.ROOT))) {
                return existing;
            }
            return existing + "," + ours;
        }
        if (name.equals("SDL_GAMECONTROLLERCONFIG")) return ours + "\n" + existing;
        return existing;
    }

    /** Env names {@link #mergeEnv} combines rather than leaving to the user's value. */
    public static boolean isMergedEnv(String name) {
        return name.startsWith("PROTON_DISABLE_HIDRA") || name.equals("SDL_GAMECONTROLLERCONFIG");
    }

    /**
     * winebus "Map Controllers" for this identity: false only for the Classic DirectInput pad. Mapped
     * (the default) means winebus turns every pad SDL recognises into an XInput-style gamepad, and
     * SDL recognises any evdev pad with gamepad buttons - that is why even the generic pad is
     * XInput-compatible today. Unmapped, winebus presents the raw joystick (11 buttons, so not an
     * XInput gamepad either): a plain DirectInput pad for old games.
     */
    public static boolean mapControllers(String value) {
        return !DINPUT_PAD.equals(value);
    }

    /**
     * The HKCU\Software\Wine\DirectInput\Joysticks value names Wine may match this identity's pads
     * by, for all four slots: the evdev name (what winebus gets when SDL knows no better name), the
     * name SDL substitutes for an id it knows, and the product string Proton's hidclass reports for
     * it - each also with its first four characters cut. The cut copy is the "ric HID Gamepad N"
     * rule this list always carried for the generic pad (added upstream as "hack for proton 10": the
     * instance name reached DirectInput without its first four characters on those layers; the
     * winebus/hidclass/dinput sources do not show the cut, so it is applied as observed). Generic
     * yields exactly the eight names written before this setting existed, in the same order.
     */
    public static List<String> dinputNames(String value) {
        Identity id = find(value);
        if (id == null) id = TABLE[0];
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String template = id.nameTemplate != null ? id.nameTemplate : GENERIC_NAME_TEMPLATE;
        for (int slot = 0; slot < 4; slot++) {
            String name = String.format(Locale.ROOT, template, slot);
            names.add(name);
            addCut(names, name);
        }
        for (String name : id.busNames) {
            names.add(name);
            addCut(names, name);
        }
        return new ArrayList<>(names);
    }

    /** Every name {@link #dinputNames} can produce, for clearing another identity's leftovers. */
    public static Set<String> allDinputNames() {
        Set<String> all = new LinkedHashSet<>();
        for (Identity id : TABLE) all.addAll(dinputNames(id.value));
        return all;
    }

    /** One line for the session log. */
    public static String describe(String value) {
        Identity id = find(value);
        if (id == null) id = TABLE[0];
        String name = id.nameTemplate != null ? String.format(Locale.ROOT, id.nameTemplate, 0)
                : String.format(Locale.ROOT, GENERIC_NAME_TEMPLATE, 0);
        return id.label + " [" + id.value + ", " + id.vidPid() + ", \"" + name + "\"]";
    }

    private static void addCut(Set<String> names, String name) {
        if (name.length() > 4) names.add(name.substring(4));
    }

    /** SDL's GUID for our pad with this identity: bus 3 (USB), no CRC, vendor, product, version. */
    private static String sdlGuid(Identity id) {
        return String.format(Locale.ROOT, "03000000%02x%02x0000%02x%02x0000%02x%02x0000",
                id.vendor & 0xFF, (id.vendor >> 8) & 0xFF,
                id.product & 0xFF, (id.product >> 8) & 0xFF,
                PAD_VERSION & 0xFF, (PAD_VERSION >> 8) & 0xFF);
    }

    private static String envGet(String raw, String key) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            EnvVars e = new EnvVars(raw);
            return e.has(key) ? e.get(key) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
