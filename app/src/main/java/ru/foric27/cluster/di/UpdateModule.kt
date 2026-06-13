package ru.foric27.cluster.di

import org.koin.dsl.module

val updateModule = module {
    // Update компоненты (AppUpdateManager, UpdateServerManager и т.д.)
    // являются Kotlin object-синглтонами и не нуждаются в Koin.
}
