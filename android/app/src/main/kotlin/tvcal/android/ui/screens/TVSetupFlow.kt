package tvcal.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import tvcal.android.LocalApp
import tvcal.android.storage.Brand
import tvcal.android.storage.Multipoint
import tvcal.android.storage.PanelType
import tvcal.android.storage.TVRecord
import tvcal.android.ui.components.ColourMenuArt
import tvcal.android.ui.components.FullScreenSheet
import tvcal.android.ui.components.NavBar
import tvcal.android.ui.components.NavLeading
import tvcal.android.ui.components.PinnedAction
import tvcal.android.ui.components.PinnedActionHost
import tvcal.android.ui.components.ScreenColumn
import tvcal.android.ui.components.ScreenScaffold
import tvcal.android.ui.components.ShrinkToFitText
import tvcal.android.ui.components.WalkArtPanel
import tvcal.android.ui.components.WalkPage
import tvcal.android.ui.components.Walkthrough
import tvcal.android.ui.components.WhiteBalanceArt
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.lineSpacing
import tvcal.android.ui.theme.secondary
import tvcal.kit.ColourMenu

// Port of Views/TVSetupFlow.swift: add (or edit) a TV one page at a time, the choice tiles, and the
// MenuQuestionCard that asks later about a menu the user wasn't sure of.

/**
 * Add ([tv] null) or edit a TV as a FullScreenSheet: PinnedActionHost + NavBar "Add a Screen" / "Screen
 * settings" with a close X ("Cancel"), a 4-page Walkthrough (section "Screen details", finish "Add this
 * screen" / "Save", page 0 needs a non-blank name): name (BasicTextField, words capitalisation, teal border while focused;
 * dragging the page down hides the keyboard) + brand
 * grid (3 columns; picking a brand sets its multipoint/colour menu unless known), PanelChoice,
 * MultipointChoice, ColourMenuChoice. Save: app.model.add(t) then [onDismiss] then [onAdded](t), or
 * app.model.update(t) then [onDismiss]. New TVs start with the brand's defaults and "known" false.
 */
@Composable
fun TVSetupFlow(tv: TVRecord?, onDismiss: () -> Unit, onAdded: (TVRecord) -> Unit = {}) {
    val app = LocalApp.current
    // Swift load(): the TV's details, or a new TV with the default brand's menus.
    var name by rememberSaveable { mutableStateOf(tv?.name ?: "") }
    var brand by rememberSaveable { mutableStateOf(tv?.brand ?: Brand.LG) }
    var multipoint by rememberSaveable { mutableStateOf(tv?.multipoint ?: Brand.LG.multipoint) }
    var multipointKnown by rememberSaveable { mutableStateOf(tv?.multipointKnown ?: false) }
    var colourMenu by rememberSaveable { mutableStateOf(tv?.colourMenu ?: Brand.LG.colourMenu) }
    var colourMenuKnown by rememberSaveable { mutableStateOf(tv?.colourMenuKnown ?: false) }
    var panel by rememberSaveable { mutableStateOf(tv?.panel ?: PanelType.UNKNOWN) }

    // Swift trimmingCharacters(in: .whitespaces): the Unicode space separators and tab (not newlines).
    val trimmedName = name.trim { it == '\t' || it.category == CharCategory.SPACE_SEPARATOR }

    fun save() {
        val t = (tv ?: TVRecord(name = "")).copy(
            name = trimmedName,
            brand = brand,
            multipoint = multipoint,
            multipointKnown = multipointKnown,
            colourMenu = colourMenu,
            colourMenuKnown = colourMenuKnown,
            panel = panel,
        )
        if (tv == null) {
            app.model.add(t)
            onDismiss()
            onAdded(t)
        } else {
            app.model.update(t)
            onDismiss()
        }
    }

    val pages = listOf(
        WalkPage(
            title = "Which screen is this?",
            text = "You can change these details later.",
            extra = {
                NameAndBrand(
                    name = name,
                    onName = { name = it },
                    brand = brand,
                    onBrand = { b ->
                        brand = b
                        if (!multipointKnown) multipoint = b.multipoint
                        if (!colourMenuKnown) colourMenu = b.colourMenu
                    },
                )
            },
        ),
        WalkPage(
            title = "What kind of screen is it?",
            text = "The camera reads each kind differently. The model name or the box usually says.",
            extra = { PanelChoice(selection = panel, onSelect = { panel = it }) },
        ),
        WalkPage(
            title = "Its white balance menu",
            text = "On the TV, open ${brand.whiteBalancePath}",
            extra = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WalkArtPanel(height = 130.dp) { WhiteBalanceArt(points = multipoint.artPoints, modifier = Modifier.fillMaxSize()) }
                    MultipointChoice(brand = brand, selection = multipoint, known = multipointKnown, onChange = { m, k ->
                        multipoint = m
                        multipointKnown = k
                    })
                }
            },
        ),
        WalkPage(
            title = "Its colour menu",
            text = "On the TV, open ${brand.colourMenuPath}. Which controls can you see?",
            extra = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WalkArtPanel(height = 130.dp) { ColourMenuArt(menu = colourMenu, modifier = Modifier.fillMaxSize()) }
                    ColourMenuChoice(brand = brand, selection = colourMenu, known = colourMenuKnown, onChange = { c, k ->
                        colourMenu = c
                        colourMenuKnown = k
                    })
                }
            },
        ),
    )

    FullScreenSheet(onDismiss = onDismiss) {
        // Swift .scrollDismissesKeyboard(.interactively): dragging the page down puts the keyboard away.
        // (Read inside the sheet: the Dialog window has its own focus manager.)
        val focus = LocalFocusManager.current
        val dismissKeyboard = remember(focus) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y > 0f) focus.clearFocus()
                    return Offset.Zero
                }
            }
        }
        ScreenScaffold(title = null) {
            Column(Modifier.fillMaxSize()) {
                // The inline title with the cancellation X (Swift accessibilityLabel "Cancel").
                NavBar(
                    if (tv == null) "Add a Screen" else "Screen settings",
                    leading = NavLeading.CLOSE,
                    onLeading = onDismiss,
                    leadingLabel = "Cancel",
                )
                PinnedActionHost(Modifier.weight(1f).fillMaxWidth().nestedScroll(dismissKeyboard)) {
                    ScreenColumn {
                        Walkthrough(
                            pages = pages,
                            finishTitle = if (tv == null) "Add this screen" else "Save",
                            section = "Screen details",
                            canAdvance = { i -> i != 0 || trimmedName.isNotEmpty() },
                            onFinish = { save() },
                        )
                    }
                }
            }
        }
    }
}

