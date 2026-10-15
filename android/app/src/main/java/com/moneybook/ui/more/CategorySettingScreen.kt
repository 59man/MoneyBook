package com.moneybook.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.CategoryRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.db.CategoryEntity
import com.moneybook.shared.model.CategoryType
import com.moneybook.ui.common.ChoiceDialog
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.common.DragHandle
import com.moneybook.ui.common.EditPencil
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.ListTopGap
import com.moneybook.ui.common.LocalAppLocked
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.OutlinedButtonBox
import com.moneybook.ui.common.RemoveBadge
import com.moneybook.ui.common.ReorderableColumn
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.bottomHairline
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategorySettingViewModel(type: CategoryType, private val repo: CategoryRepository, private val settings: SettingsRepository) : ViewModel() {
    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeTop(type).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val subs: StateFlow<List<CategoryEntity>> =
        repo.observeSubs(type).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val subcategory: StateFlow<Boolean> =
        settings.settings.map { it.subcategory }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setSubcategory(on: Boolean) {
        viewModelScope.launch { settings.replace(settings.current().copy(subcategory = on)) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun moveAndDelete(id: String, targetId: String) {
        viewModelScope.launch { repo.moveAndDelete(id, targetId) }
    }

    fun reorder(ids: List<String>) {
        viewModelScope.launch { repo.reorder(ids) }
    }
}

@Composable
fun CategorySettingScreen(type: CategoryType, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit, onOpenSubs: (String) -> Unit) {
    val c = appContainer()
    val vm: CategorySettingViewModel = viewModel(key = "categories-${type.name}") { CategorySettingViewModel(type, c.categories, c.settings) }
    val items by vm.categories.collectAsStateWithLifecycle()
    val subs by vm.subs.collectAsStateWithLifecycle()
    val subcategory by vm.subcategory.collectAsStateWithLifecycle()
    CategorySettingContent(
        type, items, onBack, onAdd, onEdit, vm::delete, vm::reorder, vm::moveAndDelete,
        subcategory = subcategory, onSubcategory = vm::setSubcategory, subs = subs, onOpenSubs = onOpenSubs,
    )
}

/** Realbyte's "Subcategory" list of one main category: the same add, edit, delete and reorder as the main list. */
class SubcategoryListViewModel(private val parentId: String, private val repo: CategoryRepository) : ViewModel() {
    val children: StateFlow<List<CategoryEntity>> =
        repo.observeChildren(parentId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun moveAndDelete(id: String, targetId: String) {
        viewModelScope.launch { repo.moveAndDelete(id, targetId) }
    }

    fun reorder(ids: List<String>) {
        viewModelScope.launch { repo.reorder(ids) }
    }
}

@Composable
fun SubcategoryListScreen(parentId: String, type: CategoryType, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val c = appContainer()
    val vm: SubcategoryListViewModel = viewModel(key = "subcategories-$parentId") { SubcategoryListViewModel(parentId, c.categories) }
    val items by vm.children.collectAsStateWithLifecycle()
    CategorySettingContent(type, items, onBack, onAdd, onEdit, vm::delete, vm::reorder, vm::moveAndDelete, title = "Subcategory")
}

@Composable
fun CategorySettingContent(
    type: CategoryType,
    items: List<CategoryEntity>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    /** Delete → "Move entries…": the category being deleted, then the one its entries move to. */
    onMove: (String, String) -> Unit = { _, _ -> },
    title: String = if (type == CategoryType.INCOME) "Income Category" else "Expenses Category",
    /** The "Subcategory" switch row is shown when [onSubcategory] is set. ON: rows read "Name (N)" and open their [onOpenSubs] list. */
    subcategory: Boolean = false,
    onSubcategory: ((Boolean) -> Unit)? = null,
    /** Every live subcategory of this type. */
    subs: List<CategoryEntity> = emptyList(),
    onOpenSubs: (String) -> Unit = {},
) {
    var order by remember(items) { mutableStateOf(items) }
    var confirm by remember { mutableStateOf<CategoryEntity?>(null) }
    var moving by remember { mutableStateOf<CategoryEntity?>(null) }
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(
            onBack = onBack,
            title = { TitleText(title) },
            actions = { IconButtonBox(R.drawable.ic_add, "Add", onAdd) },
        )
        Hairline(px = 2)
        if (onSubcategory != null) {
            Row(
                Modifier.fillMaxWidth().background(Mb.colors.surface).heightIn(min = MbDimens.listRow).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Subcategory", style = MbType.listItem, color = Mb.colors.textPrimary, modifier = Modifier.weight(1f))
                Switch(
                    checked = subcategory, onCheckedChange = onSubcategory, modifier = Modifier.semantics { contentDescription = "Subcategory" },
                    colors = SwitchDefaults.colors(checkedTrackColor = Mb.colors.accent),
                )
            }
        }
        ListTopGap()
        val subsOf = remember(subs) { subs.groupBy { it.parentId } }
        ReorderableColumn(
            items = order,
            keyOf = { it.id },
            onMove = { from, to -> order = order.toMutableList().apply { add(to, removeAt(from)) } },
            onDragEnd = { onReorder(order.map { it.id }) },
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) { item, handle ->
            val kids = if (subcategory) subsOf[item.id].orEmpty() else emptyList()
            Row(
                Modifier.fillMaxWidth().background(Mb.colors.surface).bottomHairline(Mb.colors.divider)
                    .heightIn(min = MbDimens.listRow).height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoveBadge("Delete ${item.name}") { confirm = item }
                if (subcategory) {
                    Column(Modifier.weight(1f).clickable { onOpenSubs(item.id) }.fillMaxHeight().wrapContentHeight(Alignment.CenterVertically)) {
                        FitText(if (kids.isEmpty()) item.name else "${item.name} (${kids.size})", MbType.listItem, Mb.colors.textPrimary, Modifier.fillMaxWidth(), maxLines = 2)
                        if (kids.isNotEmpty()) {
                            FitText(kids.joinToString(", ") { it.name }, MbType.categorySubs, Mb.colors.textSecondary, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    FitText(item.name, MbType.listItem, Mb.colors.textPrimary, Modifier.weight(1f), maxLines = 2)
                }
                EditPencil("Edit ${item.name}") { onEdit(item.id) }
                DragHandle(handle, "Move ${item.name}")
            }
        }
    }
    // Dialogs are windows of their own, above the lock screen.
    val locked = LocalAppLocked.current
    confirm?.takeIf { !locked }?.let { category ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete category") },
            text = { Text("Delete \"${category.name}\"? Its entries can move to another category, or keep showing this name.") },
            confirmButton = {
                TextButton(onClick = { confirm = null; onDelete(category.id) }) { Text("Delete", color = Mb.colors.danger) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirm = null; moving = category }) { Text("Move entries…", color = Mb.colors.accent) }
                    TextButton(onClick = { confirm = null }) { Text("Cancel", color = Mb.colors.textBody) }
                }
            },
        )
    }
    moving?.takeIf { !locked }?.let { category ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Move entries to") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    items.filter { it.id != category.id }.forEach { target ->
                        Text(
                            target.name, style = MbType.listItem, color = Mb.colors.textPrimary,
                            modifier = Modifier.fillMaxWidth().clickable { moving = null; onMove(category.id, target.id) }
                                .heightIn(min = MbDimens.listRow).wrapContentHeight(),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moving = null }) { Text("Cancel", color = Mb.colors.textBody) } },
        )
    }
}

