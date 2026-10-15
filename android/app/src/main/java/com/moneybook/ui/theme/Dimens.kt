package com.moneybook.ui.theme

import androidx.compose.ui.unit.dp

/** Layout sizes from docs/ui-spec.md ("Dimensions"), in dp on a 360 dp wide screen. */
object MbDimens {
    val topBar = 44.dp
    val iconTouch = 51.dp
    val monthArrow = 40.dp
    val monthArrowIcon = 28.dp
    val subTabRow = 40.dp
    val tabIndicator = 3.dp
    val summaryBar = 46.dp
    val accountsSummaryBar = 54.dp
    val dayHeader = 35.dp
    val txnRow = 50.dp
    val groupGap = 8.dp
    val sidePadding = 15.dp
    val amountColumn = 94.dp
    val fab = 56.dp
    val fabMargin = 16.dp
    val bottomNav = 54.dp
    val formRow = 43.dp
    val formLabelWidth = 61.dp
    val segmentHeight = 32.dp
    val panelHeader = 42.dp
    val keyHeight = 60.dp
    val currencyStrip = 41.dp
    val pickerCell = 60.dp
    val listRow = 46.dp
    val listTopGap = 10.dp
    val removeSlot = 60.dp
    /** The red box that slides in at the right of a row in Delete mode. */
    val trashBox = 60.dp
    val rowIcon = 50.dp
    val nameField = 39.dp
    val menuWidth = 136.dp
    val menuItem = 48.dp
    val calendarHeader = 16.dp
    val calendarCell = 78.dp
    val monthRow = 51.dp
    val monthRowCollapsed = 46.dp
    val weekRow = 46.dp
    val weekRowInset = 41.dp
    val monthlyColumn = 95.dp
    val accountGroupHeader = 54.dp
    /** A card row's Balance Payable and Outst. Balance columns (capture 32). */
    val cardColumn = 110.dp
    /** Account detail's "Statement" block (captures 33/34). */
    val statementBlock = 54.dp
    /** Card expenses display config's A / B buttons (capture 78). */
    val choiceButton = 45.dp
    val gridTileHeight = 105.dp
    val statsPieArea = 300.dp
    /** Realbyte's pie is this big when its labels leave room, and shrinks when they don't. */
    val pieMaxDiameter = 182.dp
    /** Realbyte's crowded pies are about this big; below it labels are cut instead. */
    val pieMinDiameter = 114.dp
    /** A label with less room than this beside the smallest pie is left out. */
    val pieLabelMinRoom = 40.dp
    val pieLeader = 30.dp
    /** Realbyte draws the pie right of the middle, leaving the left labels more room. */
    val pieCenterShift = 20.dp
    val pieLabelHeight = 38.dp
    val statsRow = 44.dp
    val percentChipWidth = 38.dp
    val percentChipHeight = 18.dp
    val chartAxisWidth = 40.dp
    /** Chart plot heights, from the top gridline to the bottom one, and the white above them. */
    val lineChartHeight = 137.dp
    val budgetChartHeight = 146.dp
    /** Budget detail's chart (budgetLineBar.html: marginLeft 44). */
    val budgetChartAxisWidth = 44.dp
    /** Realbyte's budget help block: a 40 dp ring 23 dp from the edges, at least 80 dp high; the Default Budget row is 50 dp. */
    val budgetHelpRing = 40.dp
    val budgetHelpSide = 23.dp
    val budgetHelpMin = 80.dp
    val budgetDefaultRow = 50.dp
    val barChartHeight = 197.dp
    val lineChartTop = 10.dp
    val barChartTop = 14.dp
    /** White between a plot and the axis line over its label table. */
    val chartAxisGap = 14.dp
    val barWidth = 10.dp
    /** Left inset of the big amount block on category detail and Total Stats. */
    val bigTotalInset = 21.dp
    /** Trans → Total's card and button inset. */
    val totalInset = 17.dp
    /** Trans → Total's budget bar (Realbyte cLBackgroundGraph 21 dp, corners 4 dp). */
    val budgetBarHeight = 21.dp
    val budgetBarRadius = 4.dp
    /** White under Trans → Total's export button. */
    val totalBottom = 56.dp
    val totalLine = 29.dp
    val filterBand = 48.dp
    val ring = 95.dp
    val ringStroke = 10.dp
    val filterRings = 145.dp
    val checkBox = 21.dp
    val memoColorBar = 4.dp
    val memoInset = 26.dp
    val paletteDot = 28.dp
    val searchField = 44.dp
    val searchRow = 54.dp
    val searchDateColumn = 88.dp
    val suggestionRow = 40.dp
    val repeatRow = 52.dp
    val installmentField = 72.dp
    /** Realbyte's option dialog: 91.6 % of the screen width, 7 dp corners, option rows of at least 46 dp. */
    const val choiceWidth = 0.916f
    val choiceCorner = 7.dp
    val choiceRow = 46.dp
    /** Realbyte's two-pane category picker row (panel_button_item_with_sub). */
    val subPickerRow = 50.dp
    /** Edit → Copy, measured on realbyte_34: an 80 % wide card, and OK on its own 44 dp card 12 dp below it. */
    const val copyWidth = 0.8f
    val copyOk = 44.dp
    val copyGap = 12.dp
    val choiceTitle = 58.dp // measured on realbyte_34 (title centre 30.5 dp below the card top)
    val radio = 26.dp
    val styleRow = 60.dp
    val passcodeKey = 72.dp
    val passcodeDot = 16.dp
    val sliderKnob = 26.dp
    // Photos in the entry form (reference/apk/notes-extras.md §2, layout input_display_layout.xml).
    /** Camera button at the end of Description: 42 dp wide, 18 dp glyph; Description keeps 50 dp end padding for it. */
    val photoCameraWidth = 42.dp
    /** A Material glyph fills about 79 % of its box: 23 dp draws Realbyte's 18 dp FontAwesome camera (measured on realbyte_34). */
    val photoCameraGlyph = 23.dp
    /** Realbyte's button sits 4 dp inside Description's 20 dp end margin (ours is 17 dp), and its glyph 3.5 dp lower than centred. */
    val photoCameraEnd = 7.dp
    /** The Camera / Gallery menu (popup_window_camera, measured on realbyte_34): 180 dp wide, 50 dp items, 20 dp text padding. */
    val photoMenuWidth = 180.dp
    val photoMenuItem = 50.dp
    val photoMenuPadding = 20.dp
    val photoCameraTop = 7.dp
    val photoCameraSpace = 50.dp
    /** The strip under Description: 136 dp high, 18.5 dp side margins, 15 dp above; each cell has 1.5 dp side margins and 3 dp corners. */
    val photoStripHeight = 136.dp
    val photoStripSide = 18.5.dp
    val photoStripTop = 15.dp
    val photoCellMargin = 1.5.dp
    val photoCorner = 3.dp
    /** The ✕ on a cell: 25 dp at its top right, 18 dp white glyph. */
    val photoRemove = 25.dp
    val photoRemoveGlyph = 18.dp
    // Photo viewer (notes-extras.md §2, photo_viewpager_main.xml) and the list icon (item_day_adapter_middle.xml).
    /** The viewer's top bar: 54 dp over the photo, white glyphs (Realbyte's 18 dp FontAwesome = a 25 dp Material box, measured on realbyte_34), 6 dp page dots with 4 dp margins each side. */
    val photoViewerBar = 54.dp
    val photoViewerGlyph = 25.dp
    val photoViewerDownload = 22.dp
    val photoViewerDot = 6.dp
    val photoViewerDotMargin = 4.dp
    /** The day row's photo icon: a 12.2 x 8.8 dp glyph (measured on realbyte_34), 2 dp after the account or under it. */
    val listPhotoIcon = 12.2.dp
}
