package com.darktv.player

import android.os.Bundle
import android.os.Build
import android.app.PictureInPictureParams
import android.util.Rational
import android.widget.Toast
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.annotation.OptIn
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.disk.DiskCache
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

private fun categoryLabel(key: String, english: Boolean): String {
    val labels = mapOf(
        "all" to ("همه" to "All"), "general" to ("عمومی" to "General"),
        "news" to ("خبری" to "News"), "movies" to ("فیلم و سریال" to "Movies & Series"),
        "music" to ("موسیقی" to "Music"), "entertainment" to ("سرگرمی" to "Entertainment"),
        "kids" to ("کودک" to "Kids"), "sports" to ("ورزشی" to "Sports"),
        "documentary" to ("مستند و فرهنگی" to "Documentary"),
        "lifestyle" to ("سبک زندگی" to "Lifestyle"), "religious" to ("مذهبی" to "Religious"),
        "education" to ("آموزشی" to "Education"), "other" to ("سایر" to "Other")
    )
    val label = labels[key] ?: labels.getValue("other")
    return if (english) label.second else label.first
}

class MainActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)
    private var pipEligible = false
    private var pipExitPending = false
    private var pipPlayer: Player? = null
    private var onPipClosed: (() -> Unit)? = null

    fun bindPipPlayer(player: Player?, onClosed: (() -> Unit)? = null) {
        pipPlayer = player
        onPipClosed = onClosed
    }

    private fun stopClosedPip() {
        pipPlayer?.pause()
        onPipClosed?.invoke()
    }

    fun setAutoPipEligible(eligible: Boolean) {
        if (pipEligible == eligible) return
        pipEligible = eligible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            try {
                setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setAutoEnterEnabled(eligible).build())
            } catch (_: IllegalStateException) { } catch (_: SecurityException) { }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R && pipEligible && !inPip) enterPlayerPip()
    }

    override fun onResume() {
        super.onResume()
        pipExitPending = false
    }

    override fun onStop() {
        super.onStop()
        if (pipExitPending && (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode)) {
            stopClosedPip()
            pipExitPending = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AppTheme(inPip) { enterPlayerPip() } }
    }

    private fun enterPlayerPip() {
        val english = getSharedPreferences("settings", 0).getBoolean("english", false)
        fun message(fa: String, en: String) = Toast.makeText(this, if (english) en else fa, Toast.LENGTH_SHORT).show()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            message("پخش شناور در این دستگاه پشتیبانی نمی‌شود", "Picture-in-Picture is not supported on this device")
            return
        }
        try {
            if (!enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())) {
                message("پخش شناور اجرا نشد", "Could not start Picture-in-Picture")
            }
        } catch (_: IllegalStateException) {
            message("پخش شناور اجرا نشد", "Could not start Picture-in-Picture")
        } catch (_: SecurityException) {
            message("اجازه پخش شناور داده نشده است", "Picture-in-Picture is not allowed")
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) pipExitPending = false
        else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            stopClosedPip()
            pipExitPending = false
        } else pipExitPending = true
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvApp(settings: AppearanceSettings, telegramSettings: TelegramSettings, inPictureInPicture: Boolean, onEnterPictureInPicture: () -> Unit) {
    val palette = LocalPalette.current
    val themeAccent = LocalAccent.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = remember(configuration.uiMode) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    }
    val canUsePip = !isTv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    val activity = context as? MainActivity
    val bundledChannels = remember { ChannelRepository.load(context) }
    var channels by remember { mutableStateOf(bundledChannels) }
    LaunchedEffect(bundledChannels) {
        val cached = withContext(Dispatchers.IO) { ApiCatalogue.loadCached(context, bundledChannels) }
        channels = cached
        val refreshed = withContext(Dispatchers.IO) {
            try { ApiCatalogue.refresh(context, bundledChannels) }
            catch (e: Exception) {
                android.util.Log.w("TvApp", "Could not refresh channels; using cached or bundled catalogue", e)
                null
            }
        }
        if (refreshed != null) channels = refreshed
    }
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    val telegramSettings = remember { TelegramSettings(context) }
    var english by remember { mutableStateOf(prefs.getBoolean("english", false)) }
    var showSettings by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Checking) }
    var updateCheck by remember { mutableIntStateOf(0) }
    var acknowledgedVersion by remember { mutableStateOf(prefs.getString("acknowledged_update", null)) }
    var openUpdates by remember { mutableStateOf(false) }
    val availableUpdate = updateStatus as? UpdateStatus.Available
    val updateUnread = availableUpdate != null && availableUpdate.version != acknowledgedVersion
    fun acknowledgeUpdate() {
        availableUpdate?.let { acknowledgedVersion = it.version; prefs.edit().putString("acknowledged_update", it.version).apply() }
    }
    val gridState = rememberLazyGridState()
    val homeScope = rememberCoroutineScope()
    val scrollTopFocus = remember { FocusRequester() }
    val homeSettingsFocus = remember { FocusRequester() }
    val homeUpdateFocus = remember { FocusRequester() }
    val playerSettingsFocus = remember { FocusRequester() }
    var settingsReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val visibleChannelFocus = remember { mutableMapOf<Int, FocusRequester>() }
    LaunchedEffect(updateCheck) {
        updateStatus = UpdateStatus.Checking
        val version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        updateStatus = withContext(Dispatchers.IO) { UpdateChecker.check(version) }
    }
    // Keep retrieved logos on disk between launches and do not revalidate every time.
    val imageLoader = remember(context) {
        ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }
            .diskCache { DiskCache.Builder().directory(context.filesDir.resolve("channel_logos")).maxSizeBytes(100L * 1024 * 1024).build() }
            .respectCacheHeaders(false).build()
    }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorite_channels", emptySet())?.toSet() ?: emptySet()) }
    fun toggleFavorite(ch: Channel) {
        favorites = if (ch.favoriteKey in favorites) favorites - ch.favoriteKey else favorites + ch.favoriteKey
        prefs.edit().putStringSet("favorite_channels", favorites).apply()
    }
    fun tr(fa: String, en: String) = if (english) en else fa
    var channel by remember { mutableStateOf(channels.first()) }
    // home, player, channels, controls, servers
    var panel by remember { mutableStateOf("home") }
    var hudVisible by remember { mutableStateOf(false) }
    var hudActivity by remember { mutableIntStateOf(0) }
    var source by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var failure by remember { mutableIntStateOf(0) }
    var buffering by remember { mutableStateOf(false) }
    var findingServer by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var streamingToTelegram by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var resize by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var query by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchActive) {
        if (searchActive) {
            delay(50)
            try { searchFocus.requestFocus(); keyboard?.show() } catch (_: IllegalStateException) { searchActive = false }
        }
    }
    var category by remember { mutableStateOf("all") }
    val availableCategories = remember(channels) {
        ChannelRepository.categoryOrder.filter { it == "all" || channels.any { ch -> it in ch.categories() } }
    }
    val focus = remember { FocusRequester() }
    val player = remember {
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(NetworkHelper.createDataSourceFactory())).build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) findingServer = false
                if (state == Player.STATE_ENDED) { findingServer = true; failure++ }
            }
            override fun onPlayerError(error: PlaybackException) { buffering = false; findingServer = true; failure++ }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        activity?.bindPipPlayer(player) { failure = 0; buffering = false; findingServer = false }
        onDispose { activity?.setAutoPipEligible(false); activity?.bindPipPlayer(null); player.removeListener(listener); player.release() }
    }
    SideEffect { activity?.setAutoPipEligible(canUsePip && panel != "home" && playing) }
    LaunchedEffect(channel.id, source, retry) {
        if (retry > 0) {
            val selected = channel.streamSources()[source]
            val mediaSource = DefaultMediaSourceFactory(NetworkHelper.createDataSourceFactory(selected.headers))
                .createMediaSource(MediaItem.fromUri(selected.url))
            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true
        }
    }
    // Cycle through every available server, then keep retrying with a bounded delay.
    LaunchedEffect(failure) {
        if (failure > 0) {
            delay(if (failure <= channel.sources().size) 1500 else 8000)
            source = (source + 1) % channel.sources().size
            retry++
        }
    }
    LaunchedEffect(buffering, channel.id, source, retry) {
        if (buffering) { delay(30000); findingServer = true; failure++ }
    }
    LaunchedEffect(inPictureInPicture) {
        if (inPictureInPicture) { showSettings = false; panel = "player"; hudVisible = false }
    }
    LaunchedEffect(panel, showSettings, inPictureInPicture) {
        // Restore remote focus after a panel or the settings dialog closes.
        if (!showSettings && !inPictureInPicture) {
            delay(100)
            val target = settingsReturnFocus
            settingsReturnFocus = null
            try { (target ?: focus).requestFocus() } catch (_: IllegalStateException) {
                try { focus.requestFocus() } catch (_: IllegalStateException) { }
            }
        }
    }
    LaunchedEffect(panel, hudActivity, channel.id, showSettings, inPictureInPicture) {
        if (!showSettings && !inPictureInPicture && (panel == "player" || panel == "controls" || panel == "servers") && hudVisible) {
            delay(5000)
            hudVisible = false
            if (panel == "controls" || panel == "servers") panel = "player"
        }
    }
    fun select(ch: Channel) { channel = ch; source = 0; failure = 0; findingServer = false; retry++; panel = "player"; hudVisible = true; hudActivity++ }
    BackHandler(enabled = panel != "home" && !showSettings && !inPictureInPicture) {
        if (panel == "player") { player.pause(); panel = "home" } else panel = "player"
    }
    Box(Modifier.fillMaxSize().background(palette.background).onPreviewKeyEvent { event ->
        if (inPictureInPicture || showSettings || event.type != KeyEventType.KeyDown) false else {
            if (panel != "home") { hudVisible = true; hudActivity++ }
            when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { select(channels[(channels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0) - 1 + channels.size) % channels.size]); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { select(channels[(channels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0) + 1) % channels.size]); true }
            KeyEvent.KEYCODE_DPAD_UP -> when (panel) {
                "player" -> { panel = "channels"; true }
                "controls" -> { panel = "player"; true }
                else -> false
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when (panel) {
                "player" -> { panel = "controls"; true }
                "channels" -> { panel = "player"; true }
                else -> false
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (panel == "player") { panel = "servers"; true } else false
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (panel == "player") { panel = "controls"; true } else false
            else -> false
            }
        }
    }) {
        if (panel != "home") {
            AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } },
                update = { it.resizeMode = resize }, modifier = Modifier.fillMaxSize().clickable { panel = "controls" })
            Box(Modifier.fillMaxSize()
                .then(if (panel == "player") Modifier.focusRequester(focus) else Modifier)
                .clickable { panel = if (panel == "player") "controls" else "player"; hudVisible = true; hudActivity++ })
            AnimatedVisibility(visible = hudVisible && !inPictureInPicture, enter = fadeIn(tween(350)), exit = fadeOut(tween(800)),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp)) {
                Row(Modifier.background(Color(0xA61A1924), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!channel.logoUrl.isNullOrBlank()) {
                        SubcomposeAsyncImage(model = channel.logoUrl, imageLoader = imageLoader, contentDescription = null,
                            modifier = Modifier.size(24.dp), contentScale = ContentScale.Fit)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (english) channel.codeName else channel.fullName, color = Color.White,
                        fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                    Spacer(Modifier.width(8.dp))
                    GlowDot(Color(0xFFFF4055), 12)
                    Text("LIVE", color = Color(0xFFFF4055), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            if ((buffering || findingServer) && !inPictureInPicture) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(30.dp).semantics {
                        contentDescription = if (english) "Finding an available server" else "در حال پیدا کردن سرور متصل"
                    }, color = themeAccent, strokeWidth = 3.dp
                )
            }
        }
        if (panel == "home") {
            Column(Modifier.fillMaxSize().padding(horizontal = if (isTv) 24.dp else 12.dp, vertical = if (isTv) 16.dp else 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.app_icon), contentDescription = "Dark TV", modifier = Modifier.size(42.dp))
                        Spacer(Modifier.width(8.dp))
                        Column { Text("DARK TV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = palette.text)
                            Text(tr("تلویزیون زنده، همه‌جا", "LIVE TV, EVERYWHERE"), color = palette.muted, style = MaterialTheme.typography.labelSmall) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (updateUnread) HomeIconButton(Icons.Default.Notifications, tr("به‌روزرسانی", "Updates"), Color(0xFFE53935), Modifier.focusRequester(homeUpdateFocus)) {
                            settingsReturnFocus = homeUpdateFocus; acknowledgeUpdate(); openUpdates = true; showSettings = true
                        }
                        HomeIconButton(Icons.Default.Search, tr("جستجو", "Search"), palette.text) { searchActive = true }
                        HomeIconButton(Icons.Default.Settings, tr("تنظیمات", "Settings"), palette.text, Modifier.focusRequester(homeSettingsFocus)) { settingsReturnFocus = homeSettingsFocus; openUpdates = false; showSettings = true }
                    }
                }
                AnimatedVisibility(visible = searchActive, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { IconButton(onClick = { query = ""; searchActive = false; keyboard?.hide() }) { Icon(Icons.Default.Close, tr("بستن", "Close")) } },
                        label = { Text(tr("جستجوی شبکه", "Search channels")) },
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus), shape = RoundedCornerShape(16.dp))
                }
                Spacer(Modifier.height(10.dp))
                val matching = channels.filter {
                    (category == "all" || category in it.categories()) &&
                        (it.fullName.contains(query, true) || it.codeName.contains(query, true))
                }
                val filtered = if (category == "all") orderFavorites(matching, favorites) else matching
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryRail(availableCategories, category, english, { category = it }, Modifier.fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text(categoryLabel(category, english) + "  ·  ${filtered.size}", color = palette.text,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        if (filtered.isEmpty()) Text(tr("شبکه‌ای پیدا نشد", "No channels found"), color = palette.muted, style = MaterialTheme.typography.bodySmall)
                        val showScrollTop = gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
                        @Composable fun ScrollTopButton() {
                            androidx.compose.animation.AnimatedVisibility(visible = showScrollTop,
                                enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                                var arrowFocused by remember { mutableStateOf(false) }
                                SmallFloatingActionButton(onClick = { homeScope.launch {
                                    gridState.animateScrollToItem(0)
                                    if (isTv) { delay(100); try { focus.requestFocus() } catch (_: IllegalStateException) {} }
                                } }, modifier = Modifier.focusRequester(scrollTopFocus).onPreviewKeyEvent { event ->
                                    if (isTv && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                                        val visible = gridState.layoutInfo.visibleItemsInfo
                                            .filter { it.offset.y < gridState.layoutInfo.viewportEndOffset && it.offset.y + it.size.height > gridState.layoutInfo.viewportStartOffset }
                                        val bottom = visible.maxByOrNull { it.index }
                                        try { bottom?.let { visibleChannelFocus[it.index]?.requestFocus() } } catch (_: IllegalStateException) { }
                                        true
                                    } else false
                                }.onFocusChanged { arrowFocused = it.isFocused }
                                    .border(if (arrowFocused) 3.dp else 0.dp, if (arrowFocused) MaterialTheme.colorScheme.onSurface else Color.Transparent, RoundedCornerShape(16.dp)),
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                                    Icon(Icons.Default.KeyboardArrowUp, tr("بازگشت به بالا", "Scroll to top"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxSize()) {
                                LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(if (isTv) 6 else 3),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    itemsIndexed(filtered, key = { _, ch -> ch.id }) { index, ch ->
                                        val itemFocus = remember(ch.id) { FocusRequester() }
                                        DisposableEffect(index, itemFocus) {
                                            visibleChannelFocus[index] = itemFocus
                                            onDispose { if (visibleChannelFocus[index] == itemFocus) visibleChannelFocus.remove(index) }
                                        }
                                        val direction = if (isTv) when (gridRightDestination(index, filtered.size, 6)) {
                                            GridRightDestination.ScrollTop -> Modifier.focusProperties { right = if (showScrollTop) scrollTopFocus else FocusRequester.Cancel }
                                            GridRightDestination.Block -> Modifier.focusProperties { right = FocusRequester.Cancel }
                                            GridRightDestination.NextChannel -> Modifier
                                        } else Modifier
                                        ChannelCard(ch, english, imageLoader, ch.favoriteKey in favorites, { toggleFavorite(ch) },
                                            (if (index == 0) Modifier.focusRequester(focus) else Modifier).focusRequester(itemFocus).then(direction)) { select(ch) }
                                    }
                                }
                                if (isTv) Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) { ScrollTopButton() }
                            }
                            if (!isTv) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { ScrollTopButton() }
                        }
                    }
                }
            }
        }
        if (panel == "channels") {
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp).glass(palette).padding(16.dp)) {
                Text(tr("انتخاب شبکه", "Choose a channel"), color = palette.text, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                LazyRow(state = rememberLazyListState(initialFirstVisibleItemIndex = channels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(channels, key = { it.id }) { ch ->
                        ChannelCard(ch, english, imageLoader, ch.favoriteKey in favorites, { toggleFavorite(ch) }, Modifier.width(130.dp).then(if (ch.id == channel.id) Modifier.focusRequester(focus) else Modifier)) { select(ch) }
                    }
                }
            }
        }
        AnimatedVisibility(visible = panel == "controls" && !inPictureInPicture, enter = fadeIn(tween(250)), exit = fadeOut(tween(800)),
            modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.padding(10.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(pass = PointerEventPass.Initial); hudVisible = true; hudActivity++ } }
                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(if (english) channel.codeName else channel.fullName, color = palette.text.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayerControlButton(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, tr("پخش", "Play"),
                        Modifier.then(if (panel == "controls") Modifier.focusRequester(focus) else Modifier)) { if (player.isPlaying) player.pause() else player.play() }
                    PlayerControlButton(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, tr("صدا", "Volume")) { muted = !muted; player.volume = if (muted) 0f else 1f }
                    PlayerControlButton(Icons.Default.AspectRatio, tr("اندازه تصویر", "Aspect ratio")) { resize = if (resize == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT }
                    PlayerControlButton(Icons.Default.Refresh, tr("تلاش دوباره", "Retry")) { retry++ }
                    PlayerControlButton(Icons.Default.List, tr("شبکه‌ها", "Channels")) { panel = "channels" }
                    PlayerControlButton(Icons.Default.Dns, tr("سرورها", "Servers")) { panel = "servers" }
                    PlayerControlButton(if (streamingToTelegram) Icons.Default.Stop else Icons.Default.Send,
                        if (streamingToTelegram) tr("توقف استریم", "Stop Stream") else tr("شروع استریم", "Start Stream")) {
                        if (streamingToTelegram) {
                            FFmpegKit.cancel()
                            streamingToTelegram = false
                            Toast.makeText(context, tr("استریم متوقف شد", "Stream stopped"), Toast.LENGTH_SHORT).show()
                        } else {
                            val server = telegramSettings.rtmpServerUrl.trim().trimEnd('/')
                            val key = telegramSettings.streamKey.trim().trimStart('/')
                            if (!(server.startsWith("rtmp://", true) || server.startsWith("rtmps://", true)) || key.isBlank()) {
                                Toast.makeText(context, tr("آدرس RTMP و کلید استریم را در تنظیمات وارد کن", "Enter the RTMP server URL and stream key in Settings"), Toast.LENGTH_LONG).show()
                            } else {
                                val destination = "$server/$key"
                                fun quoteArg(value: String) = "'" + value.replace("'", "'\\''") + "'"
                                val selected = channel.streamSources()[source]
                                val headers = selected.headers.toMutableMap()
                                if (selected.url.contains("46.245.122.7")) {
                                    headers.putIfAbsent("Referer", "https://mytvsat.tv/")
                                    headers.putIfAbsent("Origin", "https://mytvsat.tv")
                                }
                                val headerLines = headers.entries.joinToString("") { "${it.key}: ${it.value}\\r\\n" }
                                val headerOption = if (headerLines.isNotEmpty()) "-headers ${quoteArg(headerLines)} " else ""
                                val command = "${headerOption}-i ${quoteArg(selected.url)} -map 0:v:0 -map 0:a:0? -c copy -f flv ${quoteArg(destination)}"
                                streamingToTelegram = true
                                try {
                                    FFmpegKit.executeAsync(command) { session ->
                                        val success = ReturnCode.isSuccess(session.returnCode)
                                        homeScope.launch(Dispatchers.Main) {
                                            streamingToTelegram = false
                                            Toast.makeText(context,
                                                if (success) tr("استریم پایان یافت", "Stream finished") else tr("استریم ناموفق بود؛ آدرس یا سازگاری کانال را بررسی کن", "Stream failed; check the RTMP details or channel compatibility"),
                                                Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } catch (_: Exception) {
                                    streamingToTelegram = false
                                    Toast.makeText(context, tr("شروع استریم ناموفق بود", "Could not start stream"), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    if (canUsePip) PlayerControlButton(Icons.Default.PictureInPictureAlt, tr("پخش شناور", "Picture-in-picture")) { onEnterPictureInPicture() }
                    PlayerControlButton(Icons.Default.Settings, tr("تنظیمات", "Settings"), Modifier.focusRequester(playerSettingsFocus)) {
                        settingsReturnFocus = playerSettingsFocus; openUpdates = false; showSettings = true
                    }
                }
            }
        }
        if (panel == "servers" && !inPictureInPicture) {
            Column(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(150.dp).padding(8.dp)
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(pass = PointerEventPass.Initial); hudVisible = true; hudActivity++ } }
                .verticalScroll(rememberScrollState()).padding(8.dp)) {
                Text(tr("سرورهای پخش", "Playback servers"), color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(10.dp)).padding(6.dp))
                Spacer(Modifier.height(12.dp))
                channel.streamSources().forEachIndexed { index, server ->
                    var serverFocused by remember { mutableStateOf(false) }
                    Surface(onClick = { failure = 0; findingServer = true; source = index; retry++; panel = "player" },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).then(if (index == source) Modifier.focusRequester(focus) else Modifier)
                            .onFocusChanged { serverFocused = it.isFocused }
                            .border(if (serverFocused) 3.dp else if (index == source) 2.dp else 1.dp,
                                if (serverFocused || index == source) themeAccent else palette.glassBorder, RoundedCornerShape(10.dp)),
                        color = if (index == source) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp)) {
                        Text((server.title ?: (tr("سرور", "Server") + " ${index + 1}")) + if (index == source) " ✓" else "",
                            color = palette.text, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp))
                    }
                }
            }
        }
    }
    if (showSettings && !inPictureInPicture) SettingsPopup(settings, telegramSettings, english, isTv, updateStatus, updateUnread, openUpdates,
        onPictureInPicture = if (canUsePip && panel != "home") { { showSettings = false; panel = "player"; hudVisible = false; onEnterPictureInPicture() } } else null,
        onUpdateSeen = { acknowledgeUpdate() }, onCheckUpdate = { updateCheck++ }, onLanguage = {
        english = it
        prefs.edit().putBoolean("english", it).apply()
    }, onClose = { showSettings = false })
}