/**
 * "Change Category" for [categoryId], or "Add Category" when it is null; with [parentId] it is a subcategory ("Subcategory" / "Modify Subcategory").
 * While Subcategory is ON an existing category also gets Realbyte's "→ Subcategory" / "→ Main Category" button.
 */
@Composable
fun CategoryEditScreen(type: CategoryType, categoryId: String?, onClose: () -> Unit, parentId: String? = null) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var existing by remember { mutableStateOf<CategoryEntity?>(null) }
    var targets by remember { mutableStateOf(emptyList<CategoryEntity>()) }
    var loaded by remember { mutableStateOf(categoryId == null) }
    var saving by remember { mutableStateOf(false) }
    val subcategory by c.settings.settings.map { it.subcategory }.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(categoryId) {
        if (categoryId != null) {
            existing = c.categories.get(categoryId)
            targets = c.categories.top(type).filter { it.id != categoryId }
            loaded = true
        }
    }
    if (!loaded) return
    val isSub = parentId != null || existing?.parentId != null
    val title = when {
        !isSub -> if (categoryId == null) "Add Category" else "Change Category"
        categoryId == null -> "Subcategory"
        else -> "Modify Subcategory"
    }
    NameEditScreen(
        title, existing?.name.orEmpty(), onClose,
        below = {
            val current = existing
            if (subcategory && current != null) {
                CategoryMoveBlock(
                    current.name, isMain = current.parentId == null, targets = targets,
                    onToSub = { target -> scope.launch { c.categories.toSubcategory(current.id, target); onClose() } },
                    onToMain = { scope.launch { c.categories.toMain(current.id); onClose() } },
                )
            }
        },
    ) { name ->
        if (saving) return@NameEditScreen // a double-tapped Save must not add twice
        saving = true
        scope.launch {
            when {
                categoryId != null -> c.categories.rename(categoryId, name)
                parentId != null -> c.categories.addSub(parentId, name)
                else -> c.categories.add(type, name)
            }
            onClose()
        }
    }
}

/** Change Category's "→ Subcategory" (pick the new parent among [targets], then Realbyte's confirmation) or a subcategory's "→ Main Category". */
@Composable
fun CategoryMoveBlock(name: String, isMain: Boolean, targets: List<CategoryEntity>, onToSub: (String) -> Unit, onToMain: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<CategoryEntity?>(null) }
    OutlinedButtonBox(
        if (isMain) "\u2192 Subcategory" else "\u2192 Main Category",
        Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 36.dp),
    ) { if (isMain) picking = true else onToMain() }
    if (picking) {
        ChoiceDialog("Category", targets.map { it.name }, onDismiss = { picking = false }) { picking = false; confirm = targets[it] }
    }
    confirm?.let { parent ->
        ConfirmDialog(
            "Subcategory",
            "Category \u2019$name\u2019 will now become a subcategory of \u2019${parent.name}\u2019. " +
                "All subcategories of category \u2019$name\u2019 will become subcategories of \u2019${parent.name}\u2019. Do you wish to continue?",
            "YES", "NO", onConfirm = { onToSub(parent.id) }, onDismiss = { confirm = null },
        )
    }
}
