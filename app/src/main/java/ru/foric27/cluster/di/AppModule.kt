package ru.foric27.cluster.di

import org.koin.dsl.module

val appModule = module {
    // Kotlin object-синглтоны (RuntimeConfig, ProductConfig, AppSettings и т.д.)
    // не регистрируются в Koin — они глобально доступны напрямую.
    // Koin будет использоваться для компонентов с реальными зависимостями
    // (координаторы, ViewModel и т.д.) при необходимости.
}
