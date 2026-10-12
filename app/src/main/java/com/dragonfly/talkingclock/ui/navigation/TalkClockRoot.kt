package com.dragonfly.talkingclock.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.dragonfly.talkingclock.ui.home.HomeScreen
import com.dragonfly.talkingclock.ui.rules.RulesScreen
import com.dragonfly.talkingclock.ui.settings.TtsSettingsScreen
import kotlinx.coroutines.launch

private data class TopDestination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    TopDestination("home", "Главная", Icons.Filled.Schedule),
    TopDestination("rules", "Правила", Icons.AutoMirrored.Filled.Rule),
    TopDestination("tts", "Голос", Icons.Filled.GraphicEq),
)

/**
 * Три вкладки — один [HorizontalPager]: свайпы обрабатывает сам pager через nested scroll,
 * поэтому они работают поверх вертикальных списков (в отличие от ручного
 * `detectHorizontalDragGestures`, который глох на скроллах, слайдерах и карточках).
 * Нижняя панель и свайп идут через общий `pagerState`, `beyondViewportPageCount=2`
 * держит все 3 страницы живыми (состояние скроллов/полей не сбрасывается).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TalkClockRoot() {
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val scope = rememberCoroutineScope()
    // ИИ-комментарий (требование «кнопка на Главной ведёт сразу к пункту вне правил»):
    // счётчик запросов на скролл к FallbackCard. Кнопка «Правила озвучки» на Главной делает
    // +1 и листает pager на вкладку Правил; RulesScreen при изменении токена скроллит
    // LazyColumn к карточке «Вне правил». rememberSaveable — переживает поворот.
    var scrollToFallbackToken by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEachIndexed { index, dest ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding),
            beyondViewportPageCount = 2,
        ) { page ->
            when (destinations[page].route) {
                "home" -> HomeScreen(
                    // ИИ-комментарий: переход к правилам СРАЗУ к пункту «вне правил»: сначала
                    // инкремент токена скролла, затем анимация pager на страницу Правил.
                    onOpenRules = {
                        scrollToFallbackToken += 1
                        scope.launch { pagerState.animateScrollToPage(1) }
                    },
                )
                "rules" -> RulesScreen(scrollToFallbackToken = scrollToFallbackToken)
                "tts" -> TtsSettingsScreen()
            }
        }
    }
}
