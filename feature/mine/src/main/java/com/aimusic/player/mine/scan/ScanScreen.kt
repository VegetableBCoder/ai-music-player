package com.aimusic.player.mine.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.data.scan.ScanPhase
import com.aimusic.player.storage.FileRef

/**
 * 文件扫描（`09 §3.2.9`）—— **有状态外壳**。
 *
 * 它只做三件与「外面」有关的事：取 ViewModel、把一次性事件变成副作用（权限弹窗 / snackbar）、
 * 把状态交给 [ScanScreenContent]。界面本身一行都不在这里 —— 那样 UI 测试才能不碰 DI
 * （`09 §8` 原先的示例是「传一个 fake ViewModel」，实测更省事的做法是把屏幕拆成
 * 有状态外壳 + 无状态内容，见 `09 §8` 的落地补充）。
 *
 * 三个入口按 QQ 音乐扫描页的形态（真机对照过）：
 * - **开始扫描**（主按钮）：没有来源时自动走「一键扫描」，不逼用户先选目录
 * - **自定义扫描**（底部左）：进应用内目录浏览器手动选目录
 * - **扫描设置**（底部右）：就地展开两条过滤规则，不做弹窗（少一个「关闭」按钮的文案问题，
 *   也少一层返回栈）
 *
 * 本期（Phase 3）不含「选择音乐来源」的独立选择器页面：一键扫描 + 目录浏览器已覆盖需求
 * `01 §2.4` 的两条路径。
 */
