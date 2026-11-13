package com.winlator.star.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.FeatureFlags
import com.winlator.star.store.InGameFriendsSource
import com.winlator.star.ui.FriendsContent
import com.winlator.star.ui.XServerDrawerState
import com.winlator.star.ui.deck.DeckInGameState.MenuTab
import com.winlator.star.ui.simple.L
import com.winlator.star.ui.simple.LabArt
import com.winlator.star.ui.simple.LabInGameIcon
import com.winlator.star.ui.simple.LabPicture
import com.winlator.star.ui.simple.loadLabShortcut

// The XMB mode's in-game menu: Lab's one drawer (Quick first, then every page, picked from an icon strip, Pause and Exit in
// the footer) dressed in the XMB's glass look. XServerDisplayActivity's key plumbing and DeckInGameState's Lab state machine
// (labQuick, selectLabQuick / selectMenuTab, L1 / R1, X, B) drive it unchanged; DeckInGameOverlay shows it instead of the Deck's
// quick menu + game menu while DeckInGameState.xmbDrawer is on, and every page is the Deck / Lab page itself.

/** The drawer's width in dp on a screen [screenDp] wide: all of a compact (phone portrait) screen, else 46% of it, held to 420..600 dp (Lab's width). */
internal fun xmbInGameDrawerWidth(screenDp: Float, compact: Boolean): Float =
    if (compact) screenDp else (screenDp * 0.46f).coerceIn(minOf(420f, screenDp), 600f)

/** Under this width (dp) the pages stack a row's control under its text, as on a phone, instead of leaving it beside the text. */
internal const val XMB_DRAWER_STACK_BELOW_DP = 560f

internal fun xmbInGameDrawerStacksRows(drawerDp: Float): Boolean = drawerDp < XMB_DRAWER_STACK_BELOW_DP

/** How much of the game's art shows through the glass (it is laid over the opaque glass base), and where the fade to the soft edge starts. */
private const val XMB_DRAWER_ART_ALPHA = 0.34f
private const val XMB_DRAWER_ART_FADE_FROM = 0.62f

/** The glass without art is a touch see-through, so the game just shows behind it. */
private const val XMB_DRAWER_GLASS_ALPHA = 0.95f

/**
 * The in-game drawer of the XMB mode, [widthDp] wide (see [xmbInGameDrawerWidth]). It brings its own glass theme and highlight
 * host, so the cards are glass and the focused control is marked by the one gliding accent outline, as on the XMB pages.
 */
@Composable
internal fun XmbInGameMenu(modifier: Modifier, widthDp: Float, onExit: () -> Unit) {
    XmbGlassTheme(enabled = true) {
        XmbHighlightHost(enabled = true, modifier = modifier) {
            XmbInGameMenuBody(widthDp, onExit)
        }
    }
}

