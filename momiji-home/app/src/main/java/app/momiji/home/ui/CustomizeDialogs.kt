package app.momiji.home.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import app.momiji.home.data.HomeStore
import app.momiji.home.model.AppEntry
import app.momiji.home.model.HomeCategory

/** Dialogs capture profile/category IDs, so a delayed result never edits another profile. */
class CustomizeDialogs(
    private val activity: Activity,
    private val store: HomeStore,
    private val apps: () -> List<AppEntry>,
    private val changed: () -> Unit
) {
    private fun alive(id: String) = !activity.isDestroyed && store.profiles().any { it.id == id }
    private fun notice(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    fun appearance() {
        val profile = store.activeProfile(); val layout = store.layout(profile.id)
        val labels = arrayOf("Цвет акцента", "Карточки · ${arrayOf("компактные", "обычные", "крупные")[layout.cardSize]}",
            "Колонки · ${if (layout.columns < 3) "автоматически" else layout.columns}",
            "Текст · ${if (layout.largeText) "крупный" else "обычный"}",
            "Углы · ${if (layout.roundCards) "скруглённые" else "строгие"}",
            "Ряды на главном экране", "Мои категории", "Шрифт карточек", "Подписи приложений · ${if(layout.showAppLabels) "показаны" else "скрыты"}")
        AlertDialog.Builder(activity).setTitle("Оформление · ${profile.name}").setItems(labels) { _, which ->
            if (!alive(profile.id)) return@setItems
            when (which) {
                0 -> {
                    val colors = intArrayOf(0xFFFFB347.toInt(),0xFF8EC8C4.toInt(),0xFFC4A7E7.toInt(),0xFFF28B82.toInt(),0xFFA8C986.toInt(),0xFF87B7E8.toInt(),0xFFF5E6CA.toInt())
                    AlertDialog.Builder(activity).setTitle("Цвет акцента").setItems(arrayOf("Клён", "Нефрит", "Лаванда", "Коралл", "Матча", "Небо", "Слоновая кость")) { _, index ->
                        if (alive(profile.id)) { store.setAccent(profile.id, colors[index]); changed() }
                    }.setNegativeButton("Отмена", null).show()
                }
                1 -> choice("Размер карточек", arrayOf("Компактные", "Обычные", "Крупные"), layout.cardSize) {
                    if (alive(profile.id)) { store.setLayout(profile.id, store.layout(profile.id).copy(cardSize = it)); changed() }
                }
                2 -> choice("Колонки приложений", arrayOf("Автоматически", "3", "4", "5", "6"), intArrayOf(0,3,4,5,6).indexOf(layout.columns).coerceAtLeast(0)) {
                    if (alive(profile.id)) { store.setLayout(profile.id, store.layout(profile.id).copy(columns = intArrayOf(0,3,4,5,6)[it])); changed() }
                }
                3 -> { store.setLayout(profile.id, layout.copy(largeText = !layout.largeText)); changed() }
                4 -> { store.setLayout(profile.id, layout.copy(roundCards = !layout.roundCards)); changed() }
                5 -> AlertDialog.Builder(activity).setTitle("Показывать на главной")
                    .setMultiChoiceItems(arrayOf("Недавние запуски профиля", "Карточки сервисов", "HDMI и входы"),
                        booleanArrayOf(layout.showRecent, layout.showServices, layout.showInputs)) { _, index, checked ->
                        if (alive(profile.id)) {
                            val current = store.layout(profile.id)
                            store.setLayout(profile.id, when (index) {
                                0 -> current.copy(showRecent = checked); 1 -> current.copy(showServices = checked); else -> current.copy(showInputs = checked)
                            })
                        }
                    }.setPositiveButton("Готово") { _, _ -> changed() }.show()
                6 -> categories()
                7 -> choice("Шрифт карточек",arrayOf("Современный", "Узкий", "Книжный", "Моноширинный"),layout.fontStyle) {
                    if (alive(profile.id)) { store.setLayout(profile.id,store.layout(profile.id).copy(fontStyle=it)); changed() }
                }
                8 -> { store.setLayout(profile.id,layout.copy(showAppLabels=!layout.showAppLabels)); changed() }
            }
        }.setNegativeButton("Закрыть", null).show()
    }

    fun categories() {
        val profile = store.activeProfile(); val categories = store.categories(profile.id)
        AlertDialog.Builder(activity).setTitle("Категории · ${profile.name}")
            .setItems((categories.map { "${it.name} · ${it.appKeys.size}" } + "＋ Создать категорию").toTypedArray()) { _, index ->
                if (!alive(profile.id)) return@setItems
                if (index == categories.size) nameDialog("Новая категория", "") { name ->
                    val category = store.createCategory(profile.id, name); changed(); chooseApps(profile.id, category)
                } else manageCategory(profile.id, categories[index])
            }.setNegativeButton("Закрыть", null).show()
    }

    private fun manageCategory(profileId: String, category: HomeCategory) {
        AlertDialog.Builder(activity).setTitle(category.name)
            .setItems(arrayOf("Выбрать приложения", "Порядок приложений", "Переименовать", "Поднять ряд", "Опустить ряд", "Удалить категорию")) { _, which ->
                if (!alive(profileId)) return@setItems
                when (which) {
                    0 -> chooseApps(profileId, category)
                    1 -> orderApps(profileId, category.id)
                    2 -> nameDialog("Имя категории", category.name) { name ->
                        store.categories(profileId).firstOrNull { it.id == category.id }?.let { store.updateCategory(profileId, it.copy(name = name)); changed() }
                    }
                    3,4 -> { store.moveCategory(profileId, category.id, if (which == 3) -1 else 1); changed() }
                    5 -> AlertDialog.Builder(activity).setTitle("Удалить «${category.name}»?")
                        .setMessage("Приложения останутся установленными.")
                        .setPositiveButton("Удалить") { _, _ -> if (alive(profileId)) { store.deleteCategory(profileId, category.id); changed() } }
                        .setNegativeButton("Отмена", null).show()
                }
            }.setNegativeButton("Назад", null).show()
    }

    private fun chooseApps(profileId: String, category: HomeCategory) {
        val choices = apps().toList(); val selected = category.appKeys.toMutableList()
        AlertDialog.Builder(activity).setTitle(category.name)
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), choices.map { it.key in selected }.toBooleanArray()) { _, index, checked ->
                val key = choices[index].key
                if (checked && key !in selected) selected.add(key) else if (!checked) selected.remove(key)
            }.setPositiveButton("Сохранить") { _, _ ->
                if (alive(profileId)) store.categories(profileId).firstOrNull { it.id == category.id }?.let {
                    store.updateCategory(profileId, it.copy(appKeys = selected)); changed()
                }
            }.setNegativeButton("Отмена", null).show()
    }

    private fun orderApps(profileId: String, id: String) {
        if (!alive(profileId)) return
        val category = store.categories(profileId).firstOrNull { it.id == id } ?: return
        val labels = apps().associate { it.key to it.label }
        if (category.appKeys.isEmpty()) { notice("Сначала добавь приложения"); return }
        AlertDialog.Builder(activity).setTitle("Порядок · ${category.name}")
            .setItems(category.appKeys.mapIndexed { index, key -> "${index+1}. ${labels[key] ?: key.substringBefore('/')}" }.toTypedArray()) { _, selected ->
                AlertDialog.Builder(activity).setTitle("Переместить приложение")
                    .setItems(arrayOf("В начало", "На позицию левее", "На позицию правее", "В конец")) { _, action ->
                        if (alive(profileId)) store.categories(profileId).firstOrNull { it.id == id }?.let { current ->
                            val keys = current.appKeys.toMutableList(); val key = category.appKeys[selected]
                            val at = keys.indexOf(key)
                            if (at >= 0) {
                                val target = when (action) { 0 -> 0; 1 -> (at-1).coerceAtLeast(0); 2 -> (at+1).coerceAtMost(keys.lastIndex); else -> keys.lastIndex }
                                keys.removeAt(at); keys.add(target, key)
                                store.updateCategory(profileId, current.copy(appKeys = keys)); changed(); orderApps(profileId, id)
                            }
                        }
                    }.setNegativeButton("Отмена", null).show()
            }.setNegativeButton("Готово", null).show()
    }

    fun appCategories(profileId: String, app: AppEntry) {
        val choices = store.categories(profileId)
        if (choices.isEmpty()) { notice("Создай категорию в настройках оформления"); categories(); return }
        val selected = choices.map { app.key in it.appKeys }.toBooleanArray()
        AlertDialog.Builder(activity).setTitle("Категории · ${app.label}")
            .setMultiChoiceItems(choices.map { it.name }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setPositiveButton("Сохранить") { _, _ ->
                if (alive(profileId)) choices.forEachIndexed { index, category ->
                    store.categories(profileId).firstOrNull { it.id == category.id }?.let { current ->
                        store.updateCategory(profileId, current.copy(appKeys = if (selected[index]) (current.appKeys + app.key).distinct() else current.appKeys.filter { it != app.key }))
                    }
                }; changed()
            }.setNegativeButton("Отмена", null).show()
    }

    fun renameApp(profileId: String, app: AppEntry) {
        nameDialog("Название карточки", store.aliases(profileId)[app.key] ?: app.label, allowEmpty = true) { name ->
            if (alive(profileId)) { store.setAlias(profileId, app.key, name); changed() }
        }
    }

    private fun choice(title: String, labels: Array<String>, current: Int, action: (Int) -> Unit) {
        AlertDialog.Builder(activity).setTitle(title).setSingleChoiceItems(labels, current) { dialog, index ->
            dialog.dismiss(); action(index)
        }.setNegativeButton("Отмена", null).show()
    }
    private fun nameDialog(title: String, initial: String, allowEmpty: Boolean = false, save: (String) -> Unit) {
        val input = EditText(activity).apply {
            setSingleLine(true); setText(initial); setSelectAllOnFocus(true)
            filters = arrayOf(InputFilter.LengthFilter(if (allowEmpty) 80 else 40))
            hint = if (allowEmpty) "Пустое поле вернёт исходное имя" else "Название"
        }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val box = FrameLayout(activity).apply { setPadding(pad, pad/3, pad, pad/3); addView(input) }
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(box)
            .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (!allowEmpty && value.isEmpty()) { input.error = "Введи название"; return@setOnClickListener }
                try { save(value); dialog.dismiss() } catch (error: Exception) { input.error = error.message ?: "Не удалось сохранить" }
            }
        }; dialog.show(); input.requestFocus()
    }
}