@Composable
private fun PlayerControlButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = LocalPalette.current.text.copy(alpha = if (focused || pressed) 1f else 0.6f)
    IconButton(onClick = onClick, interactionSource = interaction,
        modifier = modifier.size(40.dp).onFocusChanged { focused = it.isFocused }) {
        Icon(icon, label, tint = tint)
    }
}

@Composable
private fun HomeIconButton(icon: ImageVector, label: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    IconButton(onClick = onClick, modifier = modifier.onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 0.dp, if (focused) LocalAccent.current else Color.Transparent, CircleShape)) {
        Icon(icon, label, tint = tint)
    }
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "all" -> Icons.Default.Apps
    "general" -> Icons.Default.Tv
    "news" -> Icons.Default.Article
    "movies" -> Icons.Default.Movie
    "music" -> Icons.Default.MusicNote
    "entertainment" -> Icons.Default.Star
    "kids" -> Icons.Default.ChildCare
    "sports" -> Icons.Default.SportsSoccer
    "documentary" -> Icons.Default.Explore
    "lifestyle" -> Icons.Default.Favorite
    "religious" -> Icons.Default.AccountBalance
    "education" -> Icons.Default.School
    else -> Icons.Default.MoreHoriz
}

@Composable
private fun CategoryRail(categories: List<String>, selected: String, english: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val highlight = LocalAccent.current
    val simple = LocalIsSimple.current
    val light = LocalIsLight.current
    LazyColumn(modifier.width(56.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        items(categories, key = { it }) { key ->
            var focused by remember { mutableStateOf(false) }
            val active = key == selected
            val categoryFill = if (active && simple) lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, if (light) 0.12f else 0.80f)
                else if (active) MaterialTheme.colorScheme.secondaryContainer else palette.background
            val categoryText = if (active && simple && !light) MaterialTheme.colorScheme.surface else palette.text
            val darkBorder = lerp(highlight, Color.Black, 0.25f)
            val brighterBorder = lerp(highlight, Color.White, 0.30f)
            val contrast = (maxOf(darkBorder.luminance(), categoryFill.luminance()) + 0.05f) /
                (minOf(darkBorder.luminance(), categoryFill.luminance()) + 0.05f)
            val selectedBorder = if (contrast < 3f && categoryFill.luminance() < 0.2f) brighterBorder else darkBorder
            Box(Modifier.fillMaxWidth().background(
                if (active && simple) Brush.radialGradient(listOf(highlight.copy(alpha = 0.65f), highlight.copy(alpha = 0.25f), Color.Transparent))
                else Brush.radialGradient(listOf(Color.Transparent, Color.Transparent)), RoundedCornerShape(18.dp)).padding(3.dp)) {
            Surface(onClick = { onSelect(key) }, shape = RoundedCornerShape(16.dp),
                color = if (active) categoryFill else Color.Transparent,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                    .border(if (focused) 3.dp else if (active) 2.dp else 0.dp,
                        if (focused) highlight else if (active) selectedBorder else Color.Transparent, RoundedCornerShape(16.dp))
                    .semantics { contentDescription = categoryLabel(key, english) }) {
                Column(Modifier.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(categoryIcon(key), null, modifier = Modifier.size(22.dp), tint = if (active && simple) categoryText else if (active) MaterialTheme.colorScheme.onSecondaryContainer else palette.muted)
                    if (active || focused) Text(categoryLabel(key, english), fontSize = 8.sp, lineHeight = 10.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = categoryText)
                }
            }
            }
        }
    }
}