@Composable
private fun BoxScope.XmbInGameMenuBody(widthDp: Float, onExit: () -> Unit) {
    val state = XServerDrawerState
    val tab by DeckInGameState.menuTab.collectAsState()
    val quick by DeckInGameState.labQuick.collectAsState()
    val panel by DeckInGameState.panel.collectAsState()
    val title by DeckInGameState.gameTitle.collectAsState()
    val isPaused by state.isPaused.collectAsState()
    val kind by DeckInGameState.sessionKind.collectAsState()
    val tvConnected by state.tvConnected.collectAsState()
    val castSupported by state.castSupported.collectAsState()
    val friendsSource by InGameFriendsSource.state.collectAsState()
    val mods by state.mods.collectAsState()
    val tabs = DeckInGameState.availableMenuTabs(kind, FeatureFlags.TV_OUTPUT_ENABLED && (tvConnected || castSupported), friendsSource.tabVisible, mods.isNotEmpty())
    val shown = if (tab in tabs) tab else tabs.first()
    val cs = MaterialTheme.colorScheme
    val glass = rememberXmbGlass()
    val stripFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    FocusOnRequest(panel, pageFocus, fallback = stripFocus)
    LaunchedEffect(Unit) { InGameFriendsSource.poke() }

    // The running game's pictures (Game details > Artwork > In-game menu, else the app pages'): its art faded behind the
    // glass, its icon and logo at the top in place of the name.
    val context = LocalContext.current
    val gamePath by DeckInGameState.gameShortcutPath.collectAsState()
    val game = remember(gamePath) { gamePath?.let { loadLabShortcut(context, it) } }
    LaunchedEffect(game) { game?.let { LabArt.resolve(context, it) } }
    val artTick = LabArt.tick
    val art = remember(game, artTick) { game?.let { LabArt.inGameBackground(it) }.orEmpty() }
    val logo = remember(game, artTick) {
        game?.let { g -> LabArt.inGameModels(g, "logo").map { LabArt.Pic(it) } }.orEmpty()
    }
    val hasArt = art.isNotEmpty()

    // The panel: frosted glass flush with the screen's left edge, rounded on the game side, with the glass cards' soft shadow
    // and the bright hairline along its top edge. Over the game's art the glass is its opaque base with the art laid under the
    // cards, fading out toward the right so the edge stays soft.
    val shape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)
    Box(Modifier.matchParentSize().xmbGlassCard(shape, fill = glass.base.copy(alpha = if (hasArt) 1f else XMB_DRAWER_GLASS_ALPHA))) {
        if (hasArt) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = XMB_DRAWER_ART_ALPHA
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.horizontalGradient(0f to Color.Black, XMB_DRAWER_ART_FADE_FROM to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) { LabPicture(art, Modifier.fillMaxSize()) }
        }
    }

    // Chips and buttons are a little see-through, so the glass reads under them; a narrow drawer stacks each row's control under its text.
    val stack = xmbInGameDrawerStacksRows(widthDp)
    CompositionLocalProvider(
        LocalIgRaisedAlpha provides 0.6f,
        LocalIgCompact provides (LocalIgCompact.current || stack),
    ) {
        Column(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .focusProperties { canFocus = false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(start = xmbFrame()),
        ) {
            // The icon on the left, the logo in the middle (the name on the left when there is no logo), the status on the right.
            val hasIcon = game != null && LabArt.pick(game, "igicon") != "none"
            Box(Modifier.fillMaxWidth().height(60.dp).padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                val name: @Composable () -> Unit = {
                    Text(
                        title.ifEmpty { L("In game") }, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold,
                        fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = if (hasIcon && game != null) 52.dp else 0.dp).fillMaxWidth(0.62f),
                    )
                }
                if (hasIcon && game != null) LabInGameIcon(game, Modifier.align(Alignment.CenterStart).size(42.dp))
                if (logo.isEmpty()) name()
                else LabPicture(
                    logo, Modifier.align(Alignment.Center).fillMaxWidth(0.46f).fillMaxHeight(),
                    contentScale = ContentScale.Fit, alignment = Alignment.Center, onNone = name,
                )
                Box(Modifier.align(Alignment.CenterEnd)) { DeviceStatus() }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.padding(horizontal = 16.dp).width(32.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(cs.primary))
            Spacer(Modifier.height(10.dp))

            // The pages: Quick, then the full menu's, as icons on a glass strip between the bumper glyphs (the open one tinted; its
            // name is the page title just below).
            val stripShape = RoundedCornerShape(18.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                DeckGlyph("L1", GlyphKind.BUMPER)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .xmbGlassCard(stripShape)
                        .border(1.dp, deckLine(), stripShape)
                        .horizontalScroll(rememberScrollState())
                        .padding(5.dp),
                ) {
                    XmbDrawerTabChip(Icons.Outlined.Bolt, L("Quick"), quick, stripFocus, pageFocus) { DeckInGameState.selectLabQuick() }
                    tabs.forEach { t ->
                        XmbDrawerTabChip(menuTabIcon(t), menuTabTitle(t), !quick && t == shown, stripFocus, pageFocus) { DeckInGameState.selectMenuTab(t) }
                    }
                }
                DeckGlyph("R1", GlyphKind.BUMPER)
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(glass.frost.line))

            val pageTitle = if (quick) L("Quick") else menuTabTitle(shown)
            val pageDesc = if (quick) L("The settings changed most during a game.") else menuTabDesc(shown, kind)
            val pageHeader: @Composable () -> Unit = {
                Text(pageTitle, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
                Text(pageDesc, color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 12.5.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(4.dp))
            }
            if (!quick && shown == MenuTab.FRIENDS) {
                // The roster owns its scrolling (a LazyColumn), so it gets the space without a scroll wrapper.
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).focusRequester(pageFocus)) {
                    pageHeader()
                    Spacer(Modifier.height(8.dp))
                    IgHostedCard { FriendsContent(state) }
                }
            } else {
                // Each page starts at its top rather than at the previous page's scroll offset.
                val scroll = key(quick, shown) { rememberScrollState() }
                Column(Modifier.weight(1f).fillMaxWidth().igPageScroll(scroll).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    pageHeader()
                    // Inside the scroll container, so the request lands on the page's first control, not the scroll group.
                    Column(Modifier.fillMaxWidth().focusRequester(pageFocus)) {
                        if (quick) QuickControls(kind)
                        else when (shown) {
                            MenuTab.SCREEN -> ScreenPage(state)
                            MenuTab.PERFORMANCE -> PerformancePage(state)
                            MenuTab.EFFECTS -> EffectsPage(state, kind)
                            MenuTab.OVERLAY -> OverlayPage(state)
                            MenuTab.CONTROLS -> ControlsPage(state, kind)
                            MenuTab.AUDIO -> AudioPage(state)
                            MenuTab.STEAM -> SteamPage(state)
                            MenuTab.TV -> TvPage(state)
                            MenuTab.MODS -> ModsPage(state, mods)
                            MenuTab.TOOLS -> ToolsPage(state, kind)
                            MenuTab.FRIENDS -> Unit
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            // Footer: the XMB bar (a veil of the glass with a hairline above it), the pad hints, Pause / Resume and Exit (which asks first).
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(glass.frost.bar)
                    .drawBehind { drawLine(glass.frost.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                ) {
                    DeckFloatingHint(
                        "X", GlyphKind.Y, if (quick) L("Pages") else L("Quick"),
                        { if (quick) DeckInGameState.selectMenuTab(shown) else DeckInGameState.selectLabQuick() },
                        compact = true,
                    )
                    DeckFloatingHint("B", GlyphKind.Y, L("Back"), { DeckInGameState.close() }, compact = true)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                ) {
                    IgButton(if (isPaused) L("Resume") else L("Pause"), if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, ::pauseAndClose, modifier = Modifier.weight(1f))
                    IgButton(L("Exit game"), Icons.AutoMirrored.Outlined.Logout, onExit, danger = true, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    // The glass edge over everything, so the art and the footer bar never cover it.
    Box(Modifier.matchParentSize().border(1.dp, glass.frost.line, shape))
}

/**
 * One page chip of the drawer's strip: the page's icon alone, tinted accent while it is the open page. Focus is the XMB's
 * gliding outline with the icon turning accent under it, and D-pad down goes into the page.
 */
@Composable
private fun XmbDrawerTabChip(
    icon: ImageVector,
    label: String,
    sel: Boolean,
    stripFocus: FocusRequester,
    pageFocus: FocusRequester,
    onPick: () -> Unit,
) {
    val shape = deckShape(12.dp)
    DeckInk { item, cs ->
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .then(if (sel) Modifier.focusRequester(stripFocus) else Modifier)
                .focusProperties { down = pageFocus }
                .deckFocusRingInk(shape, item, scaleTo = 1f)
                .clip(shape)
                .background(if (sel) cs.primary.copy(alpha = 0.22f) else Color.Transparent)
                .clickable(onClick = onPick)
                .size(width = 44.dp, height = 40.dp),
        ) {
            Icon(icon, contentDescription = label, tint = if (sel) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}