@Composable
fun ScanScreen(
    onRequestAllFilesAccess: () -> Unit,
    onRequestMediaPermission: () -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
    onNavigateToAnalysis: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.refreshPermission() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ScanEvent.RequestAllFilesPermission -> onRequestAllFilesAccess()
                ScanEvent.RequestMediaPermission -> onRequestMediaPermission()
                ScanEvent.NavigateToAnalysis -> onNavigateToAnalysis()
                is ScanEvent.ShowMessage -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    ScanScreenContent(
        state = state,
        onStartScan = viewModel::onStartScan,
        onCancelScan = viewModel::onCancelScan,
        onConfirmImport = viewModel::onConfirmImport,
        onDiscard = viewModel::onDiscard,
        onToggleSource = viewModel::onToggleSource,
        onToggleDurationRule = viewModel::onToggleDurationRule,
        onToggleSizeRule = viewModel::onToggleSizeRule,
        onRequestAllFilesAccess = onRequestAllFilesAccess,
        onPickDirectory = viewModel::onAddSource,
        listDirectories = viewModel::listDirectories,
        primaryRootPath = viewModel.primaryRootPath,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * 扫描页的**全部**渲染（无状态）。
 *
 * 所有依赖都从参数进来：给一个 [ScanUiState] 就画出对应界面，点按钮就走对应回调。
 * 回调大多有 `= {}` 默认值 —— 一条测试只传它真正要断言的那两三个。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScanScreenContent(
    state: ScanUiState,
    onStartScan: () -> Unit = {},
    onCancelScan: () -> Unit = {},
    onConfirmImport: () -> Unit = {},
    onDiscard: () -> Unit = {},
    onToggleSource: (Long, Boolean) -> Unit = { _, _ -> },
    onToggleDurationRule: (Boolean) -> Unit = {},
    onToggleSizeRule: (Boolean) -> Unit = {},
    onRequestAllFilesAccess: () -> Unit = {},
    onPickDirectory: (String) -> Unit = {},
    listDirectories: suspend (String) -> List<FileRef> = { emptyList() },
    primaryRootPath: String = "/",
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var showSettings by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("文件扫描") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ScanBottomBar(
                state = state,
                onStartScan = onStartScan,
                onCancelScan = onCancelScan,
                onConfirmImport = onConfirmImport,
                onDiscard = onDiscard,
                onCustomScan = { showPicker = true },
                onToggleSettings = { showSettings = !showSettings },
                onRequestAllFilesAccess = onRequestAllFilesAccess,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (showSettings) {
                ScanSettingsSection(
                    durationRuleOn = state.durationRuleOn,
                    sizeRuleOn = state.sizeRuleOn,
                    onToggleDuration = onToggleDurationRule,
                    onToggleSize = onToggleSizeRule,
                )
                HorizontalDivider()
            }

            when {
                !state.permissionGranted -> PermissionNotice(onRequestAllFilesAccess)
                state.scanning || state.phase == ScanPhase.COMMITTING -> ScanProgress(state)
                state.diff != null && state.diff.newCount > 0 -> DiffNotice(state)
                state.sources.isEmpty() -> EmptyNotice(
                    text = ErrorText.resolve("scan.need_source"),
                    actionLabel = "选择音乐来源",
                    onAction = { showPicker = true },
                )
                // 09 §5.1：这两条是**空状态**（文案 + 动作），不是一次性提示
                state.diff != null || state.emptyNotice == ScanEmptyNotice.NO_NEW_FILES -> EmptyNotice(
                    text = ErrorText.resolve("scan.diff.none"),
                    actionLabel = "重新选择来源",
                    onAction = { showPicker = true },
                )
                state.emptyNotice == ScanEmptyNotice.NO_MUSIC_FOUND -> EmptyNotice(
                    text = ErrorText.resolve("scan.empty"),
                    actionLabel = "重新选择来源",
                    onAction = { showPicker = true },
                )
                // ANALYZING：结果由一次性提示（snackbar）说明，这里不再重复一块状态文字
            }

            SourceList(state = state, onToggleSource = onToggleSource)
        }
    }

    if (showPicker) {
        DirectoryPickerDialog(
            startPath = primaryRootPath,
            onListDirectories = listDirectories,
            onPick = { path ->
                onPickDirectory(path)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun PermissionNotice(onRequestAllFilesAccess: () -> Unit) {
    Column(Modifier.padding(vertical = 16.dp)) {
        Text(ErrorText.resolve("perm.denied"))
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRequestAllFilesAccess) { Text("去授权") }
    }
}

/** 空状态（`09 §5.1`）：一句文案 + 一个动作。动作就是这条空状态的出口，不能只留一句灰字。 */
@Composable
private fun EmptyNotice(text: String, actionLabel: String, onAction: () -> Unit) {
    Column(Modifier.padding(vertical = 16.dp)) {
        Text(text)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun ScanProgress(state: ScanUiState) {
    Column(Modifier.padding(vertical = 16.dp)) {
        Text(ErrorText.resolve("scan.running", mapOf("new" to state.discoveredCount)))
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(
            "已读取元数据 ${state.metadataProcessed} 个文件",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DiffNotice(state: ScanUiState) {
    val diff = state.diff ?: return
    Column(Modifier.padding(vertical = 16.dp)) {
        Text(
            ErrorText.resolve(
                "scan.diff.found",
                mapOf(
                    "new" to diff.newCount,
                    "skipped" to diff.skippedCount,
                    "cleaned" to diff.cleanedCount,
                ),
            ),
        )
    }
}

/**
 * 扫描设置（需求 `01 §2.5`）。就地展开而不是弹窗：两条规则各自独立可关。
 *
 * 不在面板里再放一次「扫描设置」标题：展开它的按钮就叫这个名字，重复的文本会让
 * `onNodeWithText` 变成二义（未来的测试得靠下标猜），也让同一句话在一屏上出现两次。
 */
@Composable
private fun ScanSettingsSection(
    durationRuleOn: Boolean,
    sizeRuleOn: Boolean,
    onToggleDuration: (Boolean) -> Unit,
    onToggleSize: (Boolean) -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        RuleRow("不扫描短于 60 秒的音频", durationRuleOn, onToggleDuration)
        RuleRow("不扫描小于 100 KB 的文件", sizeRuleOn, onToggleSize)
        Text(
            "只影响新扫描，已入库的文件不受影响",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 一行规则。
 *
 * 整行可点（`toggleable` + `Checkbox(onCheckedChange = null)`）：只让那个小方块可点的话，
 * 用户点文字没反应；a11y 上也应当是「整行是一个开关」，而不是「一个复选框加一句说明」。
 */
@Composable
private fun RuleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

@Composable
private fun SourceList(state: ScanUiState, onToggleSource: (Long, Boolean) -> Unit) {
    if (state.sources.isEmpty()) return

    Column {
        Text("扫描来源", style = MaterialTheme.typography.titleSmall)
        state.sources.forEach { source ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = source.path,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = source.enabled,
                    onCheckedChange = { onToggleSource(source.id, it) },
                )
            }
        }
    }
}

@Composable
private fun ScanBottomBar(
    state: ScanUiState,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onConfirmImport: () -> Unit,
    onDiscard: () -> Unit,
    onCustomScan: () -> Unit,
    onToggleSettings: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
) {
    Column(Modifier.padding(PaddingValues(16.dp))) {
        // 相位决定主按钮：扫描中可取消；有差异时是「分析并添加」/「放弃」
        when {
            state.scanning -> Button(onClick = onCancelScan, modifier = Modifier.fillMaxWidth()) {
                Text("取消")
            }
            // 新文件为 0 时不摆「分析并添加」：没有东西可提交，摆出来只会点出一个 NothingToCommit
            state.diff != null && state.diff.newCount > 0 ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirmImport, modifier = Modifier.weight(1f)) {
                        Text("分析并添加")
                    }
                    OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                        Text("放弃")
                    }
                }
            else -> Button(
                onClick = onStartScan,
                enabled = state.canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 09 §5.1 的空状态出口就是「重新扫描」：无事可做时别再说「开始扫描」
                Text(if (state.nothingNew) "重新扫描" else "开始扫描")
            }
        }

        if (state.degraded) {
            // 10 §4.3：降级时置灰入口 + 给「去授权」入口 —— 光置灰等于让用户卡在这里
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "仅扫描媒体库",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRequestAllFilesAccess) { Text("去授权") }
            }
        }

        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCustomScan, enabled = !state.degraded) { Text("自定义扫描") }
            TextButton(onClick = onToggleSettings) { Text("扫描设置") }
        }
    }
}

/**
 * 应用内目录浏览器（需求 `01 §2.4`：目录选择在应用内完成，不用系统文件选择器 ——
 * 后者给的是 `content://` 资源标识，与以**文件路径**为唯一键的整套设计不兼容）。
 *
 * 只列直属子目录、逐层下钻；`onListDirectories` 由 ViewModel 提供（文件系统调用要离主线程）。
 */
@Composable
internal fun DirectoryPickerDialog(
    startPath: String,
    onListDirectories: suspend (String) -> List<FileRef>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf(startPath) }
    var entries by remember { mutableStateOf<List<FileRef>>(emptyList()) }

    LaunchedEffect(current) { entries = onListDirectories(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(current, style = MaterialTheme.typography.bodySmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { current = parentOf(current) }) { Text("上一级") }
                if (entries.isEmpty()) {
                    Text("该目录下没有子目录", style = MaterialTheme.typography.bodySmall)
                }
                entries.forEach { dir ->
                    Text(
                        text = dir.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { current = dir.path }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text("选择音乐来源") }
        },
    )
}

/** `/a/b/c` → `/a/b`；已在根则停在根（不返回空串，否则浏览器会跳到无效路径）。 */
private fun parentOf(path: String): String {
    val trimmed = path.trimEnd('/')
    val parent = trimmed.substringBeforeLast('/', "")
    return parent.ifEmpty { "/" }
}