@Composable
private fun GlowDot(color: Color, size: Int = 16) {
    val transition = rememberInfiniteTransition(label = "glow")
    val pulse by transition.animateFloat(initialValue = 0.22f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "glow alpha")
    Box(Modifier.size(size.dp).background(Brush.radialGradient(listOf(color.copy(alpha = pulse), Color.Transparent)), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size((size * 0.32f).dp).background(color, CircleShape))
    }
}

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(channel: Channel, english: Boolean, imageLoader: ImageLoader, favorite: Boolean,
                        onToggleFavorite: () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var keyHeld by remember { mutableStateOf(false) }
    var longHandled by remember { mutableStateOf(false) }
    var longJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    val themeAccent = LocalAccent.current
    val name = if (english) channel.codeName else channel.fullName
    Surface(modifier = modifier.drawBehind {
        val spread = 3.dp.toPx()
        drawRoundRect(
            brush = Brush.radialGradient(listOf(themeAccent.copy(alpha = 0.28f), themeAccent.copy(alpha = 0.18f), Color.Transparent),
                center = center, radius = maxOf(size.width, size.height) * 0.8f),
            topLeft = Offset(-spread, -spread), size = Size(size.width + spread * 2, size.height + spread * 2),
            cornerRadius = CornerRadius(19.dp.toPx()))
    }.onFocusChanged {
        focused = it.isFocused
        if (!it.isFocused) { longJob?.cancel(); keyHeld = false }
    }.onPreviewKeyEvent { event ->
        val code = event.nativeKeyEvent.keyCode
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) false
        else if (event.type == KeyEventType.KeyDown) {
            if (!keyHeld) {
                keyHeld = true; longHandled = false
                longJob = scope.launch { delay(650); longHandled = true; onToggleFavorite() }
            }
            true
        } else if (event.type == KeyEventType.KeyUp && keyHeld) {
            longJob?.cancel(); keyHeld = false
            if (!longHandled) onClick()
            true
        } else false
    }.combinedClickable(onClickLabel = name, onLongClickLabel = if (english) "Toggle favorite" else "تغییر علاقه‌مندی",
        onClick = onClick, onLongClick = onToggleFavorite)
        .semantics { contentDescription = name }
        .border(if (focused) 2.dp else 1.dp, if (focused) themeAccent else palette.glassBorder.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
        color = if (focused) palette.focusedCard else palette.card, shape = RoundedCornerShape(16.dp)) {
        Box {
            Column(Modifier.fillMaxWidth().height(104.dp).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(54.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    @Composable fun Placeholder() { Icon(Icons.Default.LiveTv, contentDescription = null, tint = palette.muted, modifier = Modifier.size(28.dp)) }
                    if (channel.logoUrl.isNullOrBlank()) Placeholder() else
                        SubcomposeAsyncImage(model = channel.logoUrl, imageLoader = imageLoader,
                            contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit,
                            loading = { Placeholder() }, error = { Placeholder() })
                }
                Spacer(Modifier.height(4.dp))
                Text(name, color = palette.text, fontSize = 10.sp, textAlign = TextAlign.Center,
                    maxLines = 2, lineHeight = 14.sp, overflow = TextOverflow.Ellipsis)
            }
            if (favorite) Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) { GlowDot(themeAccent) }
        }
    }
}
