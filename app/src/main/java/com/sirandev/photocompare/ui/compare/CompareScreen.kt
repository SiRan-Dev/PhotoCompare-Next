package com.sirandev.photocompare.ui.compare

import android.view.TextureView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.sirandev.photocompare.R
import com.sirandev.photocompare.data.ImageBean
import com.sirandev.photocompare.domain.CompareMediator
import com.sirandev.photocompare.domain.Dim
import com.sirandev.photocompare.domain.ExifSummary
import com.sirandev.photocompare.domain.ImageMeta
import com.sirandev.photocompare.domain.PanZoom
import com.sirandev.photocompare.livephoto.ContentLivePhotoResolver
import com.sirandev.photocompare.livephoto.LivePhotoInfo
import com.sirandev.photocompare.livephoto.LivePhotoPlayerController
import com.sirandev.photocompare.livephoto.VideoExtractor
import com.sirandev.photocompare.ui.AppRoutes
import com.sirandev.photocompare.ui.compare.zoomable.ZoomableState
import com.sirandev.photocompare.ui.compare.zoomable.zoomable
import com.sirandev.photocompare.ui.session.SessionViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val MAX_DECODED_PIXELS = 16_000_000
private const val VIEWPORT_LOAD_FACTOR = 2.5f

/**
 * The heart of the app: two vertically stacked, independently page-able image views whose
 * pan/zoom can be synchronized (with dimension compensation), ported from
 * CompareImagesActivity + PhotoViewMediator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    navController: NavController,
    topIndex: Int,
    bottomIndex: Int,
    sessionViewModel: SessionViewModel = viewModel(),
) {
    val images by sessionViewModel.images.collectAsState()
    val prefs by sessionViewModel.prefs.collectAsStateWithLifecycle()

    var showMenu by remember { mutableStateOf(false) }
    var showLoseSelectionDialog by remember { mutableStateOf(false) }

    val topBridge = remember { PaneBridgeImpl(PaneSide.TOP) }
    val bottomBridge = remember { PaneBridgeImpl(PaneSide.BOTTOM) }
    val mediator = remember(topBridge, bottomBridge) { CompareMediator(topBridge, bottomBridge) }
    mediator.syncZoomAndPan = prefs.syncZoomAndPan

    val context = LocalContext.current
    val livePhotoResolver = remember { ContentLivePhotoResolver(context) }
    val videoExtractor = remember { VideoExtractor(context) }
    val livePhotoPlayer = remember { LivePhotoPlayerController(context) }
    DisposableEffect(Unit) {
        onDispose { livePhotoPlayer.release() }
    }

    // Live Photo playback: a long-press on either pane plays BOTH panes' current photos
    val scope = rememberCoroutineScope()
    var playingSides by remember { mutableStateOf(setOf<PaneSide>()) }
    fun startLivePlayback() {
        val imgs = sessionViewModel.images.value
        PaneSide.entries.forEach { side ->
            val bridge = if (side == PaneSide.TOP) topBridge else bottomBridge
            val bean = imgs.getOrNull(bridge.activePage) ?: return@forEach
            scope.launch {
                val info = livePhotoResolver.resolve(bean)
                if (info !is LivePhotoInfo.NotLivePhoto) {
                    val file = videoExtractor.extract(bean, info)
                    if (file != null) {
                        livePhotoPlayer.play(side, file)
                        playingSides = playingSides + side
                    }
                }
            }
        }
    }
    fun stopLivePlayback() {
        livePhotoPlayer.stopAll()
        playingSides = emptySet()
    }

    LaunchedEffect(images.size) {
        if (images.isNotEmpty()) {
            mediator.listSize = images.size
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.action_compare_images)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (sessionViewModel.hasSelection) {
                            showLoseSelectionDialog = true
                        } else {
                            leaveCompare(sessionViewModel, mediator, navController)
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(android.R.string.cancel))
                    }
                },
                actions = {
                    Text(text = stringResource(R.string.sync_state), style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = prefs.syncZoomAndPan,
                        onCheckedChange = {
                            sessionViewModel.setSyncZoomAndPan(it)
                            // toggling sync mode resets both panes to fit so the two photos
                            // start from a clean, comparable state
                            mediator.resetState()
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = { mediator.resetState() }) {
                        Icon(Icons.Filled.CenterFocusStrong, contentDescription = stringResource(R.string.action_compare_images))
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.show_exif)) },
                            trailingIcon = { Checkbox(checked = prefs.showExifDetails, onCheckedChange = null) },
                            onClick = { sessionViewModel.setShowExifDetails(!prefs.showExifDetails) },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.checkbox_dark)) },
                            trailingIcon = { Checkbox(checked = prefs.checkboxStyleDark, onCheckedChange = null) },
                            onClick = { sessionViewModel.setCheckboxStyleDark(!prefs.checkboxStyleDark) },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.show_selection)) },
                            onClick = { navController.navigate(AppRoutes.SELECTED) },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ComparePane(
                side = PaneSide.TOP,
                bridge = topBridge,
                modifier = Modifier.weight(1f),
                images = images,
                initialIndex = topIndex,
                mediator = mediator,
                sessionViewModel = sessionViewModel,
                darkCheckbox = prefs.checkboxStyleDark,
                showExif = prefs.showExifDetails,
                livePhotoResolver = livePhotoResolver,
                livePhotoPlayer = livePhotoPlayer,
                playing = PaneSide.TOP in playingSides,
                onLiveStart = { startLivePlayback() },
                onLiveEnd = { stopLivePlayback() },
            )
            ComparePane(
                side = PaneSide.BOTTOM,
                bridge = bottomBridge,
                modifier = Modifier.weight(1f),
                images = images,
                initialIndex = bottomIndex,
                mediator = mediator,
                sessionViewModel = sessionViewModel,
                darkCheckbox = prefs.checkboxStyleDark,
                showExif = prefs.showExifDetails,
                livePhotoResolver = livePhotoResolver,
                livePhotoPlayer = livePhotoPlayer,
                playing = PaneSide.BOTTOM in playingSides,
                onLiveStart = { startLivePlayback() },
                onLiveEnd = { stopLivePlayback() },
            )
        }
    }

    if (showLoseSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showLoseSelectionDialog = false },
            title = { Text(text = stringResource(R.string.navigation_will_lose_selection_title)) },
            text = { Text(text = stringResource(R.string.navigation_will_lose_selection)) },
            confirmButton = {
                TextButton(onClick = {
                    showLoseSelectionDialog = false
                    leaveCompare(sessionViewModel, mediator, navController)
                }) { Text(text = stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showLoseSelectionDialog = false }) { Text(text = stringResource(android.R.string.cancel)) }
            },
        )
    }
}

private fun leaveCompare(sessionViewModel: SessionViewModel, mediator: CompareMediator, navController: NavController) {
    sessionViewModel.onReturnedFromCompare(mediator.getTopIndex(), mediator.getBottomIndex())
    navController.popBackStack()
}

/**
 * The exact graphics-layer transform used to draw the still image (scale relative to source
 * pixels, top-left origin, viewport-centering translations). Applied verbatim to the live
 * photo video overlay so motion frames land pixel-perfectly on top of the photo.
 */
