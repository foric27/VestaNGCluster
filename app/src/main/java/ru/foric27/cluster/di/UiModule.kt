package ru.foric27.cluster.di

import org.koin.dsl.module

val uiModule = module {
    // UI компоненты создаются в Activities через Compose.
    // Koin будет использоваться для ViewModel при необходимости.
}
