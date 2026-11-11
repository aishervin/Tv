package com.darktv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val themeColors = listOf(Color(0xFF7864F6), Color(0xFF347EF1), Color(0xFF00B6C9), Color(0xFFEB1D69), Color(0xFFFF7037))

class AppearanceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", 0)
    private val defaultMode = if (prefs.contains("light_theme")) {
        if (prefs.getBoolean("light_theme", false)) "light" else "dark"
    } else "system"
    var mode by mutableStateOf(prefs.getString("theme_mode", defaultMode) ?: defaultMode)
        private set
    // Old installations may still have the removed neon style saved.
    var style by mutableStateOf(prefs.getString("glass_style", "liquid").takeIf { it == "liquid" || it == "oled" } ?: "liquid")
        private set
    var colorIndex by mutableIntStateOf(prefs.getInt("theme_color", 0).coerceIn(0, themeColors.lastIndex))
        private set
    var fontPercent by mutableIntStateOf(prefs.getInt("font_percent", 100).coerceIn(80, 130))
        private set
    fun changeMode(value: String) { mode = value; prefs.edit().putString("theme_mode", value).apply() }
    fun changeStyle(value: String) { style = value; prefs.edit().putString("glass_style", value).apply() }
    fun setColor(value: Int) { colorIndex = value; prefs.edit().putInt("theme_color", value).apply() }
    fun setFont(value: Int) { fontPercent = value.coerceIn(80, 130); prefs.edit().putInt("font_percent", fontPercent).apply() }
}

class TelegramSettings(context: Context) {
    private val prefs = context.getSharedPreferences("telegram_settings", 0)
    var botToken by mutableStateOf(prefs.getString("bot_token", "") ?: "")
    var chatId by mutableStateOf(prefs.getString("chat_id", "") ?: "")

    fun save() {
        prefs.edit()
            .putString("bot_token", botToken)
            .putString("chat_id", chatId)
            .apply()
    }
}