private fun Modifier.matchStillImageTransform(zoom: ZoomableState): Modifier = graphicsLayer {
    transformOrigin = TransformOrigin(0f, 0f)
    val bs = zoom.bitmapScale
    scaleX = zoom.scale / bs
    scaleY = zoom.scale / bs
    val center = zoom.center
    if (center != null) {
        translationX = size.width / 2f - center.x * zoom.scale
        translationY = size.height / 2f - center.y * zoom.scale
    } else {
        // fit state: the drawn content spans srcSize × scale, centered in the viewport
        translationX = size.width / 2f - (zoom.srcSize.width * zoom.scale) / 2f
        translationY = size.height / 2f - (zoom.srcSize.height * zoom.scale) / 2f
    }
}

/** Per-pane bridge state held across recompositions. */
private class PaneBridgeImpl(
    override val side: PaneSide,
) : CompareMediator.PaneBridge {

    /** The zoom state of the currently settled page, registered by the pane composable. */
    var activeZoom: ZoomableState? = null

    var activePage: Int = 0

    override val currentIndex: Int
        get() = activePage

    override val sourceDimension: Dim?
        get() = activeZoom?.takeIf { it.isReady }?.srcSize

    override val panZoom: PanZoom?
        get() = activeZoom?.takeIf { it.isReady }?.currentState()

    override fun setPanAndZoom(target: PanZoom) {
        val zoom = activeZoom ?: return
        zoom.withListenersSuppressed {
            zoom.setScaleAndCenter(target.scale, target.center)
        }
    }

    override fun resetPanZoom() {
        val zoom = activeZoom ?: return
        zoom.withListenersSuppressed { zoom.reset() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComparePane(
    side: PaneSide,
    bridge: PaneBridgeImpl,
    modifier: Modifier,
    images: List<ImageBean>,
    initialIndex: Int,
    mediator: CompareMediator,
    sessionViewModel: SessionViewModel,
    darkCheckbox: Boolean,
    showExif: Boolean,
    livePhotoResolver: ContentLivePhotoResolver,
    livePhotoPlayer: LivePhotoPlayerController,
    playing: Boolean,
    onLiveStart: () -> Unit,
    onLiveEnd: () -> Unit,
) {
    if (images.isEmpty()) return
    val zoomStates = remember { mutableMapOf<Int, ZoomableState>() }
    val initial = remember(images) { deriveInitialIndex(initialIndex, images.size) }
    val pagerState = rememberPagerState(initialPage = initial.coerceIn(0, images.size - 1), pageCount = { images.size })
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // register the active page's zoom state with the mediator bridge
    val activePage by remember { derivedStateOf { pagerState.settledPage } }
    LaunchedEffect(activePage) {
        bridge.activePage = activePage
        bridge.activeZoom = zoomStates[activePage]
    }

    // page arbitration (mutual exclusion), mirroring PhotoViewMediator.onPageSelected
    var correcting by remember { mutableStateOf(false) }
    LaunchedEffect(mediator, images.size) {
        mediator.listSize = images.size
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (correcting) return@collect
                val accepted = mediator.onPageSelected(bridge, page)
                if (!accepted) {
                    val next = mediator.getNextValidIndex(bridge, page)
                    if (next != CompareMediator.NO_VALID_IMAGE_INDEX) {
                        correcting = true
                        try {
                            pagerState.animateScrollToPage(next)
                        } finally {
                            correcting = false
                        }
                    }
                }
            }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportPx = with(density) {
            max(maxWidth.toPx(), 1f) to max(maxHeight.toPx(), 1f)
        }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val bean = images[page]
            val zoom = remember(page, bean.contentUri) {
                zoomStates.getOrPut(page) {
                    ZoomableState().apply {
                        onStateChanged = { mediator.onPanOrZoomChanged(bridge) }
                    }
                }
            }
            var meta by remember(bean.contentUri) { mutableStateOf<ImageMeta?>(null) }
            val pageContext = LocalContext.current
            LaunchedEffect(bean.contentUri, showExif) {
                val loaded = ExifSummary.load(pageContext.contentResolver, bean.contentUri, bean.displayName, showExif)
                meta = loaded
                loaded?.let { zoom.onImageLoaded(it.width, it.height, it.rotationSwapped) }
            }
            // live photo badge: detect whether the CURRENT photo carries embedded motion
            var liveInfo by remember(bean.contentUri) { mutableStateOf<LivePhotoInfo?>(null) }
            LaunchedEffect(bean.contentUri) {
                liveInfo = livePhotoResolver.resolve(bean)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged { zoom.onLayout(it.width.toFloat(), it.height.toFloat()) },
            ) {
                val m = meta
                if (m != null) {
                    // Deterministic decode+layout: Coil resolves the decode from the COMPOSABLE
                    // constraints (request-level size/scale/precision are ignored), so control
                    // the constraints instead: requiredSize = srcSize × k (same aspect as the
                    // source) → no crop; Precision.EXACT pins the bitmap to exactly k × srcSize,
                    // making bitmapScale == k an exact invariant.
                    val k = decodeScale(m.width, m.height, viewportPx.first, viewportPx.second)
                    LaunchedEffect(bean.contentUri, k) {
                        zoom.bitmapScale = k
                    }
                    val contentW = with(density) { (m.width * k).roundToInt().coerceAtLeast(1).toFloat().toDp() }
                    val contentH = with(density) { (m.height * k).roundToInt().coerceAtLeast(1).toFloat().toDp() }
                    AsyncImage(
                        model = ImageRequest.Builder(pageContext)
                            .data(bean.contentUri)
                            .size(
                                (m.width * k).roundToInt().coerceAtLeast(1),
                                (m.height * k).roundToInt().coerceAtLeast(1),
                            )
                            .precision(coil3.size.Precision.EXACT)
                            .crossfade(false)
                            .build(),
                        contentDescription = bean.displayName,
                        contentScale = ContentScale.FillBounds,
                        alignment = Alignment.TopStart,
                        modifier = Modifier
                            .requiredSize(contentW, contentH)
                            .zoomable(
                                state = zoom,
                                onLongPressStart = { onLiveStart() },
                                onLongPressEnd = { onLiveEnd() },
                            )
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0f)
                                val bs = zoom.bitmapScale
                                scaleX = zoom.scale / bs
                                scaleY = zoom.scale / bs
                                val center = zoom.center
                                if (center != null) {
                                    translationX = size.width / 2f - center.x * zoom.scale
                                    translationY = size.height / 2f - center.y * zoom.scale
                                } else {
                                    // fit state: the drawn content spans srcSize × scale, centered in the viewport
                                    translationX = size.width / 2f - (zoom.srcSize.width * zoom.scale) / 2f
                                    translationY = size.height / 2f - (zoom.srcSize.height * zoom.scale) / 2f
                                }
                            },
                    )

                    // Live photo motion overlay: a TextureView bound straight to this pane's
                    // ExoPlayer and transformed EXACTLY like the still image above, so the
                    // motion lines up 1:1 with the photo. The view is transparent until the
                    // first video frame arrives, so buffering can never flash black.
                    if (playing && page == activePage) {
                        AndroidView(
                            factory = { ctx -> TextureView(ctx).apply { isOpaque = false } },
                            update = { view -> livePhotoPlayer.attachView(side, view) },
                            onRelease = { view -> livePhotoPlayer.detachView(side, view) },
                            modifier = Modifier
                                .requiredSize(contentW, contentH)
                                .matchStillImageTransform(zoom),
                        )
                    }
                } else {
                    // metadata still loading — placeholder keeps the pane stable
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }

                // live photo badge
                if (!playing && liveInfo != null && liveInfo !is LivePhotoInfo.NotLivePhoto) {
                    Icon(
                        imageVector = Icons.Filled.MotionPhotosOn,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                    )
                }

                // selection checkbox
                Checkbox(
                    checked = bean.selected,
                    onCheckedChange = { sessionViewModel.setSelected(page, it) },
                    colors = if (darkCheckbox) {
                        CheckboxDefaults.colors(
                            checkedColor = Color.White,
                            uncheckedColor = Color.White,
                            checkmarkColor = Color.Black,
                        )
                    } else {
                        CheckboxDefaults.colors()
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )

                // EXIF overlay
                val exifText = meta?.exifText
                if (showExif && exifText != null) {
                    Text(
                        text = exifText,
                        color = Color.White,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(4.dp),
                    )
                }
            }
        }

        // navigation arrows: short press ±1, long press jumps relative to the other pane's page
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.25f)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavArrow(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.action_nav_left),
                onClick = { scope.launch { step(pagerState, -1) } },
                onLongClick = { scope.launch { jumpRelativeOther(mediator, bridge, pagerState, -1) } },
            )
            NavArrow(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.action_nav_right),
                onClick = { scope.launch { step(pagerState, 1) } },
                onLongClick = { scope.launch { jumpRelativeOther(mediator, bridge, pagerState, 1) } },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavArrow(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White)
    }
}

