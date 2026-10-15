package com.moneybook.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.moneybook.AppContainer
import com.moneybook.MoneyBookApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as MoneyBookApp).container