@Composable
fun SettingsPopup(settings: AppearanceSettings, telegramSettings: TelegramSettings, english: Boolean, isTv: Boolean, updateStatus: UpdateStatus,
                  updateUnread: Boolean, openUpdates: Boolean, onPictureInPicture: (() -> Unit)?, onUpdateSeen: () -> Unit,
                  onCheckUpdate: () -> Unit, onLanguage: (Boolean) -> Unit, onClose: () -> Unit) {
    fun tr(fa: String, en: String) = if (english) en else fa
    val focus = remember { FocusRequester() }
    val bellFocus = remember { FocusRequester() }
    val checkFocus = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val light = LocalIsLight.current
    val backdropInteraction = remember { MutableInteractionSource() }
    val surfaceInteraction = remember { MutableInteractionSource() }
    var showUpdates by remember { mutableStateOf(openUpdates) }
    var showHelp by remember { mutableStateOf(false) }
    fun openUrl(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(context, tr("مرورگر در دسترس نیست", "No browser available"), Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) { delay(100); try { focus.requestFocus() } catch (_: IllegalStateException) {} }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().focusProperties { canFocus = false }.clickable(interactionSource = backdropInteraction, indication = null) { onClose() },
            contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.widthIn(max = minOf(configuration.screenWidthDp.dp * 0.9f, if (isTv) 420.dp else 380.dp))
                .fillMaxWidth().heightIn(max = configuration.screenHeightDp.dp * 0.9f)
                .focusProperties { canFocus = false }.clickable(interactionSource = surfaceInteraction, indication = null) { },
            shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))) {
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("تنظیمات", "Settings"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsIconButton(Icons.Default.HelpOutline, tr("راهنمای استفاده", "Help"), Color(0xFFBFC4CE)) {
                            showHelp = !showHelp; showUpdates = false; scope.launch { scrollState.animateScrollTo(0) }
                        }
                        Box {
                            SettingsIconButton(Icons.Default.Notifications, tr("به‌روزرسانی", "Updates"), Color(0xFFBFC4CE),
                                modifier = Modifier.focusRequester(bellFocus).then(if (showUpdates) Modifier.focusProperties { down = checkFocus } else Modifier)) {
                                showUpdates = !showUpdates; showHelp = false; if (showUpdates) onUpdateSeen(); scope.launch { scrollState.animateScrollTo(0) }
                            }
                            if (updateUnread) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(8.dp).background(Color(0xFFE53935), CircleShape))
                        }
                        SettingsIconButton(Icons.Default.Close, tr("بستن", "Close"), MaterialTheme.colorScheme.onSurface, onClick = onClose)
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showHelp) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(tr("راهنمای ریموت و علاقه‌مندی‌ها", "TV remote and favorites"), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(5.dp))
                                Text(tr(
                                    "• روی شبکه در موبایل یا دکمه تأیید ریموت روی شبکه نگه دارید تا به علاقه‌مندی اضافه یا از آن حذف شود. شبکه‌های نشان‌دار اول فهرست همه هستند.\n• در پخش: بالا شبکه‌ها، پایین کنترل پخش و چپ سرورها را باز می‌کند. جهت مخالف پنل شبکه یا پخش را می‌بندد.\n• تأیید ریموت یا لمس صفحه کنترل‌ها را نشان می‌دهد؛ Back پنل را می‌بندد. دکمه‌های Channel ± شبکه را عوض می‌کنند.",
                                    "• Long-press a channel on mobile or hold OK on the TV remote to add or remove a favorite. Favorites appear first in All.\n• During playback: Up opens channels, Down opens playback controls, and Left opens servers. The opposite direction closes the channel or playback panel.\n• Press OK or tap the video for controls; Back closes a panel. Channel ± changes channels."
                                ), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (showUpdates) {
                        val message = when (updateStatus) {
                            is UpdateStatus.Available -> tr("نسخه جدید ${updateStatus.version} در دسترس است", "Version ${updateStatus.version} is available")
                            UpdateStatus.Checking -> tr("در حال بررسی به‌روزرسانی…", "Checking for updates…")
                            UpdateStatus.NoRelease -> tr("هنوز ریلیزی در مخزن منتشر نشده است", "No release has been published yet")
                            UpdateStatus.UpToDate -> tr("برنامه به‌روز است", "Your app is up to date")
                            UpdateStatus.Unavailable -> tr("بررسی ناموفق بود؛ اتصال را بررسی کنید", "Could not check; check your connection")
                        }
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    var checkFocused by remember { mutableStateOf(false) }
                                    TextButton(onClick = { if (updateStatus !is UpdateStatus.Checking) onCheckUpdate() },
                                        modifier = Modifier.focusRequester(checkFocus).focusProperties { up = bellFocus; down = focus }
                                            .onFocusChanged { checkFocused = it.isFocused }
                                            .border(if (checkFocused) 2.dp else 0.dp, if (checkFocused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(50))) { Text(tr("بررسی", "Check"), fontSize = 12.sp) }
                                    if (updateStatus is UpdateStatus.Available) TextButton(onClick = { openUrl(updateStatus.url) }) { Text(tr("دانلود", "Download"), fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                    if (onPictureInPicture != null) {
                        TextButton(onClick = onPictureInPicture) {
                            Icon(Icons.Default.PictureInPictureAlt, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("پخش شناور", "Picture-in-picture"), fontSize = 12.sp)
                        }
                    }
                    SectionLabel(tr("تم برنامه", "App theme"))
                    PillSelector(listOf("dark" to tr("تیره", "Dark"), "light" to tr("روشن", "Light"), "system" to tr("سیستم", "System")), settings.mode, settings::changeMode, focus)
                    SectionLabel(tr("استایل", "Style"))
                    PillSelector(if (light) listOf("liquid" to "Simple") else listOf("liquid" to "Simple", "oled" to "OLED"),
                        if (light) "liquid" else settings.style, settings::changeStyle)
                    SectionLabel(tr("رنگ تم", "Theme color"))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                        val colorNames = if (english) listOf("Purple", "Blue", "Turquoise", "Pink", "Orange")
                            else listOf("بنفش", "آبی", "فیروزه‌ای", "صورتی", "نارنجی")
                        themeColors.forEachIndexed { index, color ->
                            var focused by remember { mutableStateOf(false) }
                            Surface(onClick = { settings.setColor(index) }, shape = CircleShape, color = color,
                                modifier = Modifier.size(32.dp).semantics { contentDescription = colorNames[index] }.onFocusChanged { focused = it.isFocused }
                                    .border(if (settings.colorIndex == index || focused) 2.dp else 0.dp,
                                        if (focused) MaterialTheme.colorScheme.onSurface else Color.White, CircleShape)) { }
                        }
                    }
                    SectionLabel(tr("زبان", "Language"))
                    PillSelector(listOf("fa" to "فارسی", "en" to "English"), if (english) "en" else "fa", { onLanguage(it == "en") })
                    SectionLabel(tr("اندازه فونت", "Font size"))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        var minusFocused by remember { mutableStateOf(false) }
                        var plusFocused by remember { mutableStateOf(false) }
                        FilledIconButton(onClick = { settings.setFont(settings.fontPercent - 10) }, modifier = Modifier.size(34.dp).onFocusChanged { minusFocused = it.isFocused }
                            .border(if (minusFocused) 2.dp else 0.dp, if (minusFocused) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD4D8E0), contentColor = Color(0xFF30333A))) { Icon(Icons.Default.Remove, tr("کوچک‌تر", "Smaller"), modifier = Modifier.size(20.dp)) }
                        Text("${settings.fontPercent}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 10.dp))
                        FilledIconButton(onClick = { settings.setFont(settings.fontPercent + 10) }, modifier = Modifier.size(34.dp).onFocusChanged { plusFocused = it.isFocused }
                            .border(if (plusFocused) 2.dp else 0.dp, if (plusFocused) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD4D8E0), contentColor = Color(0xFF30333A))) { Icon(Icons.Default.Add, tr("بزرگ‌تر", "Larger"), modifier = Modifier.size(20.dp)) }
                    }
                    SectionLabel(tr("تنظیمات استریم (RTMP)", "Streaming Settings (RTMP)"))
                    OutlinedTextField(value = telegramSettings.botToken, onValueChange = { telegramSettings.botToken = it; telegramSettings.save() }, label = { Text(tr("آدرس RTMP", "RTMP URL")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = telegramSettings.chatId, onValueChange = { telegramSettings.chatId = it; telegramSettings.save() }, label = { Text(tr("کلید استریم", "Stream Key")) }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { openUrl(UpdateChecker.REPOSITORY_URL) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(painterResource(R.drawable.ic_github), null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("صفحه دانلود در گیت‌هاب", "GitHub download page"), fontSize = 12.sp)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun SettingsIconButton(icon: ImageVector, label: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    IconButton(onClick = onClick, modifier = modifier.size(44.dp).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)) {
        Icon(icon, label, tint = tint)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun PillSelector(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    firstFocus: FocusRequester? = null
) {
    val shape = RoundedCornerShape(50)
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 240.dp).fillMaxWidth()
                .background(scheme.surfaceVariant.copy(alpha = 0.65f), shape)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            options.forEachIndexed { index, (key, label) ->
                var focused by remember { mutableStateOf(false) }
                val active = selected == key
                val fill by animateColorAsState(
                    if (active) scheme.primary else Color.Transparent,
                    animationSpec = tween(220), label = "pill fill"
                )
                Box(
                    Modifier.weight(1f).height(40.dp)
                        .then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .clip(shape)
                        .background(fill, shape)
                        .border(if (focused) 2.dp else 0.dp, if (focused) scheme.onSurface else Color.Transparent, shape)
                        .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(key) }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }
    }
}