private suspend fun step(pagerState: PagerState, delta: Int) {
    if (pagerState.pageCount == 0) return
    val target = (pagerState.settledPage + delta).coerceIn(0, pagerState.pageCount - 1)
    pagerState.animateScrollToPage(target)
}

private suspend fun jumpRelativeOther(mediator: CompareMediator, bridge: PaneBridgeImpl, pagerState: PagerState, delta: Int) {
    if (pagerState.pageCount == 0) return
    val otherIndex = if (bridge.side == PaneSide.TOP) mediator.getBottomIndex() else mediator.getTopIndex()
    val target = (otherIndex + delta).coerceIn(0, pagerState.pageCount - 1)
    pagerState.animateScrollToPage(target)
}

private fun deriveInitialIndex(initialIndex: Int, size: Int): Int {
    if (size == 0) return 0
    return if (initialIndex in 0 until size) initialIndex else size - 1
}

/**
 * Decode scale for one pane: ~[VIEWPORT_LOAD_FACTOR]× the viewport, capped at
 * [MAX_DECODED_PIXELS] and never upscaled. The request is built as srcSize × this factor
 * with Scale.FIT + Precision.EXACT, so the decoded bitmap is exactly this factor × srcSize.
 */
private fun decodeScale(srcW: Float, srcH: Float, viewportW: Float, viewportH: Float): Float {
    val srcPixels = srcW.toDouble() * srcH
    val target = minOf(
        viewportW * VIEWPORT_LOAD_FACTOR / srcW,
        viewportH * VIEWPORT_LOAD_FACTOR / srcH,
        sqrt(MAX_DECODED_PIXELS / srcPixels).toFloat(),
    )
    return target.coerceIn(0.02f, 1f)
}