/** The first page's controls: the screen name, the brand grid and the hint. */
@Composable
private fun NameAndBrand(name: String, onName: (String) -> Unit, brand: Brand, onBrand: (Brand) -> Unit) {
    val focus = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val nameFocused by interaction.collectIsFocusedAsState()
    val fieldShape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Screen name", style = Fonts.captionBrand, color = Color.White)
            // Swift TextField: 16 pt, 16 pt sides, at least 54 tall, on a card with a 1 pt hairline that
            // turns teal while focused; the prompt in the secondary colour.
            BasicTextField(
                value = name,
                onValueChange = onName,
                modifier = Modifier.fillMaxWidth(),
                textStyle = Fonts.brand(16f).copy(color = Color.White),
                singleLine = true,
                cursorBrush = SolidColor(Theme.tealBright),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                interactionSource = interaction,
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .background(Theme.card, fieldShape)
                            .border(1.dp, if (nameFocused) Theme.tealBright else Theme.hairline, fieldShape)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (name.isEmpty()) {
                            Text("e.g. Living room OLED", style = Fonts.brand(16f), color = Theme.textSecondary, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
        }
        Text("Brand", style = Fonts.captionBrand, color = Color.White)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            for (row in Brand.entries.chunked(3)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    for (b in row) BrandLabel(b, selected = b == brand, onClick = { onBrand(b) }, modifier = Modifier.weight(1f))
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Text("Choose the name you’ll recognise from the sofa.", style = Fonts.captionBrand.secondary())
    }
}

@Composable
private fun BrandLabel(b: Brand, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier
            .heightIn(min = 49.dp)
            .clip(shape)
            .background(if (selected) Theme.accent.copy(alpha = 0.1f) else Theme.card)
            .border(1.dp, if (selected) Theme.accent else Theme.hairline, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Swift .lineLimit(1).minimumScaleFactor(0.8).
        ShrinkToFitText(
            b.label,
            style = if (selected) Fonts.brand(14f, FontWeight.SemiBold) else Fonts.callout14,
            color = if (selected) Theme.accent else Color.White,
            minScale = 0.8f,
        )
    }
}

/** How many sliders the white-balance illustration shows: NONE 2, TEN 10, TWENTY 20. */
internal val Multipoint.artPoints: Int
    get() = when (this) {
        Multipoint.NONE -> 2
        Multipoint.TEN -> 10
        Multipoint.TWENTY -> 20
    }

/**
 * A big tappable option: an icon (28 dp, teal; yellow when selected), a title with optional [detail] and a
 * teal [note] ("Usual for LG"), and a radio. Selected: yellow 1.5 dp border on a 5% yellow tint.
 */
@Composable
fun ChoiceTile(
    title: String,
    selected: Boolean,
    art: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    note: String? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(if (selected) Theme.accent.copy(alpha = 0.05f) else Theme.card)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Theme.accent else Theme.hairline, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Icon(art, contentDescription = null, tint = if (selected) Theme.accent else Theme.tealBright, modifier = Modifier.size(25.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Fonts.bodyBrand, color = Color.White)
            if (detail != null) Text(detail, style = Fonts.small.secondary())
            if (note != null) Text(note, style = Fonts.small, color = Theme.tealBright)
        }
        if (selected) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(Theme.accent))
        } else {
            Box(Modifier.size(20.dp).border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape))
        }
    }
}

