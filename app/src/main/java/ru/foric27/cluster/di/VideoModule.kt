package ru.foric27.cluster.di

import org.koin.dsl.module

val videoModule = module {
    // Video pipeline компоненты создаются вручную в UdpStreamService.
    // Koin будет использоваться при необходимости внедрения зависимостей.
}