/** The kind of screen: every PanelType (questionmark icon for UNKNOWN, tv for the rest). */
@Composable
fun PanelChoice(selection: PanelType, onSelect: (PanelType) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (p in PanelType.entries) {
            ChoiceTile(
                title = p.label,
                selected = selection == p,
                art = if (p == PanelType.UNKNOWN) Symbols.questionCircle else Symbols.tv,
                onClick = { onSelect(p) },
                detail = p.detail,
            )
        }
    }
}

/**
 * "Only 2 points" / "10 points" / "20 points" (selected only when [known]) and "Not sure: ask me when we get
 * there" (selected when not known; picks brand.multipoint). [onChange] gets (selection, known).
 */
@Composable
fun MultipointChoice(
    brand: Brand,
    selection: Multipoint,
    known: Boolean,
    onChange: (Multipoint, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun title(m: Multipoint): String = when (m) {
        Multipoint.NONE -> "Only 2 points"
        Multipoint.TEN -> "10 points"
        Multipoint.TWENTY -> "20 points"
    }

    fun detail(m: Multipoint): String = when (m) {
        Multipoint.NONE -> "Gain and ${brand.offsetName}"
        Multipoint.TEN -> "10%, 20% … 100%"
        Multipoint.TWENTY -> "5%, 10% … 100%"
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (m in listOf(Multipoint.NONE, Multipoint.TEN, Multipoint.TWENTY)) {
            ChoiceTile(
                title = title(m),
                selected = known && selection == m,
                art = Symbols.sliders,
                onClick = { onChange(m, true) },
                detail = detail(m),
                note = if (m == brand.multipoint) "Usual for ${brand.label}" else null,
            )
        }
        ChoiceTile(
            title = "Not sure: ask me when we get there",
            selected = !known,
            art = Symbols.sliders,
            onClick = { onChange(brand.multipoint, false) },
        )
    }
}

/** brand.cmsMenuLabel / "Red / Green / Blue" / "No colour menu", and "Not sure". [onChange] gets (selection, known). */
@Composable
fun ColourMenuChoice(
    brand: Brand,
    selection: ColourMenu,
    known: Boolean,
    onChange: (ColourMenu, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun title(c: ColourMenu): String = when (c) {
        ColourMenu.HSL -> brand.cmsMenuLabel
        ColourMenu.RGB_SLIDERS -> "Red / Green / Blue"
        ColourMenu.OFF -> "No colour menu"
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (c in listOf(ColourMenu.HSL, ColourMenu.RGB_SLIDERS, ColourMenu.OFF)) {
            ChoiceTile(
                title = title(c),
                selected = known && selection == c,
                art = Symbols.sliders,
                onClick = { onChange(c, true) },
                note = if (c == brand.colourMenu) "Usual for ${brand.label}" else null,
            )
        }
        ChoiceTile(
            title = "Not sure",
            selected = !known,
            art = Symbols.sliders,
            onClick = { onChange(brand.colourMenu, false) },
        )
    }
}

/**
 * Asked at the step that needs it ([colours]: the colour menu, else the white balance menu) when the user
 * said "not sure". Declares PinnedAction("Continue", enabled = known): app.model.update(tv with the choice
 * and *Known = true). Starts from tv.multipoint / tv.colourMenu.
 */
@Composable
fun MenuQuestionCard(tv: TVRecord, colours: Boolean, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    var multipoint by remember(tv.id) { mutableStateOf(tv.multipoint) }
    var colourMenu by remember(tv.id) { mutableStateOf(tv.colourMenu) }
    var known by remember(tv.id, colours) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (colours) "First: your TV’s colour menu" else "First: your TV’s white balance menu",
            style = Fonts.stepTitleStyle,
        )
        Text(
            if (colours) "On the TV, open ${tv.brand.colourMenuPath}. Which sliders does each colour have?"
            else "On the TV, open ${tv.brand.whiteBalancePath} and look for a 10 or 20 point option. Which does it have?",
            style = Fonts.bodyBrand.secondary().lineSpacing(4f),
        )
        if (colours) {
            WalkArtPanel(height = 130.dp) { ColourMenuArt(menu = colourMenu, modifier = Modifier.fillMaxSize()) }
            ColourMenuChoice(brand = tv.brand, selection = colourMenu, known = known, onChange = { c, k ->
                colourMenu = c
                known = k
            })
        } else {
            WalkArtPanel(height = 130.dp) { WhiteBalanceArt(points = multipoint.artPoints, modifier = Modifier.fillMaxSize()) }
            MultipointChoice(brand = tv.brand, selection = multipoint, known = known, onChange = { m, k ->
                multipoint = m
                known = k
            })
        }
    }

    PinnedAction("Continue", enabled = known) {
        val t = if (colours) {
            tv.copy(colourMenu = colourMenu, colourMenuKnown = true)
        } else {
            tv.copy(multipoint = multipoint, multipointKnown = true)
        }
        app.model.update(t)
    }
}
